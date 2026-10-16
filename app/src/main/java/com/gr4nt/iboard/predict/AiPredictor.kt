package com.gr4nt.iboard.predict

import com.gr4nt.iboard.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Context-aware predictions from a large language model.
 * Reads the text before the cursor and returns likely words plus a short phrase continuation.
 */
class AiPredictor(private val prefs: Prefs) {

    data class Prediction(val words: List<String>, val phrase: String)

    private val cache = object : LinkedHashMap<String, Prediction>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Prediction>?) = size > 60
    }

    @Volatile
    var lastError: String? = null
        private set

    val isConfigured: Boolean
        get() = prefs.aiEnabled && prefs.aiKey.isNotEmpty() && prefs.aiModel.isNotEmpty()

    fun cached(context: String, lang: String): Prediction? = synchronized(cache) { cache["$lang|$context"] }

    suspend fun predict(context: String, partialWord: String, lang: String): Prediction? =
        withContext(Dispatchers.IO) {
            val key = "$lang|$context"
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            val language = Prefs.languageName(lang)
            val system = """
                You are the word-prediction engine inside a smartphone keyboard. The user is typing in $language.
                Reply with ONLY a compact JSON object, no prose: {"words":["w1","w2","w3"],"phrase":"..."}
                - "words": exactly 3 different single words, best first.
                  If a partial word is given, these are the complete words the user most likely means
                  (finish it, and fix typos or missing accents using the context).
                  If no partial word is given, these are the most likely NEXT words.
                - "phrase": the most natural continuation of 2 to 6 words starting exactly at the cursor
                  (it must begin with the completed partial word if there is one), or "" if unsure.
                - Match the user's tone, slang and capitalization. Never add quotes or emoji unless the user uses them.
            """.trimIndent()
            val user = buildString {
                append("Text before cursor:\n<<<")
                append(context)
                append(">>>\n")
                if (partialWord.isNotEmpty()) append("Partial word at cursor: \"").append(partialWord).append("\"\n")
                else append("No partial word: predict the next word.\n")
            }
            try {
                val text = if (prefs.aiProvider == "anthropic") callAnthropic(system, user) else callOpenAi(system, user)
                val pred = parse(text) ?: return@withContext null
                synchronized(cache) { cache[key] = pred }
                lastError = null
                pred
            } catch (e: Exception) {
                lastError = e.message
                null
            }
        }

    /**
     * Rewrites [text] following [instruction] (translate, fix grammar…). Returns only the new text.
     * Needs an API key but works even when word prediction is switched off.
     */
    suspend fun transform(text: String, instruction: String): String = withContext(Dispatchers.IO) {
        val system = """
            You are a writing tool inside a smartphone keyboard. $instruction
            Reply with ONLY the resulting text: no quotes, no explanations, no notes.
            Keep emoji, names, numbers, links and line breaks. Match the original tone and formality.
        """.trimIndent()
        val out = if (prefs.aiProvider == "anthropic") callAnthropic(system, text, 2000) else callOpenAi(system, text, 2000)
        out.trim().removeSurrounding("\"").trim()
    }

    private fun callAnthropic(system: String, user: String, maxTokens: Int = 120): String {
        val body = JSONObject()
            .put("model", prefs.aiModel)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val res = post(
            "https://api.anthropic.com/v1/messages", body.toString(),
            mapOf("x-api-key" to prefs.aiKey, "anthropic-version" to "2023-06-01"),
        )
        val content = JSONObject(res).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.optString("type") == "text") sb.append(part.optString("text"))
        }
        return sb.toString()
    }

    private fun callOpenAi(system: String, user: String, maxTokens: Int = 120): String {
        val body = JSONObject()
            .put("model", prefs.aiModel)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.2)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val base = prefs.aiBaseUrl.trimEnd('/')
        val res = post("$base/chat/completions", body.toString(), mapOf("Authorization" to "Bearer ${prefs.aiKey}"))
        return JSONObject(res).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): Prediction? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(text.substring(start, end + 1))
            val arr = o.optJSONArray("words") ?: JSONArray()
            val words = (0 until arr.length()).map { arr.optString(it).trim() }
                .filter { it.isNotEmpty() && !it.contains(' ') && it.length <= 40 }
                .distinct().take(3)
            val phrase = o.optString("phrase").trim().take(80)
            Prediction(words, phrase)
        } catch (_: Exception) {
            null
        }
    }

    /** Sends a tiny request to verify the key/model from the settings screen. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        try {
            val text = if (prefs.aiProvider == "anthropic") callAnthropic("Reply with OK.", "Say OK")
            else callOpenAi("Reply with OK.", "Say OK")
            "Connected ✓ (${prefs.aiModel}): ${text.take(40)}"
        } catch (e: Exception) {
            "Failed: ${e.message?.take(160)}"
        }
    }
}

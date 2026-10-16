package com.gr4nt.iboard.voice

import android.content.Context
import com.gr4nt.iboard.predict.Dictation
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

/**
 * Fast, rule-based first pass over a spoken ramble: drops fillers and stutters, applies
 * self-corrections ("5pm, no wait, 6pm" → "6pm"), honours "scratch that", and turns spoken
 * formatting into text. Runs instantly and works even without the rewrite model.
 */
object RambleRules {

    private val fillers = mapOf(
        "en" to "um+|uh+m*|erm+|er|ah+|hmm+|mm+|uh-huh",
        "es" to "eh+|em+|mmm+",
        "fr" to "euh+|heu+|bah|ben",
        "pt" to "hã+|ahn+|é{2,}",
        "de" to "äh+m*|öh+m*|hm+",
        "ar" to "امم+|اه+",
    )

    private val corrections = mapOf(
        "en" to "no,? wait|wait,? no|no,? sorry|sorry,? I mean|I mean|actually,? no|or rather|make that|no no|correction",
        "es" to "no,? espera|quiero decir|mejor dicho|perdón",
        "fr" to "non,? attends|je veux dire|enfin non|pardon|plutôt",
        "pt" to "não,? espera|quer dizer|ou melhor|desculpa",
        "de" to "nein,? warte|ich meine|beziehungsweise|sorry|besser gesagt",
        "ar" to "لا انتظر|أقصد|عفوا",
    )

    private val scratch = mapOf(
        "en" to "scratch that|delete that|strike that|forget that",
        "es" to "borra eso|olvida eso",
        "fr" to "efface ça|oublie ça",
        "pt" to "apaga isso|esquece isso",
        "de" to "streich das|lösch das|vergiss das",
        "ar" to "امسح ذلك",
    )

    private val bullets = mapOf(
        "en" to "bullet point|next bullet|new bullet",
        "es" to "viñeta",
        "fr" to "puce",
        "pt" to "marcador",
        "de" to "aufzählungspunkt",
        "ar" to "نقطة جديدة",
    )

    fun clean(raw: String, lang: String, before: String): String {
        var t = " " + raw.replace(Regex("\\s+"), " ").trim() + " "

        // 1. "scratch that": remove the sentence that was just said.
        scratch[lang]?.let { s ->
            val re = Regex("(?i)[,.]?\\s*\\b($s)\\b[,.!]?")
            while (true) {
                val m = re.find(t) ?: break
                val head = t.substring(0, m.range.first)
                val cut = maxOf(head.lastIndexOfAny(charArrayOf('.', '!', '?', '\n')) + 1, 0)
                t = head.substring(0, cut) + " " + t.substring(m.range.last + 1)
            }
        }

        // 2. Fillers ("um", "uh", "euh"…) and comma-wrapped "you know" / "like".
        fillers[lang]?.let { f -> t = Regex("(?i)(?<=[\\s,])(?:$f)(?=[\\s,.!?])[,]?").replace(t, " ") }
        if (lang == "en") t = Regex("(?i),\\s*(you know|like|basically|so yeah)\\s*,").replace(t, ",")

        // 3. Self-corrections: keep what came after the marker in place of what it replaced.
        corrections[lang]?.let { c ->
            val re = Regex("(?i)[,.]?\\s*\\b(?:$c)\\b[,.]?\\s+")
            var from = 0
            var guard = 0
            while (guard++ < 12) {
                val m = re.find(t, from) ?: break
                val head = t.substring(0, m.range.first)
                val tail = t.substring(m.range.last + 1)
                val clauseStart = maxOf(head.lastIndexOfAny(charArrayOf('.', '!', '?', '\n')) + 1, 0)
                val clauseWords = head.substring(clauseStart).trim().split(" ").filter { it.isNotBlank() }
                if (clauseWords.isEmpty() || tail.isBlank()) {
                    // "I mean" at the start of a sentence is just talk, not a correction.
                    from = m.range.last + 1
                    continue
                }
                val first = tail.trim().split(" ").firstOrNull()?.trim(',', '.', '!', '?')?.lowercase().orEmpty()
                // Cut back to where the repaired phrase starts ("the cinema, I mean the park" → "the park").
                val idx = clauseWords.indexOfLast { first.isNotEmpty() && it.trim(',', '.').lowercase() == first }
                val keep = if (idx >= 0) clauseWords.take(idx) else clauseWords.dropLast(1)
                val rebuilt = head.substring(0, clauseStart) + " " + keep.joinToString(" ") + " "
                t = rebuilt + tail
                from = rebuilt.length
            }
        }

        // 4. Stutters: "I I think" → "I think", "the the" → "the".
        t = Regex("(?i)\\b(\\p{L}+)(?:[ ,]+\\1\\b)+").replace(t) { it.groupValues[1] }

        // 5. Spoken bullets → list lines.
        bullets[lang]?.let { b -> t = Regex("(?i)[,.]?\\s*\\b(?:$b)\\b[,:]?\\s*").replace(t, "\n• ") }

        // 6. Tidy punctuation and spacing, then the usual dictation formatting (spoken commas, capitals, end marks).
        t = t.replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("([,;:])\\s*([,.!?])"), "$2")
            .replace(Regex("^[\\s,.;:]+"), "")
            .replace(Regex(" {2,}"), " ")
        return Dictation.format(t, lang, before).replace(Regex("\n• \\s*"), "\n• ")
    }
}

/**
 * On-device rewrite ("Rambler"): a small language model turns the cleaned-up transcript into
 * the message the speaker meant, keeping their words, tone and language.
 */
class RamblerRewriter(private val context: Context) {
    private var llm: LlmInference? = null
    private var loadedPath: String? = null

    @Synchronized
    fun rewrite(modelPath: String, text: String, lang: String): String? {
        if (text.isBlank()) return text
        val engine = engine(modelPath) ?: return null
        // The model's context is ~1,280 tokens, so long rambles are polished a few sentences at a time.
        val pieces = splitForModel(text)
        val out = StringBuilder()
        for (p in pieces) {
            val r = runOnce(engine, p, lang) ?: return null
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append(' ')
            out.append(r)
        }
        return out.toString().trim()
    }

    private fun engine(path: String): LlmInference? {
        if (llm != null && loadedPath == path) return llm
        close()
        return try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(1280)
                .build()
            LlmInference.createFromOptions(context, opts).also { llm = it; loadedPath = path }
        } catch (e: Throwable) {
            null
        }
    }

    private fun runOnce(engine: LlmInference, text: String, lang: String): String? {
        val language = com.gr4nt.iboard.Prefs.languageName(lang)
        val prompt = buildString {
            append("<|im_start|>system\n")
            append("You clean up dictated speech into the text the speaker meant to type. ")
            append("Remove filler words, false starts and repetitions. When the speaker corrects themselves, keep only the correction. ")
            append("Fix punctuation, capitalization and grammar. Keep their wording, tone, meaning and language ($language). ")
            append("Never add new information, never answer questions in the text, never add quotes or notes. ")
            append("If they list several items, put each on its own line starting with •. Reply with the cleaned text only.<|im_end|>\n")
            append("<|im_start|>user\nso um I was thinking we could meet on Tuesday no wait Wednesday at like 5 and uh bring the the slides<|im_end|>\n")
            append("<|im_start|>assistant\nI was thinking we could meet on Wednesday at 5 and bring the slides.<|im_end|>\n")
            append("<|im_start|>user\nok things to buy eggs milk oh and bread<|im_end|>\n")
            append("<|im_start|>assistant\nThings to buy:\n• Eggs\n• Milk\n• Bread<|im_end|>\n")
            append("<|im_start|>user\n").append(text).append("<|im_end|>\n<|im_start|>assistant\n")
        }
        return try {
            val session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().setTemperature(0.1f).setTopK(1).build(),
            )
            val raw = try {
                session.addQueryChunk(prompt)
                session.generateResponse()
            } finally {
                session.close()
            }
            sanitize(raw, text)
        } catch (e: Throwable) {
            null
        }
    }

    /** Rejects answers that wander off: too long, chatty, or empty. Falls back to the rule-based text. */
    private fun sanitize(raw: String?, input: String): String? {
        var r = raw?.substringBefore("<|im_end|>")?.substringBefore("<|im_start|>")?.trim() ?: return null
        r = r.removePrefix("Cleaned text:").removePrefix("Here is the cleaned text:").trim().removeSurrounding("\"")
        if (r.isBlank()) return null
        if (r.length > input.length * 1.6 + 40) return null
        if (Regex("(?i)^(sure|certainly|of course|here('| i)s)").containsMatchIn(r)) return null
        return r
    }

    private fun splitForModel(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?\\n])\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.length + s.length > 900 && cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.ifEmpty { listOf(text) }
    }

    @Synchronized
    fun close() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
        loadedPath = null
    }
}

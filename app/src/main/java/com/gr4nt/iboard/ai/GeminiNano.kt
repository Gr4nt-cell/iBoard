package com.gr4nt.iboard.ai

import android.util.Log
import android.util.LruCache
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig as buildModelConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Every AI feature in iBoard runs on Gemini Nano through ML Kit's GenAI Prompt API.
 *
 * Gemini Nano is not inside the APK: Android's AICore system service installs it, keeps it up to
 * date and runs it on the phone. Phones without AICore (or with an unlocked bootloader) report
 * [State.UNAVAILABLE], and iBoard then simply types without AI.
 */
object GeminiNano {
    private const val TAG = "GeminiNano"

    enum class State { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    /** Last known state; updated by [refresh] and [download]. */
    @Volatile var state: State = State.UNKNOWN
        private set
    @Volatile var lastError: String? = null
        private set

    // Two clients: FAST for word prediction (latency matters), FULL for rewriting and translating.
    private var fast: GenerativeModel? = null
    private var full: GenerativeModel? = null
    @Volatile private var systemPromptOk: Boolean? = null
    private val lock = Mutex()

    private fun client(quick: Boolean): GenerativeModel = synchronized(this) {
        val existing = if (quick) fast else full
        existing ?: Generation.getClient(generationConfig {
            modelConfig = buildModelConfig {
                releaseStage = ModelReleaseStage.STABLE
                preference = if (quick) ModelPreference.FAST else ModelPreference.FULL
            }
        }).also { if (quick) fast = it else full = it }
    }

    private fun stateOf(status: Int) = when (status) {
        FeatureStatus.AVAILABLE -> State.AVAILABLE
        FeatureStatus.DOWNLOADING -> State.DOWNLOADING
        FeatureStatus.DOWNLOADABLE -> State.DOWNLOADABLE
        else -> State.UNAVAILABLE
    }

    /** Asks AICore whether Gemini Nano can run on this phone. */
    suspend fun refresh(): State {
        state = try {
            stateOf(client(false).checkStatus())
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = describe(e)
            Log.w(TAG, "checkStatus failed", e)
            State.UNAVAILABLE
        }
        return state
    }

    val ready get() = state == State.AVAILABLE

    /** Has AICore download Gemini Nano. [onProgress] gets a 0–100 percentage when known. */
    suspend fun download(onProgress: (Int?) -> Unit = {}): Boolean {
        if (refresh() == State.AVAILABLE) return true
        if (state == State.UNAVAILABLE) return false
        var total = 0L
        var ok = false
        try {
            client(false).download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadStarted -> { total = s.bytesToDownload; state = State.DOWNLOADING; onProgress(0) }
                    is DownloadStatus.DownloadProgress -> onProgress(if (total > 0) (s.totalBytesDownloaded * 100 / total).toInt().coerceIn(0, 100) else null)
                    is DownloadStatus.DownloadCompleted -> { ok = true; onProgress(100) }
                    is DownloadStatus.DownloadFailed -> { lastError = describe(s.e); Log.w(TAG, "download failed", s.e) }
                    else -> {}
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = describe(e)
            Log.w(TAG, "download failed", e)
        }
        refresh()
        return ok || state == State.AVAILABLE
    }

    fun describe(e: Throwable): String = when (e) {
        is GenAiException -> "Gemini Nano error ${e.errorCode}" + (e.message?.let { ": $it" } ?: "")
        else -> e.message ?: e.javaClass.simpleName
    }

    /** One prompt → one answer. Returns null when Gemini Nano isn't available or fails. */
    suspend fun ask(
        system: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float = 0.2f,
        quick: Boolean = false,
    ): String? {
        if (state != State.AVAILABLE && refresh() != State.AVAILABLE) return null
        val model = client(quick)
        return lock.withLock {
            try {
                val sys = systemPromptOk ?: runCatching { model.isSystemPromptAvailable() }.getOrDefault(false).also { systemPromptOk = it }
                val request = if (sys) generateContentRequest(SystemInstruction(system), TextPart(prompt)) {
                    this.temperature = temperature
                    this.topK = 16
                    this.maxOutputTokens = maxTokens
                } else generateContentRequest(TextPart("$system\n\n$prompt")) {
                    this.temperature = temperature
                    this.topK = 16
                    this.maxOutputTokens = maxTokens
                }
                model.generateContent(request).candidates.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = describe(e)
                Log.w(TAG, "generateContent failed", e)
                null
            }
        }
    }

    // ------------------------------------------------------------------ prediction

    private val predictionCache = LruCache<String, List<String>>(200)

    /**
     * Up to three words for the suggestion bar. With [partial] empty they are likely next words;
     * otherwise completions or corrections of the word being typed.
     */
    suspend fun predict(contextText: String, partial: String, langCode: String): List<String> {
        val ctx = contextText.takeLast(240)
        val key = "$langCode|$ctx|$partial"
        predictionCache.get(key)?.let { return it }
        val language = languageName(langCode)
        val system = "You are the word prediction engine of a phone keyboard. The user writes in $language. " +
            "Answer with exactly three single words separated by |, most likely first. No numbering, quotes or explanations."
        val prompt = if (partial.isEmpty())
            "Text so far: \"$ctx\"\nThree most likely next words:"
        else
            "Text so far: \"$ctx$partial\"\nThe user is typing the word \"$partial\". Three most likely intended words " +
                "(completions of it, or the correct spelling if it is misspelled):"
        val raw = ask(system, prompt, maxTokens = 18, temperature = 0.1f, quick = true) ?: return emptyList()
        val words = raw.split('|', '\n', ',')
            .map { it.trim().trim('"', '\'', '.', '*', '-', '•', ' ').substringAfter(". ").trim() }
            .filter { it.isNotEmpty() && it.length <= 24 && it.none { c -> c.isWhitespace() } }
            .map { matchCase(it, partial) }
            .distinctBy { it.lowercase() }
            .take(3)
        if (words.isNotEmpty()) predictionCache.put(key, words)
        return words
    }

    private fun matchCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    // ------------------------------------------------------------------ writing tools

    enum class Tool(val label: String, val instruction: String) {
        PROOFREAD("Fix grammar", "Correct the spelling, grammar and punctuation of the text. Keep its meaning, language, tone and formatting."),
        FRIENDLY("Friendly", "Rewrite the text in a warm, friendly tone. Keep its meaning and language."),
        PROFESSIONAL("Professional", "Rewrite the text in a clear, professional tone. Keep its meaning and language."),
        SHORTER("Shorter", "Rewrite the text to be shorter and more concise. Keep its meaning and language."),
        LONGER("Longer", "Expand the text with a little more detail. Keep its meaning, tone and language."),
        REPHRASE("Rephrase", "Rephrase the text in different words. Keep its meaning, tone and language."),
        EMOJIFY("Emojify", "Add fitting emoji to the text. Keep all of its words and its language."),
        SUMMARIZE("Summarize", "Summarize the text in one or two short sentences, in the same language."),
    }

    suspend fun apply(tool: Tool, text: String): String? =
        ask("You are a writing assistant inside a phone keyboard. ${tool.instruction} Reply with only the resulting text, nothing else.",
            text.take(2000), maxTokens = 512, temperature = if (tool == Tool.PROOFREAD) 0.1f else 0.5f)
            ?.let(::clean)

    suspend fun translate(text: String, fromCode: String?, toCode: String): String? {
        val from = fromCode?.let { "from ${languageName(it)} " } ?: ""
        return ask("You are a translator inside a phone keyboard. Translate the user's text ${from}into ${languageName(toCode)}. " +
            "Reply with only the translation, nothing else.", text.take(2000), maxTokens = 512, temperature = 0.1f)
            ?.let(::clean)
    }

    /** ISO 639-1 code of the text's language, or null when unsure. */
    suspend fun detect(text: String): String? {
        val raw = ask("Identify the language of the user's text. Reply with only its two-letter ISO 639-1 code.",
            text.take(300), maxTokens = 4, temperature = 0f, quick = true) ?: return null
        return Regex("[a-zA-Z]{2}").find(raw)?.value?.lowercase()
    }

    /** Drops wrapping quotes or a leading "Here is…:" line that small models sometimes add. */
    private fun clean(out: String): String {
        var s = out.trim()
        val first = s.substringBefore('\n')
        if (s.contains('\n') && first.endsWith(":") && first.length < 80) s = s.substringAfter('\n').trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1)
        return s
    }

    fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    fun close() = synchronized(this) {
        runCatching { fast?.close() }; runCatching { full?.close() }
        fast = null; full = null
    }
}

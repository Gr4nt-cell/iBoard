package com.gr4nt.iboard.voice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Online dictation through the phone's speech service (usually Google). Listens continuously:
 * each finished phrase is delivered, then it listens again until stopped or the user goes quiet.
 * Must be created and used on the main thread.
 */
class OnlineSpeech(
    private val context: Context,
    private val lang: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onEnd: (error: Int?) -> Unit,
) {
    companion object {
        /** Settings: "auto" uses online when connected, "online" always, "offline" never. */
        const val PREF = "voice_engine"

        fun available(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

        fun connected(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        fun locale(lang: String) = when (lang) {
            "es" -> "es-ES"; "fr" -> "fr-FR"; "pt" -> "pt-BR"; "de" -> "de-DE"; "ar" -> "ar"
            "tr" -> "tr-TR"; "it" -> "it-IT"; "pl" -> "pl-PL"; "da" -> "da-DK"; "cs" -> "cs-CZ"
            "hi" -> "hi-IN"; "ko" -> "ko-KR"; "zh" -> "zh-CN"; else -> "en-US"
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var heardSomething = false
    private var quietRounds = 0

    fun start(): Boolean {
        if (!available(context)) return false
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
        running = true
        listen()
        return true
    }

    /** Stops listening; a phrase in progress is still delivered. */
    fun stop() {
        if (!running) return
        running = false
        recognizer?.stopListening()
    }

    fun cancel() {
        running = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale(lang))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Allow long pauses inside a sentence before a phrase is considered finished.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        heardSomething = false
        recognizer?.startListening(intent)
    }

    private fun finish(error: Int?) {
        running = false
        recognizer?.destroy()
        recognizer = null
        onEnd(error)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { heardSomething = true }
        override fun onRmsChanged(rmsdB: Float) { onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            if (text.isNotEmpty()) { quietRounds = 0; onFinal(text) } else quietRounds++
            if (running && quietRounds < 2) listen() else finish(null)
        }

        override fun onError(error: Int) {
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (quiet) {
                quietRounds++
                if (running && quietRounds < 2) { listen(); return }
                finish(null)
            } else if (error == SpeechRecognizer.ERROR_CLIENT && !running) finish(null)
            else finish(error)
        }
    }
}

/**
 * Words learned from online dictation (names, rare words) are kept on the phone and given to the
 * offline Whisper model as context, so offline dictation gets better at the words you use.
 */
object VoiceVocabulary {
    private const val PREFS = "voice_vocabulary"
    private const val MAX = 80
    const val PREF_LEARN = "voice_learn_online"

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remembers words from an online transcript that a generic model is likely to miss. */
    fun learn(c: Context, lang: String, text: String, isCommon: (String) -> Boolean) {
        val words = Regex("[\\p{L}][\\p{L}'’-]{2,}").findAll(text).map { it.value }
            .filter { w -> !isCommon(w.lowercase()) }
            .distinct().toList()
        if (words.isEmpty()) return
        val key = "w_$lang"
        val old = sp(c).getString(key, "").orEmpty().split('\n').filter { it.isNotBlank() }
        val merged = (words + old.filter { it !in words }).take(MAX)
        sp(c).edit().putString(key, merged.joinToString("\n")).apply()
    }

    fun words(c: Context, lang: String): List<String> =
        sp(c).getString("w_$lang", "").orEmpty().split('\n').filter { it.isNotBlank() }

    /** Whisper prompt: a natural example sentence plus the user's own vocabulary. */
    fun prompt(c: Context, lang: String): String {
        val base = WhisperLib.promptFor(lang)
        val w = words(c, lang).take(40)
        return if (w.isEmpty()) base else base + " " + w.joinToString(", ") + "."
    }

    fun count(c: Context): Int = sp(c).all.values.sumOf { (it as? String)?.split('\n')?.count { s -> s.isNotBlank() } ?: 0 }
    fun clear(c: Context) = sp(c).edit().clear().apply()
}

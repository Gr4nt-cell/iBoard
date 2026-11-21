package com.gr4nt.iboard.voice

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.gr4nt.iboard.ai.GeminiNano
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dictation with ML Kit GenAI speech recognition, which runs on the phone through AICore
 * (Gemini Nano devices). Words stream in while speaking. Must be used from the main thread.
 */
class NanoSpeech(
    private val scope: CoroutineScope,
    private val language: String,
    private val onStatus: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onEnd: (error: String?) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var job: Job? = null
    private var ended = false

    fun start() {
        job = scope.launch {
            var error: String? = null
            try {
                // Advanced mode (Gemini Nano) is the most accurate; basic mode covers phones without it.
                var r = client(SpeechRecognizerOptions.Mode.MODE_ADVANCED)
                if (r.checkStatus() == FeatureStatus.UNAVAILABLE) {
                    runCatching { r.close() }
                    r = client(SpeechRecognizerOptions.Mode.MODE_BASIC)
                }
                recognizer = r
                when (r.checkStatus()) {
                    FeatureStatus.UNAVAILABLE -> { error = UNAVAILABLE; return@launch }
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                        onStatus("Getting the speech model…")
                        r.download().collect { }
                        if (r.checkStatus() != FeatureStatus.AVAILABLE) { error = "The speech model couldn't be downloaded. Try again later."; return@launch }
                    }
                }
                onStatus("Listening · Tap mic to finish")
                r.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromMic() }).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse -> onPartial(response.text)
                        is SpeechRecognizerResponse.FinalTextResponse -> onFinal(response.text)
                        is SpeechRecognizerResponse.ErrorResponse -> error = GeminiNano.describe(response.e)
                        else -> {}
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("NanoSpeech", "recognition failed", e)
                error = GeminiNano.describe(e)
            } finally {
                withContext(NonCancellable) {
                    runCatching { recognizer?.close() }
                    recognizer = null
                    if (!ended) { ended = true; onEnd(error) }
                }
            }
        }
    }

    private fun client(mode: Int): SpeechRecognizer = SpeechRecognition.getClient(speechRecognizerOptions {
        locale = Locale.forLanguageTag(language)
        preferredMode = mode
    })

    /** Stops listening; the last words still arrive before [onEnd]. */
    fun stop() {
        val r = recognizer ?: return cancel()
        scope.launch { runCatching { r.stopRecognition() } }
    }

    /** Ends at once and drops any late words. */
    fun cancel() {
        ended = true
        job?.cancel()
        job = null
    }

    companion object {
        const val UNAVAILABLE = "On-device dictation needs a phone with Gemini Nano (AICore)."
    }
}

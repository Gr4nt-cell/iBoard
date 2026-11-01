package com.gr4nt.iboard.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.sqrt

/** Thin wrapper over the whisper.cpp JNI bridge (libiboard_whisper.so). */
object WhisperLib {
    @Volatile
    private var loaded: Boolean? = null

    fun available(): Boolean {
        loaded?.let { return it }
        return try {
            System.loadLibrary("iboard_whisper"); true
        } catch (_: Throwable) {
            false
        }.also { loaded = it }
    }

    @JvmStatic private external fun nativeInit(path: String): Long
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeProgress(handle: Long): Int
    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeTranscribe(handle: Long, samples: FloatArray, lang: String, threads: Int, prompt: String, timeoutMs: Int): ByteArray

    /** One decoder per model. Cancellation/progress may be called from the UI safely. */
    class Model internal constructor(private var handle: Long, val path: String) {
        private val lock = Any()
        private var inFlight = false
        private var closeRequested = false

        fun transcribe(samples: FloatArray, lang: String, prompt: String, timeoutMs: Int = 45000): String {
            if (samples.isEmpty()) return ""
            val h = synchronized(lock) {
                if (handle == 0L || closeRequested) return ""
                check(!inFlight) { "Speech decoder is already in use" }
                inFlight = true
                handle
            }
            try {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                return String(nativeTranscribe(h, samples, lang, threads, prompt, timeoutMs), Charsets.UTF_8)
                    .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
                    .replace(Regex("\\s+"), " ").trim()
            } finally {
                synchronized(lock) {
                    inFlight = false
                    if (closeRequested) free()
                }
            }
        }

        fun progress(): Int = synchronized(lock) { if (handle == 0L) 0 else nativeProgress(handle) }
        fun cancel() = synchronized(lock) { if (handle != 0L) nativeCancel(handle) }
        fun close() = synchronized(lock) {
            closeRequested = true
            if (handle != 0L) nativeCancel(handle)
            // Free only after the active JNI call exits, even if close races with cancellation.
            if (!inFlight) free()
        }
        private fun free() {
            if (handle != 0L) nativeFree(handle)
            handle = 0L
        }
    }

    fun load(path: String): Model? {
        if (!available()) return null
        val h = nativeInit(path)
        return if (h == 0L) null else Model(h, path)
    }

    /** Short example text in each language nudges Whisper towards punctuated, capitalized output. */
    fun promptFor(lang: String) = when (lang) {
        "es" -> "Hola, ¿cómo estás? Bien, gracias. Nos vemos mañana."
        "fr" -> "Bonjour, comment ça va ? Très bien, merci. À demain."
        "pt" -> "Olá, tudo bem? Tudo ótimo, obrigado. Até amanhã."
        "de" -> "Hallo, wie geht's? Gut, danke. Bis morgen."
        "ar" -> "مرحبا، كيف حالك؟ بخير، شكرا. أراك غدا."
        else -> "Hello, how are you? I'm fine, thanks. See you tomorrow."
    }
}

/**
 * Records 16 kHz mono audio and cuts it into chunks at natural pauses, so each piece can be
 * transcribed while the user keeps talking.
 */
class PauseChunker(
    private val onChunk: (FloatArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onLongSilence: () -> Unit,
    private val onError: () -> Unit = {},
    private val onFinished: () -> Unit = {},
) {
    private val rate = 16000
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        } catch (_: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        recorder = rec
        running = true
        thread = Thread {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Exception) {}
            val frame = ShortArray(rate / 50) // 20 ms
            val chunk = Samples(rate * 30)
            var speechSamples = 0
            var silenceSamples = 0
            var idleSamples = 0
            var noiseFloor = 0.004f
            try {
                synchronized(rec) { if (running) rec.startRecording() }
                while (running) {
                    val n = rec.read(frame, 0, frame.size, AudioRecord.READ_NON_BLOCKING)
                    if (n == 0) { Thread.sleep(10); continue }
                    if (n < 0) { if (running) onError(); break }
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = frame[i] / 32768f
                        chunk.add(v)
                        sum += v * v
                    }
                    val rms = sqrt(sum / n).toFloat()
                    onLevel(rms)
                    val speaking = rms > maxOf(0.012f, noiseFloor * 3f)
                    if (!speaking) noiseFloor = noiseFloor * 0.97f + rms * 0.03f
                    if (speaking) { speechSamples += n; silenceSamples = 0; idleSamples = 0 } else { silenceSamples += n; idleSamples += n }
                    val seconds = chunk.size / rate
                    // Cut at a pause after some speech, or force a cut on very long stretches.
                    if ((speechSamples >= rate * 160 / 1000 && silenceSamples >= rate * 700 / 1000 && seconds >= 1) || seconds >= 8) {
                        if (speechSamples >= rate * 120 / 1000) onChunk(chunk.toArray())
                        chunk.clear(); speechSamples = 0; silenceSamples = 0
                    } else if (speechSamples == 0 && chunk.size > rate * 2) {
                        // Pure silence: keep only the last half second as lead-in.
                        chunk.keepLast(rate / 2)
                    }
                    if (idleSamples >= rate * 8) { running = false; onLongSilence() }
                }
            } catch (_: Exception) {
                if (running) onError()
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                if (speechSamples >= rate * 120 / 1000) onChunk(chunk.toArray())
                try { rec.release() } finally { recorder = null; onFinished() }
            }
        }.apply { name = "iBoard-mic"; start() }
        return true
    }

    /** Stops recording; any speech still in the buffer is delivered as a final chunk. */
    fun stop() {
        running = false
        // The recording thread owns stop/release. Nonblocking reads let it exit promptly.
        // Never block the keyboard thread waiting for audio I/O.
    }

    /** Growable float buffer without boxing. */
    private class Samples(capacity: Int) {
        private var data = FloatArray(capacity)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
        fun clear() { size = 0 }

        fun keepLast(n: Int) {
            if (size <= n) return
            System.arraycopy(data, size - n, data, 0, n)
            size = n
        }
    }
}

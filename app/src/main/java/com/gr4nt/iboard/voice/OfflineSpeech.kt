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
    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeTranscribe(handle: Long, samples: FloatArray, lang: String, threads: Int, prompt: String): ByteArray

    /** A loaded speech model. Not thread-safe: use from one worker thread. */
    class Model internal constructor(private var handle: Long, val path: String) {
        fun transcribe(samples: FloatArray, lang: String, prompt: String): String {
            if (handle == 0L || samples.isEmpty()) return ""
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            return String(nativeTranscribe(handle, samples, lang, threads, prompt), Charsets.UTF_8)
                .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ") // drop [BLANK_AUDIO], (music) …
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun close() {
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
) {
    private val rate = 16000
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        } catch (_: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frame = ShortArray(rate / 50) // 20 ms
            val chunk = Samples(rate * 30)
            var speechMs = 0
            var silenceMs = 0
            var idleMs = 0
            var noiseFloor = 0.004f
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
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
                    if (speaking) { speechMs += 20; silenceMs = 0; idleMs = 0 } else { silenceMs += 20; idleMs += 20 }
                    val seconds = chunk.size / rate
                    // Cut at a pause after some speech, or force a cut on very long stretches.
                    if ((speechMs > 400 && silenceMs >= 700 && seconds >= 1) || seconds >= 25) {
                        if (speechMs > 300) onChunk(chunk.toArray())
                        chunk.clear(); speechMs = 0; silenceMs = 0
                    } else if (speechMs == 0 && chunk.size > rate * 2) {
                        // Pure silence: keep only the last half second as lead-in.
                        chunk.keepLast(rate / 2)
                    }
                    if (idleMs >= 8000) { running = false; onLongSilence() }
                }
                if (speechMs > 300) onChunk(chunk.toArray())
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }.apply { name = "iBoard-mic"; start() }
        return true
    }

    /** Stops recording; any speech still in the buffer is delivered as a final chunk. */
    fun stop() {
        running = false
        val t = thread
        if (t != null && t != Thread.currentThread()) t.join(1500)
        thread = null
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

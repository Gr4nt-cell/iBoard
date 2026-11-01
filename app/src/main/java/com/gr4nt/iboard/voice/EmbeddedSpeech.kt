package com.gr4nt.iboard.voice

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Extracts only the model shipped in the APK. Never downloads or stores microphone audio. */
object EmbeddedSpeech {
    const val NAME = "ggml-base-q5_1.bin"
    private val lock = Any()
    fun model(context: Context): File = synchronized(lock) {
        val expected = context.assets.open("models/speech.sha256").bufferedReader().use { it.readText().trim().split(' ')[0] }
        val dir = File(context.noBackupFilesDir, "embedded-speech").apply { mkdirs() }
        val dest = File(dir, NAME)
        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if (dest.isFile && digest(dest) == expected) return@synchronized dest
        val tmp = File(dir, "$NAME.tmp")
        try {
            context.assets.open("models/$NAME").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(digest(tmp) == expected) { "The bundled speech model is damaged" }
            check(tmp.renameTo(dest)) { "Unable to prepare bundled speech model" }
            dest
        } finally { tmp.delete() }
    }
}

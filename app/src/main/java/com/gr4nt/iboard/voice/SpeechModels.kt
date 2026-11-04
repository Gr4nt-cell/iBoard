package com.gr4nt.iboard.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Voice models are not shipped in the app. The user downloads one from Settings › Voice Typing;
 * Android's download manager fetches it (with a notification), then it is checked and moved into
 * the app's private storage. Only the model file is downloaded; audio never leaves the phone.
 */
object SpeechModels {
    data class Model(
        val id: String, val title: String, val detail: String, val file: String,
        val approxMb: Int, val sha256: String? = null, val exactBytes: Long? = null,
    ) {
        val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
    }

    val all = listOf(
        Model("tiny", "Fast", "Whisper Tiny · quickest, less accurate", "ggml-tiny-q5_1.bin", 31),
        Model("base", "Balanced", "Whisper Base · recommended", "ggml-base-q5_1.bin", 57,
            "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", 59707625L),
        Model("small", "Accurate", "Whisper Small · best quality, slower", "ggml-small-q5_1.bin", 181),
    )
    const val DEFAULT = "base"
    private const val PREFS = "voice_models"
    private const val SELECTED = "selected"

    sealed class State {
        object NotInstalled : State()
        data class Downloading(val percent: Int) : State()
        object Verifying : State()
        object Installed : State()
        data class Failed(val reason: String) : State()
    }

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dm(c: Context) = c.getSystemService(DownloadManager::class.java)
    private fun dir(c: Context) = File(c.noBackupFilesDir, "speech").apply { mkdirs() }
    fun file(c: Context, m: Model) = File(dir(c), m.file)
    fun byId(id: String?) = all.firstOrNull { it.id == id }

    fun isInstalled(c: Context, m: Model) = file(c, m).isFile

    /** The model dictation uses: the chosen one if installed, otherwise any installed model. */
    fun active(c: Context, install: Boolean = false): Model? {
        if (install) all.forEach { finishIfDone(c, it) }
        val chosen = byId(sp(c).getString(SELECTED, DEFAULT))
        return chosen?.takeIf { isInstalled(c, it) } ?: all.firstOrNull { isInstalled(c, it) }
    }

    fun select(c: Context, m: Model) = sp(c).edit().putString(SELECTED, m.id).apply()
    fun selectedId(c: Context) = sp(c).getString(SELECTED, DEFAULT) ?: DEFAULT

    fun start(c: Context, m: Model) {
        cancel(c, m)
        val partial = File(c.getExternalFilesDir(null), "speech-download/${m.file}")
        partial.parentFile?.mkdirs(); partial.delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("iBoard voice model")
            .setDescription("${m.title} (${m.approxMb} MB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(partial))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm(c).enqueue(req)
        sp(c).edit().putLong("dl_${m.id}", id).remove("err_${m.id}").putString(SELECTED, m.id).apply()
    }

    fun cancel(c: Context, m: Model) {
        val id = sp(c).getLong("dl_${m.id}", -1)
        if (id >= 0) runCatching { dm(c).remove(id) }
        sp(c).edit().remove("dl_${m.id}").apply()
    }

    fun delete(c: Context, m: Model) {
        cancel(c, m)
        file(c, m).delete()
        sp(c).edit().remove("err_${m.id}").apply()
    }

    /** Cheap status for the UI. Call [finishIfDone] off the main thread to install finished downloads. */
    fun state(c: Context, m: Model): State {
        if (isInstalled(c, m)) return State.Installed
        sp(c).getString("err_${m.id}", null)?.let { return State.Failed(it) }
        val id = sp(c).getLong("dl_${m.id}", -1)
        if (id < 0) return State.NotInstalled
        if (verifying.contains(m.id)) return State.Verifying
        dm(c).query(DownloadManager.Query().setFilterById(id))?.use { cur ->
            if (!cur.moveToFirst()) return State.NotInstalled
            val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> State.Verifying
                DownloadManager.STATUS_FAILED -> State.Failed("Download failed. Check your connection and try again.")
                else -> State.Downloading(if (total > 0) (done * 100 / total).toInt() else 0)
            }
        }
        return State.NotInstalled
    }

    private val verifying = java.util.Collections.synchronizedSet(HashSet<String>())

    /** If the download manager finished a model, check it and move it into private storage. */
    fun finishIfDone(c: Context, m: Model) {
        val id = sp(c).getLong("dl_${m.id}", -1)
        if (id < 0 || !verifying.add(m.id)) return
        try {
            val status = dm(c).query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                if (cur.moveToFirst()) cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) return
            val partial = File(c.getExternalFilesDir(null), "speech-download/${m.file}")
            val problem = check(partial, m)
            if (problem == null) {
                val tmp = File(dir(c), m.file + ".tmp")
                partial.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(file(c, m))) { tmp.delete(); error("Unable to save the voice model") }
            } else sp(c).edit().putString("err_${m.id}", problem).apply()
            partial.delete()
            runCatching { dm(c).remove(id) }
            sp(c).edit().remove("dl_${m.id}").apply()
        } catch (e: Exception) {
            sp(c).edit().putString("err_${m.id}", e.message ?: "Unable to install the voice model").remove("dl_${m.id}").apply()
        } finally { verifying.remove(m.id) }
    }

    private fun check(f: File, m: Model): String? {
        if (!f.isFile) return "The download is missing. Please try again."
        if (m.exactBytes != null && f.length() != m.exactBytes) return "The download was incomplete. Please try again."
        if (f.length() < m.approxMb * 1_000_000L * 8 / 10) return "The download was incomplete. Please try again."
        val magic = f.inputStream().use { val b = ByteArray(4); if (it.read(b) == 4) b else null }
        // whisper.cpp model files start with "lmgg" (0x67676d6c, little-endian).
        if (magic == null || String(magic, Charsets.US_ASCII) != "lmgg") return "That file isn't a voice model. Please try again."
        if (m.sha256 != null && sha256(f) != m.sha256) return "The voice model failed its integrity check. Please try again."
        return null
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

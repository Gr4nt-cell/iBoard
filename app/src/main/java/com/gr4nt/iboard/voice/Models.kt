package com.gr4nt.iboard.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** Downloadable offline packs. All are free, openly licensed and fetched from Hugging Face. */
enum class ModelKind { SPEECH, REWRITE }

data class OfflineModel(
    val id: String,
    val kind: ModelKind,
    val title: String,
    val subtitle: String,
    val sizeMb: Int,
    val url: String,
    val fileName: String,
)

object ModelCatalog {
    val all = listOf(
        OfflineModel(
            "whisper-base", ModelKind.SPEECH, "Fast", "Whisper Base · quick on most phones", 57,
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin", "ggml-base-q5_1.bin",
        ),
        OfflineModel(
            "whisper-small", ModelKind.SPEECH, "Accurate", "Whisper Small · better with accents and noise", 190,
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", "ggml-small-q5_1.bin",
        ),
        OfflineModel(
            "qwen-0.5b", ModelKind.REWRITE, "Standard", "Qwen2.5 0.5B · light, about 1 GB of memory", 547,
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "qwen2.5-0.5b-q8.task",
        ),
        OfflineModel(
            "qwen-1.5b", ModelKind.REWRITE, "Pro", "Qwen2.5 1.5B · best rewrites, needs 6 GB+ RAM phones", 1600,
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "qwen2.5-1.5b-q8.task",
        ),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }
    fun of(kind: ModelKind) = all.filter { it.kind == kind }
}

/** Downloads packs with Android's DownloadManager (survives the app closing, shows a notification). */
class ModelStore(private val context: Context) {
    private val sp = context.getSharedPreferences("offline_models", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun dir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
    fun file(m: OfflineModel) = File(dir(), m.fileName)

    sealed class State {
        object Missing : State()
        data class Downloading(val percent: Int) : State()
        object Installed : State()
        data class Failed(val reason: String) : State()
    }

    fun state(m: OfflineModel): State {
        val id = sp.getLong(m.id, -1L)
        if (id >= 0) {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> { sp.edit().remove(m.id).apply() }
                        DownloadManager.STATUS_FAILED -> {
                            sp.edit().remove(m.id).apply()
                            file(m).delete()
                            return State.Failed("Download failed — check your connection and try again")
                        }
                        else -> {
                            val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                            return State.Downloading(pct.coerceIn(0, 99))
                        }
                    }
                } else sp.edit().remove(m.id).apply()
            }
        }
        val f = file(m)
        return if (f.exists() && f.length() > m.sizeMb * 1024L * 1024L / 2) State.Installed else State.Missing
    }

    fun isInstalled(m: OfflineModel?) = m != null && state(m) == State.Installed

    fun download(m: OfflineModel, wifiOnly: Boolean) {
        file(m).delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("iBoard offline dictation")
            .setDescription("${m.subtitle} (${m.sizeMb} MB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file(m)))
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        sp.edit().putLong(m.id, dm.enqueue(req)).apply()
    }

    fun cancel(m: OfflineModel) {
        val id = sp.getLong(m.id, -1L)
        if (id >= 0) dm.remove(id)
        sp.edit().remove(m.id).apply()
        file(m).delete()
    }

    fun delete(m: OfflineModel) = cancel(m)

    /** The selected model of [kind] if installed, otherwise any installed one. */
    fun active(kind: ModelKind, preferredId: String?): OfflineModel? {
        val preferred = ModelCatalog.byId(preferredId)?.takeIf { it.kind == kind && isInstalled(it) }
        return preferred ?: ModelCatalog.of(kind).firstOrNull { isInstalled(it) }
    }
}

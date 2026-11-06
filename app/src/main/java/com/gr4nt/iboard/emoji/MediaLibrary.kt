package com.gr4nt.iboard.emoji

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File
import java.util.concurrent.Executors

/** The bundled AURA stickers and GIFs (assets/media), their previews, and sending them into the focused app. */
object MediaLibrary {
    data class Entry(val gif: Boolean, val pack: String, val path: String, val label: String)

    private var entries: List<Entry>? = null
    private val loader = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Drawable>(48) {}

    fun entries(context: Context): List<Entry> = entries ?: context.assets.open("media/index.tsv").bufferedReader().useLines { lines ->
        lines.mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 4) null else Entry(p[0] == "gif", p[1], "media/" + p[2], p[3])
        }.toList()
    }.also { entries = it }

    fun gifs(context: Context) = entries(context).filter { it.gif }

    /** Sticker packs in display order: captioned stickers first. */
    fun stickerPacks(context: Context): List<Pair<String, List<Entry>>> {
        val packs = entries(context).filter { !it.gif }.groupBy { it.pack }
        val order = listOf("Words") + packs.keys.filter { it != "Words" }
        return order.mapNotNull { k -> packs[k]?.let { k to it } }
    }

    /** Loads a preview (animated on Android 9+) off the main thread. */
    fun preview(context: Context, path: String, sizePx: Int, done: (Drawable) -> Unit) {
        cache.get(path)?.let { done(it); return }
        val app = context.applicationContext
        loader.execute {
            val d: Drawable? = try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(app.assets, path)) { dec, info, _ ->
                        val s = minOf(1f, sizePx / maxOf(info.size.width, info.size.height).toFloat())
                        dec.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt()))
                    }
                } else {
                    app.assets.open(path).use { BitmapFactory.decodeStream(it) }?.let { BitmapDrawable(app.resources, it) }
                }
            } catch (_: Exception) { null }
            if (d != null) main.post { cache.put(path, d); done(d) }
        }
    }

    /**
     * Inserts the sticker or GIF into the app being typed in. Returns false when that field
     * doesn't take images (the caller tells the user).
     */
    fun send(service: InputMethodService, e: Entry): Boolean {
        val info = service.currentInputEditorInfo ?: return false
        val ic = service.currentInputConnection ?: return false
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        fun takes(m: String) = accepted.any { ClipDescription.compareMimeTypes(m, it) }
        val mime = when {
            e.gif && takes("image/gif") -> "image/gif"
            !e.gif && takes("image/png") -> "image/png"
            !e.gif && takes("image/webp") -> "image/webp"
            else -> return false
        }
        val dir = File(service.cacheDir, "media").apply { mkdirs() }
        val name = "aura_" + e.path.removePrefix("media/").replace('/', '_')
        val file = File(dir, if (mime == "image/png") name.substringBeforeLast('.') + ".png" else name)
        try {
            if (!file.exists()) {
                if (mime == "image/png") {
                    val bmp = service.assets.open(e.path).use { BitmapFactory.decodeStream(it) } ?: return false
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } else {
                    service.assets.open(e.path).use { i -> file.outputStream().use { i.copyTo(it) } }
                }
            }
            val uri = FileProvider.getUriForFile(service, service.packageName + ".media", file)
            val content = InputContentInfoCompat(uri, ClipDescription(e.label, arrayOf(mime)), null)
            return InputConnectionCompat.commitContent(ic, info, content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        } catch (_: Exception) {
            return false
        }
    }
}

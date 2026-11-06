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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * AURA stickers and GIFs. They are optional downloads (Settings › Stickers & GIFs, or the tabs in the
 * emoji panel), kept in the app's private files. Also previews them and sends them into the focused app.
 */
object MediaLibrary {
    data class Entry(val gif: Boolean, val pack: String, val path: String, val label: String)

    enum class Pack(val id: String, val title: String, val mb: Int, val file: String) {
        STICKERS("stickers", "AURA Stickers", 5, "stickers-1.zip"),
        GIFS("gifs", "AURA GIFs", 13, "gifs-1.zip"),
    }

    private const val BASE = "https://raw.githubusercontent.com/Gr4nt-cell/IBoard-Privacy/refs/heads/stickers/"

    private val entries = HashMap<Pack, List<Entry>>()
    private val loader = Executors.newFixedThreadPool(2)
    private val downloader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Drawable>(48) {}

    /** Percent done for packs being downloaded right now. */
    private val active = HashMap<Pack, Int>()
    private val watchers = ArrayList<() -> Unit>()

    private fun dir(context: Context, p: Pack) = File(context.filesDir, "media/${p.id}")

    fun installed(context: Context, p: Pack) = File(dir(context, p), "index.tsv").exists()

    fun progress(p: Pack): Int? = active[p]

    /** Called on the main thread whenever a download moves on, finishes or fails. */
    fun watch(w: () -> Unit) { watchers += w }
    fun unwatch(w: () -> Unit) { watchers -= w }
    private fun notifyAll() = main.post { watchers.toList().forEach { it() } }

    private fun entries(context: Context, p: Pack): List<Entry> = entries[p] ?: run {
        val d = dir(context, p)
        val f = File(d, "index.tsv")
        if (!f.exists()) return emptyList()
        f.readLines().mapNotNull { l ->
            val c = l.split('\t')
            if (c.size < 4) null else Entry(c[0] == "gif", c[1], File(d, c[2]).path, c[3])
        }.also { entries[p] = it }
    }

    fun gifs(context: Context) = entries(context, Pack.GIFS)

    /** Sticker packs in display order: captioned stickers first. */
    fun stickerPacks(context: Context): List<Pair<String, List<Entry>>> {
        val packs = entries(context, Pack.STICKERS).groupBy { it.pack }
        val order = listOf("Words") + packs.keys.filter { it != "Words" }
        return order.mapNotNull { k -> packs[k]?.let { k to it } }
    }

    /** Downloads and unpacks a pack in the background. [done] runs on the main thread. */
    fun download(context: Context, p: Pack, done: (Boolean) -> Unit = {}) {
        if (active.containsKey(p)) return
        val app = context.applicationContext
        active[p] = 0
        notifyAll()
        downloader.execute {
            val ok = try {
                val tmp = File(app.cacheDir, p.file)
                val c = URL(BASE + p.file).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: (p.mb * 1_000_000L)
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L; var last = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            val pc = (got * 100 / total).toInt().coerceIn(0, 99)
                            if (pc != last) { last = pc; main.post { active[p] = pc }; notifyAll() }
                        }
                    }
                }
                val staging = File(app.filesDir, "media/${p.id}.part").apply { deleteRecursively(); mkdirs() }
                val root = staging.canonicalPath + File.separator
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val out = File(staging, e.name)
                        if (!out.canonicalPath.startsWith(root)) throw SecurityException("bad entry")
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { z.copyTo(it) }
                    }
                }
                tmp.delete()
                if (!File(staging, "index.tsv").exists()) throw IllegalStateException("no index")
                val target = dir(app, p)
                target.deleteRecursively()
                staging.renameTo(target)
            } catch (_: Exception) { false }
            main.post {
                active.remove(p)
                entries.remove(p)
                cache.evictAll()
                watchers.toList().forEach { it() }
                done(ok)
            }
        }
    }

    fun remove(context: Context, p: Pack) {
        dir(context, p).deleteRecursively()
        File(context.cacheDir, "media").deleteRecursively()
        entries.remove(p)
        cache.evictAll()
        notifyAll()
    }

    /** Loads a preview (animated on Android 9+) off the main thread. */
    fun preview(context: Context, path: String, sizePx: Int, done: (Drawable) -> Unit) {
        cache.get(path)?.let { done(it); return }
        val res = context.applicationContext.resources
        loader.execute {
            val d: Drawable? = try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { dec, info, _ ->
                        val s = minOf(1f, sizePx / maxOf(info.size.width, info.size.height).toFloat())
                        dec.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt()))
                    }
                } else {
                    BitmapFactory.decodeFile(path)?.let { BitmapDrawable(res, it) }
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
        val src = File(e.path)
        val dir = File(service.cacheDir, "media").apply { mkdirs() }
        val name = "aura_" + src.parentFile?.name + "_" + src.name
        val file = File(dir, if (mime == "image/png") name.substringBeforeLast('.') + ".png" else name)
        try {
            if (!file.exists()) {
                if (mime == "image/png") {
                    val bmp = BitmapFactory.decodeFile(src.path) ?: return false
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } else {
                    src.copyTo(file, overwrite = true)
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

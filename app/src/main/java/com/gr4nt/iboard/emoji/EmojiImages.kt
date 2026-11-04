package com.gr4nt.iboard.emoji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache

/** Renders exclusively the AURA artwork bundled with iBoard. */
object EmojiImages {
    const val AURA = "aura"
    /** Emoji are never drawn larger than this on a keyboard, so bigger source art is scaled down on load. */
    private const val MAX_PX = 192
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    @Volatile private var available: Set<String>? = null

    fun configure(context: Context, style: String) { init(context) }
    fun init(context: Context) {
        if (available == null) synchronized(this) {
            if (available == null) available = context.applicationContext.assets.list("aura")
                ?.filter { it.endsWith(".png") }?.toSet() ?: emptySet()
        }
    }
    fun key(emoji: String): String = emoji.codePoints().toArray()
        .filter { it != 0xFE0F }.joinToString("_") { Integer.toHexString(it) }
    fun has(emoji: String): Boolean = (key(emoji) + ".png") in (available ?: emptySet())

    private fun load(context: Context, filename: String): Bitmap? = runCatching {
        val assets = context.applicationContext.assets
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open("aura/$filename").use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val decoded = assets.open("aura/$filename").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val big = maxOf(decoded.width, decoded.height)
        if (big <= MAX_PX) decoded else {
            val s = MAX_PX.toFloat() / big
            Bitmap.createScaledBitmap(decoded, (decoded.width * s).toInt(), (decoded.height * s).toInt(), true)
                .also { if (it !== decoded) decoded.recycle() }
        }
    }.getOrNull()

    fun draw(context: Context, canvas: Canvas, emoji: String, cx: Float, cy: Float, size: Float) {
        init(context)
        val filename = key(emoji) + ".png"
        if (filename !in (available ?: emptySet())) return
        val bitmap = synchronized(cache) {
            cache.get(filename) ?: load(context, filename)?.also { cache.put(filename, it) }
        } ?: return
        val half = size * 0.55f
        dst.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
    }
}

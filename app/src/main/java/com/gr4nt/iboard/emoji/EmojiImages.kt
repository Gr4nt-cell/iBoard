package com.gr4nt.iboard.emoji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache

/**
 * Glossy 3D emoji art (Microsoft Fluent Emoji, MIT licence) bundled as small WebP files.
 * Emoji without art (mostly flags) fall back to the phone's emoji font.
 */
object EmojiImages {
    @Volatile
    private var available: Set<String>? = null
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // Emoji are drawn 10% less saturated for a softer look.
    private val softer = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.9f) })
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = softer }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; colorFilter = softer }
    private val rect = RectF()

    /** False when the user picked "Phone's emoji font": everything is drawn with the system font. */
    @Volatile
    var useArt = true

    /** Lists the bundled art once. Safe to call from any thread. */
    fun init(context: Context) {
        if (available != null) return
        synchronized(this) {
            if (available != null) return
            available = context.assets.list("emoji3d")?.map { it.removeSuffix(".webp") }?.toHashSet() ?: emptySet()
        }
    }

    fun key(emoji: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            if (cp != 0xFE0F) {
                if (sb.isNotEmpty()) sb.append('_')
                sb.append(Integer.toHexString(cp))
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun has(emoji: String): Boolean = useArt && available?.contains(key(emoji)) == true

    fun bitmap(context: Context, emoji: String): Bitmap? {
        if (!useArt) return null
        val k = key(emoji)
        cache.get(k)?.let { return it }
        if (available?.contains(k) != true) return null
        return try {
            context.assets.open("emoji3d/$k.webp").use { BitmapFactory.decodeStream(it) }?.also { cache.put(k, it) }
        } catch (_: Exception) {
            null
        }
    }

    /** Draws [emoji] centered in a square of side [size]: 3D art when bundled, the font otherwise. */
    fun draw(context: Context, canvas: Canvas, emoji: String, cx: Float, cy: Float, size: Float) {
        val bmp = bitmap(context, emoji)
        if (bmp != null) {
            val h = size / 2f
            rect.set(cx - h, cy - h, cx + h, cy + h)
            canvas.drawBitmap(bmp, null, rect, paint)
        } else {
            textPaint.textSize = size * 0.82f
            canvas.drawText(emoji, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }
    }
}

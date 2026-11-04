package com.gr4nt.iboard.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws emoji with Gemoji, iBoard's bundled 3D color emoji font (assets/fonts/gemoji.ttf), or with
 * the phone's own emoji font when the user picks "Phone's Emoji". Anything Gemoji doesn't cover
 * falls back to the phone's font automatically.
 */
object EmojiImages {
    const val GEMOJI = "gemoji"
    const val SYSTEM = "system"

    @Volatile var customTypeface: Typeface? = null
        private set
    @Volatile private var configured = false
    private var gemoji: Typeface? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun gemoji(context: Context): Typeface? = gemoji ?: runCatching {
        Typeface.createFromAsset(context.applicationContext.assets, "fonts/gemoji.ttf")
    }.getOrNull().also { gemoji = it }

    fun configure(context: Context, style: String) {
        customTypeface = if (style == SYSTEM) null else gemoji(context)
        paint.typeface = customTypeface // null = the phone's own emoji font
        EmojiData.setTypeface(customTypeface)
        configured = true
    }

    fun init(context: Context) { if (!configured) configure(context, GEMOJI) }
    fun key(emoji: String): String = emoji.codePoints().toArray().filter { it != 0xFE0F }.joinToString("_") { Integer.toHexString(it) }
    fun has(emoji: String): Boolean = runCatching { paint.hasGlyph(emoji) }.getOrDefault(true)

    fun draw(context: Context, canvas: Canvas, emoji: String, cx: Float, cy: Float, size: Float) {
        init(context)
        paint.textSize = size * 0.9f
        canvas.drawText(emoji, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
    }
}

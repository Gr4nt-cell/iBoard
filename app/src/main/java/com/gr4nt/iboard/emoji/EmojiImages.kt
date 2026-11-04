package com.gr4nt.iboard.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws emoji with the font the user imported on their own phone (Settings › Emoji), or with the
 * phone's built-in emoji font. No third-party emoji artwork is shipped inside the app.
 */
object EmojiImages {
    @Volatile var customTypeface: Typeface? = null
        private set
    private var supported: Set<String> = emptySet()
    private var imported = false
    @Volatile private var configured = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun customFontFile(context: Context) = java.io.File(context.filesDir, "custom_emoji.ttf")

    fun configure(context: Context, style: String) {
        imported = style == "custom" && customFontFile(context).exists()
        customTypeface = if (imported) runCatching { Typeface.createFromFile(customFontFile(context)) }.getOrNull() else null
        if (customTypeface == null) imported = false
        supported = emptySet()
        paint.typeface = customTypeface // null = the phone's own emoji font
        EmojiData.setTypeface(customTypeface)
        configured = true
    }
    fun init(context: Context) { if (!configured) configure(context, "bundled") }
    fun key(emoji: String): String = emoji.codePoints().toArray().filter { it != 0xFE0F }.joinToString("_") { Integer.toHexString(it) }
    fun has(emoji: String): Boolean = runCatching { paint.hasGlyph(emoji) }.getOrDefault(true)

    fun draw(context: Context, canvas: Canvas, emoji: String, cx: Float, cy: Float, size: Float) {
        init(context)
        paint.textSize = size * 0.9f
        canvas.drawText(emoji, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
    }
}

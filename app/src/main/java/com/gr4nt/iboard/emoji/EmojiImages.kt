package com.gr4nt.iboard.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/** Draws complete Unicode emoji sequences using the bundled color font, without bitmap artwork. */
object EmojiImages {
    @Volatile var customTypeface: Typeface? = null
        private set
    private var supported: Set<String> = emptySet()
    private var imported = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun customFontFile(context: Context) = java.io.File(context.filesDir, "custom_emoji.ttf")

    fun configure(context: Context, style: String) {
        imported = style == "custom" && customFontFile(context).exists()
        customTypeface = if (imported) runCatching { Typeface.createFromFile(customFontFile(context)) }.getOrNull() else null
        if (customTypeface == null) {
            imported = false
            customTypeface = Typeface.createFromAsset(context.assets, "fonts/ios-style.ttf")
        }
        supported = context.assets.open("fonts/supported_emoji.txt").bufferedReader().useLines { it.toSet() }
        paint.typeface = customTypeface
        EmojiData.setTypeface(customTypeface)
    }
    fun init(context: Context) { if (customTypeface == null) configure(context, "bundled") }
    fun key(emoji: String): String = emoji.codePoints().toArray().filter { it != 0xFE0F }.joinToString("_") { Integer.toHexString(it) }
    fun has(emoji: String): Boolean = if (imported) paint.hasGlyph(emoji) else key(emoji) in supported

    fun draw(context: Context, canvas: Canvas, emoji: String, cx: Float, cy: Float, size: Float) {
        init(context)
        paint.textSize = size * 0.9f
        canvas.drawText(emoji, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
    }
}

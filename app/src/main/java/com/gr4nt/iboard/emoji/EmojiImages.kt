package com.gr4nt.iboard.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Personal build: draws emoji with iBoard Personal Emoji (assets/fonts/personal.ttf). Not for distribution.
 * Lumo covers the full Unicode emoji set, including skin tones, families and flags.
 */
object EmojiImages {
    const val LUMO = "lumo"

    @Volatile private var configured = false
    @Volatile var typeface: Typeface? = null
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun configure(context: Context, style: String) { init(context) }

    fun init(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            typeface = runCatching { Typeface.createFromAsset(context.applicationContext.assets, "fonts/personal.ttf") }.getOrNull()
            paint.typeface = typeface
            configured = true
        }
    }

    fun key(emoji: String): String = emoji.codePoints().toArray()
        .filter { it != 0xFE0F }.joinToString("_") { Integer.toHexString(it) }

    fun has(emoji: String): Boolean = runCatching { paint.hasGlyph(emoji) }.getOrDefault(true)

    fun draw(context: Context, canvas: Canvas, emoji: String, cx: Float, cy: Float, size: Float) {
        init(context)
        paint.textSize = size * 0.9f
        canvas.drawText(emoji, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
    }
}

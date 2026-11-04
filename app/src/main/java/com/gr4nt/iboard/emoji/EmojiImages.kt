package com.gr4nt.iboard.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/** Renders only sequences verified in the bundled, restyled AURA color font. */
object EmojiImages {
    const val AURA = "aura"
    @Volatile private var auraTypeface: Typeface? = null
    @Volatile private var supported: Set<String> = emptySet()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Keep the existing call shape while there is only one installed emoji style. */
    fun configure(context: Context, style: String) { init(context) }

    fun init(context: Context) {
        if (auraTypeface != null && supported.isNotEmpty()) return
        synchronized(this) {
            if (auraTypeface == null || supported.isEmpty()) {
                val appContext = context.applicationContext
                val font = Typeface.createFromAsset(appContext.assets, "fonts/aura-emoji.ttf")
                val covered = appContext.assets.open("fonts/supported_emoji.txt")
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.map(String::trim).filter(String::isNotEmpty).toSet()
                    }
                auraTypeface = font
                supported = covered
                paint.typeface = font
            }
        }
    }

    fun key(emoji: String): String = emoji.codePoints().toArray()
        .filter { it != 0xFE0F }.joinToString("_") { Integer.toHexString(it) }
    fun has(emoji: String): Boolean = key(emoji) in supported

    fun draw(context: Context, canvas: Canvas, emoji: String, cx: Float, cy: Float, size: Float) {
        init(context)
        // Skip missing sequences rather than drawing them with Android's system emoji font.
        if (!has(emoji)) return
        paint.typeface = auraTypeface ?: return
        paint.textSize = size * 0.9f
        canvas.drawText(emoji, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
    }
}

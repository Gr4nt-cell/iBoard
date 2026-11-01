package com.gr4nt.iboard.settings

import android.graphics.*
import android.graphics.drawable.Drawable

/** Shared keyboard hues, with broad translucent pools of color behind the settings cards. */
class GlassBackdrop(private val colors: IntArray) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        paint.shader = LinearGradient(0f, 0f, w, h, colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        for ((i, color) in colors.withIndex()) {
            paint.shader = RadialGradient(w * if (i % 2 == 0) .1f else .95f,
                h * (i + .5f) / colors.size, w * .85f,
                intArrayOf((color and 0xFFFFFF) or 0x88000000.toInt(), color and 0xFFFFFF),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
        }
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.OPAQUE
}

package com.gr4nt.iboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/** Small, crisp line icons drawn in code (SF-Symbols-like weight). */
object Icons {
    enum class Kind { SHIFT, SHIFT_ON, CAPS_LOCK, BACKSPACE, GLOBE, RETURN, EMOJI, CLIPBOARD, SPARKLE, PIN, TRASH, BACK, CLOCK, SMILEY, PAW, FOOD, BALL, CAR, BULB, SYMBOLS, FLAG, KEYBOARD_DOWN, SEARCH, MIC, SUN, DROP, KEYCAP, SPEAKER, WAVE, EYE_OFF, CHECK, CHEVRON, TEXT_AA, NUM, HEIGHT, CORNER, BOOK, CAPSULE, PALETTE, KEYBOARD }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()

    /** Draws [kind] centered in a square of side [size] around (cx, cy). */
    fun draw(canvas: Canvas, kind: Kind, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size
        val h = s / 2f
        stroke.color = color
        stroke.strokeWidth = s * 0.085f
        fill.color = color
        path.reset()
        when (kind) {
            Kind.SHIFT, Kind.SHIFT_ON, Kind.CAPS_LOCK -> {
                val top = cy - h * 0.85f
                val mid = cy + h * 0.05f
                val bottom = if (kind == Kind.CAPS_LOCK) cy + h * 0.45f else cy + h * 0.75f
                path.moveTo(cx, top)
                path.lineTo(cx + h * 0.85f, mid)
                path.lineTo(cx + h * 0.38f, mid)
                path.lineTo(cx + h * 0.38f, bottom)
                path.lineTo(cx - h * 0.38f, bottom)
                path.lineTo(cx - h * 0.38f, mid)
                path.lineTo(cx - h * 0.85f, mid)
                path.close()
                if (kind == Kind.SHIFT) canvas.drawPath(path, stroke) else canvas.drawPath(path, fill)
                if (kind == Kind.CAPS_LOCK) {
                    r.set(cx - h * 0.38f, cy + h * 0.62f, cx + h * 0.38f, cy + h * 0.85f)
                    canvas.drawRect(r, fill)
                }
            }
            Kind.BACKSPACE -> {
                val l = cx - h * 0.95f
                val rr = cx + h * 0.95f
                val t = cy - h * 0.62f
                val b = cy + h * 0.62f
                val notch = cx - h * 0.4f
                path.moveTo(l, cy)
                path.lineTo(notch, t)
                path.lineTo(rr, t)
                path.lineTo(rr, b)
                path.lineTo(notch, b)
                path.close()
                canvas.drawPath(path, stroke)
                val xc = cx + h * 0.28f
                val d = h * 0.25f
                canvas.drawLine(xc - d, cy - d, xc + d, cy + d, stroke)
                canvas.drawLine(xc + d, cy - d, xc - d, cy + d, stroke)
            }
            Kind.GLOBE -> {
                val rad = h * 0.85f
                canvas.drawCircle(cx, cy, rad, stroke)
                r.set(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad)
                canvas.drawOval(r, stroke)
                canvas.drawLine(cx - rad, cy, cx + rad, cy, stroke)
                canvas.drawLine(cx - rad * 0.85f, cy - rad * 0.5f, cx + rad * 0.85f, cy - rad * 0.5f, stroke)
                canvas.drawLine(cx - rad * 0.85f, cy + rad * 0.5f, cx + rad * 0.85f, cy + rad * 0.5f, stroke)
            }
            Kind.RETURN -> {
                val rx = cx + h * 0.7f
                path.moveTo(rx, cy - h * 0.6f)
                path.lineTo(rx, cy + h * 0.15f)
                path.lineTo(cx - h * 0.7f, cy + h * 0.15f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx - h * 0.3f, cy - h * 0.25f)
                path.lineTo(cx - h * 0.72f, cy + h * 0.15f)
                path.lineTo(cx - h * 0.3f, cy + h * 0.55f)
                canvas.drawPath(path, stroke)
            }
            Kind.EMOJI, Kind.SMILEY -> {
                val rad = h * 0.85f
                canvas.drawCircle(cx, cy, rad, stroke)
                canvas.drawCircle(cx - rad * 0.35f, cy - rad * 0.22f, s * 0.06f, fill)
                canvas.drawCircle(cx + rad * 0.35f, cy - rad * 0.22f, s * 0.06f, fill)
                r.set(cx - rad * 0.5f, cy - rad * 0.3f, cx + rad * 0.5f, cy + rad * 0.55f)
                canvas.drawArc(r, 20f, 140f, false, stroke)
            }
            Kind.CLIPBOARD -> {
                r.set(cx - h * 0.62f, cy - h * 0.7f, cx + h * 0.62f, cy + h * 0.9f)
                canvas.drawRoundRect(r, s * 0.1f, s * 0.1f, stroke)
                r.set(cx - h * 0.3f, cy - h * 0.92f, cx + h * 0.3f, cy - h * 0.55f)
                canvas.drawRoundRect(r, s * 0.06f, s * 0.06f, fill)
                canvas.drawLine(cx - h * 0.3f, cy - h * 0.05f, cx + h * 0.3f, cy - h * 0.05f, stroke)
                canvas.drawLine(cx - h * 0.3f, cy + h * 0.35f, cx + h * 0.15f, cy + h * 0.35f, stroke)
            }
            Kind.SPARKLE -> {
                star(cx - h * 0.15f, cy + h * 0.1f, h * 0.75f)
                canvas.drawPath(path, fill)
                path.reset()
                star(cx + h * 0.6f, cy - h * 0.55f, h * 0.32f)
                canvas.drawPath(path, fill)
            }
            Kind.PIN -> {
                path.moveTo(cx - h * 0.35f, cy - h * 0.8f)
                path.lineTo(cx + h * 0.35f, cy - h * 0.8f)
                path.lineTo(cx + h * 0.25f, cy - h * 0.1f)
                path.lineTo(cx + h * 0.55f, cy + h * 0.2f)
                path.lineTo(cx - h * 0.55f, cy + h * 0.2f)
                path.lineTo(cx - h * 0.25f, cy - h * 0.1f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawLine(cx, cy + h * 0.2f, cx, cy + h * 0.9f, stroke)
            }
            Kind.TRASH -> {
                canvas.drawLine(cx - h * 0.75f, cy - h * 0.55f, cx + h * 0.75f, cy - h * 0.55f, stroke)
                canvas.drawLine(cx - h * 0.25f, cy - h * 0.8f, cx + h * 0.25f, cy - h * 0.8f, stroke)
                path.moveTo(cx - h * 0.55f, cy - h * 0.55f)
                path.lineTo(cx - h * 0.45f, cy + h * 0.85f)
                path.lineTo(cx + h * 0.45f, cy + h * 0.85f)
                path.lineTo(cx + h * 0.55f, cy - h * 0.55f)
                canvas.drawPath(path, stroke)
            }
            Kind.BACK -> {
                path.moveTo(cx + h * 0.25f, cy - h * 0.7f)
                path.lineTo(cx - h * 0.45f, cy)
                path.lineTo(cx + h * 0.25f, cy + h * 0.7f)
                canvas.drawPath(path, stroke)
            }
            Kind.KEYBOARD_DOWN -> {
                path.moveTo(cx - h * 0.6f, cy - h * 0.25f)
                path.lineTo(cx, cy + h * 0.35f)
                path.lineTo(cx + h * 0.6f, cy - h * 0.25f)
                canvas.drawPath(path, stroke)
            }
            Kind.SEARCH -> {
                canvas.drawCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.55f, stroke)
                canvas.drawLine(cx + h * 0.25f, cy + h * 0.25f, cx + h * 0.75f, cy + h * 0.75f, stroke)
            }
            Kind.MIC -> {
                r.set(cx - h * 0.3f, cy - h * 0.95f, cx + h * 0.3f, cy + h * 0.25f)
                canvas.drawRoundRect(r, h * 0.3f, h * 0.3f, stroke)
                r.set(cx - h * 0.6f, cy - h * 0.35f, cx + h * 0.6f, cy + h * 0.55f)
                canvas.drawArc(r, 0f, 180f, false, stroke)
                canvas.drawLine(cx, cy + h * 0.55f, cx, cy + h * 0.9f, stroke)
                canvas.drawLine(cx - h * 0.3f, cy + h * 0.9f, cx + h * 0.3f, cy + h * 0.9f, stroke)
            }
            Kind.SUN -> {
                canvas.drawCircle(cx, cy, h * 0.38f, fill)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * h * 0.6f, cy + sn * h * 0.6f, cx + c * h * 0.85f, cy + sn * h * 0.85f, stroke)
                }
            }
            Kind.DROP -> {
                path.moveTo(cx, cy - h * 0.85f)
                path.cubicTo(cx + h * 0.2f, cy - h * 0.45f, cx + h * 0.65f, cy - h * 0.05f, cx + h * 0.6f, cy + h * 0.3f)
                path.cubicTo(cx + h * 0.55f, cy + h * 0.95f, cx - h * 0.55f, cy + h * 0.95f, cx - h * 0.6f, cy + h * 0.3f)
                path.cubicTo(cx - h * 0.65f, cy - h * 0.05f, cx - h * 0.2f, cy - h * 0.45f, cx, cy - h * 0.85f)
                path.close()
                canvas.drawPath(path, fill)
            }
            Kind.KEYCAP -> {
                r.set(cx - h * 0.75f, cy - h * 0.75f, cx + h * 0.75f, cy + h * 0.75f)
                canvas.drawRoundRect(r, h * 0.3f, h * 0.3f, stroke)
                r.set(cx - h * 0.35f, cy - h * 0.35f, cx + h * 0.35f, cy + h * 0.35f)
                canvas.drawRoundRect(r, h * 0.12f, h * 0.12f, fill)
            }
            Kind.KEYBOARD -> {
                r.set(cx - h * 0.9f, cy - h * 0.55f, cx + h * 0.9f, cy + h * 0.55f)
                canvas.drawRoundRect(r, h * 0.18f, h * 0.18f, stroke)
                for (row in 0..1) for (col in 0..3) {
                    canvas.drawCircle(cx - h * 0.5f + col * h * 0.33f, cy - h * 0.2f + row * h * 0.28f, s * 0.035f, fill)
                }
                canvas.drawLine(cx - h * 0.35f, cy + h * 0.33f, cx + h * 0.35f, cy + h * 0.33f, stroke)
            }
            Kind.SPEAKER -> {
                path.moveTo(cx - h * 0.8f, cy - h * 0.25f)
                path.lineTo(cx - h * 0.45f, cy - h * 0.25f)
                path.lineTo(cx - h * 0.05f, cy - h * 0.65f)
                path.lineTo(cx - h * 0.05f, cy + h * 0.65f)
                path.lineTo(cx - h * 0.45f, cy + h * 0.25f)
                path.lineTo(cx - h * 0.8f, cy + h * 0.25f)
                path.close()
                canvas.drawPath(path, fill)
                r.set(cx - h * 0.25f, cy - h * 0.45f, cx + h * 0.45f, cy + h * 0.45f)
                canvas.drawArc(r, -50f, 100f, false, stroke)
                r.set(cx - h * 0.35f, cy - h * 0.8f, cx + h * 0.85f, cy + h * 0.8f)
                canvas.drawArc(r, -50f, 100f, false, stroke)
            }
            Kind.WAVE -> {
                path.moveTo(cx - h * 0.9f, cy)
                path.lineTo(cx - h * 0.55f, cy)
                path.lineTo(cx - h * 0.3f, cy - h * 0.6f)
                path.lineTo(cx, cy + h * 0.6f)
                path.lineTo(cx + h * 0.3f, cy - h * 0.6f)
                path.lineTo(cx + h * 0.55f, cy)
                path.lineTo(cx + h * 0.9f, cy)
                canvas.drawPath(path, stroke)
            }
            Kind.EYE_OFF -> {
                r.set(cx - h * 0.85f, cy - h * 0.5f, cx + h * 0.85f, cy + h * 0.5f)
                canvas.drawOval(r, stroke)
                canvas.drawCircle(cx, cy, h * 0.2f, fill)
                canvas.drawLine(cx - h * 0.7f, cy - h * 0.7f, cx + h * 0.7f, cy + h * 0.7f, stroke)
            }
            Kind.CHECK -> {
                path.moveTo(cx - h * 0.6f, cy)
                path.lineTo(cx - h * 0.15f, cy + h * 0.45f)
                path.lineTo(cx + h * 0.65f, cy - h * 0.5f)
                canvas.drawPath(path, stroke)
            }
            Kind.CHEVRON -> {
                path.moveTo(cx - h * 0.25f, cy - h * 0.6f)
                path.lineTo(cx + h * 0.3f, cy)
                path.lineTo(cx - h * 0.25f, cy + h * 0.6f)
                canvas.drawPath(path, stroke)
            }
            Kind.TEXT_AA, Kind.NUM -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; textAlign = Paint.Align.CENTER; isFakeBoldText = true
                    textSize = if (kind == Kind.NUM) s * 0.48f else s * 0.62f
                }
                canvas.drawText(if (kind == Kind.NUM) "123" else "Aa", cx, cy - (p.descent() + p.ascent()) / 2f, p)
            }
            Kind.HEIGHT -> {
                canvas.drawLine(cx, cy - h * 0.8f, cx, cy + h * 0.8f, stroke)
                path.moveTo(cx - h * 0.35f, cy - h * 0.45f); path.lineTo(cx, cy - h * 0.8f); path.lineTo(cx + h * 0.35f, cy - h * 0.45f)
                path.moveTo(cx - h * 0.35f, cy + h * 0.45f); path.lineTo(cx, cy + h * 0.8f); path.lineTo(cx + h * 0.35f, cy + h * 0.45f)
                canvas.drawPath(path, stroke)
            }
            Kind.CORNER -> {
                path.moveTo(cx - h * 0.65f, cy + h * 0.7f)
                path.lineTo(cx - h * 0.65f, cy - h * 0.1f)
                path.quadTo(cx - h * 0.65f, cy - h * 0.65f, cx - h * 0.1f, cy - h * 0.65f)
                path.lineTo(cx + h * 0.7f, cy - h * 0.65f)
                canvas.drawPath(path, stroke)
            }
            Kind.BOOK -> {
                path.moveTo(cx, cy - h * 0.55f)
                path.quadTo(cx - h * 0.45f, cy - h * 0.8f, cx - h * 0.85f, cy - h * 0.6f)
                path.lineTo(cx - h * 0.85f, cy + h * 0.65f)
                path.quadTo(cx - h * 0.45f, cy + h * 0.45f, cx, cy + h * 0.7f)
                path.quadTo(cx + h * 0.45f, cy + h * 0.45f, cx + h * 0.85f, cy + h * 0.65f)
                path.lineTo(cx + h * 0.85f, cy - h * 0.6f)
                path.quadTo(cx + h * 0.45f, cy - h * 0.8f, cx, cy - h * 0.55f)
                canvas.drawPath(path, stroke)
                canvas.drawLine(cx, cy - h * 0.55f, cx, cy + h * 0.7f, stroke)
            }
            Kind.CAPSULE -> {
                r.set(cx - h * 0.9f, cy - h * 0.4f, cx + h * 0.9f, cy + h * 0.4f)
                canvas.drawRoundRect(r, h * 0.4f, h * 0.4f, stroke)
                for (i in -1..1) canvas.drawCircle(cx + i * h * 0.5f, cy, s * 0.05f, fill)
            }
            Kind.PALETTE -> {
                canvas.drawCircle(cx, cy, h * 0.82f, stroke)
                canvas.drawCircle(cx - h * 0.35f, cy - h * 0.25f, s * 0.07f, fill)
                canvas.drawCircle(cx + h * 0.1f, cy - h * 0.45f, s * 0.07f, fill)
                canvas.drawCircle(cx + h * 0.45f, cy - h * 0.1f, s * 0.07f, fill)
                canvas.drawCircle(cx - h * 0.1f, cy + h * 0.35f, s * 0.09f, fill)
            }
            Kind.CLOCK -> {
                val rad = h * 0.85f
                canvas.drawCircle(cx, cy, rad, stroke)
                canvas.drawLine(cx, cy, cx, cy - rad * 0.55f, stroke)
                canvas.drawLine(cx, cy, cx + rad * 0.4f, cy + rad * 0.2f, stroke)
            }
            Kind.PAW -> {
                canvas.drawCircle(cx, cy + h * 0.3f, h * 0.38f, fill)
                canvas.drawCircle(cx - h * 0.55f, cy - h * 0.1f, h * 0.17f, fill)
                canvas.drawCircle(cx + h * 0.55f, cy - h * 0.1f, h * 0.17f, fill)
                canvas.drawCircle(cx - h * 0.22f, cy - h * 0.55f, h * 0.17f, fill)
                canvas.drawCircle(cx + h * 0.22f, cy - h * 0.55f, h * 0.17f, fill)
            }
            Kind.FOOD -> {
                // a cup
                path.moveTo(cx - h * 0.65f, cy - h * 0.45f)
                path.lineTo(cx + h * 0.45f, cy - h * 0.45f)
                path.lineTo(cx + h * 0.35f, cy + h * 0.6f)
                path.lineTo(cx - h * 0.55f, cy + h * 0.6f)
                path.close()
                canvas.drawPath(path, stroke)
                r.set(cx + h * 0.3f, cy - h * 0.3f, cx + h * 0.85f, cy + h * 0.2f)
                canvas.drawArc(r, -90f, 180f, false, stroke)
                canvas.drawLine(cx - h * 0.25f, cy - h * 0.95f, cx - h * 0.25f, cy - h * 0.65f, stroke)
                canvas.drawLine(cx + h * 0.05f, cy - h * 0.95f, cx + h * 0.05f, cy - h * 0.65f, stroke)
            }
            Kind.BALL -> {
                val rad = h * 0.85f
                canvas.drawCircle(cx, cy, rad, stroke)
                r.set(cx - rad * 1.6f, cy - rad, cx - rad * 0.4f, cy + rad)
                canvas.drawArc(r, -50f, 100f, false, stroke)
                r.set(cx + rad * 0.4f, cy - rad, cx + rad * 1.6f, cy + rad)
                canvas.drawArc(r, 130f, 100f, false, stroke)
            }
            Kind.CAR -> {
                r.set(cx - h * 0.85f, cy - h * 0.15f, cx + h * 0.85f, cy + h * 0.45f)
                canvas.drawRoundRect(r, s * 0.08f, s * 0.08f, stroke)
                path.moveTo(cx - h * 0.55f, cy - h * 0.15f)
                path.lineTo(cx - h * 0.35f, cy - h * 0.6f)
                path.lineTo(cx + h * 0.35f, cy - h * 0.6f)
                path.lineTo(cx + h * 0.55f, cy - h * 0.15f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(cx - h * 0.45f, cy + h * 0.55f, h * 0.16f, fill)
                canvas.drawCircle(cx + h * 0.45f, cy + h * 0.55f, h * 0.16f, fill)
            }
            Kind.BULB -> {
                canvas.drawCircle(cx, cy - h * 0.2f, h * 0.55f, stroke)
                canvas.drawLine(cx - h * 0.25f, cy + h * 0.55f, cx + h * 0.25f, cy + h * 0.55f, stroke)
                canvas.drawLine(cx - h * 0.18f, cy + h * 0.8f, cx + h * 0.18f, cy + h * 0.8f, stroke)
            }
            Kind.SYMBOLS -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; textSize = s * 0.62f; textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                }
                canvas.drawText("&%", cx, cy + s * 0.22f, p)
            }
            Kind.FLAG -> {
                canvas.drawLine(cx - h * 0.6f, cy - h * 0.85f, cx - h * 0.6f, cy + h * 0.9f, stroke)
                path.moveTo(cx - h * 0.6f, cy - h * 0.8f)
                path.lineTo(cx + h * 0.75f, cy - h * 0.8f)
                path.lineTo(cx + h * 0.45f, cy - h * 0.4f)
                path.lineTo(cx + h * 0.75f, cy)
                path.lineTo(cx - h * 0.6f, cy)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun star(cx: Float, cy: Float, rad: Float) {
        val k = rad * 0.28f
        path.moveTo(cx, cy - rad)
        path.quadTo(cx + k * 0.3f, cy - k * 0.3f, cx + rad, cy)
        path.quadTo(cx + k * 0.3f, cy + k * 0.3f, cx, cy + rad)
        path.quadTo(cx - k * 0.3f, cy + k * 0.3f, cx - rad, cy)
        path.quadTo(cx - k * 0.3f, cy - k * 0.3f, cx, cy - rad)
        path.close()
    }

    fun sizeFor(w: Float, h: Float) = min(w, h) * 0.42f
}

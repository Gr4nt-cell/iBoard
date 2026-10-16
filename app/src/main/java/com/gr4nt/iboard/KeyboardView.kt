package com.gr4nt.iboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.gr4nt.iboard.predict.Suggestion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ShiftState { OFF, ON, LOCKED }

/**
 * Draws the suggestion bar and the glass keys, and turns touches into key events.
 * Everything is drawn on one canvas so key previews can float over the bar, like on iOS.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKey(key: Key)
        fun onText(text: String)
        fun onBackspace(repeat: Int)
        fun onShift(doubleTap: Boolean)
        fun onGlobeLongPress()
        fun onCursorMove(steps: Int)
        fun onSuggestion(index: Int)
        fun onClipboardButton()
        fun onAiButton()
        fun onKeyFeedback(key: Key)
        fun onEmojiResult(text: String)
        fun onEmojiSearchClose()
    }

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var theme: KeyboardTheme = KeyboardTheme.resolve(context, Prefs(context))
        set(v) { field = v; shaderCache.clear(); invalidate() }
    var showPreview = true
    var layout: KeyboardLayout? = null
        set(v) { field = v; requestLayout(); computeKeys(); invalidate() }
    var shift: ShiftState = ShiftState.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var returnLabel: String? = null
        set(v) { field = v; invalidate() }
    var returnAccent = false
        set(v) { field = v; invalidate() }
    var suggestions: List<Suggestion> = emptyList()
        set(v) { field = v; invalidate() }
    var aiActive = false
        set(v) { field = v; invalidate() }
    var aiBusy = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var languageLabel: String = ""
    var returnIcon: Icons.Kind? = null
        set(v) { field = v; invalidate() }
    var listening = false
        set(v) { field = v; invalidate() }
    /** Replaces the suggestions with a message (e.g. live dictation text). */
    var statusText: String? = null
        set(v) { field = v; invalidate() }
    private var spaceFlashUntil = 0L

    /** How the keys look, from the "Key customization" settings. */
    data class Look(
        val solid: Boolean = true,
        val radiusDp: Int = 8,
        val heightPct: Int = 100,
        val textPct: Int = 100,
        val uppercase: Boolean = true,
        val spaceLabel: Boolean = false,
        val stripIcons: Boolean = false,
    )

    var look = Look()
        set(v) {
            field = v
            keyH = 45 * d * v.heightPct / 100f
            numKeyH = 38 * d * v.heightPct / 100f
            radius = v.radiusDp * d
            textScale = v.textPct / 100f
            shaderCache.clear(); requestLayout(); computeKeys(); invalidate()
        }

    /** Briefly shows the language name on the space bar after switching. */
    fun flashSpaceLabel() {
        spaceFlashUntil = SystemClock.uptimeMillis() + 1500
        invalidate()
        postDelayed({ invalidate() }, 1600)
    }

    // --- geometry ---
    /** The bar grows to two rows (search field + results) while searching emoji. */
    private val stripH get() = if (emojiQuery != null) 98 * d else 44 * d

    /** Non-null while the user is searching emoji; typed letters go into this query. */
    var emojiQuery: String? = null
        set(v) {
            val resize = (field == null) != (v == null)
            field = v
            if (resize) { requestLayout(); computeKeys() }
            invalidate()
        }
    var emojiResults: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    private val sidePad = 3 * d
    private val keyGap = 6 * d
    private val rowGap = 10 * d
    private val topPad = 4 * d
    private val bottomPad = 6 * d
    private var keyH = 45 * d
    private var numKeyH = 38 * d
    private val utilH = 43.6f * d // capsule = utilH - 4dp
    private var radius = 8 * d
    private var textScale = 1f

    private class KeyBox(val key: Key, val rect: RectF, val row: Int)

    private val boxes = ArrayList<KeyBox>()
    private val capsule = RectF()
    private var capsuleShader: Shader? = null
    private val rowBounds = ArrayList<Pair<Float, Float>>()

    // --- paints ---
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * d }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stripPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 16.5f * sp }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * d }
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val shaderCache = HashMap<Int, Shader>()
    private val tmpRect = RectF()
    private val bubblePath = Path()

    // --- touch state ---
    private class Press(val box: KeyBox, val downX: Float, val downY: Float) {
        var longPressed = false
        var trackpad = false
        var lastCursorX = 0f
        var altIndex = 0
    }

    private val presses = HashMap<Int, Press>()
    private val keyHandler = Handler(Looper.getMainLooper())
    private var popupAlts: List<String> = emptyList()
    private var popupOwner: Press? = null
    private val popupRect = RectF()
    private var lastShiftTap = 0L
    private var backspaceRepeat = 0
    private var stripPressed = -2
    private var animStart = 0L

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, desiredHeight().toInt())
    }

    fun desiredHeight(): Float {
        val l = layout ?: return stripH + 4 * keyH
        var h = stripH + topPad + bottomPad
        l.rows.forEachIndexed { i, r ->
            h += rowHeight(r)
            if (i > 0) h += if (r.isUtility) rowGap / 2f else rowGap
        }
        return h
    }

    private fun rowHeight(r: KeyRow) = when {
        r.isUtility -> utilH
        r.isNumberRow -> numKeyH
        else -> keyH
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeKeys()
    }

    private fun computeKeys() {
        boxes.clear(); rowBounds.clear()
        val l = layout ?: return
        val w = width.toFloat()
        if (w <= 0f) return
        val unit = (w - 2 * sidePad - (l.unitsPerRow - 1) * keyGap) / l.unitsPerRow
        var y = stripH + topPad
        l.rows.forEachIndexed { ri, row ->
            val h = rowHeight(row)
            if (row.isUtility) y -= rowGap / 2f
            val fixed = row.keys.filter { it.width != Key.FLEX }.sumOf { it.width.toDouble() }.toFloat()
            val flexCount = row.keys.count { it.width == Key.FLEX }
            val flexUnits = if (flexCount > 0) max(1f, (l.unitsPerRow - fixed) / flexCount) else 0f
            if (row.isUtility) {
                // A slim, centered rounded bar holding the icons (fixed slot per icon).
                val slot = min(52 * d, (w - 2 * sidePad - 24 * d) / row.keys.size)
                val total = slot * row.keys.size
                var x = (w - total) / 2f
                for (k in row.keys) {
                    boxes += KeyBox(k, RectF(x, y, x + slot, y + h), ri)
                    x += slot
                }
                val capH = 36 * d
                val top = y + (h - capH) / 2f
                capsule.set((w - total) / 2f - 10 * d, top, (w + total) / 2f + 10 * d, top + capH)
                capsuleShader = null
                rowBounds += (y - rowGap / 2f) to (y + h + rowGap / 2f)
                y += h + rowGap
                return@forEachIndexed
            }
            val widths = row.keys.map { k ->
                val u = if (k.width == Key.FLEX) flexUnits else k.width
                u * unit + (u - 1) * keyGap
            }
            val total = widths.sum() + (row.keys.size - 1) * keyGap
            var x = (w - total) / 2f
            for (i in row.keys.indices) {
                boxes += KeyBox(row.keys[i], RectF(x, y, x + widths[i], y + h), ri)
                x += widths[i] + keyGap
            }
            rowBounds += (y - rowGap / 2f) to (y + h + rowGap / 2f)
            y += h + rowGap
        }
    }

    /** Plays the iOS-27-style "keys slide up" entrance. */
    fun animateIn() {
        animStart = SystemClock.uptimeMillis()
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        drawStrip(canvas)
        val now = SystemClock.uptimeMillis()
        val rows = layout?.rows?.size ?: 0
        var animating = false
        for (b in boxes) {
            val t = if (animStart == 0L) 1f else {
                val fromBottom = rows - 1 - b.row
                val p = ((now - animStart - fromBottom * 28L) / 240f).coerceIn(0f, 1f)
                if (p < 1f) animating = true
                1f - (1f - p) * (1f - p) * (1f - p)
            }
            if (t <= 0f) continue
            val dy = (1f - t) * 26 * d * (1 + b.row)
            canvas.save()
            canvas.translate(0f, dy)
            drawKey(canvas, b, t)
            canvas.restore()
        }
        if (animating) postInvalidateOnAnimation() else animStart = 0L

        // Previews and popups float above everything.
        for (p in presses.values) {
            if (p === popupOwner) continue
            if (showPreview && p.box.key.type == KeyType.CHAR && !p.trackpad) drawBubble(canvas, p.box)
        }
        popupOwner?.let { drawPopup(canvas, it) }
    }

    private fun isPressed(b: KeyBox) = presses.values.any { it.box === b }

    private fun drawKey(canvas: Canvas, b: KeyBox, alpha: Float) {
        val r = b.rect
        val k = b.key
        val pressed = isPressed(b)
        if (k.type == KeyType.SPACER) return
        if (layout?.rows?.getOrNull(b.row)?.isUtility == true) {
            if (boxes.firstOrNull { it.row == b.row } === b) drawCapsule(canvas, alpha)
            if (pressed) {
                fillPaint.color = if (theme.dark) 0x33FFFFFF else 0x22000000
                tmpRect.set(r.left + 4 * d, capsule.top + 4 * d, r.right - 4 * d, capsule.bottom - 4 * d)
                val rr = tmpRect.height() * 0.42f
                canvas.drawRoundRect(tmpRect, rr, rr, fillPaint)
            }
            val kind = when (k.type) {
                KeyType.GLOBE -> Icons.Kind.GLOBE
                KeyType.CLIPBOARD -> Icons.Kind.CLIPBOARD
                KeyType.EMOJI -> Icons.Kind.EMOJI
                KeyType.AI -> Icons.Kind.SPARKLE
                else -> Icons.Kind.MIC
            }
            val color = if ((k.type == KeyType.MIC && listening) || (k.type == KeyType.AI && (aiActive || aiBusy))) theme.accent else theme.keyText.withAlpha(0.85f * alpha)
            Icons.draw(canvas, kind, r.centerX(), r.centerY(), min(r.height() * 0.62f, 26 * d), color)
            return
        }
        val trackpadOn = presses.values.any { it.trackpad }
        val accent = k.type == KeyType.RETURN && returnAccent
        val fillColor = when {
            accent -> if (pressed) theme.accent.withAlpha(0.75f) else theme.accent
            k.type == KeyType.SHIFT && shift != ShiftState.OFF -> theme.keyFill
            k.isSpecial -> if (pressed) theme.specialFillPressed else theme.specialFill
            pressed && !(showPreview && k.type == KeyType.CHAR) -> theme.keyFillPressed
            else -> theme.keyFill
        }
        val a = (alpha * 255).toInt()

        // soft drop shadow
        shadowPaint.color = theme.keyShadow
        shadowPaint.alpha = (shadowPaint.alpha * alpha).toInt()
        tmpRect.set(r.left, r.top + 1.1f * d, r.right, r.bottom + 1.1f * d)
        canvas.drawRoundRect(tmpRect, radius, radius, shadowPaint)

        fillPaint.color = fillColor
        fillPaint.alpha = (fillPaint.alpha * alpha).toInt()
        canvas.drawRoundRect(r, radius, radius, fillPaint)

        // liquid-glass sheen on the upper half
        if (!accent && !look.solid) {
            glossPaint.shader = shaderCache.getOrPut(r.height().toInt()) {
                LinearGradient(0f, 0f, 0f, r.height() * 0.55f, theme.keyHighlightTop, 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            glossPaint.alpha = a
            canvas.save()
            canvas.translate(r.left, r.top)
            tmpRect.set(0f, 0f, r.width(), r.height())
            canvas.drawRoundRect(tmpRect, radius, radius, glossPaint)
            canvas.restore()
        }
        if (!look.solid) {
            strokePaint.color = theme.keyStroke
            strokePaint.alpha = (strokePaint.alpha * alpha).toInt()
            canvas.drawRoundRect(r, radius, radius, strokePaint)
        }

        val fg = if (accent) theme.accentText else theme.keyText
        val contentAlpha = if (trackpadOn && k.type != KeyType.SPACE) 0.15f else 1f
        val fgA = fg.withAlpha(alpha * contentAlpha)
        val cx = r.centerX()
        val cy = r.centerY()
        val iconSize = min(r.height() * 0.5f, 24 * d)
        when (k.type) {
            KeyType.CHAR -> {
                val label = displayLabel(k.label)
                textPaint.typeface = regular
                textPaint.textSize = textScale * when {
                    label.length > 2 -> 15 * sp
                    layout?.rows?.getOrNull(b.row)?.isNumberRow == true -> 19 * sp
                    else -> 23 * sp
                }
                textPaint.color = fgA
                canvas.drawText(label, cx, baseline(cy, textPaint), textPaint)
            }
            KeyType.SPACE -> {
                textPaint.typeface = regular
                textPaint.textSize = 15 * sp * textScale
                textPaint.color = theme.keySubText.withAlpha(alpha)
                val showLabel = look.spaceLabel || SystemClock.uptimeMillis() < spaceFlashUntil
                val label = if (trackpadOn || !showLabel) "" else k.label
                canvas.drawText(label, cx, baseline(cy, textPaint), textPaint)
            }
            KeyType.SHIFT -> Icons.draw(
                canvas, when (shift) {
                    ShiftState.OFF -> Icons.Kind.SHIFT
                    ShiftState.ON -> Icons.Kind.SHIFT_ON
                    ShiftState.LOCKED -> Icons.Kind.CAPS_LOCK
                }, cx, cy, iconSize, fgA
            )
            KeyType.BACKSPACE -> Icons.draw(canvas, Icons.Kind.BACKSPACE, cx, cy, iconSize, fgA)
            KeyType.EMOJI -> Icons.draw(canvas, Icons.Kind.EMOJI, cx, cy, iconSize, fgA)
            KeyType.GLOBE -> Icons.draw(canvas, Icons.Kind.GLOBE, cx, cy, iconSize, fgA)
            KeyType.RETURN -> {
                val label = returnLabel
                val icon = returnIcon
                if (icon != null) Icons.draw(canvas, icon, cx, cy, iconSize, fgA)
                else if (label == null) Icons.draw(canvas, Icons.Kind.RETURN, cx, cy, iconSize, fgA)
                else {
                    textPaint.typeface = medium
                    textPaint.textSize = 15.5f * sp * textScale
                    textPaint.color = fgA
                    canvas.drawText(label, cx, baseline(cy, textPaint), textPaint)
                }
            }
            else -> {
                textPaint.typeface = regular
                textPaint.textSize = 16 * sp * textScale
                textPaint.color = fgA
                canvas.drawText(k.label, cx, baseline(cy, textPaint), textPaint)
            }
        }
    }

    /** A frosted Liquid Glass pill holding the globe, emoji, clipboard and mic icons. */
    private fun drawCapsule(canvas: Canvas, alpha: Float) {
        if (capsule.isEmpty) return
        val rr = capsule.height() * 0.42f // rounded ends, not a full pill
        shadowPaint.color = if (theme.dark) 0x55000000 else 0x1F3C2A5A
        shadowPaint.alpha = (shadowPaint.alpha * alpha).toInt()
        tmpRect.set(capsule.left, capsule.top + 1.5f * d, capsule.right, capsule.bottom + 1.5f * d)
        canvas.drawRoundRect(tmpRect, rr, rr, shadowPaint)
        fillPaint.color = if (theme.dark) 0x2EFFFFFF else 0x8CFFFFFF.toInt()
        fillPaint.alpha = (fillPaint.alpha * alpha).toInt()
        canvas.drawRoundRect(capsule, rr, rr, fillPaint)
        val shader = capsuleShader ?: LinearGradient(
            0f, capsule.top, 0f, capsule.top + capsule.height() * 0.6f,
            if (theme.dark) 0x30FFFFFF else 0xA6FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP,
        ).also { capsuleShader = it }
        glossPaint.shader = shader
        glossPaint.alpha = (alpha * 255).toInt()
        canvas.drawRoundRect(capsule, rr, rr, glossPaint)
        strokePaint.color = if (theme.dark) 0x40FFFFFF else 0xB3FFFFFF.toInt()
        strokePaint.alpha = (strokePaint.alpha * alpha).toInt()
        canvas.drawRoundRect(capsule, rr, rr, strokePaint)
    }

    private fun displayLabel(label: String): String {
        if ((shift == ShiftState.OFF && !look.uppercase) || label.length != 1) return label
        val up = label.uppercase()
        return if (up.length == 1) up else label
    }

    private fun typedLabel(label: String): String {
        if (shift == ShiftState.OFF || label.length != 1) return label
        val up = label.uppercase()
        return if (up.length == 1) up else label
    }

    private fun baseline(cy: Float, p: Paint): Float = cy - (p.descent() + p.ascent()) / 2f

    private fun drawBubble(canvas: Canvas, b: KeyBox) {
        val r = b.rect
        val ext = r.width() * 0.28f
        val topH = r.height() * 1.08f
        var left = r.left - ext
        var right = r.right + ext
        if (left < 2 * d) { right += 2 * d - left; left = 2 * d }
        if (right > width - 2 * d) { left -= right - (width - 2 * d); right = width - 2 * d }
        val top = max(2 * d, r.top - topH - 4 * d)
        val topBottom = top + topH
        bubblePath.reset()
        bubblePath.addRoundRect(RectF(left, top, right, topBottom), radius * 1.3f, radius * 1.3f, Path.Direction.CW)
        val stem = Path().apply {
            moveTo(left, topBottom - radius)
            cubicTo(left, topBottom + 4 * d, r.left, r.top - 2 * d, r.left, r.top + radius)
            lineTo(r.left, r.bottom - radius)
            quadTo(r.left, r.bottom, r.left + radius, r.bottom)
            lineTo(r.right - radius, r.bottom)
            quadTo(r.right, r.bottom, r.right, r.bottom - radius)
            lineTo(r.right, r.top + radius)
            cubicTo(r.right, r.top - 2 * d, right, topBottom + 4 * d, right, topBottom - radius)
            close()
        }
        bubblePath.op(stem, Path.Op.UNION)
        canvas.save()
        canvas.translate(0f, 1.5f * d)
        shadowPaint.color = 0x33000000
        canvas.drawPath(bubblePath, shadowPaint)
        canvas.restore()
        fillPaint.color = theme.bubbleFill
        canvas.drawPath(bubblePath, fillPaint)
        textPaint.typeface = regular
        textPaint.textSize = 32 * sp
        textPaint.color = theme.keyText
        canvas.drawText(typedLabel(b.key.label), (left + right) / 2f, baseline(top + topH * 0.48f, textPaint), textPaint)
    }

    private fun drawPopup(canvas: Canvas, p: Press) {
        val cellW = max(p.box.rect.width(), 30 * d)
        val n = popupAlts.size
        if (n == 0) return
        val w = cellW * n
        val h = p.box.rect.height() * 1.1f
        var left = p.box.rect.left - 2 * d
        if (left + w > width - 3 * d) left = width - 3 * d - w
        if (left < 3 * d) left = 3 * d
        val top = max(2 * d, p.box.rect.top - h - 6 * d)
        popupRect.set(left, top, left + w, top + h)
        tmpRect.set(popupRect.left, popupRect.top + 2 * d, popupRect.right, popupRect.bottom + 2 * d)
        shadowPaint.color = 0x40000000
        canvas.drawRoundRect(tmpRect, radius * 1.3f, radius * 1.3f, shadowPaint)
        fillPaint.color = theme.bubbleFill
        canvas.drawRoundRect(popupRect, radius * 1.3f, radius * 1.3f, fillPaint)
        textPaint.typeface = regular
        textPaint.textSize = 24 * sp
        for (i in 0 until n) {
            val cl = left + i * cellW
            tmpRect.set(cl + 3 * d, top + 4 * d, cl + cellW - 3 * d, top + h - 4 * d)
            if (i == p.altIndex) {
                fillPaint.color = theme.accent
                canvas.drawRoundRect(tmpRect, radius, radius, fillPaint)
                textPaint.color = theme.accentText
            } else textPaint.color = theme.keyText
            val label = typedLabel(popupAlts[i])
            textPaint.textSize = if (label.length > 2) 15 * sp else 24 * sp
            canvas.drawText(label, tmpRect.centerX(), baseline(tmpRect.centerY(), textPaint), textPaint)
        }
    }

    // --- suggestion bar ---

    private val iconW get() = if (look.stripIcons) 44 * d else 4 * d

    private fun slotOrder(): IntArray = intArrayOf(1, 0, 2) // display slot -> suggestion index

    private fun slotRects(): List<RectF> {
        val avail = width - 2 * iconW
        val texts = slotOrder().map { suggestions.getOrNull(it)?.text ?: "" }
        val minW = avail / 3f
        val wants = texts.map { t -> max(minW * 0.7f, stripPaint.measureText(t) + 28 * d) }
        val sum = wants.sum()
        val widths = if (sum <= avail) {
            val extra = (avail - sum) / 3f
            wants.map { it + extra }
        } else wants.map { it / sum * avail }
        var x = iconW
        return widths.map { w -> RectF(x, 0f, x + w, stripH).also { x += w } }
    }

    private fun drawEmojiSearch(canvas: Canvas, query: String) {
        val rowH = 50 * d
        val pillH = 36 * d
        val top = (rowH - pillH) / 2f + 2 * d
        val right = width - 50 * d
        fillPaint.color = if (theme.dark) 0x33FFFFFF else 0xE6FFFFFF.toInt()
        tmpRect.set(8 * d, top, right, top + pillH)
        canvas.drawRoundRect(tmpRect, pillH / 2f, pillH / 2f, fillPaint)
        val cy = top + pillH / 2f
        Icons.draw(canvas, Icons.Kind.SEARCH, 26 * d, cy, 16 * d, theme.keySubText)
        stripPaint.textAlign = Paint.Align.LEFT
        stripPaint.typeface = regular
        val tx = 42 * d
        if (query.isEmpty()) {
            stripPaint.color = theme.keySubText
            canvas.drawText("Search Emoji", tx, baseline(cy, stripPaint), stripPaint)
            fillPaint.color = theme.accent
            canvas.drawRect(tx - 1.5f * d, cy - 10 * d, tx, cy + 10 * d, fillPaint)
        } else {
            stripPaint.color = theme.stripText
            val shownText = TextUtils.ellipsize(query, stripPaint, right - tx - 16 * d, TextUtils.TruncateAt.START).toString()
            canvas.drawText(shownText, tx, baseline(cy, stripPaint), stripPaint)
            val end = tx + stripPaint.measureText(shownText) + 2 * d
            fillPaint.color = theme.accent
            canvas.drawRect(end, cy - 10 * d, end + 1.5f * d, cy + 10 * d, fillPaint)
        }
        stripPaint.textAlign = Paint.Align.CENTER
        // close (×)
        val cx = width - 26 * d
        fillPaint.color = if (theme.dark) 0x33FFFFFF else 0xE6FFFFFF.toInt()
        canvas.drawCircle(cx, cy, 16 * d, fillPaint)
        strokePaint.color = theme.keyText
        val old = strokePaint.strokeWidth
        strokePaint.strokeWidth = 2 * d
        canvas.drawLine(cx - 5 * d, cy - 5 * d, cx + 5 * d, cy + 5 * d, strokePaint)
        canvas.drawLine(cx + 5 * d, cy - 5 * d, cx - 5 * d, cy + 5 * d, strokePaint)
        strokePaint.strokeWidth = old
        // results
        val cell = 46 * d
        val ry = rowH + (stripH - rowH) / 2f
        if (emojiResults.isEmpty() && query.isNotEmpty()) {
            stripPaint.color = theme.keySubText
            canvas.drawText("No emoji found", width / 2f, baseline(ry, stripPaint), stripPaint)
        }
        emojiResults.forEachIndexed { i, e ->
            val x = 8 * d + cell * i + cell / 2f
            if (x + cell / 2f > width) return
            if (stripPressed == 200 + i) {
                fillPaint.color = theme.keyFillPressed
                canvas.drawCircle(x, ry, cell * 0.46f, fillPaint)
            }
            com.gr4nt.iboard.emoji.EmojiImages.draw(context, canvas, e, x, ry, cell * 0.72f)
        }
    }

    private fun drawStrip(canvas: Canvas) {
        emojiQuery?.let { drawEmojiSearch(canvas, it); return }
        statusText?.let { msg ->
            stripPaint.typeface = medium
            stripPaint.color = theme.stripText
            Icons.draw(canvas, Icons.Kind.MIC, 22 * d, stripH / 2f, 18 * d, theme.accent)
            val t = TextUtils.ellipsize(msg, stripPaint, width - 70 * d, TextUtils.TruncateAt.START).toString()
            canvas.drawText(t, width / 2f, baseline(stripH / 2f, stripPaint), stripPaint)
            return
        }
        if (look.stripIcons) drawStripIcons(canvas)
        drawSuggestions(canvas)
    }

    private fun drawStripIcons(canvas: Canvas) {
        val iconColor = theme.stripText.withAlpha(0.55f)
        // clipboard button
        if (stripPressed == -1) {
            fillPaint.color = theme.keyFillPressed
            canvas.drawCircle(iconW / 2f + 4 * d, stripH / 2f, 17 * d, fillPaint)
        }
        Icons.draw(canvas, Icons.Kind.CLIPBOARD, iconW / 2f + 4 * d, stripH / 2f, 19 * d, iconColor)
        // AI button
        val aiColor = when {
            aiBusy -> theme.accent
            aiActive -> theme.accent.withAlpha(0.8f)
            else -> iconColor
        }
        if (stripPressed == 3) {
            fillPaint.color = theme.keyFillPressed
            canvas.drawCircle(width - iconW / 2f - 4 * d, stripH / 2f, 17 * d, fillPaint)
        }
        Icons.draw(canvas, Icons.Kind.SPARKLE, width - iconW / 2f - 4 * d, stripH / 2f, 20 * d, aiColor)
    }

    private fun drawSuggestions(canvas: Canvas) {
        if (suggestions.isEmpty()) {
            // Empty iOS bar still shows its two faint dividers.
            dividerPaint.color = theme.stripDivider
            canvas.drawLine(width / 3f, stripH * 0.3f, width / 3f, stripH * 0.7f, dividerPaint)
            canvas.drawLine(width * 2 / 3f, stripH * 0.3f, width * 2 / 3f, stripH * 0.7f, dividerPaint)
            return
        }
        val rects = slotRects()
        val order = slotOrder()
        dividerPaint.color = theme.stripDivider
        for ((slot, rect) in rects.withIndex()) {
            val s = suggestions.getOrNull(order[slot])
            if (slot > 0 && (s != null || suggestions.getOrNull(order[slot - 1]) != null)) {
                canvas.drawLine(rect.left, stripH * 0.28f, rect.left, stripH * 0.72f, dividerPaint)
            }
            s ?: continue
            if (s.autoCorrect || stripPressed == slot) {
                fillPaint.color = if (stripPressed == slot) theme.keyFillPressed else theme.keyFill
                tmpRect.set(rect.left + 4 * d, 6 * d, rect.right - 4 * d, stripH - 6 * d)
                canvas.drawRoundRect(tmpRect, 9 * d, 9 * d, fillPaint)
            }
            var label = when (s.kind) {
                Suggestion.Kind.LITERAL -> if (suggestions.any { it.autoCorrect }) "“${s.text}”" else s.text
                else -> s.text
            }
            val showSpark = s.kind == Suggestion.Kind.AI || s.kind == Suggestion.Kind.PHRASE
            val showClip = s.kind == Suggestion.Kind.CLIP
            val iconSpace = if (showSpark || showClip) 18 * d else 0f
            stripPaint.typeface = if (s.autoCorrect) medium else regular
            stripPaint.color = theme.stripText
            label = TextUtils.ellipsize(label, stripPaint, rect.width() - 16 * d - iconSpace, TextUtils.TruncateAt.END).toString()
            val tw = stripPaint.measureText(label)
            val cx = rect.centerX() + iconSpace / 2f
            if (showSpark) Icons.draw(canvas, Icons.Kind.SPARKLE, cx - tw / 2f - 11 * d, stripH / 2f, 12 * d, theme.accent)
            if (showClip) Icons.draw(canvas, Icons.Kind.CLIPBOARD, cx - tw / 2f - 11 * d, stripH / 2f, 13 * d, theme.accent)
            canvas.drawText(label, cx, baseline(stripH / 2f, stripPaint), stripPaint)
        }
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx)
                val y = e.getY(idx)
                if (y < stripH) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) stripPressed = stripHit(x, y)
                    invalidate()
                    return true
                }
                // Fast typing: a new finger commits the previous character immediately.
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    presses.entries.toList().forEach { (id, p) ->
                        if (p.box.key.type == KeyType.CHAR && !p.longPressed) {
                            release(p, commit = true); presses.remove(id)
                        }
                    }
                }
                val box = hit(x, y) ?: return true
                val p = Press(box, x, y)
                presses[e.getPointerId(idx)] = p
                onPress(p)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = presses[e.getPointerId(i)] ?: continue
                    onMove(p, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (stripPressed != -2 && e.actionMasked == MotionEvent.ACTION_UP) {
                    val hitSlot = stripHit(e.getX(idx), e.getY(idx))
                    if (hitSlot == stripPressed && e.getY(idx) < stripH * 1.5f) onStripTap(hitSlot)
                    stripPressed = -2
                    invalidate()
                }
                val p = presses.remove(e.getPointerId(idx))
                if (p != null) release(p, commit = true)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                presses.values.forEach { release(it, commit = false) }
                presses.clear()
                stripPressed = -2
                invalidate()
            }
        }
        return true
    }

    private fun stripHit(x: Float, y: Float): Int {
        if (emojiQuery != null) {
            val rowH = 50 * d
            if (y < rowH) return if (x > width - 50 * d) 100 else -2
            val i = ((x - 8 * d) / (46 * d)).toInt()
            return if (i in emojiResults.indices) 200 + i else -2
        }
        if (statusText != null) return -2
        if (look.stripIcons && x < iconW + 4 * d) return -1
        if (look.stripIcons && x > width - iconW - 4 * d) return 3
        if (suggestions.isEmpty()) return -2
        slotRects().forEachIndexed { i, r -> if (x >= r.left && x < r.right) return i }
        return -2
    }

    private fun onStripTap(slot: Int) {
        when (slot) {
            100 -> listener.onEmojiSearchClose()
            in 200..299 -> emojiResults.getOrNull(slot - 200)?.let { listener.onEmojiResult(it) }
            -1 -> listener.onClipboardButton()
            3 -> listener.onAiButton()
            in 0..2 -> {
                val index = slotOrder()[slot]
                if (index < suggestions.size) listener.onSuggestion(index)
            }
        }
    }

    private fun hit(x: Float, y: Float): KeyBox? {
        // Pick the row first (gaps belong to the nearest row), then the nearest key in that row.
        var row = -1
        for ((i, rb) in rowBounds.withIndex()) if (y >= rb.first && y < rb.second) { row = i; break }
        if (row < 0) row = if (y >= (rowBounds.lastOrNull()?.second ?: 0f)) rowBounds.lastIndex else 0
        var best: KeyBox? = null
        var bestDist = Float.MAX_VALUE
        for (b in boxes) {
            if (b.row != row) continue
            val dist = when {
                x < b.rect.left -> b.rect.left - x
                x > b.rect.right -> x - b.rect.right
                else -> 0f
            }
            if (dist < bestDist) { bestDist = dist; best = b }
        }
        return best
    }

    private fun onPress(p: Press) {
        val k = p.box.key
        if (k.type == KeyType.SPACER) return
        listener.onKeyFeedback(k)
        when (k.type) {
            KeyType.BACKSPACE -> {
                backspaceRepeat = 0
                listener.onBackspace(0)
                keyHandler.postDelayed(object : Runnable {
                    override fun run() {
                        if (!presses.containsValue(p)) return
                        backspaceRepeat++
                        listener.onBackspace(backspaceRepeat)
                        listener.onKeyFeedback(k)
                        keyHandler.postDelayed(this, if (backspaceRepeat > 12) 90L else 65L)
                    }
                }, 420L)
            }
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                val double = now - lastShiftTap < 320
                lastShiftTap = if (double) 0L else now
                listener.onShift(double)
            }
            KeyType.CHAR -> if (k.longPress.isNotEmpty()) {
                keyHandler.postDelayed({
                    if (presses.containsValue(p) && !p.trackpad) {
                        p.longPressed = true
                        popupAlts = listOf(k.label) + k.longPress
                        if (k.label.length == 1 && k.label[0].isDigit()) popupAlts = k.longPress
                        p.altIndex = if (popupAlts.size > 1 && popupAlts[0] == k.label) 1 else 0
                        popupOwner = p
                        listener.onKeyFeedback(k)
                        invalidate()
                    }
                }, 360L)
            }
            KeyType.SPACE -> keyHandler.postDelayed({
                if (presses.containsValue(p) && !p.trackpad) {
                    p.trackpad = true
                    p.lastCursorX = p.downX
                    listener.onKeyFeedback(k)
                    invalidate()
                }
            }, 380L)
            KeyType.GLOBE -> keyHandler.postDelayed({
                if (presses.containsValue(p)) {
                    p.longPressed = true
                    listener.onGlobeLongPress()
                }
            }, 450L)
            else -> {}
        }
    }

    private fun onMove(p: Press, x: Float, y: Float) {
        val k = p.box.key
        if (p === popupOwner && popupAlts.isNotEmpty()) {
            val cellW = popupRect.width() / popupAlts.size
            val i = ((x - popupRect.left) / cellW).toInt().coerceIn(0, popupAlts.size - 1)
            if (i != p.altIndex) { p.altIndex = i; invalidate() }
            return
        }
        if (k.type == KeyType.SPACE) {
            if (!p.trackpad && abs(x - p.downX) > 18 * d) {
                p.trackpad = true
                p.lastCursorX = x
                invalidate()
            }
            if (p.trackpad) {
                val step = 9 * d
                val steps = ((x - p.lastCursorX) / step).toInt()
                if (steps != 0) {
                    p.lastCursorX += steps * step
                    listener.onCursorMove(if (layout?.rtl == true) -steps else steps)
                }
            }
            return
        }
        // Sliding onto a neighboring letter re-targets the press (iOS behavior).
        if (k.type == KeyType.CHAR && !p.longPressed) {
            val nb = hit(x, y)
            if (nb != null && nb !== p.box && nb.key.type == KeyType.CHAR) {
                val id = presses.entries.firstOrNull { it.value === p }?.key ?: return
                presses[id] = Press(nb, x, y)
                invalidate()
            }
        }
    }

    private fun release(p: Press, commit: Boolean) {
        val k = p.box.key
        keyHandler.removeCallbacksAndMessages(null)
        if (p === popupOwner) {
            val alt = popupAlts.getOrNull(p.altIndex)
            popupOwner = null
            popupAlts = emptyList()
            if (commit && alt != null) listener.onText(alt)
            return
        }
        if (!commit || p.longPressed || p.trackpad) return
        when (k.type) {
            KeyType.BACKSPACE, KeyType.SHIFT -> {}
            else -> listener.onKey(k)
        }
    }

    fun cancelTouches() {
        keyHandler.removeCallbacksAndMessages(null)
        presses.clear(); popupOwner = null; popupAlts = emptyList(); stripPressed = -2
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        keyHandler.removeCallbacksAndMessages(null)
    }
}

/** Returns this color with its alpha multiplied by [f]. */
fun Int.withAlpha(f: Float): Int {
    val a = ((this ushr 24) * f).toInt().coerceIn(0, 255)
    return (a shl 24) or (this and 0x00FFFFFF)
}

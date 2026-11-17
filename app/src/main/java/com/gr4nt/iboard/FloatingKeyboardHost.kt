package com.gr4nt.iboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The floating keyboard lives in the IME window; no overlay permission or external window is used. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FloatingKeyboardHost(
    context: Context,
    content: View,
    dark: Boolean,
    private val preferences: SharedPreferences,
    onDock: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(if (dark) Color.argb(242, 27, 29, 36) else Color.argb(242, 243, 245, 251))
            cornerRadius = 24 * density
            setStroke((density * .8f).roundToInt().coerceAtLeast(1), if (dark) 0x55FFFFFF else 0x99FFFFFF.toInt())
        }
        clipToOutline = true
        elevation = 12 * density
        pivotX = 0f; pivotY = 0f
    }
    private var fractionX = preferences.getFloat("floating_x", .5f).coerceIn(0f, 1f)
    private var fractionY = preferences.getFloat("floating_y", .78f).coerceIn(0f, 1f)
    private var dragX = 0f
    private var dragY = 0f
    private var originX = 0f
    private var originY = 0f
    private var displayWidth = 0
    private var displayHeight = 0
    private var safeTop = 0
    private var safeBottom = 0

    init {
        clipChildren = false
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(context).apply {
            text = "━━  Move"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (dark) Color.WHITE else Color.DKGRAY)
            contentDescription = "Drag to move floating keyboard"
            isFocusable = false
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragX = event.rawX; dragY = event.rawY
                        originX = card.left.toFloat(); originY = card.top.toFloat()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        moveTo(originX + event.rawX - dragX, originY + event.rawY - dragY)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        preferences.edit().putFloat("floating_x", fractionX).putFloat("floating_y", fractionY).apply()
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        header.addView(handle, LinearLayout.LayoutParams(0, (38 * density).roundToInt(), 1f))
        header.addView(TextView(context).apply {
            text = "Dock"
            textSize = 14f
            gravity = Gravity.CENTER
            contentDescription = "Dock keyboard at bottom of screen"
            setTextColor(if (dark) 0xFF9FC7FF.toInt() else 0xFF1769D2.toInt())
            isFocusable = false
            setOnClickListener { onDock() }
        }, LinearLayout.LayoutParams((70 * density).roundToInt(), (38 * density).roundToInt()))
        card.addView(header)
        card.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnApplyWindowInsetsListener { _, insets ->
            // Legacy system inset getters are available on every supported API, including API 26.
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            if (safeTop != top || safeBottom != bottom) { safeTop = top; safeBottom = bottom; requestLayout() }
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val width = minOf((360 * density).roundToInt(), w - (24 * density).roundToInt()).coerceAtLeast(1)
        card.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val available = (h - safeTop - safeBottom - 24 * density).coerceAtLeast(1f)
        val scale = minOf(1f, available / card.measuredHeight.coerceAtLeast(1))
        card.scaleX = scale; card.scaleY = scale
        displayWidth = (card.measuredWidth * scale).roundToInt()
        displayHeight = (card.measuredHeight * scale).roundToInt()
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val margin = (12 * density).roundToInt()
        val minY = safeTop + margin
        val x = FloatingBounds.position(fractionX, margin, width - displayWidth - margin)
        val y = FloatingBounds.position(fractionY, minY, height - safeBottom - displayHeight - margin)
        card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
    }

    private fun moveTo(x: Float, y: Float) {
        val margin = (12 * density).roundToInt()
        fractionX = FloatingBounds.fraction(x, margin, width - displayWidth - margin)
        fractionY = FloatingBounds.fraction(y, safeTop + margin, height - safeBottom - displayHeight - margin)
        requestLayout()
    }

    fun touchBounds(): Rect {
        val location = IntArray(2)
        card.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + displayWidth, location[1] + displayHeight)
    }
}

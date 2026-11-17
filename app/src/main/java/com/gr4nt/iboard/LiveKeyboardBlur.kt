package com.gr4nt.iboard

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.roundToInt
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import java.util.function.Consumer

/** System-composited backdrop blur, bounded to the IME. Never captures another app's pixels. */
class LiveKeyboardBlur(
    context: Context,
    private val window: Window,
    private val view: View,
    private val theme: KeyboardTheme,
    private val opacity: Int,
) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var enabled = false
    private val listener = Consumer<Boolean> { value -> enabled = value; update() }
    private var lastBounds: Rect? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val xy = IntArray(2)
        view.getLocationInWindow(xy)
        var scaleX = 1f; var scaleY = 1f
        var ancestor: View? = view
        while (ancestor != null) {
            scaleX *= ancestor.scaleX; scaleY *= ancestor.scaleY
            ancestor = ancestor.parent as? View
        }
        val decor = window.decorView
        val bounds = Rect(xy[0], xy[1], xy[0] + (view.width * scaleX).roundToInt(), xy[1] + (view.height * scaleY).roundToInt())
        if (bounds != lastBounds || decor.width != windowWidth || decor.height != windowHeight) {
            lastBounds = bounds
            windowWidth = decor.width; windowHeight = decor.height
            update()
        }
    }
    init {
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                enabled = manager.isCrossWindowBlurEnabled
                manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
            }.onFailure { enabled = false }
        }
        update()
    }
    private fun update() {
        val gradient = theme.backgroundDrawable()
        gradient.alpha = ((if (enabled) opacity.coerceIn(40, 85) else maxOf(opacity, 90)) * 255) / 100
        view.background = gradient
        if (Build.VERSION.SDK_INT >= 31) {
            val bounds = lastBounds
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
                window.setBackgroundDrawableResource(android.R.color.transparent)
                runCatching { window.setBackgroundBlurRadius(0) }
                return
            }
            // Inset the drawable outline to the input view so the host's visible content stays sharp.
            val pane = GradientDrawable().apply {
                cornerRadii = floatArrayOf(24*density,24*density,24*density,24*density,0f,0f,0f,0f)
                setColor(if (theme.dark) 0x24101A30 else 0x24FFFFFF)
            }
            // Bound all four edges, including scale, so a floating keyboard does not blur/tint the rest of the app.
            window.setBackgroundDrawable(InsetDrawable(pane,
                bounds.left.coerceAtLeast(0), bounds.top.coerceAtLeast(0),
                (windowWidth - bounds.right).coerceAtLeast(0), (windowHeight - bounds.bottom).coerceAtLeast(0)))
            runCatching { window.setBackgroundBlurRadius(if (enabled) (32 * density).toInt() else 0) }
        } else window.setBackgroundDrawableResource(android.R.color.transparent)
    }
    override fun close() {
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { manager.removeCrossWindowBlurEnabledListener(listener) }
            runCatching { window.setBackgroundBlurRadius(0) }
        }
    }
}

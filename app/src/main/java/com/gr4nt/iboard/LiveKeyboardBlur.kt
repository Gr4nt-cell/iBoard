package com.gr4nt.iboard

import android.content.Context
import android.graphics.Color
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
    private var lastTop = -1
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val xy = IntArray(2);view.getLocationInWindow(xy)
        if (xy[1] != lastTop) { lastTop = xy[1]; update() }
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
            if (lastTop < 0) {
                window.setBackgroundDrawableResource(android.R.color.transparent)
                runCatching { window.setBackgroundBlurRadius(0) }
                return
            }
            // Inset the drawable outline to the input view so the host's visible content stays sharp.
            val pane = GradientDrawable().apply {
                cornerRadii = floatArrayOf(24*density,24*density,24*density,24*density,0f,0f,0f,0f)
                setColor(if (theme.dark) 0x24101A30 else 0x24FFFFFF)
            }
            window.setBackgroundDrawable(InsetDrawable(pane, 0, maxOf(0, lastTop), 0, 0))
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

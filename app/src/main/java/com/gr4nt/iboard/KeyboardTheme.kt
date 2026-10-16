package com.gr4nt.iboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Colors for the glass keys and the soft gradient backdrop. */
data class KeyboardTheme(
    val dark: Boolean,
    val gradient: IntArray,
    val keyFill: Int,
    val keyFillPressed: Int,
    val specialFill: Int,
    val specialFillPressed: Int,
    val keyHighlightTop: Int,
    val keyStroke: Int,
    val keyShadow: Int,
    val keyText: Int,
    val keySubText: Int,
    val accent: Int,
    val accentText: Int,
    val stripText: Int,
    val stripDivider: Int,
    val bubbleFill: Int,
    val panelCard: Int,
) {
    fun backgroundDrawable(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, gradient)

    companion object {
        fun isNight(context: Context, prefs: Prefs): Boolean = when (prefs.theme) {
            "light" -> false
            "dark" -> true
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }

        fun resolve(context: Context, prefs: Prefs): KeyboardTheme {
            val dark = isNight(context, prefs)
            val g = gradientFor(prefs.gradient, dark)
            val base = if (prefs.keyStyle == "glass") glass(dark, g) else solid(dark, g)
            return base.copy(accent = accentFor(prefs.accentColor, dark))
        }

        /** iOS 27 look: opaque rounded keys with a crisp bottom shadow. */
        private fun solid(dark: Boolean, g: IntArray): KeyboardTheme = if (!dark) KeyboardTheme(
            dark = false,
            gradient = g,
            keyFill = Color.WHITE,
            keyFillPressed = Color.rgb(212, 214, 220),
            specialFill = Color.WHITE,
            specialFillPressed = Color.rgb(212, 214, 220),
            keyHighlightTop = Color.TRANSPARENT,
            keyStroke = Color.TRANSPARENT,
            keyShadow = Color.argb(90, 137, 138, 141),
            keyText = Color.BLACK,
            keySubText = Color.argb(140, 0, 0, 0),
            accent = Color.rgb(0, 122, 255),
            accentText = Color.WHITE,
            stripText = Color.BLACK,
            stripDivider = Color.argb(50, 60, 60, 67),
            bubbleFill = Color.WHITE,
            panelCard = Color.argb(220, 255, 255, 255),
        ) else KeyboardTheme(
            dark = true,
            gradient = g,
            keyFill = Color.rgb(88, 88, 94),
            keyFillPressed = Color.rgb(125, 125, 132),
            specialFill = Color.rgb(88, 88, 94),
            specialFillPressed = Color.rgb(125, 125, 132),
            keyHighlightTop = Color.TRANSPARENT,
            keyStroke = Color.TRANSPARENT,
            keyShadow = Color.argb(140, 0, 0, 0),
            keyText = Color.WHITE,
            keySubText = Color.argb(160, 255, 255, 255),
            accent = Color.rgb(10, 132, 255),
            accentText = Color.WHITE,
            stripText = Color.WHITE,
            stripDivider = Color.argb(60, 255, 255, 255),
            bubbleFill = Color.rgb(110, 110, 116),
            panelCard = Color.argb(60, 255, 255, 255),
        )

        private fun accentFor(name: String, dark: Boolean): Int {
            val pair: Pair<Long, Long> = when (name) {
                "green" -> 0xFF34C759 to 0xFF30D158
                "purple" -> 0xFFAF52DE to 0xFFBF5AF2
                "pink" -> 0xFFFF2D55 to 0xFFFF375F
                "orange" -> 0xFFFF9500 to 0xFFFF9F0A
                "teal" -> 0xFF30B0C7 to 0xFF40C8E0
                "graphite" -> 0xFF8E8E93 to 0xFF636366
                else -> 0xFF007AFF to 0xFF0A84FF
            }
            return (if (dark) pair.second else pair.first).toInt()
        }

        private fun glass(dark: Boolean, g: IntArray): KeyboardTheme {
            return if (!dark) {
                KeyboardTheme(
                    dark = false,
                    gradient = g,
                    keyFill = Color.argb(200, 255, 255, 255),
                    keyFillPressed = Color.argb(120, 255, 255, 255),
                    specialFill = Color.argb(110, 255, 255, 255),
                    specialFillPressed = Color.argb(225, 255, 255, 255),
                    keyHighlightTop = Color.argb(150, 255, 255, 255),
                    keyStroke = Color.argb(140, 255, 255, 255),
                    keyShadow = Color.argb(38, 60, 40, 90),
                    keyText = Color.rgb(28, 28, 30),
                    keySubText = Color.argb(150, 28, 28, 30),
                    accent = Color.rgb(0, 122, 255),
                    accentText = Color.WHITE,
                    stripText = Color.rgb(28, 28, 30),
                    stripDivider = Color.argb(45, 60, 40, 90),
                    bubbleFill = Color.argb(250, 255, 255, 255),
                    panelCard = Color.argb(170, 255, 255, 255),
                )
            } else {
                KeyboardTheme(
                    dark = true,
                    gradient = g,
                    keyFill = Color.argb(60, 255, 255, 255),
                    keyFillPressed = Color.argb(110, 255, 255, 255),
                    specialFill = Color.argb(30, 255, 255, 255),
                    specialFillPressed = Color.argb(80, 255, 255, 255),
                    keyHighlightTop = Color.argb(40, 255, 255, 255),
                    keyStroke = Color.argb(45, 255, 255, 255),
                    keyShadow = Color.argb(90, 0, 0, 0),
                    keyText = Color.WHITE,
                    keySubText = Color.argb(160, 255, 255, 255),
                    accent = Color.rgb(10, 132, 255),
                    accentText = Color.WHITE,
                    stripText = Color.WHITE,
                    stripDivider = Color.argb(50, 255, 255, 255),
                    bubbleFill = Color.argb(255, 70, 66, 92),
                    panelCard = Color.argb(45, 255, 255, 255),
                )
            }
        }

        private fun gradientFor(name: String, dark: Boolean): IntArray = when (name) {
            "ocean" -> if (dark) intArrayOf(0xFF0F2433.toInt(), 0xFF112B3A.toInt(), 0xFF0E1F2A.toInt())
            else intArrayOf(0xFFCFE3F7.toInt(), 0xFFD5EEF0.toInt(), 0xFFD8F2E4.toInt())
            "sunset" -> if (dark) intArrayOf(0xFF34202A.toInt(), 0xFF2E1D2E.toInt(), 0xFF1E1726.toInt())
            else intArrayOf(0xFFF8DCCB.toInt(), 0xFFF3D3DC.toInt(), 0xFFE7D2EC.toInt())
            "classic" -> if (dark) intArrayOf(0xFF2B2B2E.toInt(), 0xFF262629.toInt(), 0xFF202023.toInt())
            else intArrayOf(0xFFE3E4E9.toInt(), 0xFFE0E1E6.toInt(), 0xFFDCDDE2.toInt())
            "graphite" -> if (dark) intArrayOf(0xFF1F1F23.toInt(), 0xFF1A1A1E.toInt(), 0xFF141417.toInt())
            else intArrayOf(0xFFE3E4E9.toInt(), 0xFFDCDDE3.toInt(), 0xFFD3D5DC.toInt())
            // "blossom" – matches the soft pink→lavender→blue look of the dialer
            else -> if (dark) intArrayOf(0xFF2B1E3A.toInt(), 0xFF221F3F.toInt(), 0xFF141A2E.toInt())
            else intArrayOf(0xFFECD2EA.toInt(), 0xFFE2D8F2.toInt(), 0xFFD2DCF3.toInt())
        }
    }
}

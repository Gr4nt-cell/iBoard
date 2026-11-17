package com.gr4nt.iboard

import kotlin.math.roundToInt

/** Bounded normalized positions survive rotation and keyboard/panel size changes. */
object FloatingBounds {
    fun position(fraction: Float, minimum: Int, maximum: Int): Int =
        minimum + ((maximum - minimum).coerceAtLeast(0) * fraction.coerceIn(0f, 1f)).roundToInt()
    fun fraction(position: Float, minimum: Int, maximum: Int): Float =
        if (maximum <= minimum) .5f else ((position - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
}

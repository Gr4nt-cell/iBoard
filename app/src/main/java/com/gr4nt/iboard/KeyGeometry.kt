package com.gr4nt.iboard

import kotlin.math.max
import kotlin.math.min

/** Shared, density-independent layout used for touch bounds and responsive keyboard sizing. */
object KeyGeometry {
    data class Bounds(val key: Key, val left: Float, val right: Float)
    fun centerGap(width: Float, density: Float): Float = (width * 0.14f).coerceIn(48 * density, 180 * density)

    fun row(keys: List<Key>, width: Float, density: Float, split: Boolean, units: Float, utility: Boolean = false): List<Bounds> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<Bounds>()
        val outer = if (split) max((if (utility) 16 else 10) * density, (width - 1100 * density) / 2f) else max((if (utility) 16 else 6) * density, (width - 760 * density) / 2f)
        val gap = 6 * density
        fun place(part: List<Key>, start: Float, available: Float, targetUnits: Float) {
            if (part.isEmpty()) return
            if (utility) {
                // Compact controls: equal touch targets, rather than stretching FLEX across the screen.
                val spacing = min(8 * density, available / (part.size * 3))
                val cell = min(44 * density, (available - (part.size - 1) * spacing) / part.size)
                val content = part.size * cell + (part.size - 1) * spacing
                var x = start + (available - content) / 2
                for (key in part) { out += Bounds(key, x, x + cell); x += cell + spacing }
                return
            }
            val fixed = part.filter { it.width != Key.FLEX }.sumOf { it.width.toDouble() }.toFloat()
            val flex = part.count { it.width == Key.FLEX }
            val target = max(targetUnits, fixed + flex)
            val weights = part.map { if (it.width == Key.FLEX) (target - fixed) / max(1, flex) else it.width }
            val unit = (available - (target - 1) * gap).coerceAtLeast(target) / target
            val widths = weights.map { it * unit + (it - 1) * gap }
            val contentWidth = widths.sum() + (part.size - 1) * gap
            var x = start + (available - contentWidth) / 2f
            part.forEachIndexed { i, key -> val w = widths[i];out += Bounds(key, x, x + w);x += w + gap }

        }
        if (!split) place(keys, outer, width - outer * 2, units)
        else {
            val space = keys.indexOfFirst { it.type == KeyType.SPACE }
            val left: List<Key>;val right: List<Key>
            if (space >= 0) {
                left = keys.take(space) + keys[space].copy(width = Key.FLEX)
                right = listOf(keys[space].copy(width = Key.FLEX)) + keys.drop(space + 1)
            } else {
                var cut = (keys.size + 1) / 2
                // Keep language and dictation together inside one landscape utility capsule.
                if (cut > 1 && cut < keys.size - 1 && keys[cut - 1].type == KeyType.GLOBE && keys[cut].type == KeyType.MIC) cut--
                left = keys.take(cut);right = keys.drop(cut)
            }
            val gutter = centerGap(width, density)
            val panel = (width - outer * 2 - gutter) / 2
            place(left, outer, panel, units / 2)
            place(right, width / 2 + gutter / 2, panel, units / 2)
        }
        return out
    }
    /** Shared glass behind a utility row; landscape leaves the thumb gutter empty. */
    fun utilityPanels(bounds: List<Bounds>, width: Float, split: Boolean): List<Pair<Float, Float>> {
        val groups = if (split) listOf(bounds.filter { it.right <= width / 2 }, bounds.filter { it.left >= width / 2 }) else listOf(bounds)
        return groups.filter { it.isNotEmpty() }.map { group ->
            group.minOf { it.left } to group.maxOf { it.right }
        }
    }

}

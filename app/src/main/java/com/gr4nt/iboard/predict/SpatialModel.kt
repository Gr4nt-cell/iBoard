package com.gr4nt.iboard.predict

import kotlin.math.min

/**
 * Where each letter key sits on screen, so a typed letter can be scored by how far the finger
 * landed from every key instead of only "same key" or "neighbouring key". A touch on the edge
 * between two keys makes both letters nearly equally likely, as on modern keyboards.
 */
class SpatialModel(private val centers: Map<Char, Point>, private val keyWidth: Float, private val keyHeight: Float) {
    data class Point(val x: Float, val y: Float)

    fun hasKey(c: Char) = centers.containsKey(c.lowercaseChar())

    /** Squared distance from [touch] to the centre of [c]'s key, in key widths/heights; null if no such key. */
    fun distance2(touch: Point, c: Char): Float? {
        val ctr = centers[c.lowercaseChar()] ?: return null
        val dx = (touch.x - ctr.x) / keyWidth
        val dy = (touch.y - ctr.y) / keyHeight
        return dx * dx + dy * dy
    }

    /**
     * Cost of reading [touch] as letter [c] (0 = a perfect hit, 1 = a full edit). Close to a
     * Gaussian touch model: inside the key is nearly free, half a key outside costs about half an edit.
     */
    fun cost(touch: Point, c: Char): Float? {
        val d2 = distance2(touch, c) ?: return null
        return min(1f, 0.04f + 0.62f * d2)
    }

    /** Letters whose keys are within [radius] key sizes of [touch]. */
    fun near(touch: Point, radius: Float = 1.25f): List<Char> =
        centers.keys.filter { (distance2(touch, it) ?: 99f) <= radius * radius }

    companion object {
        /** A plain QWERTY grid with the usual half-key row offsets; used by tests and as a fallback. */
        fun grid(rows: List<String>, keyWidth: Float = 1f, keyHeight: Float = 1.4f): SpatialModel {
            val map = HashMap<Char, Point>()
            rows.forEachIndexed { r, row ->
                val letters = row.split(" ").filter { it.length == 1 }.map { it[0] }
                val offset = r * 0.5f
                letters.forEachIndexed { i, c -> map[c] = Point((i + offset + 0.5f) * keyWidth, (r + 0.5f) * keyHeight) }
            }
            return SpatialModel(map, keyWidth, keyHeight)
        }
    }
}

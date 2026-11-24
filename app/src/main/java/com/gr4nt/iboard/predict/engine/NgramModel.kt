package com.gr4nt.iboard.predict.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * Pruned Kneser-Ney bigram / trigram / 4-gram tables (tools/prediction/build_ngram.py), read straight
 * from a memory-mapped file: contexts are binary-searched, nothing is parsed or copied onto the heap.
 * It answers in well under a millisecond and keeps suggestions sensible while the neural model is busy.
 */
class NgramModel(file: ByteBuffer) {
    private class Order(val contextLength: Int, val keys: LongBuffer, val totals: ByteBuffer, val offsets: java.nio.IntBuffer,
                        val words: java.nio.ShortBuffer, val costs: java.nio.ShortBuffer, val count: Int)

    private val orders = ArrayList<Order>()
    val vocabSize: Int

    init {
        val b = file.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        b.position(0)
        require(b.get().toInt().toChar() == 'I' && b.get().toInt().toChar() == 'B' && b.get().toInt().toChar() == 'N' && b.get().toInt().toChar() == 'G') { "not an iBoard n-gram file" }
        require(b.int == 1) { "unsupported n-gram version" }
        vocabSize = b.int
        val n = b.int
        repeat(n) {
            val ctxLen = b.int; val nCtx = b.int; val nEntries = b.int
            fun slice(bytes: Int): ByteBuffer {
                val s = b.slice().order(ByteOrder.LITTLE_ENDIAN); s.limit(bytes)
                b.position(b.position() + bytes); return s
            }
            val keys = slice(nCtx * 8).asLongBuffer()
            val totals = slice(nCtx)
            val offsets = slice((nCtx + 1) * 4).asIntBuffer()
            val words = slice(nEntries * 2).asShortBuffer()
            val costs = slice(nEntries * 2).asShortBuffer()
            orders += Order(ctxLen, keys, totals, offsets, words, costs, nCtx)
        }
    }

    /** Candidate words (id → probability) for the text ending in [ids]; empty when nothing was seen. */
    fun predict(ids: IntArray, into: MutableMap<Int, Float> = HashMap()): MutableMap<Int, Float> {
        var weightSum = 0f
        val acc = HashMap<Int, Float>()
        for (o in orders.asReversed()) { // highest order first
            if (ids.size < o.contextLength) continue
            var key = 0L
            for (i in ids.size - o.contextLength until ids.size) key = (key shl 16) or ids[i].toLong()
            val at = find(o, key)
            if (at < 0) continue
            // Longer, better-attested contexts weigh more; the weight grows with how often the context occurred.
            val seen = (o.totals.get(at).toInt() and 0xFF) / 8f
            val w = (0.35f + 0.65f * (o.contextLength / 3f)) * (seen / (seen + 3f))
            weightSum += w
            for (e in o.offsets.get(at) until o.offsets.get(at + 1)) {
                val word = o.words.get(e).toInt() and 0xFFFF
                val p = exp(-(o.costs.get(e).toInt() and 0xFFFF) / 3000f)
                acc[word] = (acc[word] ?: 0f) + w * p
            }
        }
        if (weightSum > 0f) for ((k, v) in acc) into[k] = v / weightSum
        return into
    }

    private fun find(o: Order, key: Long): Int {
        var lo = 0; var hi = o.count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = o.keys.get(mid)
            // keys are unsigned; ids use at most 48 bits so signed comparison is safe
            if (v < key) lo = mid + 1 else if (v > key) hi = mid - 1 else return mid
        }
        return -1
    }
}

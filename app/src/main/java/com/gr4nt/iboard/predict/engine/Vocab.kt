package com.gr4nt.iboard.predict.engine

import com.gr4nt.iboard.predict.Dictionary

/**
 * The model vocabulary: ids 0..4 are specials, the rest are words sorted by frequency (id order = rank).
 * [display] holds the usual spelling (I, London, I'm) and [lower] the lookup key.
 */
class Vocab(val lower: Array<String>, val display: Array<String>, val count: IntArray) {
    val size get() = lower.size
    private val index = HashMap<String, Int>(lower.size * 2).also { m ->
        for (i in Tokenizer.FIRST_WORD until lower.size) m[lower[i]] = i
    }
    val totalCount: Double = count.sumOf { it.toDouble() }.coerceAtLeast(1.0)

    // Accent-free spellings in sorted order, for prefix ranges ("caf" finds café).
    private val sortedKeys: Array<String>
    private val sortedIds: IntArray

    init {
        val order = (Tokenizer.FIRST_WORD until lower.size).sortedBy { Dictionary.normalize(lower[it]) }
        sortedIds = order.toIntArray()
        sortedKeys = Array(order.size) { Dictionary.normalize(lower[order[it]]) }
    }

    fun id(word: String): Int = index[word] ?: Tokenizer.UNK
    fun contains(word: String): Boolean = index.containsKey(word.lowercase())

    /** Ids of every word whose accent-free spelling starts with [normalizedPrefix]. */
    fun withPrefix(normalizedPrefix: String, limit: Int = Int.MAX_VALUE): IntArray {
        var lo = 0
        var hi = sortedKeys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedKeys[mid] < normalizedPrefix) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < sortedKeys.size && sortedKeys[end].startsWith(normalizedPrefix) && end - lo < limit) end++
        return IntArray(end - lo) { sortedIds[lo + it] }
    }

    /** Smoothed unigram log-probability, a weak prior for words the model has no context for. */
    fun logUnigram(id: Int): Float = kotlin.math.ln((count[id] + 1.0) / totalCount).toFloat()

    companion object {
        /** Parses vocab.tsv: id, lower, display, count per line. */
        fun parse(lines: Sequence<String>): Vocab {
            val lower = ArrayList<String>(); val display = ArrayList<String>(); val count = ArrayList<Int>()
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 4) continue
                lower += p[1]; display += p[2]; count += p[3].toIntOrNull() ?: 0
            }
            return Vocab(lower.toTypedArray(), display.toTypedArray(), count.toIntArray())
        }
    }
}

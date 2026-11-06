package com.gr4nt.iboard.input

import android.content.Context

/**
 * Chinese (Simplified) pinyin lookup from assets/dict/zh_pinyin.tsv ("pinyin<TAB>word<TAB>freq",
 * sorted by pinyin, then by frequency). Toneless pinyin; ü is typed as v.
 */
class PinyinEngine private constructor(
    private val keys: Array<String>,
    private val words: Array<String>,
    private val freqs: IntArray,
) {
    /** A candidate and how many typed letters it uses up. */
    data class Candidate(val text: String, val consumes: Int)

    fun candidates(input: String, limit: Int = 40): List<Candidate> {
        if (input.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        fun add(i: Int, consumes: Int) { if (seen.add(words[i])) out += Candidate(words[i], consumes) }

        // 0. A whole phrase: the most likely split of the input into words (woaini → 我爱你).
        if (input.length >= 4) phrase(input)?.let { if (seen.add(it)) out += Candidate(it, input.length) }
        // 1. Words spelled exactly as typed.
        exact(input).take(12).forEach { add(it, input.length) }
        // 2. Words that start with what's typed (an unfinished syllable or word).
        if (input.length >= 1) topByFreq(prefixRange(input), 8).forEach { add(it, input.length) }
        // 3. The longest words that match the start of the input; picking one leaves the rest.
        var k = input.length - 1
        var groups = 0
        while (k >= 1 && out.size < limit && groups < 3) {
            val e = exact(input.substring(0, k))
            if (e.isNotEmpty()) { e.take(if (groups == 0) 10 else 6).forEach { add(it, k) }; groups++ }
            k--
        }
        return out.take(limit)
    }

    /** Splits the whole input into dictionary words, favouring fewer, more common words. */
    private fun phrase(input: String): String? {
        val n = input.length
        val score = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val pick = arrayOfNulls<String>(n + 1)
        score[0] = 0.0
        for (i in 0 until n) {
            if (score[i] == Double.NEGATIVE_INFINITY) continue
            for (j in i + 1..minOf(n, i + 14)) {
                val best = exact(input.substring(i, j)).firstOrNull() ?: continue
                val sc = score[i] + kotlin.math.ln(freqs[best] + 1.0) - 18.0
                if (sc > score[j]) { score[j] = sc; from[j] = i; pick[j] = words[best] }
            }
        }
        if (from[n] < 0) return null
        val parts = ArrayList<String>()
        var k = n
        while (k > 0) { parts += pick[k]!!; k = from[k] }
        return if (parts.size >= 2) parts.reversed().joinToString("") else null
    }

    private fun lowerBound(p: String): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (keys[m] < p) lo = m + 1 else hi = m }
        return lo
    }

    private fun exact(k: String): List<Int> {
        val r = ArrayList<Int>()
        var i = lowerBound(k)
        while (i < keys.size && keys[i] == k) { r += i; i++ }
        return r
    }

    private fun prefixRange(p: String): IntRange {
        val start = lowerBound(p)
        var end = start
        while (end < keys.size && keys[end].startsWith(p)) end++
        return start until end
    }

    private fun topByFreq(range: IntRange, n: Int): List<Int> {
        if (range.isEmpty()) return emptyList()
        val best = ArrayList<Int>(n + 1)
        for (i in range) {
            if (best.size == n && freqs[best.last()] >= freqs[i]) continue
            var pos = best.size
            while (pos > 0 && freqs[best[pos - 1]] < freqs[i]) pos--
            best.add(pos, i)
            if (best.size > n) best.removeAt(best.size - 1)
        }
        return best
    }

    companion object {
        @Volatile private var instance: PinyinEngine? = null

        fun load(context: Context): PinyinEngine = instance ?: synchronized(this) {
            instance ?: run {
                val k = ArrayList<String>(120000); val w = ArrayList<String>(120000); val f = ArrayList<Int>(120000)
                context.assets.open("dict/zh_pinyin.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val a = line.indexOf('\t'); val b = line.indexOf('\t', a + 1)
                        if (a > 0 && b > a) {
                            k += line.substring(0, a); w += line.substring(a + 1, b)
                            f += line.substring(b + 1).toIntOrNull() ?: 1
                        }
                    }
                }
                PinyinEngine(k.toTypedArray(), w.toTypedArray(), f.toIntArray()).also { instance = it }
            }
        }
    }
}

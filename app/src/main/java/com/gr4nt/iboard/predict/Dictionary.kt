package com.gr4nt.iboard.predict

import android.content.Context
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

/**
 * A frequency dictionary loaded from assets/dict/<lang>.txt ("word\tcount" per line).
 * Words are indexed by an accent-free key so "espanol" finds "español".
 */
class Dictionary private constructor(
    val lang: String,
    private val keys: Array<String>,
    private val words: Array<String>,
    private val scores: FloatArray,
    private val lookup: HashMap<String, Float>,
    val topWords: List<String>,
    val contextWords: Map<String, List<Pair<String, Float>>>,
    val contextModel: ContextModel,
) {
    private val missingFirst by lazy {
        val index = HashMap<String, MutableList<Int>>()
        keys.forEachIndexed { i, key -> if (key.length > 2) index.getOrPut(key.drop(1).take(3)) { ArrayList() }.add(i) }
        index
    }
    private val correctionCache = object : LinkedHashMap<String, List<Correction>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Correction>>?) = size > 64
    }

    fun score(word: String): Float? = lookup[word.lowercase()] ?: lookup[word]

    fun contains(word: String): Boolean = score(word) != null

    /** Best-scoring words whose key starts with [prefix] (already normalized). */
    fun completions(prefix: String, limit: Int): List<Pair<String, Float>> {
        if (prefix.isEmpty()) return emptyList()
        var i = lowerBound(prefix)
        val best = ArrayList<Pair<String, Float>>(limit + 1)
        var scanned = 0
        while (i < keys.size && keys[i].startsWith(prefix) && scanned < 6000) {
            insertTop(best, words[i] to scores[i], limit)
            i++; scanned++
        }
        return best
    }

    /**
     * Typo corrections for [typed] using a keyboard-aware edit distance.
     * Only words starting with the typed first letter (or a neighboring key) are scanned.
     */
    fun corrections(typed: String, neighbors: Map<Char, String>, limit: Int): List<Correction> {
        val t = typed.lowercase()
        if (t.length < 2) return emptyList()
        val cacheKey = "$t|$limit"
        correctionCache[cacheKey]?.let { return it }
        val firstKey = normalize(t.substring(0, 1))
        val starts = LinkedHashSet<String>()
        starts += firstKey
        // An extra leading letter or a transposed first pair can change the first-key bucket.
        if (t.length > 2) starts += normalize(t.substring(1, 2))
        neighbors[t[0]]?.forEach { starts += normalize(it.toString()) }
        val maxDist = when {
            t.length <= 3 -> 1.0f
            t.length <= 6 -> 1.6f
            else -> 2.2f
        }
        val candidateIds = LinkedHashSet<Int>()
        for (prefix in starts) {
            if (prefix.isEmpty()) continue
            var i = lowerBound(prefix)
            while (i < keys.size && keys[i].startsWith(prefix)) { candidateIds += i; i++ }
        }
        // Recover a missing first letter, which a first-letter-only scan cannot find.
        missingFirst[normalize(t).take(3)]?.let { candidateIds.addAll(it) }
        val out = ArrayList<Correction>()
        for (i in candidateIds) {
            val w = words[i]
            if (abs(w.length - t.length) <= 2 && w != t) {
                val d = distance(t, w, neighbors, maxDist)
                if (d <= maxDist) out += Correction(w, d, scores[i])
            }
        }
        out.sortBy { it.distance * 2.4f - it.score }
        val result = out.take(limit)
        correctionCache[cacheKey] = result
        return result
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    data class Correction(val word: String, val distance: Float, val score: Float)

    companion object {
        private val MARKS = Regex("\\p{Mn}+")

        fun normalize(s: String): String {
            val lower = s.lowercase()
            var n = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(MARKS, "")
            // Fold Arabic letter variants so prefix search is forgiving.
            if (n.any { it in '؀'..'ۿ' }) {
                n = n.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                    .replace('ى', 'ي').replace('ة', 'ه')
            }
            // Apostrophes are ignored when matching, so "dont" finds "don't" and "cest" finds "c'est".
            return n.replace("ß", "ss").replace("œ", "oe").replace("æ", "ae")
                .replace("'", "").replace("\u2019", "")
        }

        fun load(context: Context, lang: String): Dictionary {
            val rawWords = ArrayList<String>(40000)
            val rawScores = ArrayList<Float>(40000)
            context.assets.open("dict/$lang.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val count = line.substring(tab + 1).toLongOrNull() ?: 1L
                        rawWords += line.substring(0, tab)
                        rawScores += log10(count.toDouble().coerceAtLeast(1.0)).toFloat()
                    }
                }
            }
            val top = rawWords.take(60)
            val lookup = HashMap<String, Float>(rawWords.size * 2)
            for (i in rawWords.indices) lookup.putIfAbsent(rawWords[i], rawScores[i])
            val order = rawWords.indices.map { normalize(rawWords[it]) to it }.sortedBy { it.first }
            return Dictionary(
                lang,
                Array(order.size) { order[it].first },
                Array(order.size) { rawWords[order[it].second] },
                FloatArray(order.size) { rawScores[order[it].second] },
                lookup,
                top,
                loadContext(context, lang),
                ContextModel.load(context, lang),
            )
        }

        private fun loadContext(context: Context, lang: String): Map<String, List<Pair<String, Float>>> {
            val pairs = HashMap<String, MutableList<Pair<String, Float>>>()
            runCatching {
                context.assets.open("dict/${lang}_bigrams.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val p = line.split('\t')
                        if (p.size == 3) pairs.getOrPut(p[0].lowercase()) { ArrayList() }.add(p[1] to (p[2].toFloatOrNull() ?: 1f))
                    }
                }
            }
            return pairs.mapValues { it.value.sortedByDescending { pair -> pair.second } }
        }

        private fun insertTop(list: ArrayList<Pair<String, Float>>, item: Pair<String, Float>, limit: Int) {
            if (list.size == limit && list.last().second >= item.second) return
            var idx = list.size
            while (idx > 0 && list[idx - 1].second < item.second) idx--
            list.add(idx, item)
            if (list.size > limit) list.removeAt(list.size - 1)
        }

        /** Weighted Damerau–Levenshtein: neighboring-key and accent slips are cheap. */
        fun distance(a: String, b: String, neighbors: Map<Char, String>, cutoff: Float): Float {
            val n = a.length
            val m = b.length
            val d = Array(n + 1) { FloatArray(m + 1) }
            for (i in 0..n) d[i][0] = i.toFloat()
            for (j in 0..m) d[0][j] = j.toFloat()
            for (i in 1..n) {
                var rowMin = Float.MAX_VALUE
                for (j in 1..m) {
                    val ca = a[i - 1]
                    val cb = b[j - 1]
                    val sub = when {
                        ca == cb -> 0f
                        baseChar(ca) == baseChar(cb) -> 0.15f
                        neighbors[ca]?.indexOf(cb)?.let { it >= 0 } == true -> 0.55f
                        else -> 1f
                    }
                    var v = min(min(d[i - 1][j] + 1f, d[i][j - 1] + 1f), d[i - 1][j - 1] + sub)
                    if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, d[i - 2][j - 2] + 0.7f)
                    d[i][j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > cutoff) return Float.MAX_VALUE
            }
            return d[n][m]
        }

        private fun baseChar(c: Char): Char {
            if (c.code < 128) return c
            val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            return n.firstOrNull() ?: c
        }
    }
}

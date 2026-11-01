package com.gr4nt.iboard.predict

import android.content.Context

/** Corpus-trained, interpolated trigram model; bounded fanout keeps inference off the hot path. */
class ContextModel private constructor(
    private val pairs: Map<String, List<Pair<String, Float>>>,
    private val triples: Map<String, List<Pair<String, Float>>>,
) {
    private var cachedKey: String? = null
    private var cached = emptyList<Pair<String, Float>>()
    fun predict(previous: String?, before: String?): List<Pair<String, Float>> {
        if (previous == null) return emptyList()
        val p = previous.lowercase().replace('’', '\'')
        val key = "$before\t$p"
        if (cachedKey == key) return cached
        val bi = pairs[p].orEmpty()
        val tri = triples["${before?.lowercase()?.replace('’', '\'')}\t$p"].orEmpty()
        val scores = HashMap<String, Float>()
        for ((word, score) in bi) scores[word] = score * if (tri.isEmpty()) 1f else .3f
        for ((word, score) in tri) scores[word] = (scores[word] ?: 0f) + .7f * score
        cached = scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
        cachedKey = key
        return cached
    }

    companion object {
        fun load(context: Context, lang: String): ContextModel {
            val bi = HashMap<String, MutableList<Pair<String, Float>>>()
            val tri = HashMap<String, MutableList<Pair<String, Float>>>()
            // A missing or damaged embedded model is a packaging error, never an online fallback.
            context.assets.open("prediction/$lang.tsv").bufferedReader().useLines { lines ->
                lines.filterNot { it.startsWith('#') }.forEach { line ->
                    val cols = line.split('\t')
                    if (cols.size == 4 && cols[0] == "B")
                        bi.getOrPut(cols[1]) { ArrayList() }.add(cols[2] to cols[3].toFloat())
                    else if (cols.size == 5 && cols[0] == "T")
                        tri.getOrPut("${cols[1]}\t${cols[2]}") { ArrayList() }.add(cols[3] to cols[4].toFloat())
                }
            }
            fun normalize(rows: Map<String, List<Pair<String, Float>>>) = rows.mapValues { (_, items) ->
                val total = items.sumOf { it.second.toDouble() }.toFloat()
                items.map { it.first to it.second / total }
            }
            return ContextModel(normalize(bi), normalize(tri))
        }
    }
}

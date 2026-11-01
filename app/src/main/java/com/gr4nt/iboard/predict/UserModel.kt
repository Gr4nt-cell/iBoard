package com.gr4nt.iboard.predict

import android.content.Context
import java.io.File

/** Words and word pairs the user actually types, learned on-device and saved per language. */
class UserModel(private val context: Context, val lang: String) {
    private val unigrams = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private val trigrams = HashMap<String, HashMap<String, Int>>()
    private var dirty = false

    private val file: File get() = File(context.filesDir, "user_$lang.tsv")

    init {
        try {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 5 && p[0] == "t" -> trigrams.getOrPut(p[1] + "|" + p[2]) { HashMap() }[p[3]] = p[4].toIntOrNull() ?: 1
                    p.size == 3 && p[0] == "u" -> unigrams[p[1]] = p[2].toIntOrNull() ?: 1
                    p.size == 4 && p[0] == "b" ->
                        bigrams.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toIntOrNull() ?: 1
                }
            }
        } catch (_: Exception) {
        }
    }

    fun count(word: String): Int = unigrams[word] ?: unigrams[word.lowercase()] ?: 0

    /** A word the user has typed at least twice is treated as valid (never auto-corrected). */
    fun knows(word: String): Boolean = count(word) >= 2

    fun learn(word: String, previous: String?, previousPrevious: String? = null) {
        if (word.isBlank() || word.length > 40) return
        val w = keyOf(word)
        unigrams[w] = (unigrams[w] ?: 0) + 1
        if (!previous.isNullOrBlank()) {
            val m = bigrams.getOrPut(keyOf(previous)) { HashMap() }
            m[w] = (m[w] ?: 0) + 1
        }
        if (!previous.isNullOrBlank() && !previousPrevious.isNullOrBlank()) {
            val m = trigrams.getOrPut(keyOf(previousPrevious) + "|" + keyOf(previous)) { HashMap() }
            m[w] = (m[w] ?: 0) + 1
        }
        dirty = true
        if (unigrams.size > 20000) prune()
    }

    /** Marks a word as known right away (e.g. the user undid an autocorrect). */
    fun trust(word: String) {
        val w = keyOf(word)
        unigrams[w] = maxOf(unigrams[w] ?: 0, 2)
        dirty = true
    }

    fun forget(word: String) {
        val key = keyOf(word)
        unigrams.remove(key)
        bigrams.remove(key);bigrams.values.forEach { it.remove(key) }
        trigrams.entries.removeAll { key in it.key.split('|') }
        trigrams.values.forEach { it.remove(key) }
        dirty = true
    }

    fun trigramCount(previousPrevious: String?, previous: String?, word: String): Int =
        if (previousPrevious.isNullOrBlank() || previous.isNullOrBlank()) 0
        else trigrams[keyOf(previousPrevious) + "|" + keyOf(previous)]?.get(keyOf(word)) ?: 0

    fun nextWords(previous: String?, limit: Int, previousPrevious: String? = null): List<Pair<String, Int>> {
        if (previous.isNullOrBlank()) return emptyList()
        val scores = HashMap<String, Int>()
        bigrams[keyOf(previous)]?.forEach { (w, c) -> scores[w] = c }
        if (!previousPrevious.isNullOrBlank()) trigrams[keyOf(previousPrevious) + "|" + keyOf(previous)]?.forEach { (w, c) -> scores[w] = (scores[w] ?: 0) + c * 4 }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    fun bigramCount(previous: String?, word: String): Int {
        if (previous.isNullOrBlank()) return 0
        return bigrams[keyOf(previous)]?.get(keyOf(word)) ?: 0
    }

    fun prefixed(prefixLower: String, limit: Int): List<Pair<String, Int>> =
        unigrams.entries.asSequence()
            .filter { it.value >= 2 && it.key.lowercase().startsWith(prefixLower) }
            .sortedByDescending { it.value }.take(limit).map { it.key to it.value }.toList()

    fun save() {
        if (!dirty) return
        try {
            val sb = StringBuilder()
            unigrams.forEach { (w, c) -> sb.append("u\t").append(w).append('\t').append(c).append('\n') }
            bigrams.forEach { (p, m) ->
                m.forEach { (w, c) -> sb.append("b\t").append(p).append('\t').append(w).append('\t').append(c).append('\n') }
            }
            trigrams.forEach { (pair, words) ->
                val parts = pair.split('|')
                words.forEach { (w, c) -> sb.append("t\t").append(parts[0]).append('\t').append(parts[1]).append('\t').append(w).append('\t').append(c).append('\n') }
            }
            val tmp = File(context.filesDir, "user_$lang.tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(file)
            dirty = false
        } catch (_: Exception) {
        }
    }

    fun clear() {
        unigrams.clear(); bigrams.clear(); trigrams.clear(); file.delete(); dirty = false
    }

    private fun prune() {
        unigrams.entries.removeAll { it.value <= 1 }
        bigrams.values.forEach { m -> m.entries.removeAll { it.value <= 1 } }
        trigrams.values.forEach { m -> m.entries.removeAll { it.value <= 1 } }
        trigrams.entries.removeAll { it.value.isEmpty() }
        bigrams.entries.removeAll { it.value.isEmpty() }
    }

    /** Keep capitalization only for words that are naturally capitalized (names, "I"). */
    private fun keyOf(word: String): String {
        val clean = word.replace("\t", "").replace("\n", "")
        return if (clean.length > 1 && clean[0].isUpperCase() && clean.drop(1).all { !it.isUpperCase() })
            clean.lowercase() else clean
    }
}

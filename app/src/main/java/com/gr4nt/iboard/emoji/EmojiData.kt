package com.gr4nt.iboard.emoji

import android.content.Context
import android.graphics.Paint

data class Emoji(val text: String, val skin: Boolean, val name: String)

/** Emoji grouped into iOS's categories, filtered to what this phone's emoji font can draw. */
object EmojiData {
    val CATEGORY_NAMES = listOf(
        "FREQUENTLY USED", "SMILEYS & PEOPLE", "ANIMALS & NATURE", "FOOD & DRINK",
        "ACTIVITY", "TRAVEL & PLACES", "OBJECTS", "SYMBOLS", "FLAGS",
    )
    val TONES = listOf("🏻", "🏼", "🏽", "🏾", "🏿")

    @Volatile
    private var cached: List<List<Emoji>>? = null
    private val paint = Paint()

    /** Categories 1..8 (index 0 here is "Smileys & People"). Call off the main thread. */
    fun categories(context: Context): List<List<Emoji>> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            EmojiImages.init(context)
            val cats = List(8) { ArrayList<Emoji>() }
            context.assets.open("emoji.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size >= 4) {
                        val c = p[0].toIntOrNull() ?: return@forEach
                        if (c in 0..7 && (EmojiImages.has(p[1]) || canDraw(p[1]))) cats[c] += Emoji(p[1], p[2] == "1", p[3])
                    }
                }
            }
            cached = cats
            return cats
        }
    }

    @Volatile
    private var keywords: Map<String, String>? = null

    /** Finds emoji whose name or keywords start with every word of [query]. Call off the main thread. */
    fun search(context: Context, query: String, limit: Int = 24): List<String> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val kw = keywords ?: synchronized(this) {
            keywords ?: HashMap<String, String>().also { m ->
                try {
                    context.assets.open("emoji_keywords.tsv").bufferedReader().useLines { lines ->
                        lines.forEach { l ->
                            val t = l.indexOf('\t')
                            if (t > 0) m[l.substring(0, t)] = l.substring(t + 1).lowercase()
                        }
                    }
                } catch (_: Exception) {
                }
                keywords = m
            }
        }
        val scored = ArrayList<Pair<String, Int>>()
        categories(context).forEach { cat ->
            cat.forEach { e ->
                val hay = (e.name + " " + (kw[EmojiImages.key(e.text)] ?: "")).lowercase()
                val tokens = hay.split(' ', '-', ':', ',')
                if (words.all { w -> tokens.any { it.startsWith(w) } }) {
                    val score = if (e.name.lowercase().startsWith(words.first())) 0 else 1
                    scored += e.text to score
                }
            }
        }
        return scored.sortedBy { it.second }.map { it.first }.distinct().take(limit)
    }

    fun canDraw(s: String): Boolean = try { paint.hasGlyph(s) } catch (_: Exception) { true }

    /** Applies a Fitzpatrick skin tone to an emoji that supports it. */
    fun withTone(e: String, tone: String?): String {
        if (tone.isNullOrEmpty()) return e
        val first = e.codePointAt(0)
        val n = Character.charCount(first)
        var rest = e.substring(n)
        if (rest.startsWith("️")) rest = rest.substring(1)
        val out = e.substring(0, n) + tone + rest
        return if (EmojiImages.has(out) || canDraw(out)) out else e
    }
}

/** Recently used emoji and remembered skin tones. */
class EmojiHistory(context: Context) {
    private val sp = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)

    fun recents(limit: Int = 32): List<String> {
        val raw = sp.getString("recents", "") ?: ""
        return raw.split('\n').mapNotNull { l ->
            val p = l.split('\t')
            if (p.size == 2) p[0] to (p[1].toFloatOrNull() ?: 0f) else null
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun record(emoji: String) {
        val raw = sp.getString("recents", "") ?: ""
        val map = LinkedHashMap<String, Float>()
        raw.split('\n').forEach { l ->
            val p = l.split('\t')
            if (p.size == 2) map[p[0]] = (p[1].toFloatOrNull() ?: 0f) * 0.97f
        }
        map[emoji] = (map[emoji] ?: 0f) + 1f
        val kept = map.entries.sortedByDescending { it.value }.take(60)
        sp.edit().putString("recents", kept.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    fun tone(base: String): String? = sp.getString("tone_$base", null)

    fun setTone(base: String, tone: String?) {
        sp.edit().putString("tone_$base", tone ?: "").apply()
    }
}

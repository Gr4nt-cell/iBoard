package com.gr4nt.iboard.predict.engine

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

/** Where the personal store keeps its rows: SQLite on the phone, memory in unit tests. */
interface PersonalPersistence {
    class Row(val kind: Char, val key: String, val count: Int, val day: Int)
    fun load(sink: (Row) -> Unit)
    fun write(rows: Collection<Row>, deletes: Collection<Pair<Char, String>>)
    fun clear()
}

class MemoryPersistence : PersonalPersistence {
    val rows = HashMap<Pair<Char, String>, PersonalPersistence.Row>()
    override fun load(sink: (PersonalPersistence.Row) -> Unit) = rows.values.forEach(sink)
    override fun write(rows: Collection<PersonalPersistence.Row>, deletes: Collection<Pair<Char, String>>) {
        rows.forEach { this.rows[it.kind to it.key] = it }; deletes.forEach { this.rows.remove(it) }
    }
    override fun clear() = rows.clear()
}

/** SQLite file in the app's private storage (never backed up, never uploaded). */
class SqlitePersistence(context: Context, lang: String) : PersonalPersistence {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "personal_$lang.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE grams(kind TEXT NOT NULL, k TEXT NOT NULL, n INTEGER NOT NULL, day INTEGER NOT NULL, PRIMARY KEY(kind, k))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    override fun load(sink: (PersonalPersistence.Row) -> Unit) {
        try {
            helper.readableDatabase.rawQuery("SELECT kind, k, n, day FROM grams", null).use { c ->
                while (c.moveToNext()) sink(PersonalPersistence.Row(c.getString(0)[0], c.getString(1), c.getInt(2), c.getInt(3)))
            }
        } catch (_: Exception) {}
    }

    override fun write(rows: Collection<PersonalPersistence.Row>, deletes: Collection<Pair<Char, String>>) {
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                for (d in deletes) db.delete("grams", "kind=? AND k=?", arrayOf(d.first.toString(), d.second))
                for (r in rows) db.insertWithOnConflict("grams", null, ContentValues().apply {
                    put("kind", r.kind.toString()); put("k", r.key); put("n", r.count); put("day", r.day)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (_: Exception) {}
    }

    override fun clear() { try { helper.writableDatabase.delete("grams", null, null) } catch (_: Exception) {} }
}

/**
 * The user's own language: words, word pairs and triples they type, which suggestions they accept and
 * which they ignore. Everything lives on the phone. Counts fade slowly (half-weight after ~6 weeks, never
 * below a quarter) so recent habits win; a tapped suggestion counts more than a typed word; a suggestion
 * shown and then typed over counts against that word in that context.
 */
class PersonalStore(
    private val persistence: PersonalPersistence,
    val lang: String,
    legacyFile: File? = null,
    private val today: () -> Int = { (System.currentTimeMillis() / 86_400_000L).toInt() },
) {
    private class E(var n: Int, var day: Int)

    private val unigrams = HashMap<String, E>()
    private val bigrams = HashMap<String, HashMap<String, E>>()
    private val trigrams = HashMap<String, HashMap<String, E>>()
    private val accepts = HashMap<String, E>()   // "prev\tword"
    private val rejects = HashMap<String, E>()   // "prev\tword"
    /** Changes whenever the user's habits change, so cached suggestions are never stale. */
    @Volatile var version = 0
        private set
    private val dirty = HashSet<Pair<Char, String>>()
    private val deleted = HashSet<Pair<Char, String>>()

    constructor(context: Context, lang: String) : this(
        SqlitePersistence(context, lang), lang, File(context.filesDir, "user_$lang.tsv"))

    init {
        var loaded = 0
        persistence.load { r -> loaded++; put(r.kind, r.key, E(r.count, r.day)) }
        if (loaded == 0 && legacyFile != null && legacyFile.exists()) importLegacy(legacyFile)
    }

    private fun put(kind: Char, key: String, e: E) {
        val p = key.split('\t')
        when (kind) {
            'u' -> unigrams[key] = e
            'b' -> if (p.size == 2) bigrams.getOrPut(p[0]) { HashMap() }[p[1]] = e
            't' -> if (p.size == 3) trigrams.getOrPut(p[0] + "\t" + p[1]) { HashMap() }[p[2]] = e
            'a' -> accepts[key] = e
            'r' -> rejects[key] = e
        }
    }

    /** One-time import of the earlier tab-separated file so learned words survive the upgrade. */
    private fun importLegacy(file: File) {
        val day = today()
        try {
            file.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 3 && p[0] == "u" -> { put('u', p[1], E(p[2].toIntOrNull() ?: 1, day)); dirty += 'u' to p[1] }
                    p.size == 4 && p[0] == "b" -> { put('b', p[1] + "\t" + p[2], E(p[3].toIntOrNull() ?: 1, day)); dirty += 'b' to (p[1] + "\t" + p[2]) }
                    p.size == 5 && p[0] == "t" -> { val k = p[1] + "\t" + p[2] + "\t" + p[3]; put('t', k, E(p[4].toIntOrNull() ?: 1, day)); dirty += 't' to k }
                }
            }
            save()
            file.renameTo(File(file.path + ".migrated"))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ reading

    private fun fade(e: E?): Float {
        if (e == null) return 0f
        val age = (today() - e.day).coerceAtLeast(0)
        return e.n * (0.25f + 0.75f * 0.5f.pow(age / 42f))
    }

    @Synchronized fun count(word: String): Int = (unigrams[word] ?: unigrams[word.lowercase()])?.n ?: 0

    /** A word the user has typed at least twice is treated as valid (never auto-corrected). */
    @Synchronized fun knows(word: String): Boolean = count(word) >= 2

    @Synchronized fun bigramCount(previous: String?, word: String): Int =
        if (previous.isNullOrBlank()) 0 else bigrams[keyOf(previous)]?.get(keyOf(word))?.n ?: 0

    @Synchronized fun trigramCount(previousPrevious: String?, previous: String?, word: String): Int =
        if (previousPrevious.isNullOrBlank() || previous.isNullOrBlank()) 0
        else trigrams[keyOf(previousPrevious) + "\t" + keyOf(previous)]?.get(keyOf(word))?.n ?: 0

    /** Words the user tends to type after [previous] (and [previousPrevious]), strongest first, recency weighted. */
    @Synchronized fun nextWordsWeighted(previous: String?, previousPrevious: String?, limit: Int): List<Pair<String, Float>> {
        if (previous.isNullOrBlank()) return emptyList()
        val scores = HashMap<String, Float>()
        bigrams[keyOf(previous)]?.forEach { (w, e) -> scores[w] = fade(e) }
        if (!previousPrevious.isNullOrBlank())
            trigrams[keyOf(previousPrevious) + "\t" + keyOf(previous)]?.forEach { (w, e) -> scores[w] = (scores[w] ?: 0f) + 4f * fade(e) }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    @Synchronized fun nextWords(previous: String?, limit: Int, previousPrevious: String? = null): List<Pair<String, Int>> =
        nextWordsWeighted(previous, previousPrevious, limit).map { it.first to kotlin.math.ceil(it.second).toInt() }

    @Synchronized fun prefixed(prefixLower: String, limit: Int): List<Pair<String, Int>> =
        unigrams.entries.asSequence()
            .filter { it.value.n >= 2 && it.key.lowercase().startsWith(prefixLower) }
            .sortedByDescending { fade(it.value) }.take(limit).map { it.key to it.value.n }.toList()

    /**
     * How strongly this user's habits favour [word] after the given words, as a log-style score
     * (0 = no personal evidence, up to ~+4; negative when they keep ignoring it here).
     */
    @Synchronized fun score(previous: String?, previousPrevious: String?, word: String): Float {
        val w = keyOf(word)
        val u = fade(unigrams[w] ?: unigrams[w.lowercase()])
        val p = previous?.takeIf { it.isNotBlank() }?.let(::keyOf)
        val b = if (p != null) fade(bigrams[p]?.get(w)) else 0f
        val t = if (p != null && !previousPrevious.isNullOrBlank()) fade(trigrams[keyOf(previousPrevious) + "\t" + p]?.get(w)) else 0f
        val a = if (p != null) fade(accepts["$p\t$w"]) else 0f
        val r = if (p != null) fade(rejects["$p\t$w"]) else 0f
        val pos = 0.9f * ln(1f + b + 4f * t) + 0.25f * ln(1f + u) + 0.7f * ln(1f + a)
        return (pos - 0.55f * ln(1f + r) * (1f / (1f + b + a))).coerceIn(-1.5f, 4f)
    }

    // ------------------------------------------------------------------ learning

    private fun bump(map: HashMap<String, E>, key: String, by: Int, kind: Char, fullKey: String) {
        val e = map.getOrPut(key) { E(0, today()) }
        e.n += by; e.day = today(); dirty += kind to fullKey; version++
    }

    @Synchronized fun learn(word: String, previous: String?, previousPrevious: String? = null) {
        if (word.isBlank() || word.length > 40) return
        val w = keyOf(word)
        bump(unigrams, w, 1, 'u', w)
        if (!previous.isNullOrBlank()) {
            val p = keyOf(previous)
            bump(bigrams.getOrPut(p) { HashMap() }, w, 1, 'b', "$p\t$w")
            if (!previousPrevious.isNullOrBlank()) {
                val pp = keyOf(previousPrevious)
                bump(trigrams.getOrPut("$pp\t$p") { HashMap() }, w, 1, 't', "$pp\t$p\t$w")
            }
        }
        if (unigrams.size > 20000) prune()
    }

    /** The user tapped a suggestion: a stronger signal than typing the word. */
    @Synchronized fun accepted(previous: String?, word: String) {
        if (word.isBlank() || previous.isNullOrBlank()) return
        val p = keyOf(previous); val w = keyOf(word)
        bump(accepts, "$p\t$w", 1, 'a', "$p\t$w")
        rejects["$p\t$w"]?.let { it.n = (it.n - 1).coerceAtLeast(0); dirty += 'r' to "$p\t$w" }
    }

    /** These suggestions were visible but the user typed something else: a mild vote against them here. */
    @Synchronized fun ignored(previous: String?, shown: List<String>, typed: String) {
        if (previous.isNullOrBlank() || typed.isBlank()) return
        val p = keyOf(previous); val t = keyOf(typed)
        for (s in shown) {
            val w = keyOf(s)
            if (w.equals(t, true) || w.isEmpty()) continue
            bump(rejects, "$p\t$w", 1, 'r', "$p\t$w")
        }
        if (rejects.size > 8000) rejects.entries.removeAll { it.value.n <= 1 }
    }

    /** Marks a word as known right away (e.g. the user undid an autocorrect). */
    @Synchronized fun trust(word: String) {
        val w = keyOf(word)
        val e = unigrams.getOrPut(w) { E(0, today()) }
        e.n = maxOf(e.n, 2); e.day = today(); dirty += 'u' to w; version++
    }

    @Synchronized fun forget(word: String) {
        val key = keyOf(word); version++
        if (unigrams.remove(key) != null) { deleted += 'u' to key; dirty -= 'u' to key }
        bigrams.remove(key)?.keys?.forEach { deleted += 'b' to "$key\t$it" }
        for ((p, m) in bigrams) if (m.remove(key) != null) deleted += 'b' to "$p\t$key"
        val tri = trigrams.entries.filter { key in it.key.split('\t') }
        tri.forEach { (pair, m) -> m.keys.forEach { deleted += 't' to "$pair\t$it" } }
        trigrams.entries.removeAll { key in it.key.split('\t') }
        for ((pair, m) in trigrams) if (m.remove(key) != null) deleted += 't' to "$pair\t$key"
        accepts.keys.filter { key in it.split('\t') }.forEach { accepts.remove(it); deleted += 'a' to it }
        rejects.keys.filter { key in it.split('\t') }.forEach { rejects.remove(it); deleted += 'r' to it }
    }

    @Synchronized fun clear() {
        unigrams.clear(); bigrams.clear(); trigrams.clear(); accepts.clear(); rejects.clear()
        dirty.clear(); deleted.clear(); persistence.clear(); version++
    }

    @Synchronized fun save() {
        if (dirty.isEmpty() && deleted.isEmpty()) return
        val rows = ArrayList<PersonalPersistence.Row>(dirty.size)
        for ((kind, key) in dirty) {
            val p = key.split('\t')
            val e = when (kind) {
                'u' -> unigrams[key]
                'b' -> bigrams[p[0]]?.get(p.getOrElse(1) { "" })
                't' -> trigrams[p[0] + "\t" + p.getOrElse(1) { "" }]?.get(p.getOrElse(2) { "" })
                'a' -> accepts[key]
                else -> rejects[key]
            } ?: continue
            rows += PersonalPersistence.Row(kind, key, e.n, e.day)
        }
        persistence.write(rows, deleted)
        dirty.clear(); deleted.clear()
    }

    private fun prune() {
        unigrams.entries.removeAll { (it.value.n <= 1).also { gone -> if (gone) deleted += 'u' to it.key } }
        bigrams.values.forEach { m -> m.entries.removeAll { it.value.n <= 1 } }
        trigrams.values.forEach { m -> m.entries.removeAll { it.value.n <= 1 } }
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

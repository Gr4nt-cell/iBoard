package com.gr4nt.iboard.predict.engine

import android.content.Context
import com.gr4nt.iboard.predict.Dictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.ln

/**
 * iBoard's built-in prediction engine: it always knows which words can grammatically and sensibly come
 * next, on every phone. It layers
 *   BundledLm      neural model that reads the whole sentence (the grammar brain)
 *   NgramModel     Kneser-Ney tables, instant and memory-mapped
 *   PersonalStore  the user's own words, habits, accepted and ignored suggestions
 *   GrammarFilter  word-class fit and agreement rules
 *   NanoBooster    optional extra candidates from Gemini Nano; never needed
 * and merges them in [Ranker]. The result is identical in kind with or without Gemini Nano.
 */
class PredictionEngine(
    val lang: String,
    val vocab: Vocab,
    private val lm: BundledLm?,
    private val ngram: NgramModel?,
    val personal: PersonalStore,
    private val grammar: GrammarFilter,
    val nano: NanoBooster? = null,
) {
    /**
     * [head] is the text before the word being typed; [atStart] says it begins at the start of the field.
     * [typed] is the partial word (empty for "what comes next"). [extra] adds candidates from outside the
     * model (dictionary completions, typo corrections) with a bonus that says how good a fit they are.
     */
    class Request(
        val head: String, val atStart: Boolean, val typed: String,
        val extra: Map<String, Float> = emptyMap(), val useLm: Boolean = true,
        val nano: List<String> = emptyList(), val limit: Int = 8,
    )

    class Ranked(val text: String, val score: Float)

    private class Cand(val text: String, val id: Int) { val f = Ranker.Features(); var fromExtra = false; var lmSet = false }
    private data class Key(val ctx: Int, val typed: String, val useLm: Boolean, val extra: Int, val nano: Int, val version: Int)

    /** Neural scores for one context: [ids] ascending, their raw [logits], and the log-normaliser over them. */
    private inner class LmScores(val state: BundledLm.State, val ids: IntArray, val logits: FloatArray, val lse: Float) {
        fun logit(id: Int): Float {
            val at = ids.binarySearch(id)
            return if (at >= 0) logits[at] else lm!!.logits(state, intArrayOf(id))[0]
        }
    }

    private val results = object : LinkedHashMap<Key, List<Ranked>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Key, List<Ranked>>?) = size > 48
    }
    private val lmCache = object : LinkedHashMap<Pair<Int, String>, LmScores>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Pair<Int, String>, LmScores>?) = size > 8
    }

    val hasLm get() = lm != null

    /** Ranking weights; the shipped values come from the benchmark ([Ranker.FULL] and [Ranker.FAST]). Tests may override them. */
    @Volatile var fullWeights: Ranker.Weights = Ranker.FULL
    @Volatile var fastWeights: Ranker.Weights = Ranker.FAST

    /** Loads the models into memory and runs one prediction so the first keystroke is already warm. */
    fun warmUp() {
        runCatching { rank(Request("how are you", true, "", useLm = true)) }
    }

    fun rank(req: Request): List<Ranked> {
        val ctx = Tokenizer.parse(req.head, vocab, req.atStart)
        val typed = req.typed
        val key = Key(ctx.ids.contentHashCode() * 31 + ctx.words.size, typed.lowercase(), req.useLm && lm != null,
            req.extra.hashCode() * 31 + fullWeights.hashCode() + fastWeights.hashCode(), req.nano.hashCode(), personal.version)
        synchronized(results) { results[key] }?.let { return it.take(req.limit) }

        val typedNorm = Dictionary.normalize(typed)
        val prefix = typedNorm.isNotEmpty()
        val prev = ctx.previous(1); val prev2 = ctx.previous(2)
        val pool = LinkedHashMap<String, Cand>()
        fun compatible(word: String) = !prefix || Dictionary.normalize(word).startsWith(typedNorm)
        fun cand(word: String, free: Boolean = false): Cand? {
            if (word.isEmpty()) return null
            val k = word.lowercase()
            pool[k]?.let { return it }
            if (!free && !compatible(word)) return null
            val id = vocab.id(k)
            val shown = if (id >= Tokenizer.FIRST_WORD) vocab.display[id] else word
            return Cand(shown, if (id >= Tokenizer.FIRST_WORD) id else -1).also { pool[k] = it }
        }

        // 1. n-gram tables
        val ng = ngram?.predict(ctx.ids) ?: emptyMap<Int, Float>()
        val ngSorted = ng.entries.sortedByDescending { it.value }
        for ((id, _) in ngSorted) { if (id < vocab.size) cand(vocab.lower[id]); if (pool.size >= 30) break }
        // 2. the user's own habits
        for ((w, _) in personal.nextWordsWeighted(prev, prev2, 6)) cand(w)
        if (prefix) for ((w, _) in personal.prefixed(typed.lowercase(), 5)) cand(w)
        // 3. neural model
        val lmScores = if (req.useLm && lm != null) lmScores(ctx, typedNorm) else null
        var lmTop = 0
        if (lmScores != null) {
            val order = lmScores.ids.indices.sortedByDescending { lmScores.logits[it] }
            for (i in order.take(24)) { cand(vocab.lower[lmScores.ids[i]]); lmTop++ }
        }
        // 4. callers' candidates (dictionary completions, typo corrections) and Gemini Nano's
        for ((w, _) in req.extra) cand(w, free = true)?.fromExtra = true
        req.nano.forEach { cand(it, free = false) }
        // 5. last resort: the most common words
        if (pool.size < 8) for (id in Tokenizer.FIRST_WORD until minOf(vocab.size, Tokenizer.FIRST_WORD + 40)) { cand(vocab.lower[id]); if (pool.size >= 12) break }

        val gctx = grammar.context(ctx, vocab)
        val nanoRank = HashMap<String, Int>().also { m -> req.nano.forEachIndexed { i, w -> m.putIfAbsent(w.lowercase(), i) } }
        val weights = if (lmScores != null) fullWeights else fastWeights
        var lmFloor = 0f
        var lseAll: Float? = null
        if (lmScores != null) {
            val sorted = pool.values.mapNotNull { c -> if (c.id >= 0) lmScores.logit(c.id) else null }.sortedDescending()
            lmFloor = (sorted.getOrNull(11) ?: sorted.lastOrNull() ?: lmScores.lse) - lmScores.lse - 0.5f
        }
        for ((k, c) in pool) {
            val f = c.f
            if (lmScores != null) {
                f.lm = if (c.id >= 0) {
                    // Words that do not continue what was typed (typo fixes) are scored against the unconditional next-word distribution.
                    val logit = lmScores.logit(c.id)
                    if (prefix && !compatible(c.text)) {
                        val all = lseAll ?: lmAllLse(ctx).also { lseAll = it }
                        logit - all
                    } else logit - lmScores.lse
                } else lmFloor
            }
            f.ngram = ln((c.id.takeIf { it >= 0 }?.let { ng[it] } ?: 0f) + 0.003f)
            f.personal = personal.score(prev, prev2, c.text)
            f.grammar = grammar.adjust(gctx, c.id, c.text)
            f.nano = nanoRank[k]?.let { Ranker.nanoBonus(it) } ?: 0f
            f.prior = if (c.id >= 0) vocab.logUnigram(c.id) else -12f
            f.bonus = req.extra[c.text] ?: req.extra[k] ?: req.extra.entries.firstOrNull { it.key.equals(c.text, true) }?.value ?: 0f
        }
        val ranked = pool.values.map { Ranked(it.text, Ranker.score(it.f, weights)) }.sortedByDescending { it.score }
        val out = ranked.take(maxOf(req.limit, 12))
        synchronized(results) { results[key] = out }
        return out.take(req.limit)
    }

    /** Neural scores for the words that may come next: those starting with [typedNorm], or the commonest words. */
    private fun lmScores(ctx: Tokenizer.Context, typedNorm: String): LmScores {
        val ck = ctx.ids.contentHashCode() to typedNorm
        synchronized(lmCache) { lmCache[ck] }?.let { return it }
        val state = lm!!.stateFor(ctx.ids)
        var ids = if (typedNorm.isEmpty()) IntArray(minOf(vocab.size, Tokenizer.FIRST_WORD + NEXT_WORDS) - Tokenizer.FIRST_WORD) { Tokenizer.FIRST_WORD + it }
        else vocab.withPrefix(typedNorm)
        if (ids.size > MAX_PREFIX_IDS) ids = ids.sortedBy { it }.take(MAX_PREFIX_IDS).toIntArray()   // ids are frequency ranks
        else ids.sort()
        val logits = lm.logits(state, ids)
        val s = LmScores(state, ids, logits, logSumExp(logits))
        synchronized(lmCache) { lmCache[ck] = s }
        return s
    }

    private fun lmAllLse(ctx: Tokenizer.Context): Float = lmScores(ctx, "").lse

    private fun logSumExp(l: FloatArray): Float {
        if (l.isEmpty()) return 0f
        var mx = Float.NEGATIVE_INFINITY
        for (v in l) if (v > mx) mx = v
        var sum = 0.0
        for (v in l) sum += exp((v - mx).toDouble())
        return mx + ln(sum).toFloat()
    }

    /**
     * The full answer: built-in layers plus the neural model, and Gemini Nano's guesses when it is there.
     * Safe to cancel; Nano is capped at [NanoBooster.BUDGET_MS] and its absence changes nothing.
     */
    suspend fun rankLive(head: String, atStart: Boolean, typed: String, extra: Map<String, Float>, limit: Int = 8): List<Ranked> =
        coroutineScope {
            val booster = nano
            val nanoJob = booster?.let { async { it.candidates(head, typed, lang) } }
            val base = withContext(Dispatchers.Default) { rank(Request(head, atStart, typed, extra, useLm = true, limit = limit)) }
            val words = nanoJob?.await().orEmpty()
            if (words.isEmpty()) base
            else withContext(Dispatchers.Default) { rank(Request(head, atStart, typed, extra, useLm = true, nano = words, limit = limit)) }
        }

    companion object {
        /** How many of the most frequent words compete for "what comes next". */
        const val NEXT_WORDS = 16000
        const val MAX_PREFIX_IDS = 6000

        /** Loads the language pack from the APK, or null when this language has no neural pack (other languages keep their classic models). */
        fun load(context: Context, lang: String, personal: PersonalStore, nano: NanoBooster?): PredictionEngine? {
            val am = context.assets
            val have = am.list("prediction")?.toSet().orEmpty()
            if ("$lang.vocab.tsv" !in have) return null
            val vocab = am.open("prediction/$lang.vocab.tsv").bufferedReader().useLines { Vocab.parse(it) }
            val lm = if ("$lang.lm" in have) runCatching { BundledLm(am.open("prediction/$lang.lm").readBytes()) }.getOrNull() else null
            val ngram = if ("$lang.ng" in have) runCatching { NgramModel(mapAsset(context, "prediction/$lang.ng")) }.getOrNull() else null
            val pos = if ("$lang.pos" in have) runCatching { am.open("prediction/$lang.pos").readBytes() }.getOrNull() else null
            val grammar = runCatching { GrammarFilter(pos, vocab.size) }.getOrElse { GrammarFilter(null, vocab.size) }
            return PredictionEngine(lang, vocab, lm, ngram, personal, grammar, nano)
        }

        /** Memory-maps an uncompressed asset straight out of the APK; falls back to reading it. */
        private fun mapAsset(context: Context, path: String): ByteBuffer = try {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
            }
        } catch (_: Exception) {
            ByteBuffer.wrap(context.assets.open(path).readBytes())
        }

        /** Loads a language pack from a folder (unit tests and the benchmark). */
        fun fromFiles(dir: File, lang: String, personal: PersonalStore, nano: NanoBooster? = null): PredictionEngine {
            val vocab = File(dir, "$lang.vocab.tsv").bufferedReader().useLines { Vocab.parse(it) }
            val lm = File(dir, "$lang.lm").takeIf { it.exists() }?.let { BundledLm(it.readBytes()) }
            val ngram = File(dir, "$lang.ng").takeIf { it.exists() }?.let { f ->
                FileInputStream(f).channel.use { NgramModel(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())) }
            }
            val grammar = GrammarFilter(File(dir, "$lang.pos").takeIf { it.exists() }?.readBytes(), vocab.size)
            return PredictionEngine(lang, vocab, lm, ngram, personal, grammar, nano)
        }
    }
}

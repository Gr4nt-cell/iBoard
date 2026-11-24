package com.gr4nt.iboard.predict.engine

import com.gr4nt.iboard.predict.EnglishGrammar
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * Grammar sense-check for candidate words. Two layers:
 *  1. A word-class table (tools/prediction/build_pos.py): every vocabulary word has up to three likely classes
 *     (noun, adjective, -ing verb, modal…) and the file holds P(class | previous two classes). A candidate
 *     whose class the sentence cannot take next ("the" → "quickly") is pushed down; one that fits is nudged up.
 *  2. Explicit agreement rules (I am / he is, modal + base verb, a / an) from [EnglishGrammar].
 * It only reorders suggestions; it never rewrites what the user typed.
 */
class GrammarFilter(pos: ByteArray?, private val vocabSize: Int) {
    /** What the sentence so far allows next. */
    class Context(val expected: FloatArray?, val previous: String?, val before: String?)

    private val n: Int
    private val classes: ByteArray   // vocabSize * 3 class ids
    private val probs: ByteArray     // vocabSize * 3 probabilities (0..255)
    private val transitions: ShortArray

    init {
        if (pos != null && pos.size > 16) {
            val b = ByteBuffer.wrap(pos).order(ByteOrder.LITTLE_ENDIAN)
            require(b.get().toInt().toChar() == 'I' && b.get().toInt().toChar() == 'B' && b.get().toInt().toChar() == 'P' && b.get().toInt().toChar() == 'S')
            require(b.int == 1)
            val v = b.int; n = b.int
            require(v == vocabSize) { "grammar table does not match the vocabulary" }
            classes = ByteArray(v * 3); probs = ByteArray(v * 3)
            for (i in 0 until v * 3) { classes[i] = b.get(); probs[i] = b.get() }
            transitions = ShortArray(n * n * n).also { b.asShortBuffer().get(it) }
        } else {
            n = 0; classes = ByteArray(0); probs = ByteArray(0); transitions = ShortArray(0)
        }
    }

    val hasTable get() = n > 0

    private fun classDistribution(id: Int, surface: String?): List<Pair<Int, Float>> {
        if (id >= Tokenizer.FIRST_WORD || id == Tokenizer.NUM || id == Tokenizer.COMMA || id == Tokenizer.BOS) {
            val out = ArrayList<Pair<Int, Float>>(3)
            for (k in 0 until 3) {
                val p = probs[id * 3 + k].toInt() and 0xFF
                if (p > 0) out += (classes[id * 3 + k].toInt() and 0xFF) to p / 255f
            }
            if (out.isNotEmpty()) return out
        }
        // Words outside the vocabulary: guess from the spelling.
        val w = surface?.lowercase().orEmpty()
        return when {
            w.endsWith("ing") && w.length > 4 -> listOf(C_VBG to 0.8f, C_NOUN to 0.2f)
            w.endsWith("ly") && w.length > 4 -> listOf(C_ADV to 0.9f)
            w.endsWith("ed") && w.length > 3 -> listOf(C_VBD to 0.5f, C_VBN to 0.4f, C_ADJ to 0.1f)
            surface != null && surface.firstOrNull()?.isUpperCase() == true -> listOf(C_NOUN to 0.9f)
            else -> listOf(C_NOUN to 0.6f, C_ADJ to 0.2f, C_VB to 0.2f)
        }
    }

    private fun topClass(id: Int, surface: String?) = classDistribution(id, surface).maxByOrNull { it.second }?.first ?: C_OTHER

    /** Reads the sentence so far (words of the current sentence, original case). */
    fun context(ctx: Tokenizer.Context, vocab: Vocab): Context {
        val words = ctx.words
        var expected: FloatArray? = null
        if (hasTable) {
            fun cls(i: Int): Int = if (i < 0) C_BOS else topClass(vocab.id(words[i].lowercase()), words[i])
            val c1 = if (ctx.lastWasComma) C_COMMA else if (words.isEmpty()) C_BOS else cls(words.size - 1)
            val c2 = if (ctx.lastWasComma) (if (words.isEmpty()) C_BOS else cls(words.size - 1)) else if (words.size <= 1) C_BOS else cls(words.size - 2)
            expected = FloatArray(n) { transitions[(c2 * n + c1) * n + it].toInt().and(0xFFFF) / 65535f }
        }
        return Context(expected, ctx.previous(1), ctx.previous(2))
    }

    /**
     * Additive log-score for [word] (vocabulary id [id], or -1 for words outside the vocabulary):
     * 0 = neutral, small bonus when it fits, strong penalty when an agreement rule is broken.
     */
    fun adjust(c: Context, id: Int, word: String): Float {
        var score = 0f
        val rule = EnglishGrammar.adjustment(c.previous, c.before, word)
        score += rule * 0.35f
        val exp = c.expected
        if (exp != null && id >= 0) {
            var fit = 0f
            for ((cls, p) in classDistribution(id, word)) fit += p * exp[cls]
            score += (0.5f * ln((fit + 0.02f) / 0.15f)).coerceIn(-2.5f, 0.6f)
        }
        return score
    }

    companion object {
        const val C_OTHER = 0; const val C_NOUN = 1; const val C_ADJ = 2; const val C_ADV = 3
        const val C_VB = 10; const val C_VBD = 12; const val C_VBN = 13; const val C_VBG = 14
        const val C_BOS = 19; const val C_COMMA = 20
    }
}

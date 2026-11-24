package com.gr4nt.iboard.predict.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.tanh

/**
 * The bundled neural language model: a tied-embedding GRU with int8 weights (see tools/prediction/train_lm.py),
 * run in plain Kotlin so it behaves identically on every phone, with or without Gemini Nano.
 *
 * It reads the sentence word by word and keeps its GRU state, so typing one more word costs one
 * GRU step; the state of the last [CACHE] contexts is remembered for backspace and re-reads.
 */
class BundledLm(data: ByteArray) {
    class State internal constructor(val tokens: IntArray, internal val h: Array<FloatArray>, val z: FloatArray)

    private class Layer(val inSize: Int, val wih: ByteArray, val sih: FloatArray, val whh: ByteArray, val shh: FloatArray, val bih: FloatArray, val bhh: FloatArray)

    val vocabSize: Int
    val embSize: Int
    val hiddenSize: Int
    private val layers: Array<Layer>
    private val emb: ByteArray
    private val embScale: FloatArray
    private val outBias: FloatArray
    private val projW: ByteArray
    private val projS: FloatArray
    private val projB: FloatArray

    init {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get().toInt().toChar() == 'I' && b.get().toInt().toChar() == 'B' && b.get().toInt().toChar() == 'L' && b.get().toInt().toChar() == 'M') { "not an iBoard LM file" }
        require(b.int == 1) { "unsupported LM version" }
        vocabSize = b.int; embSize = b.int; hiddenSize = b.int
        val nLayers = b.int
        fun bytes(n: Int) = ByteArray(n).also { b.get(it) }
        fun floats(n: Int) = FloatArray(n).also { b.asFloatBuffer().get(it); b.position(b.position() + 4 * n) }
        emb = bytes(vocabSize * embSize); embScale = floats(vocabSize); outBias = floats(vocabSize)
        layers = Array(nLayers) { l ->
            val inSize = if (l == 0) embSize else hiddenSize
            val wih = bytes(3 * hiddenSize * inSize); val sih = floats(3 * hiddenSize)
            val whh = bytes(3 * hiddenSize * hiddenSize); val shh = floats(3 * hiddenSize)
            Layer(inSize, wih, sih, whh, shh, floats(3 * hiddenSize), floats(3 * hiddenSize))
        }
        projW = bytes(embSize * hiddenSize); projS = floats(embSize); projB = floats(embSize)
    }

    // ---------------------------------------------------------------- state handling

    private val cache = object : LinkedHashMap<Long, State>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, State>?) = size > CACHE
    }
    private var session: State? = null

    private fun key(tokens: IntArray, n: Int = tokens.size): Long {
        var h = 1125899906842597L
        for (i in 0 until n) h = 31 * h + tokens[i]
        return h xor (n.toLong() shl 48)
    }

    /** The model state after reading [tokens]; reuses earlier work whenever the text only grew or shrank. */
    @Synchronized fun stateFor(tokens: IntArray): State {
        cache[key(tokens)]?.let { if (it.tokens.contentEquals(tokens)) return it }
        // Typing: the new text is the old text plus a few words (the window may have slid at the front).
        session?.let { old ->
            val max = minOf(8, tokens.size)
            for (k in 0..max) {
                val keep = tokens.size - k
                if (keep <= 0 || keep > old.tokens.size) continue
                val off = old.tokens.size - keep
                var same = true
                for (i in 0 until keep) if (old.tokens[off + i] != tokens[i]) { same = false; break }
                if (same && (keep >= 3 || keep == tokens.size)) return advance(old, tokens, k)
            }
        }
        // Backspace / new field: longest cached prefix, otherwise start from a blank state.
        for (n in tokens.size - 1 downTo maxOf(1, tokens.size - 24)) {
            val c = cache[key(tokens, n)]
            if (c != null && c.tokens.size == n && (0 until n).all { c.tokens[it] == tokens[it] }) return advance(c, tokens, tokens.size - n)
        }
        val blank = State(IntArray(0), Array(layers.size) { FloatArray(hiddenSize) }, FloatArray(embSize))
        return advance(blank, tokens, tokens.size)
    }

    /** Runs [count] tokens (the last ones of [tokens]) on top of [base]. */
    private fun advance(base: State, tokens: IntArray, count: Int): State {
        var h = base.h
        var z = base.z
        for (i in tokens.size - count until tokens.size) {
            val next = Array(layers.size) { FloatArray(hiddenSize) }
            step(tokens[i], h, next)
            h = next
            z = projectOut(h[layers.size - 1])
            cache[key(tokens, i + 1)] = State(tokens.copyOf(i + 1), h, z)
        }
        val s = State(tokens.copyOf(), h, z)
        session = s
        cache[key(tokens)] = s
        return s
    }

    // ---------------------------------------------------------------- inference

    private val gi = FloatArray(3 * 1024)
    private val gh = FloatArray(3 * 1024)

    private fun step(token: Int, hPrev: Array<FloatArray>, hOut: Array<FloatArray>) {
        val H = hiddenSize
        var x = FloatArray(embSize)
        val base = token * embSize; val sc = embScale[token]
        for (j in 0 until embSize) x[j] = emb[base + j] * sc
        for (l in layers.indices) {
            val ly = layers[l]
            matVec(ly.wih, ly.sih, ly.bih, x, 3 * H, ly.inSize, gi)
            matVec(ly.whh, ly.shh, ly.bhh, hPrev[l], 3 * H, H, gh)
            val h0 = hPrev[l]; val out = hOut[l]
            for (j in 0 until H) {
                val r = sigmoid(gi[j] + gh[j])
                val u = sigmoid(gi[H + j] + gh[H + j])
                val n = tanh(gi[2 * H + j] + r * gh[2 * H + j])
                out[j] = (1f - u) * n + u * h0[j]
            }
            x = out
        }
    }

    private fun matVec(w: ByteArray, scale: FloatArray, bias: FloatArray, x: FloatArray, rows: Int, cols: Int, out: FloatArray) {
        var p = 0
        for (r in 0 until rows) {
            var s0 = 0f; var s1 = 0f
            var j = 0
            while (j + 1 < cols) { s0 += w[p + j] * x[j]; s1 += w[p + j + 1] * x[j + 1]; j += 2 }
            if (j < cols) s0 += w[p + j] * x[j]
            out[r] = (s0 + s1) * scale[r] + bias[r]
            p += cols
        }
    }

    private fun projectOut(h: FloatArray): FloatArray {
        val z = FloatArray(embSize)
        var p = 0
        for (r in 0 until embSize) {
            var s = 0f
            for (j in 0 until hiddenSize) s += projW[p + j] * h[j]
            z[r] = s * projS[r] + projB[r]
            p += hiddenSize
        }
        return z
    }

    /** Raw model scores (logits) of the words [ids] after [state]. */
    fun logits(state: State, ids: IntArray, out: FloatArray = FloatArray(ids.size)): FloatArray {
        val z = state.z; val E = embSize
        for (n in ids.indices) {
            val v = ids[n]; val p = v * E
            var s0 = 0f; var s1 = 0f
            var j = 0
            while (j + 1 < E) { s0 += emb[p + j] * z[j]; s1 += emb[p + j + 1] * z[j + 1]; j += 2 }
            out[n] = (s0 + s1) * embScale[v] + outBias[v]
        }
        return out
    }

    /** Log-probabilities of [ids], normalised over just those words (the right thing for a typed prefix). */
    fun logProbs(state: State, ids: IntArray): FloatArray {
        val l = logits(state, ids)
        var mx = Float.NEGATIVE_INFINITY
        for (v in l) if (v > mx) mx = v
        var sum = 0.0
        for (v in l) sum += exp((v - mx).toDouble())
        val lse = mx + kotlin.math.ln(sum).toFloat()
        for (i in l.indices) l[i] -= lse
        return l
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    companion object {
        const val CACHE = 24
    }
}

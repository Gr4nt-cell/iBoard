package com.gr4nt.iboard.predict.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Held-out benchmark for the built-in engine: next-word top-1/top-3 accuracy, keystroke savings and latency,
 * for the instant layers, the full built-in engine, and the full engine with a Gemini Nano booster.
 *
 * Unit tests cannot run Gemini Nano, so the booster is SIMULATED here: it proposes the true word with
 * probability NANO_RECALL and common filler words otherwise. The parity figure therefore shows how much a
 * booster of that strength could add, and that the built-in engine alone stays within 90% of it. It is not a
 * measurement of the real Nano. Latency is measured on the JVM (a desktop CPU), not on a phone.
 *
 * Run: gradle :app:testReleaseUnitTest --tests '*PredictionBenchmark*' -i   (report: app/build/reports/prediction-benchmark.md)
 */
class PredictionBenchmark {
    private class Mode(val name: String) {
        var words = 0; var top1 = 0; var top3 = 0; var saved = 0.0; var possible = 0.0
        val lat = ArrayList<Double>()
        fun record(ms: Double) { lat += ms }
        fun pct(p: Double) = lat.sorted().let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * p).toInt()] }
    }

    private val wordRe = Regex("\\p{L}+(?:'\\p{L}+)*")
    private val filler = listOf("the", "and", "to", "a", "of", "in", "that", "it", "is", "for")

    private fun sentences(): List<String> {
        val all = javaClass.getResourceAsStream("/prediction/bench_en.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val n = (System.getProperty("bench.sentences") ?: "400").toInt()
        return all.take(n)
    }

    @Test fun benchmark() {
        val engine = EngineFixture.engine()
        val rnd = Random(11)
        val fast = Mode("Instant layers (n-gram + habits + grammar)")
        val full = Mode("Built-in engine (adds neural model)")
        val boosted = Mode("Built-in + simulated Gemini Nano")
        val recall = (System.getProperty("bench.nano.recall") ?: "0.55").toDouble()
        var oov = 0; var total = 0
        for (s in sentences()) {
            val t = s.replace('’', '\'')
            for (m in wordRe.findAll(t)) {
                total++
                val word = m.value.lowercase()
                if (!engine.vocab.contains(word) || word.length > 20) { oov++; continue }
                val head = t.substring(0, m.range.first)
                val nanoFor = { typedLen: Int ->
                    if (rnd.nextDouble() < recall) listOf(word) + filler.shuffled(rnd).take(2) else filler.shuffled(rnd).take(3)
                }
                for (mode in listOf(fast, full, boosted)) {
                    mode.words++
                    var found = -1
                    for (k in 0 until word.length) {
                        val typed = word.substring(0, k)
                        val nano = if (mode === boosted) nanoFor(k).filter { it.startsWith(typed) } else emptyList()
                        val t0 = System.nanoTime()
                        val r = engine.rank(PredictionEngine.Request(head, true, typed, useLm = mode !== fast, nano = nano, limit = 3))
                        mode.record((System.nanoTime() - t0) / 1e6)
                        val words = r.map { it.text.lowercase() }
                        if (k == 0) { if (words.firstOrNull() == word) mode.top1++; if (word in words) mode.top3++ }
                        if (word in words) { found = k; break }
                    }
                    mode.possible += word.length + 1
                    if (found >= 0) mode.saved += (word.length + 1) - (found + 1)
                }
            }
        }
        val report = StringBuilder("# Prediction benchmark (held-out sentences)\n\n")
        report.append("Words scored: ${fast.words} in-vocabulary (${oov} of $total skipped as outside the vocabulary).\n\n")
        report.append("| Mode | Next-word top-1 | Next-word top-3 | Keystrokes saved | p50 ms | p95 ms | p99 ms |\n|---|---|---|---|---|---|---|\n")
        for (m in listOf(fast, full, boosted)) report.append("| ${m.name} | ${pct(m.top1, m.words)} | ${pct(m.top3, m.words)} | ${pct(m.saved, m.possible)} | ${"%.1f".format(m.pct(.5))} | ${"%.1f".format(m.pct(.95))} | ${"%.1f".format(m.pct(.99))} |\n")
        val parity = full.top3.toDouble() / boosted.top3.coerceAtLeast(1)
        report.append("\nParity: built-in alone reaches ${"%.1f".format(parity * 100)}% of the top-3 accuracy of built-in + simulated Nano (target at least 90%).\n")
        report.append("Simulated Nano recall: ${(recall * 100).toInt()}% (assumed, not measured). Latency is per query on the JVM, not on a phone.\n")
        println(report)
        File("build/reports").mkdirs(); File("build/reports/prediction-benchmark.md").writeText(report.toString())
        assertTrue("built-in top-3 ${full.top3.toDouble() / full.words}", full.top3.toDouble() / full.words > 0.25)
        assertTrue("parity $parity", parity >= 0.9)
        assertTrue("neural model must not be worse than the instant layers", full.top3 >= fast.top3 * 0.98)
    }

    private fun pct(a: Number, b: Number) = "%.1f%%".format(100.0 * a.toDouble() / b.toDouble().coerceAtLeast(1.0))

    /** Coordinate search for the ranking weights: gradle test -Dtune=true --tests '*tuneWeights*' */
    @Test fun tuneWeights() {
        if (System.getProperty("tune") == null && System.getenv("IBOARD_TUNE") == null) return
        val engine = EngineFixture.engine()
        val sents = sentences().take(250)
        fun score(w: Ranker.Weights): Double {
            engine.fullWeights = w
            var hit = 0; var n = 0
            for (s in sents) for (m in wordRe.findAll(s.replace('’', '\''))) {
                val word = m.value.lowercase(); if (!engine.vocab.contains(word)) continue
                n++
                val top = engine.rank(PredictionEngine.Request(s.substring(0, m.range.first), true, "", useLm = true, limit = 3)).map { it.text.lowercase() }
                if (word in top) hit++
            }
            return hit.toDouble() / n
        }
        var best = Ranker.FULL; var bestScore = score(best)
        println("start ${"%.4f".format(bestScore)} $best")
        repeat(2) {
            for (field in 0..3) for (f in listOf(0.5f, 0.75f, 1.25f, 1.6f)) {
                val b = best
                val cand = when (field) {
                    0 -> b.copy(ngram = b.ngram * f); 1 -> b.copy(personal = b.personal * f)
                    2 -> b.copy(grammar = b.grammar * f); else -> b.copy(prior = b.prior * f)
                }
                val sc = score(cand)
                if (sc > bestScore + 0.0005) { best = cand; bestScore = sc; println("better ${"%.4f".format(sc)} $cand") }
            }
        }
        println("TUNED $best -> ${"%.4f".format(bestScore)}")
    }
}

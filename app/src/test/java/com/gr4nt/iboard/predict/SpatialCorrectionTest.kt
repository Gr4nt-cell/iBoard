package com.gr4nt.iboard.predict

import com.gr4nt.iboard.Layouts
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.log10
import kotlin.random.Random

/**
 * Simulates sloppy thumb typing on the real English dictionary: every tap lands near the
 * intended key with Gaussian noise, and the keyboard registers whichever key is closest.
 * Measures how often the intended word comes first, with and without touch positions.
 */
class SpatialCorrectionTest {
    @get:Rule val folder = TemporaryFolder()

    private fun realDictionary(): Dictionary {
        val file = listOf("src/main/assets/dict/en.txt", "app/src/main/assets/dict/en.txt").map(::File).first { it.exists() }
        val words = ArrayList<String>(); val scores = ArrayList<Float>()
        file.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) { words += line.substring(0, tab); scores += log10((line.substring(tab + 1).toLongOrNull() ?: 1L).toDouble().coerceAtLeast(1.0)).toFloat() }
        }
        val lookup = HashMap<String, Float>()
        words.indices.forEach { lookup.putIfAbsent(words[it], scores[it]) }
        val order = words.indices.map { Dictionary.normalize(words[it]) to it }.sortedBy { it.first }
        return Dictionary("en", Array(order.size) { order[it].first }, Array(order.size) { words[order[it].second] },
            FloatArray(order.size) { scores[order[it].second] }, lookup, words.take(60), emptyMap(), ContextModel(emptyMap(), emptyMap()))
    }

    private fun gauss(r: Random): Float {
        var u = 0.0; var v = 0.0
        while (u == 0.0) u = r.nextDouble()
        while (v == 0.0) v = r.nextDouble()
        return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)).toFloat()
    }

    @Test fun touchPositionsImproveCorrection() {
        val rows = Layouts.letterRows.getValue("en")
        val keyW = 1f; val keyH = 1.4f
        val grid = SpatialModel.grid(rows, keyW, keyH)
        val letters = rows.flatMap { r -> r.split(" ").filter { it.length == 1 }.map { it[0] } }
        val dict = realDictionary()
        val words = File(listOf("src/main/assets/dict/en.txt", "app/src/main/assets/dict/en.txt").first { File(it).exists() })
            .readLines().map { it.substringBefore('\t') }.drop(150).take(2500)
            .filter { w -> w.length in 4..9 && w.all { it in 'a'..'z' } }.take(500)
        val rnd = Random(7)
        val plain = Suggester(dict, UserModel(folder.newFolder(), "en"), Suggester.neighborsFor(rows))
        val touchy = Suggester(dict, UserModel(folder.newFolder(), "en"), Suggester.neighborsFor(rows)).apply { spatial = grid }
        var trials = 0; var hitPlain = 0; var hitTouch = 0
        for (w in words) {
            val touches = ArrayList<SpatialModel.Point>()
            val typed = StringBuilder()
            for (c in w) {
                val ctr = letterCenter(rows, c, keyW, keyH)
                val p = SpatialModel.Point(ctr.x + gauss(rnd) * 0.42f * keyW, ctr.y + gauss(rnd) * 0.38f * keyH)
                touches += p
                typed.append(letters.minByOrNull { grid.distance2(p, it) ?: 99f }!!)
            }
            val t = typed.toString()
            if (t == w) continue
            trials++
            fun top(r: Suggester.Result) = r.autoCorrect ?: r.items.firstOrNull { it.kind == Suggestion.Kind.WORD }?.text
            if (top(plain.forWord(t, null, true)) == w) hitPlain++
            if (top(touchy.forWord(t, null, true, touches = touches)) == w) hitTouch++
        }
        val a = hitPlain * 100f / trials; val b = hitTouch * 100f / trials
        println("Mistyped words: $trials · first suggestion right: neighbour keys ${"%.1f".format(a)}% → touch positions ${"%.1f".format(b)}%")
        assertTrue("touch model should beat neighbour keys ($a% vs $b%)", b > a + 3f)
    }

    private fun letterCenter(rows: List<String>, c: Char, w: Float, h: Float): SpatialModel.Point {
        rows.forEachIndexed { r, row ->
            val i = row.split(" ").filter { it.length == 1 }.map { it[0] }.indexOf(c)
            if (i >= 0) return SpatialModel.Point((i + r * 0.5f + 0.5f) * w, (r + 0.5f) * h)
        }
        error("no key $c")
    }
}

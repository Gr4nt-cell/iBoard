package com.gr4nt.iboard.predict

import com.gr4nt.iboard.predict.engine.PersonalStore
import com.gr4nt.iboard.predict.engine.PredictionEngine

/**
 * On-device prediction. Typo correction and autocorrect (keyboard-aware, touch-aware) live here; the ranking of
 * what to show comes from [engine], the built-in neural + n-gram + personal + grammar engine, whenever the language
 * has one. Languages without an engine keep the classic dictionary-and-pairs ranking.
 */
class Suggester(
    val dictionary: Dictionary,
    val user: PersonalStore,
    val neighbors: Map<Char, String>,
    val engine: PredictionEngine? = null,
) {
    class Result(val items: List<Suggestion>, val autoCorrect: String?)

    /**
     * Everything decided about one keystroke before the final ranking: the candidates to rank, whether the
     * typed word is valid, and the autocorrect. [finish] turns a ranking into the suggestion strip.
     */
    class Plan internal constructor(
        val typed: String,
        internal val head: String,
        internal val atStart: Boolean,
        internal val sentenceStart: Boolean,
        internal val extra: Map<String, Float>,
        internal val legacy: List<String>,
        internal val auto: String?,
        internal val valid: Boolean,
        internal val fixed: Result?,
    )

    /** Key positions of the current layout, set by the keyboard; enables touch-aware correction. */
    @Volatile var spatial: SpatialModel? = null

    /** Instant answer (no neural model yet): see [plan] and [finish]. */
    fun forWord(
        typed: String,
        previous: String?,
        allowAutoCorrect: Boolean,
        sentenceStart: Boolean = false,
        previousPrevious: String? = null,
        touches: List<SpatialModel.Point?>? = null,
        head: String = "",
        atStart: Boolean = true,
    ): Result {
        val plan = plan(typed, previous, allowAutoCorrect, sentenceStart, previousPrevious, touches, head, atStart)
        return finish(plan, quickRank(plan))
    }

    /** The first, instant ranking: n-gram, personal habits and grammar (no neural model). */
    fun quickRank(plan: Plan): List<String> =
        if (plan.fixed != null) emptyList()
        else if (engine == null) plan.legacy
        else engine.rank(PredictionEngine.Request(plan.head, plan.atStart, plan.typed, plan.extra, useLm = false)).map { it.text }

    /** The full ranking: adds the neural model and, where present, Gemini Nano. Call off the main thread. */
    suspend fun liveRank(plan: Plan): List<String> =
        if (plan.fixed != null || engine == null) quickRank(plan)
        else engine.rankLive(plan.head, plan.atStart, plan.typed, plan.extra).map { it.text }

    fun plan(
        typed: String,
        previous: String?,
        allowAutoCorrect: Boolean,
        sentenceStart: Boolean = false,
        previousPrevious: String? = null,
        touches: List<SpatialModel.Point?>? = null,
        head: String = "",
        atStart: Boolean = true,
    ): Plan {
        if (typed.isEmpty()) return Plan(typed, head, atStart, sentenceStart, emptyMap(), emptyList(), null, false, null)
        if (dictionary.lang == "en" && typed.lowercase() in ENGLISH_I && typed[0] == 'i') {
            val fixed = "I" + typed.substring(1)
            return Plan(typed, head, atStart, sentenceStart, emptyMap(), emptyList(), null, true, Result(
                listOf(Suggestion(fixed, Suggestion.Kind.WORD, autoCorrect = allowAutoCorrect),
                    Suggestion(typed, Suggestion.Kind.LITERAL)),
                if (allowAutoCorrect) fixed else null,
            ))
        }
        // Elisions like l'homme, d'accord, qu'il: predict the part after the apostrophe.
        val apo = typed.lastIndexOf('\'')
        if (apo in 1..3 && apo < typed.length - 1 && dictionary.score(typed) == null) {
            val elided = typed.substring(0, apo + 1)
            val r = forWord(typed.substring(apo + 1), previous, allowAutoCorrect, false, previousPrevious,
                touches?.let { if (it.size == typed.length) it.drop(apo + 1) else null })
            return Plan(typed, head, atStart, sentenceStart, emptyMap(), emptyList(), null, true, Result(
                r.items.map { it.copy(text = if (it.text.startsWith(elided)) it.text else elided + it.text) },
                r.autoCorrect?.let { elided + it },
            ))
        }
        // A capitalized word in the middle of a sentence is probably a name: don't "fix" it.
        val autoOk = allowAutoCorrect && (sentenceStart || !typed[0].isUpperCase())
        val lower = typed.lowercase()
        val norm = Dictionary.normalize(typed)
        val dictScore = dictionary.score(typed)
        val userCount = user.count(typed)
        // "dont" → "don't", "im" → "I'm", "cest" → "c'est": a known contraction always wins, even if the
        // apostrophe-less spelling was learned from earlier typing.
        val contraction = if ('\'' !in typed && dictScore == null)
            dictionary.completions(norm, 8).firstOrNull { '\'' in it.first && Dictionary.normalize(it.first) == norm }?.first
        else null
        val valid = contraction == null && (dictScore != null || user.knows(typed) || typed.any { it.isDigit() })

        val scored = LinkedHashMap<String, Float>()
        // With an engine the candidates go to it with a bonus for how well they fit what was typed;
        // without one the classic score decides the order.
        val extra = LinkedHashMap<String, Float>()
        fun add(word: String, score: Float, fit: Float) {
            val key = word.lowercase()
            if (key == lower) return
            if (engine != null) { if ((extra[word] ?: Float.NEGATIVE_INFINITY) < fit) extra[word] = fit; return }
            val s = score + boost(previous, word, previousPrevious)
            if ((scored[word] ?: Float.NEGATIVE_INFINITY) < s) scored[word] = s
        }

        dictionary.completions(norm, 10).forEach { (w, s) ->
            // Completions are slightly penalized per missing letter so short exact-ish words win.
            add(w, s - 0.12f * (w.length - typed.length), 0f)
        }
        if (engine == null) {
            dictionary.contextModel.predict(previous, previousPrevious).filter { Dictionary.normalize(it.first).startsWith(norm) }
                .forEach { (word, _) -> add(word, dictionary.score(word) ?: 3f, 0f) }
            if (dictionary.lang == "en") EnglishGrammar.candidates(previous, previousPrevious)
                .filter { Dictionary.normalize(it).startsWith(norm) && dictionary.contains(it) }
                .forEach { add(it, dictionary.score(it) ?: 3f, 0f) }
            user.prefixed(lower, 5).forEach { (w, c) -> add(w, 3.5f + minOf(c, 30) * 0.15f, 0f) }
        }

        var corrections = emptyList<Dictionary.Correction>()
        if (typed.length >= 2 && typed.all { it.isLetter() || it == '\'' || it == '-' }) {
            corrections = dictionary.corrections(typed, neighbors, 12, touches, spatial).sortedByDescending { it.score - it.distance * 2.4f + boost(previous, it.word, previousPrevious) }
            corrections.forEach { add(it.word, it.score - it.distance * 2.4f + 0.4f, 0.4f - it.distance * 2.4f) }
        }

        var auto: String? = null
        if (autoOk && !valid && corrections.isNotEmpty()) {
            val best = corrections.first()
            val limit = when {
                typed.length <= 3 -> 0.7f
                typed.length <= 6 -> 1.2f
                else -> 1.8f
            }
            if (best.distance <= limit && best.score > 1.2f) auto = best.word
        }
        if (autoOk && contraction != null) auto = contraction
        // Accent fixes are always safe: "cafe" → "café" when only the accented form exists.
        if (autoOk && auto == null && dictScore == null && userCount == 0) {
            val accent = dictionary.completions(norm, 3).firstOrNull { Dictionary.normalize(it.first) == norm }
            if (accent != null) auto = accent.first
        }

        val legacy = scored.entries.sortedByDescending { it.value }.map { it.key }
        return Plan(typed, head, atStart, sentenceStart, extra, legacy, auto, valid, null)
    }

    /** Builds the suggestion strip from a [ranking] (best first) for the keystroke described by [plan]. */
    fun finish(plan: Plan, ranking: List<String>): Result {
        plan.fixed?.let { return it }
        val typed = plan.typed
        if (typed.isEmpty()) return Result(nextFrom(ranking, plan.sentenceStart), null)
        val ranked = ranking.filter { !it.equals(typed, ignoreCase = true) }
        val auto = plan.auto
        val valid = plan.valid
        val items = ArrayList<Suggestion>()
        if (auto != null) {
            items += Suggestion(applyCase(typed, auto), Suggestion.Kind.WORD, autoCorrect = true)
            items += Suggestion(typed, Suggestion.Kind.LITERAL)
        } else if (valid) {
            // iOS order: best guess in the middle, what you typed beside it.
            ranked.firstOrNull()?.let { items += Suggestion(applyCase(typed, it), Suggestion.Kind.WORD) }
            items += Suggestion(typed, Suggestion.Kind.LITERAL)
        } else if (ranked.isEmpty()) {
            items += Suggestion(typed, Suggestion.Kind.LITERAL)
        }
        for (w in ranked) {
            if (items.size >= 6) break
            val shown = applyCase(typed, w)
            if (items.none { it.text.equals(shown, true) }) items += Suggestion(shown, Suggestion.Kind.WORD)
        }
        if (!valid && auto == null && items.none { it.kind == Suggestion.Kind.LITERAL }) {
            items.add(minOf(1, items.size), Suggestion(typed, Suggestion.Kind.LITERAL))
        }
        return Result(items, auto?.let { applyCase(typed, it) })
    }

    private fun nextFrom(ranking: List<String>, sentenceStart: Boolean): List<Suggestion> {
        val seen = HashSet<String>()
        return ranking.mapNotNull { word ->
            val shown = if (dictionary.lang == "en" && word.lowercase() in ENGLISH_I) "I" + word.substring(1) else word
            if (!seen.add(shown.lowercase())) null
            else Suggestion(if (sentenceStart) shown.replaceFirstChar { it.titlecase() } else shown, Suggestion.Kind.WORD)
        }.take(6)
    }

    /** What may come next, with no word typed yet. */
    fun nextWords(previous: String?, sentenceStart: Boolean, previousPrevious: String? = null, head: String = "", atStart: Boolean = true): List<Suggestion> {
        if (engine != null) {
            val plan = plan("", previous, false, sentenceStart, previousPrevious, null, head, atStart)
            return finish(plan, quickRank(plan)).items
        }
        val scores = LinkedHashMap<String, Pair<String, Float>>()
        fun add(word: String, score: Float) {
            val key = word.lowercase(java.util.Locale.ROOT)
            val grammar = if (dictionary.lang == "en") EnglishGrammar.adjustment(previous, previousPrevious, word) else 0f
            val value = score + grammar
            if (value > (scores[key]?.second ?: Float.NEGATIVE_INFINITY)) scores[key] = word to value
        }
        if (sentenceStart) {
            (STARTERS[dictionary.lang] ?: dictionary.topWords.take(6)).forEachIndexed { i, w -> add(w, 5f - i * .2f) }
        } else {
            dictionary.contextWords[previous?.lowercase()]?.forEach { add(it.first, it.second) }
            dictionary.contextModel.predict(previous, previousPrevious).forEach { add(it.first, it.second * 7f) }
            user.nextWords(previous, 12, previousPrevious).forEach { (w, count) ->
                val learned = 2f + kotlin.math.ln(1f + count.coerceAtMost(100)) * .7f
                add(w, learned)
            }
            if (dictionary.lang == "en") EnglishGrammar.candidates(previous, previousPrevious).forEachIndexed { i, w ->
                if (dictionary.contains(w)) add(w, 2.8f - i * .08f)
            }
        }
        dictionary.topWords.take(24).forEachIndexed { i, w -> add(w, -2f - i * .05f) }
        return scores.values.sortedByDescending { it.second }.take(6).map { (word, _) ->
            val shown = if (dictionary.lang == "en" && word.lowercase() in ENGLISH_I) "I" + word.substring(1) else word
            Suggestion(if (sentenceStart) shown.replaceFirstChar { it.titlecase() } else shown, Suggestion.Kind.WORD)
        }
    }

    private fun boost(previous: String?, word: String, previousPrevious: String? = null): Float {
        val b = user.bigramCount(previous, word)
        val u = user.count(word)
        val tri = user.trigramCount(previousPrevious, previous, word)
        val builtIn = dictionary.contextWords[previous?.lowercase()]?.firstOrNull { it.first.equals(word, true) }?.second ?: 0f
        val context = dictionary.contextModel.predict(previous, previousPrevious).firstOrNull { it.first.equals(word, true) }?.second ?: 0f
        val grammar = if (dictionary.lang == "en") EnglishGrammar.adjustment(previous, previousPrevious, word) else 0f
        return context * 5f + minOf(b, 8) * 0.2f + minOf(tri, 6) * 0.4f + minOf(u, 30) * 0.03f + builtIn + grammar
    }

    companion object {
        private val ENGLISH_I = setOf("i", "i'm", "i'll", "i've", "i'd")
        private val STARTERS = mapOf(
            "en" to listOf("I", "The", "Hi", "Thanks", "Okay", "What"),
            "es" to listOf("Hola", "El", "Yo", "Gracias", "La", "Qué"),
            "fr" to listOf("Je", "Bonjour", "Merci", "Le", "Oui", "La"),
            "pt" to listOf("Eu", "Olá", "Obrigado", "O", "A", "Sim"),
            "de" to listOf("Ich", "Hallo", "Danke", "Das", "Ja", "Wir"),
            "ar" to listOf("مرحبا", "شكرا", "أنا", "في", "هل", "السلام"),
            "tr" to listOf("Merhaba", "Bu", "Ben", "Teşekkürler", "Evet", "Ne"),
            "it" to listOf("Ciao", "Io", "Grazie", "Il", "Sì", "La"),
            "pl" to listOf("Cześć", "To", "Ja", "Dziękuję", "Tak", "Nie"),
            "da" to listOf("Hej", "Jeg", "Tak", "Det", "Ja", "Vi"),
            "cs" to listOf("Ahoj", "To", "Já", "Děkuji", "Ano", "Ne"),
            "hi" to listOf("मैं", "नमस्ते", "धन्यवाद", "क्या", "यह", "हाँ"),
            "ko" to listOf("안녕하세요", "네", "감사합니다", "저는", "오늘", "그래"),
        )

        /** Mirrors the case the user typed onto a suggestion. */
        fun applyCase(typed: String, word: String): String {
            if (typed.isEmpty()) return word
            if (typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }) return word.uppercase()
            if (typed[0].isUpperCase()) return word.replaceFirstChar { it.titlecase() }
            return word
        }

        /** Neighboring keys for the letter rows, used to cheapen likely slips. */
        fun neighborsFor(rows: List<String>): Map<Char, String> {
            val grid = rows.map { r -> r.split(" ").filter { it.length == 1 }.map { it[0] } }
            val map = HashMap<Char, String>()
            for ((ri, row) in grid.withIndex()) {
                for ((ci, c) in row.withIndex()) {
                    val sb = StringBuilder()
                    row.getOrNull(ci - 1)?.let { sb.append(it) }
                    row.getOrNull(ci + 1)?.let { sb.append(it) }
                    for (dr in listOf(-1, 1)) {
                        val other = grid.getOrNull(ri + dr) ?: continue
                        // Rows are offset by roughly half a key.
                        val shift = if (dr == 1) 0 else 1
                        other.getOrNull(ci - shift)?.let { sb.append(it) }
                        other.getOrNull(ci - shift + 1)?.let { sb.append(it) }
                    }
                    map[c] = sb.toString()
                }
            }
            return map
        }
    }
}

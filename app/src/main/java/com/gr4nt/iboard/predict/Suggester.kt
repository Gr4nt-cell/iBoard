package com.gr4nt.iboard.predict

/** A candidate shown in the suggestion bar. */
data class Suggestion(
    val text: String,
    val kind: Kind,
    /** True when this word will replace the typed word on space (autocorrect). */
    val autoCorrect: Boolean = false,
) {
    enum class Kind { LITERAL, WORD, AI, PHRASE, CLIP }
}

/**
 * On-device prediction: prefix completion, keyboard-aware typo correction and
 * next-word prediction from the user's own word pairs.
 */
class Suggester(
    val dictionary: Dictionary,
    val user: UserModel,
    val neighbors: Map<Char, String>,
) {
    class Result(val items: List<Suggestion>, val autoCorrect: String?)

    fun forWord(typed: String, previous: String?, allowAutoCorrect: Boolean, sentenceStart: Boolean = false): Result {
        if (dictionary.lang == "en" && typed.lowercase() in ENGLISH_I && typed[0] == 'i') {
            val fixed = "I" + typed.substring(1)
            return Result(
                listOf(Suggestion(fixed, Suggestion.Kind.WORD, autoCorrect = allowAutoCorrect),
                    Suggestion(typed, Suggestion.Kind.LITERAL)),
                if (allowAutoCorrect) fixed else null,
            )
        }
        // Elisions like l'homme, d'accord, qu'il: predict the part after the apostrophe.
        val apo = typed.lastIndexOf('\'')
        if (apo in 1..3 && apo < typed.length - 1 && dictionary.score(typed) == null) {
            val head = typed.substring(0, apo + 1)
            val r = forWord(typed.substring(apo + 1), previous, allowAutoCorrect, false)
            return Result(
                r.items.map { it.copy(text = if (it.text.startsWith(head)) it.text else head + it.text) },
                r.autoCorrect?.let { head + it },
            )
        }
        // A capitalized word in the middle of a sentence is probably a name: don't "fix" it.
        val autoOk = allowAutoCorrect && (sentenceStart || !typed[0].isUpperCase())
        val lower = typed.lowercase()
        val norm = Dictionary.normalize(typed)
        val dictScore = dictionary.score(typed)
        val userCount = user.count(typed)
        val valid = dictScore != null || user.knows(typed) || typed.any { it.isDigit() }

        val scored = LinkedHashMap<String, Float>()
        fun add(word: String, score: Float) {
            val key = word.lowercase()
            if (key == lower) return
            val s = score + boost(previous, word)
            if ((scored[word] ?: Float.NEGATIVE_INFINITY) < s) scored[word] = s
        }

        dictionary.completions(norm, 10).forEach { (w, s) ->
            // Completions are slightly penalized per missing letter so short exact-ish words win.
            add(w, s - 0.12f * (w.length - typed.length))
        }
        user.prefixed(lower, 5).forEach { (w, c) -> add(w, 3.5f + minOf(c, 30) * 0.15f) }

        var corrections = emptyList<Dictionary.Correction>()
        if (typed.length >= 2 && typed.all { it.isLetter() || it == '\'' || it == '-' }) {
            corrections = dictionary.corrections(typed, neighbors, 6)
            corrections.forEach { add(it.word, it.score - it.distance * 2.4f + 0.4f) }
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
        // Accent fixes are always safe: "cafe" → "café" when only the accented form exists.
        if (autoOk && auto == null && dictScore == null && userCount == 0) {
            val accent = dictionary.completions(norm, 3).firstOrNull { Dictionary.normalize(it.first) == norm }
            if (accent != null) auto = accent.first
        }

        val ranked = scored.entries.sortedByDescending { it.value }.map { it.key }
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

    fun nextWords(previous: String?, sentenceStart: Boolean): List<Suggestion> {
        val out = LinkedHashSet<String>()
        user.nextWords(previous, 6).forEach { out += it.first }
        if (out.size < 3) {
            val fallback = if (sentenceStart) STARTERS[dictionary.lang] ?: emptyList()
            else dictionary.topWords
            for (w in fallback) {
                if (out.size >= 3) break
                if (w.length > 1 || w == "I" || dictionary.lang == "ar") out += w
            }
        }
        return out.take(6).map {
            Suggestion(if (sentenceStart) it.replaceFirstChar { c -> c.titlecase() } else it, Suggestion.Kind.WORD)
        }
    }

    private fun boost(previous: String?, word: String): Float {
        val b = user.bigramCount(previous, word)
        val u = user.count(word)
        return minOf(b, 20) * 0.35f + minOf(u, 50) * 0.04f
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

package com.gr4nt.iboard.predict

/**
 * Cleans up raw speech-recognition text: spoken punctuation ("comma", "question mark", "new line"),
 * spacing around marks, capitalization, and a sensible mark at the end of each spoken sentence.
 */
object Dictation {

    private val spoken: Map<String, List<Pair<String, String>>> = mapOf(
        "en" to listOf(
            "new paragraph" to "\n\n", "new line" to "\n", "question mark" to "?", "exclamation mark" to "!",
            "exclamation point" to "!", "full stop" to ".", "period" to ".", "comma" to ",", "semicolon" to ";",
            "colon" to ":", "ellipsis" to "…", "dot dot dot" to "…",
        ),
        "es" to listOf(
            "nuevo párrafo" to "\n\n", "nueva línea" to "\n", "signo de interrogación" to "?",
            "signo de exclamación" to "!", "punto y coma" to ";", "dos puntos" to ":", "punto" to ".", "coma" to ",",
        ),
        "fr" to listOf(
            "nouveau paragraphe" to "\n\n", "nouvelle ligne" to "\n", "à la ligne" to "\n",
            "point d'interrogation" to "?", "point d'exclamation" to "!", "point virgule" to ";",
            "deux points" to ":", "virgule" to ",", "point" to ".",
        ),
        "pt" to listOf(
            "novo parágrafo" to "\n\n", "nova linha" to "\n", "ponto de interrogação" to "?",
            "ponto de exclamação" to "!", "ponto e vírgula" to ";", "dois pontos" to ":", "ponto final" to ".",
            "vírgula" to ",",
        ),
        "de" to listOf(
            "neuer absatz" to "\n\n", "neue zeile" to "\n", "fragezeichen" to "?", "ausrufezeichen" to "!",
            "semikolon" to ";", "doppelpunkt" to ":", "komma" to ",", "punkt" to ".",
        ),
        "ar" to listOf(
            "سطر جديد" to "\n", "علامة استفهام" to "؟", "علامة تعجب" to "!", "فاصلة" to "،", "نقطة" to ".",
        ),
    )

    private val questionStarts: Map<String, Set<String>> = mapOf(
        "en" to setOf("what", "why", "how", "who", "whom", "whose", "where", "when", "which", "is", "are", "am",
            "was", "were", "can", "could", "do", "does", "did", "will", "would", "should", "shall", "may",
            "might", "have", "has", "had", "isn't", "aren't", "don't", "doesn't", "didn't", "won't", "can't"),
        "es" to setOf("qué", "que", "por", "cómo", "como", "quién", "quien", "dónde", "donde", "cuándo", "cuando", "cuál", "cual"),
        "fr" to setOf("que", "quoi", "pourquoi", "comment", "qui", "où", "quand", "quel", "quelle", "est-ce"),
        "pt" to setOf("que", "o", "por", "como", "quem", "onde", "quando", "qual"),
        "de" to setOf("was", "warum", "wie", "wer", "wo", "wann", "welche", "welcher", "ist", "sind", "kannst", "hast"),
    )

    private const val END_MARKS = ".!?…,;:؟"

    /**
     * @param raw recognizer output for one spoken segment
     * @param before text already in the field before the cursor (for capitalization)
     */
    fun format(raw: String, lang: String, before: String): String {
        var t = " " + raw.trim() + " "
        for ((phrase, mark) in spoken[lang].orEmpty()) {
            t = Regex("(?i)(?<=\\s)" + Regex.escape(phrase) + "(?=\\s)").replace(t, mark)
        }
        // No space before marks, one space after them.
        t = t.replace(Regex("\\s+([.,!?;:…،؟])"), "$1")
            .replace(Regex("([,!?;:…،؟])(?=\\p{L})"), "$1 ")
            .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
            .replace(Regex(" {2,}"), " ")
            .trim(' ')
        if (lang == "en") t = t.replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi'(m|ll|ve|d)\\b"), "I'$1")

        // Capitalize sentence starts (after . ! ? newline, and at the very beginning if the field starts a sentence).
        val prev = before.trimEnd(' ')
        val startsSentence = prev.isEmpty() || prev.last() in ".!?\n؟"
        val sb = StringBuilder(t)
        var cap = startsSentence
        for (i in sb.indices) {
            val c = sb[i]
            if (cap && c.isLetter()) { sb.setCharAt(i, c.titlecaseChar()); cap = false }
            else if (c in ".!?\n؟") cap = true
            else if (!c.isWhitespace() && c.isLetterOrDigit()) cap = false
        }
        t = sb.toString()

        // A spoken sentence of 3+ words gets an end mark if the recognizer gave none.
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 3 && t.isNotEmpty() && t.last() !in END_MARKS && t.last() != '\n') {
            val first = words.first().lowercase().trim(',', '.')
            val isQuestion = first in questionStarts[lang].orEmpty()
            t += when {
                isQuestion && lang == "ar" -> "؟"
                isQuestion -> "?"
                else -> "."
            }
        }
        return t
    }
}

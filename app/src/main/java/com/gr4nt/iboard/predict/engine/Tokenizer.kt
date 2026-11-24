package com.gr4nt.iboard.predict.engine

/**
 * Turns the text before the cursor into the token ids the language model was trained on.
 * The rules mirror tools/prediction/tok.py exactly: words (with inner apostrophes) are lower-cased,
 * digit runs become <num>, , ; : become a comma, and . ! ? … or a line break start a new sentence.
 * Everything else (emoji, quotes, brackets) is ignored.
 */
object Tokenizer {
    const val PAD = 0
    const val UNK = 1
    const val BOS = 2
    const val NUM = 3
    const val COMMA = 4
    const val FIRST_WORD = 5
    const val MAX_WORD = 24

    private val TOKEN = Regex("(\\p{L}+(?:'\\p{L}+)*)|(\\d+(?:[.,:]\\d+)*)|([,;:])|([.!?…\n\r؟।。！？]+)")

    /**
     * The model's view of the text: [ids] (ending at the cursor), the [words] of the sentence being
     * written (surface forms, original case) and whether the next word starts a sentence.
     */
    class Context(val ids: IntArray, val words: List<String>, val sentenceStart: Boolean, val lastWasComma: Boolean) {
        /** Last [n] words of the current sentence, lower-cased; missing ones are null. */
        fun previous(n: Int): String? = words.getOrNull(words.size - n)?.lowercase()
    }

    /**
     * [atStart] says the text begins at the start of the field, so a sentence start is implied;
     * otherwise the first token is a mid-sentence word (the window cut the text).
     */
    fun parse(text: String, vocab: Vocab, atStart: Boolean, maxTokens: Int = 48): Context {
        val ids = ArrayList<Int>(64)
        if (atStart) ids += BOS
        val sentence = ArrayList<String>()
        var lastComma = false
        val t = text.replace('’', '\'').replace('‘', '\'').replace('`', '\'')
        for (m in TOKEN.findAll(t)) {
            lastComma = false
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> {
                    val lw = g[1].lowercase()
                    ids += if (lw.length <= MAX_WORD) vocab.id(lw) else UNK
                    sentence += g[1]
                }
                g[2].isNotEmpty() -> { ids += NUM; sentence += g[2] }
                g[3].isNotEmpty() -> { if (ids.isNotEmpty() && ids.last() != BOS) { ids += COMMA; lastComma = true } }
                else -> {
                    if (ids.isNotEmpty() && ids.last() != BOS) ids += BOS
                    else if (ids.isEmpty()) ids += BOS
                    sentence.clear()
                }
            }
        }
        val start = if (ids.size > maxTokens) ids.size - maxTokens else 0
        val window = IntArray(ids.size - start) { ids[start + it] }
        return Context(window, sentence, sentence.isEmpty(), lastComma)
    }
}

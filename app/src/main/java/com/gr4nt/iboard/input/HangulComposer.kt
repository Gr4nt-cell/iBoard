package com.gr4nt.iboard.input

/**
 * Korean 2-set (Dubeolsik) composer: turns typed jamo (ㄱ ㅏ ㄴ …) into Hangul syllables (간).
 * [input] returns the finished text to commit and the syllable still being composed.
 */
class HangulComposer {
    private var cho: Char? = null
    private var jung: Char? = null
    private var jong: Char? = null
    /** Jamo typed for the syllable being composed, so backspace can undo one at a time. */
    private val typed = ArrayList<Char>()

    val composing: String get() = render()
    val isActive: Boolean get() = typed.isNotEmpty()

    fun reset() { cho = null; jung = null; jong = null; typed.clear() }

    /** Returns (text to commit, new composing text). */
    fun input(j: Char): Pair<String, String> {
        var commit = ""
        fun startNew(vararg js: Char) {
            commit += render()
            reset()
            js.forEach { feed(it, record = true) }
        }
        when {
            isConsonant(j) -> when {
                cho == null && jung == null -> feed(j)
                jung == null -> startNew(j)                       // consonant after a lone consonant
                cho == null -> startNew(j)                        // consonant after a lone vowel
                jong == null && j in JONG -> feed(j)               // becomes the final
                jong != null && FINAL_PAIRS.containsKey("$jong$j") -> feed(j)
                else -> startNew(j)
            }
            isVowel(j) -> when {
                jong != null -> {
                    // The final consonant moves to the next syllable: 갑 + ㅏ = 가바
                    val pair = FINAL_SPLIT[jong!!]
                    val moved: Char
                    if (pair != null) { jong = pair.first; moved = pair.second } else { moved = jong!!; jong = null }
                    commit += render()
                    reset()
                    feed(moved); feed(j)
                }
                jung != null && VOWEL_PAIRS.containsKey("$jung$j") -> feed(j)
                jung != null -> startNew(j)
                else -> feed(j)
            }
            else -> { commit += render() + j; reset() }
        }
        return commit to render()
    }

    /** Removes the last typed jamo. Returns false if nothing was being composed. */
    fun backspace(): Boolean {
        if (typed.isEmpty()) return false
        typed.removeAt(typed.size - 1)
        val keep = typed.toList()
        reset()
        keep.forEach { feed(it) }
        return true
    }

    private fun feed(j: Char, record: Boolean = true) {
        if (record) typed += j
        if (isConsonant(j)) {
            when {
                cho == null && jung == null -> cho = j
                jong == null -> jong = j
                else -> jong = FINAL_PAIRS["$jong$j"] ?: jong
            }
        } else {
            jung = if (jung == null) j else VOWEL_PAIRS["$jung$j"] ?: jung
        }
    }

    private fun render(): String {
        val c = cho; val v = jung
        if (c != null && v != null) {
            val ci = CHO.indexOf(c); val vi = JUNG.indexOf(v)
            val fi = jong?.let { JONG.indexOf(it) } ?: 0
            if (ci >= 0 && vi >= 0 && fi >= 0) return (0xAC00 + (ci * 21 + vi) * 28 + fi).toChar().toString()
        }
        return listOfNotNull(c, v, jong).joinToString("")
    }

    companion object {
        const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        /** Index 0 is "no final"; a placeholder keeps the indices aligned with Unicode. */
        const val JONG = "\u0000ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

        private val VOWEL_PAIRS = mapOf(
            "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ',
        )
        private val FINAL_PAIRS = mapOf(
            "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ',
            "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ',
        )
        private val FINAL_SPLIT = FINAL_PAIRS.entries.associate { (k, v) -> v to (k[0] to k[1]) }

        fun isConsonant(c: Char) = c in CHO
        fun isVowel(c: Char) = c in JUNG
        fun isJamo(s: String) = s.length == 1 && (isConsonant(s[0]) || isVowel(s[0]))
    }
}

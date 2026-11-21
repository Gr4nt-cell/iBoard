package com.gr4nt.iboard.predict

data class Suggestion(
    val text: String,
    val kind: Kind,
    /** True when this word will replace the typed word on space (autocorrect). */
    val autoCorrect: Boolean = false,
) {
    enum class Kind { LITERAL, WORD, CLIP, AI }
}

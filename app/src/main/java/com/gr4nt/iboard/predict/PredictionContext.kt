package com.gr4nt.iboard.predict

/** Context stops at sentence/paragraph boundaries, including punctuation used by other layouts. */
data class PredictionContext(val previous: String?, val before: String?, val sentenceStart: Boolean) {
    companion object {
        fun from(head: String): PredictionContext {
            val boundary = head.indexOfLast { it in ".!?…\n\r؟।。！？" }
            val sentence = head.substring(boundary + 1)
            val words = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")
                .findAll(sentence).map { it.value.replace('’', '\'') }.toList()
            return PredictionContext(words.lastOrNull(), words.getOrNull(words.lastIndex - 1), words.isEmpty())
        }
    }
}

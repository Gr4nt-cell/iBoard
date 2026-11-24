package com.gr4nt.iboard.predict.engine

/**
 * Combines every signal about a candidate word into one score (higher is better):
 *   lm       log-probability from the bundled neural model, normalised over the candidates
 *   ngram    log of the Kneser-Ney n-gram probability
 *   personal the user's own habits, accepted and ignored suggestions
 *   grammar  word-class fit and agreement rules
 *   nano     small bonus for words Gemini Nano also proposed (optional)
 *   prior    weak unigram frequency prior
 *   bonus    caller-supplied evidence, e.g. how close a typo candidate is to what was typed
 * The two weight sets were tuned on the held-out benchmark (see PredictionBenchmark).
 */
object Ranker {
    data class Weights(
        val lm: Float, val ngram: Float, val personal: Float, val grammar: Float,
        val nano: Float, val prior: Float, val bonus: Float,
    )

    /** Neural model available. */
    val FULL = Weights(lm = 1.0f, ngram = 0.875f, personal = 1.8f, grammar = 0.375f, nano = 1.0f, prior = 0.0375f, bonus = 0.45f)
    /** First instant pass: n-gram, habits and grammar only. */
    val FAST = Weights(lm = 0f, ngram = 1.0f, personal = 1.8f, grammar = 0.5f, nano = 1.0f, prior = 0.15f, bonus = 0.45f)

    class Features(
        var lm: Float = 0f, var ngram: Float = 0f, var personal: Float = 0f, var grammar: Float = 0f,
        var nano: Float = 0f, var prior: Float = 0f, var bonus: Float = 0f,
    )

    fun score(f: Features, w: Weights): Float =
        w.lm * f.lm + w.ngram * f.ngram + w.personal * f.personal + w.grammar * f.grammar +
            w.nano * f.nano + w.prior * f.prior + w.bonus * f.bonus

    /** Bonus for the position in Gemini Nano's own ordering. */
    fun nanoBonus(rank: Int): Float = when (rank) { 0 -> 1.2f; 1 -> 0.9f; 2 -> 0.7f; else -> 0.4f }
}

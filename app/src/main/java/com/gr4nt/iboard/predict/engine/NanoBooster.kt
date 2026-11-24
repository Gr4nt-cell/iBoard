package com.gr4nt.iboard.predict.engine

import com.gr4nt.iboard.ai.GeminiNano
import kotlinx.coroutines.withTimeoutOrNull

/** A source of extra candidate words (Gemini Nano on phones that have it). Never required. */
fun interface NanoSource {
    suspend fun candidates(head: String, partial: String, lang: String): List<String>
}

/**
 * Optional booster: when Gemini Nano is installed and the user has not switched it off, its three
 * guesses join the other candidates as one more signal. If Nano is missing, slow (over [BUDGET_MS]),
 * or fails, nothing changes: the built-in engine already produced a complete answer on its own.
 */
class NanoBooster(
    private val enabled: () -> Boolean,
    private val source: NanoSource = NanoSource { head, partial, lang -> GeminiNano.predict(head, partial, lang) },
    private val available: () -> Boolean = { GeminiNano.ready },
) {
    suspend fun candidates(head: String, partial: String, lang: String): List<String> {
        if (!enabled() || !available()) return emptyList()
        if (head.isBlank() && partial.isEmpty()) return emptyList()
        return try {
            withTimeoutOrNull(BUDGET_MS) { source.candidates(head, partial, lang) } ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object { const val BUDGET_MS = 150L }
}

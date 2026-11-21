package com.gr4nt.iboard.translate

import android.content.Context
import com.gr4nt.iboard.ai.GeminiNano
import java.util.Locale

/**
 * Translation with Gemini Nano on the phone (through AICore). There are no per-language packs:
 * one on-device model handles every pair, and the text never leaves the phone.
 */
class TranslatorEngine(@Suppress("UNUSED_PARAMETER") context: Context) {
    companion object {
        const val DETECT = "auto"
        const val PREF_FROM = "translate_from"
        const val PREF_TO = "translate_to"

        /** Languages offered in the pickers, sorted by English name. */
        val languages: List<String> by lazy {
            listOf("af", "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr",
                "gu", "ha", "he", "hi", "hr", "hu", "id", "it", "ja", "kn", "ko", "lt", "lv", "ml", "mr",
                "ms", "nl", "no", "pa", "pl", "pt", "ro", "ru", "sk", "sl", "sr", "sv", "sw", "ta", "te",
                "th", "tl", "tr", "tw", "uk", "ur", "vi", "yo", "zh", "zu").sortedBy { name(it) }
        }

        fun name(code: String): String =
            if (code == DETECT) "Detect language"
            else Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.uppercase() }

        fun short(code: String): String = if (code == DETECT) "Detect" else name(code)
    }

    /** True when Gemini Nano is installed and ready on this phone. */
    suspend fun isDownloaded(@Suppress("UNUSED_PARAMETER") from: String, @Suppress("UNUSED_PARAMETER") to: String): Boolean =
        GeminiNano.ready || GeminiNano.refresh() == GeminiNano.State.AVAILABLE

    /** Asks AICore to install Gemini Nano. Throws when this phone can't run it. */
    suspend fun download(@Suppress("UNUSED_PARAMETER") from: String, @Suppress("UNUSED_PARAMETER") to: String) {
        if (!GeminiNano.download()) error(GeminiNano.lastError ?: "Gemini Nano isn't available on this phone")
    }

    suspend fun detect(text: String): String? = GeminiNano.detect(text)

    suspend fun translate(text: String, from: String, to: String): String {
        if (from == to) return text
        return GeminiNano.translate(text, from.takeIf { it != DETECT }, to)
            ?: error(GeminiNano.lastError ?: "Translation failed")
    }

    fun close() {}
}

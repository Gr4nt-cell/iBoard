package com.gr4nt.iboard

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Typed access to every user setting. Keys match res/xml/prefs.xml. */
class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    val theme: String get() = sp.getString(THEME, "system") ?: "system"
    val gradient: String get() = sp.getString(GRADIENT, "blossom") ?: "blossom"
    val numberRow: Boolean get() = sp.getBoolean(NUMBER_ROW, true)
    val keyPopup: Boolean get() = sp.getBoolean(KEY_POPUP, true)
    val haptics: Boolean get() = sp.getBoolean(HAPTICS, true)
    val sound: Boolean get() = sp.getBoolean(SOUND, false)
    val autoCap: Boolean get() = sp.getBoolean(AUTO_CAP, true)
    val autoCorrect: Boolean get() = sp.getBoolean(AUTO_CORRECT, true)
    val doubleSpacePeriod: Boolean get() = sp.getBoolean(DOUBLE_SPACE, true)
    val learnWords: Boolean get() = sp.getBoolean(LEARN, true)
    val clipboardHistory: Boolean get() = sp.getBoolean(CLIP_HISTORY, true)

    val languages: List<String>
        get() {
            val set = sp.getStringSet(LANGUAGES, null) ?: setOf("en")
            val ordered = ALL_LANGS.filter { it in set }
            return ordered.ifEmpty { listOf("en") }
        }

    var currentLanguage: String
        get() = sp.getString(CURRENT_LANG, null)?.takeIf { it in languages } ?: languages.first()
        set(v) = sp.edit().putString(CURRENT_LANG, v).apply()

    val aiEnabled: Boolean get() = sp.getBoolean(AI_ENABLED, false)
    val aiProvider: String get() = sp.getString(AI_PROVIDER, "anthropic") ?: "anthropic"
    val aiKey: String get() = (sp.getString(AI_KEY, "") ?: "").trim()
    val aiModel: String
        get() {
            val m = (sp.getString(AI_MODEL, "") ?: "").trim()
            if (m.isNotEmpty()) return m
            return if (aiProvider == "anthropic") DEFAULT_CLAUDE_MODEL else ""
        }
    val aiBaseUrl: String
        get() = (sp.getString(AI_BASE_URL, "") ?: "").trim().ifEmpty { "https://api.openai.com/v1" }

    /** A fingerprint of settings that require rebuilding the keyboard view. */
    fun visualSignature(night: Boolean): String =
        listOf(theme, gradient, numberRow, keyPopup, languages.joinToString(), night).joinToString("|")

    companion object {
        const val THEME = "theme"
        const val GRADIENT = "gradient"
        const val NUMBER_ROW = "number_row"
        const val KEY_POPUP = "key_popup"
        const val HAPTICS = "haptics"
        const val SOUND = "sound"
        const val AUTO_CAP = "auto_cap"
        const val AUTO_CORRECT = "auto_correct"
        const val DOUBLE_SPACE = "double_space_period"
        const val LEARN = "learn_words"
        const val CLIP_HISTORY = "clipboard_history"
        const val LANGUAGES = "languages"
        const val CURRENT_LANG = "current_language"
        const val AI_ENABLED = "ai_enabled"
        const val AI_PROVIDER = "ai_provider"
        const val AI_KEY = "ai_key"
        const val AI_MODEL = "ai_model"
        const val AI_BASE_URL = "ai_base_url"

        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"
        val ALL_LANGS = listOf("en", "es", "fr", "pt", "de", "ar")

        fun languageLabel(code: String): String = when (code) {
            "en" -> "English"
            "es" -> "Español"
            "fr" -> "Français"
            "pt" -> "Português"
            "de" -> "Deutsch"
            "ar" -> "العربية"
            else -> code
        }

        fun languageName(code: String): String = when (code) {
            "en" -> "English"
            "es" -> "Spanish"
            "fr" -> "French"
            "pt" -> "Portuguese"
            "de" -> "German"
            "ar" -> "Arabic"
            else -> code
        }
    }
}

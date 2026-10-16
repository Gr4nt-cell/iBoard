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

    // Key customization
    val keyStyle: String get() = sp.getString(KEY_STYLE, "solid") ?: "solid"
    val keyRadius: Int get() = sp.getInt(KEY_RADIUS, 8)
    val keyHeight: Int get() = sp.getInt(KEY_HEIGHT, 100)
    val keyTextSize: Int get() = sp.getInt(KEY_TEXT_SIZE, 100)
    val uppercaseLabels: Boolean get() = sp.getBoolean(UPPERCASE_LABELS, true)
    val spaceLabel: Boolean get() = sp.getBoolean(SPACE_LABEL, false)
    val utilityRow: Boolean get() = sp.getBoolean(UTILITY_ROW, true)
    val stripIcons: Boolean get() = sp.getBoolean(STRIP_ICONS, false)
    val accentColor: String get() = sp.getString(ACCENT_COLOR, "blue") ?: "blue"
    val voiceTyping: Boolean get() = sp.getBoolean(VOICE_TYPING, true)
    val bgOpacity: Int get() = sp.getInt(BG_OPACITY, 45)
    val aiConsent: Boolean get() = sp.getBoolean(AI_CONSENT, false)
    val offlineDictation: Boolean get() = sp.getBoolean(OFFLINE_DICTATION, true)
    val ramblerRewrite: Boolean get() = sp.getBoolean(RAMBLER_REWRITE, true)
    val speechModel: String get() = sp.getString(SPEECH_MODEL, "whisper-base") ?: "whisper-base"
    val rewriteModel: String get() = sp.getString(REWRITE_MODEL, "qwen-0.5b") ?: "qwen-0.5b"
    val modelsWifiOnly: Boolean get() = sp.getBoolean(MODELS_WIFI_ONLY, true)
    val emojiStyle: String get() = sp.getString(EMOJI_STYLE, "3d") ?: "3d"

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
        listOf(
            theme, gradient, numberRow, keyPopup, languages.joinToString(), night, keyStyle, keyRadius,
            keyHeight, keyTextSize, uppercaseLabels, spaceLabel, utilityRow, stripIcons, accentColor, voiceTyping, bgOpacity, emojiStyle, sp.getLong("emoji_font_stamp", 0L),
        ).joinToString("|")

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
        const val KEY_STYLE = "key_style"
        const val KEY_RADIUS = "key_radius"
        const val KEY_HEIGHT = "key_height"
        const val KEY_TEXT_SIZE = "key_text_size"
        const val UPPERCASE_LABELS = "uppercase_labels"
        const val SPACE_LABEL = "space_label"
        const val UTILITY_ROW = "utility_row"
        const val STRIP_ICONS = "strip_icons"
        const val ACCENT_COLOR = "accent_color"
        const val VOICE_TYPING = "voice_typing"
        const val BG_OPACITY = "bg_opacity"
        const val HIDE_ICON = "hide_app_icon"
        const val EMOJI_STYLE = "emoji_style"
        const val AI_CONSENT = "ai_consent"
        const val MIC_CONSENT = "mic_consent"
        const val OFFLINE_DICTATION = "offline_dictation"
        const val RAMBLER_REWRITE = "rambler_rewrite"
        const val SPEECH_MODEL = "speech_model"
        const val REWRITE_MODEL = "rewrite_model"
        const val MODELS_WIFI_ONLY = "models_wifi_only"

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

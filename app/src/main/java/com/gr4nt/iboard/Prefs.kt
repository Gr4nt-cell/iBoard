package com.gr4nt.iboard

import android.content.Context
import android.content.SharedPreferences

/** Typed access to every user setting. Keys match res/xml/prefs.xml. */
class Prefs(context: Context) {
    // Same file the old preference library used, so existing settings carry over.
    val sp: SharedPreferences = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

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
    val keyStyle: String get() = sp.getString(KEY_STYLE, "glass") ?: "glass"
    val keyRadius: Int get() = sp.getInt(KEY_RADIUS, 12)
    val keyHeight: Int get() = sp.getInt(KEY_HEIGHT, 100)
    val keyTextSize: Int get() = sp.getInt(KEY_TEXT_SIZE, 100)
    val uppercaseLabels: Boolean get() = sp.getBoolean(UPPERCASE_LABELS, true)
    val spaceLabel: Boolean get() = sp.getBoolean(SPACE_LABEL, false)
    val utilityRow: Boolean get() = sp.getBoolean(UTILITY_ROW, true)
    val stripIcons: Boolean get() = sp.getBoolean(STRIP_ICONS, false)
    val accentColor: String get() = sp.getString(ACCENT_COLOR, "blue") ?: "blue"
    val bgOpacity: Int get() = sp.getInt(BG_OPACITY, 58)
    val emojiStyle: String get() = sp.getString(EMOJI_STYLE, "bundled") ?: "bundled"

    init {
        // Retire provider credentials and model preferences on upgrade; typing stays local.
        if (!sp.getBoolean("offline_keyboard_v1", false)) {
            val legacy = context.getSharedPreferences("offline_models", Context.MODE_PRIVATE)
            val pending = legacy.all.values.mapNotNull { it as? Long }.filter { it > 0 }.toLongArray()
            if (pending.isNotEmpty()) runCatching { context.getSystemService(android.app.DownloadManager::class.java).remove(*pending) }
            legacy.edit().clear().apply()
            java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "models").deleteRecursively()
            val edit = sp.edit()
            sp.all.keys.filter { it.startsWith("ai_") || it in setOf("voice_typing", "mic_consent", "offline_dictation", "rambler_rewrite", "speech_model", "rewrite_model", "models_wifi_only") }.forEach { edit.remove(it) }
            edit.putString(KEY_STYLE, "glass").putInt(KEY_RADIUS, 12).putString(EMOJI_STYLE, "bundled")
                .putBoolean("offline_keyboard_v1", true).apply()
        }
    }

    val languages: List<String>
        get() {
            val set = sp.getStringSet(LANGUAGES, null) ?: setOf("en")
            val ordered = ALL_LANGS.filter { it in set }
            return ordered.ifEmpty { listOf("en") }
        }

    var currentLanguage: String
        get() = sp.getString(CURRENT_LANG, null)?.takeIf { it in languages } ?: languages.first()
        set(v) = sp.edit().putString(CURRENT_LANG, v).apply()

    /** A fingerprint of settings that require rebuilding the keyboard view. */
    fun visualSignature(night: Boolean): String =
        listOf(
            theme, gradient, numberRow, keyPopup, languages.joinToString(), night, keyStyle, keyRadius,
            keyHeight, keyTextSize, uppercaseLabels, spaceLabel, utilityRow, stripIcons, accentColor, bgOpacity, emojiStyle, sp.getLong("emoji_font_stamp", 0L),
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
        const val KEY_STYLE = "key_style"
        const val KEY_RADIUS = "key_radius"
        const val KEY_HEIGHT = "key_height"
        const val KEY_TEXT_SIZE = "key_text_size"
        const val UPPERCASE_LABELS = "uppercase_labels"
        const val SPACE_LABEL = "space_label"
        const val UTILITY_ROW = "utility_row"
        const val STRIP_ICONS = "strip_icons"
        const val ACCENT_COLOR = "accent_color"
        const val BG_OPACITY = "bg_opacity"
        const val HIDE_ICON = "hide_app_icon"
        const val EMOJI_STYLE = "emoji_style"

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

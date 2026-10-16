package com.gr4nt.iboard

enum class KeyType { CHAR, SHIFT, BACKSPACE, TO_SYMBOLS, TO_LETTERS, TO_MORE_SYMBOLS, EMOJI, GLOBE, SPACE, RETURN, CLIPBOARD, MIC, SPACER, AI }

enum class Page { LETTERS, SYMBOLS, MORE_SYMBOLS }

enum class FieldKind { TEXT, EMAIL, URL }

/** One key. [width] is in "letter units"; FLEX fills whatever is left in the row. */
data class Key(
    val type: KeyType,
    val label: String = "",
    val width: Float = 1f,
    val longPress: List<String> = emptyList(),
) {
    val isSpecial: Boolean get() = type != KeyType.CHAR && type != KeyType.SPACE

    companion object {
        const val FLEX = -1f
    }
}

class KeyRow(val keys: List<Key>, val isNumberRow: Boolean = false, val isUtility: Boolean = false)

class KeyboardLayout(val rows: List<KeyRow>, val unitsPerRow: Float, val rtl: Boolean)

object Layouts {

    private fun chars(s: String, lang: String): List<Key> =
        s.split(" ").filter { it.isNotEmpty() }.map { Key(KeyType.CHAR, it, 1f, alternates(it, lang)) }

    val letterRows: Map<String, List<String>> = mapOf(
        "en" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "es" to listOf("q w e r t y u i o p", "a s d f g h j k l ñ", "z x c v b n m"),
        "pt" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "fr" to listOf("a z e r t y u i o p", "q s d f g h j k l m", "w x c v b n '"),
        "de" to listOf("q w e r t z u i o p ü", "a s d f g h j k l ö ä", "y x c v b n m"),
        "ar" to listOf("ض ص ق ف غ ع ه خ ح ج", "ش س ي ب ل ا ت ن م ك ة", "ء ظ ط ذ د ز ر و ى"),
    )

    fun build(
        lang: String,
        page: Page,
        numberRow: Boolean,
        field: FieldKind,
        showGlobe: Boolean,
        utilityRow: Boolean = true,
        voice: Boolean = true,
    ): KeyboardLayout {
        val rows = mutableListOf<KeyRow>()
        val units = if (lang == "de" || lang == "ar") 11f else 10f
        if (numberRow) rows += KeyRow(numberRowKeys(lang), isNumberRow = true)

        when (page) {
            Page.LETTERS -> {
                val lr = letterRows[lang] ?: letterRows.getValue("en")
                // Arabic rows are written right-to-left, so flip them for left-to-right placement.
                fun row(i: Int) = if (lang == "ar") chars(lr[i], lang).reversed() else chars(lr[i], lang)
                rows += KeyRow(row(0))
                rows += KeyRow(row(1))
                if (lang == "ar") {
                    rows += KeyRow(row(2) + Key(KeyType.BACKSPACE, width = 2f))
                } else {
                    val side = (units - 7f) / 2f
                    rows += KeyRow(
                        listOf(Key(KeyType.SHIFT, width = side)) + chars(lr[2], lang) +
                            Key(KeyType.BACKSPACE, width = side)
                    )
                }
            }
            Page.SYMBOLS -> {
                if (!numberRow) rows += KeyRow(numberRowKeys(lang))
                rows += KeyRow(chars("- / : ; ( ) \$ & @ \"", lang))
                rows += punctuationRow(Key(KeyType.TO_MORE_SYMBOLS, "#+="), units, lang)
                if (numberRow) rows.add(1, KeyRow(chars("[ ] { } # % ^ * + =", lang)))
            }
            Page.MORE_SYMBOLS -> {
                if (!numberRow) rows += KeyRow(chars("[ ] { } # % ^ * + =", lang))
                rows += KeyRow(chars("_ \\ | ~ < > € £ ¥ •", lang))
                rows += punctuationRow(Key(KeyType.TO_SYMBOLS, "123"), units, lang)
                if (numberRow) rows.add(1, KeyRow(chars("₵ ₦ ¢ § ° ` ¿ ¡ « »", lang)))
            }
        }

        // Bottom row: 123 · , · space · . · return  (emoji moves to the glass capsule below)
        val bottom = mutableListOf<Key>()
        bottom += if (page == Page.LETTERS) Key(KeyType.TO_SYMBOLS, "123", 1.25f)
        else Key(KeyType.TO_LETTERS, if (lang == "ar") "أب" else "ABC", 1.25f)
        if (!utilityRow) {
            bottom += Key(KeyType.EMOJI, width = 1.1f)
            if (showGlobe) bottom += Key(KeyType.GLOBE, width = 1.1f)
        }
        val comma = if (lang == "ar") "،" else ","
        val left = when (field) {
            FieldKind.EMAIL -> Key(KeyType.CHAR, "@", 1f)
            FieldKind.URL -> Key(KeyType.CHAR, "/", 1f, listOf("\\", ":"))
            FieldKind.TEXT -> Key(KeyType.CHAR, comma, 1f, listOf(";", ":", "-", "\"", if (lang == "ar") "," else "،"))
        }
        val period = when (field) {
            FieldKind.TEXT -> Key(KeyType.CHAR, ".", 1f, listOf("…", "?", "!", "'", if (lang == "ar") "؟" else "¿"))
            else -> Key(KeyType.CHAR, ".", 1f, listOf(".com", ".net", ".org", ".gh"))
        }
        bottom += left
        bottom += Key(KeyType.SPACE, spaceLabel(lang), Key.FLEX)
        bottom += period
        bottom += Key(KeyType.RETURN, width = 2f)
        rows += KeyRow(bottom)

        // Globe, emoji, clipboard and mic share one Liquid Glass capsule under the keys.
        if (utilityRow) {
            val util = mutableListOf(
                Key(KeyType.GLOBE, width = Key.FLEX), Key(KeyType.EMOJI, width = Key.FLEX),
                Key(KeyType.AI, width = Key.FLEX), Key(KeyType.CLIPBOARD, width = Key.FLEX),
            )
            if (voice) util += Key(KeyType.MIC, width = Key.FLEX)
            rows += KeyRow(util, isUtility = true)
        }

        return KeyboardLayout(rows, units, rtl = lang == "ar")
    }

    private fun punctuationRow(left: Key, units: Float, lang: String): KeyRow {
        val p = chars(". , ? ! '", lang).map { it.copy(width = 1.4f) }
        val side = (units - 7f) / 2f
        return KeyRow(listOf(left.copy(width = side)) + p + Key(KeyType.BACKSPACE, width = side))
    }

    private fun numberRowKeys(lang: String): List<Key> {
        val sym = listOf("!", "@", "#", "\$", "%", "^", "&", "*", "(", ")")
        val arabicDigits = "١٢٣٤٥٦٧٨٩٠"
        return "1234567890".mapIndexed { i, c ->
            val alts = mutableListOf(sym[i])
            if (lang == "ar") alts += arabicDigits[i].toString()
            when (c) {
                '1' -> alts += listOf("¹", "½", "⅓", "¼")
                '2' -> alts += listOf("²", "⅔")
                '3' -> alts += listOf("³", "¾")
                '0' -> alts += listOf("°", "∅")
            }
            Key(KeyType.CHAR, c.toString(), 1f, alts)
        }
    }

    private fun spaceLabel(lang: String) = when (lang) {
        "es" -> "espacio"
        "fr" -> "espace"
        "pt" -> "espaço"
        "de" -> "Leerzeichen"
        "ar" -> "مسافة"
        else -> "space"
    }

    fun returnLabel(lang: String, action: Int): String? {
        val idx = when (action) {
            android.view.inputmethod.EditorInfo.IME_ACTION_GO -> 0
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> 1
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> 2
            android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> 3
            android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> 4
            else -> return null
        }
        val words = when (lang) {
            "es" -> listOf("ir", "buscar", "enviar", "siguiente", "OK")
            "fr" -> listOf("aller", "rechercher", "envoyer", "suivant", "OK")
            "pt" -> listOf("ir", "buscar", "enviar", "seguinte", "OK")
            "de" -> listOf("Öffnen", "Suchen", "Senden", "Weiter", "Fertig")
            "ar" -> listOf("انتقال", "بحث", "إرسال", "التالي", "تم")
            else -> listOf("go", "search", "send", "next", "done")
        }
        return words[idx]
    }

    private val latinAlts: Map<String, List<String>> = mapOf(
        "a" to listOf("à", "á", "â", "ä", "ã", "å", "æ", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "e" to listOf("é", "è", "ê", "ë", "ē", "ė", "ę"),
        "i" to listOf("í", "ì", "î", "ï", "ī", "į"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ó", "ò", "ô", "ö", "õ", "œ", "ø", "ō"),
        "u" to listOf("ú", "ù", "û", "ü", "ū"),
        "s" to listOf("ß", "ś", "š"),
        "y" to listOf("ÿ"),
        "z" to listOf("ž", "ź", "ż"),
        "l" to listOf("ł"),
        "." to listOf("…"),
        "," to listOf("،"),
        "?" to listOf("¿", "؟"),
        "!" to listOf("¡"),
        "'" to listOf("\"", "‘", "’", "“", "”", "«", "»"),
        "\"" to listOf("“", "”", "„", "«", "»"),
        "-" to listOf("–", "—", "•"),
        "\$" to listOf("₵", "€", "£", "¥", "₦", "¢", "₹"),
        "&" to listOf("§"),
        "/" to listOf("\\"),
        "%" to listOf("‰"),
    )

    private val arabicAlts: Map<String, List<String>> = mapOf(
        "ا" to listOf("أ", "إ", "آ", "ٱ"),
        "ي" to listOf("ئ", "ى"),
        "و" to listOf("ؤ"),
        "ه" to listOf("ة"),
        "ء" to listOf("ئ", "ؤ", "أ", "إ"),
        "ك" to listOf("گ"),
        "ف" to listOf("ڤ"),
        "ج" to listOf("چ"),
        "ب" to listOf("پ"),
    )

    private fun alternates(label: String, lang: String): List<String> {
        if (lang == "ar") arabicAlts[label]?.let { return it }
        val base = latinAlts[label] ?: return emptyList()
        // Put the accents that matter most for the language first.
        val pref = when (lang) {
            "fr" -> "éèêëàâçîïôœùûü"
            "es" -> "áéíóúñü"
            "pt" -> "ãáâàçéêíóôõú"
            "de" -> "äöüß"
            else -> ""
        }
        return base.sortedBy { c -> pref.indexOf(c).let { if (it < 0) 99 else it } }
    }
}

package com.gr4nt.iboard

enum class KeyType { CHAR, SHIFT, BACKSPACE, TO_NUMBER_PAD, TO_SYMBOLS, TO_LETTERS, TO_MORE_SYMBOLS, EMOJI, GLOBE, MIC, SPACE, RETURN, CLIPBOARD, SPACER, SETTINGS, TRANSLATE, AI_ASSIST }

enum class Page { LETTERS, NUMBER_PAD, SYMBOLS, MORE_SYMBOLS }

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

class KeyboardLayout(val rows: List<KeyRow>, val unitsPerRow: Float, val rtl: Boolean, val lang: String = "en")

object Layouts {

    private fun chars(s: String, lang: String): List<Key> =
        s.split(" ").filter { it.isNotEmpty() }.map { Key(KeyType.CHAR, it, 1f, alternates(it, lang)) }

    val letterRows: Map<String, List<String>> = mapOf(
        "en" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "es" to listOf("q w e r t y u i o p", "a s d f g h j k l Ã±", "z x c v b n m"),
        "pt" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "fr" to listOf("a z e r t y u i o p", "q s d f g h j k l m", "w x c v b n '"),
        "de" to listOf("q w e r t z u i o p Ã¼", "a s d f g h j k l Ã¶ Ã¤", "y x c v b n m"),
        "ar" to listOf("Ø¶ Øµ Ù Ù Øº Ø¹ Ù Ø® Ø­ Ø¬", "Ø´ Ø³ Ù Ø¨ Ù Ø§ Øª Ù Ù Ù Ø©", "Ø¡ Ø¸ Ø· Ø° Ø¯ Ø² Ø± Ù Ù"),
        "tr" to listOf("q w e r t y u Ä± o p Ä Ã¼", "a s d f g h j k l Å i", "z x c v b n m Ã¶ Ã§"),
        "it" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "pl" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "da" to listOf("q w e r t y u i o p Ã¥", "a s d f g h j k l Ã¦ Ã¸", "z x c v b n m"),
        "cs" to listOf("q w e r t z u i o p Ãº", "a s d f g h j k l Å¯", "y x c v b n m"),
        "hi" to listOf("à¥ à¥ à¤¾ à¥ à¥ à¤¬ à¤¹ à¤ à¤¦ à¤ à¤¡", "à¥ à¥ à¥ à¤¿ à¥ à¤ª à¤° à¤ à¤¤ à¤ à¤", "à¤ à¤® à¤¨ à¤µ à¤² à¤¸ à¤¯ à¤¶ à¤"),
        "ko" to listOf("ã ã ã· ã± ã ã ã ã ã ã", "ã ã´ ã ã¹ ã ã ã ã ã£", "ã ã ã ã ã  ã ã¡"),
        "zh" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
    )

    /** Languages without capital letters: no auto-capitalization. Hindi has no shift key at all. */
    val NO_CASE = setOf("ar", "hi", "ko", "zh")
    val NO_SHIFT_KEY = setOf("ar", "hi")

    /** Korean double consonants and diphthongs on shift. */
    private val koreanShift = mapOf(
        "ã" to "ã", "ã" to "ã", "ã·" to "ã¸", "ã±" to "ã²", "ã" to "ã", "ã" to "ã", "ã" to "ã",
    )

    /** What a letter key types (and shows) with shift on. */
    fun shifted(label: String, lang: String): String {
        if (label.length != 1) return label
        if (lang == "ko") return koreanShift[label] ?: label
        if (lang == "hi" || lang == "ar") return label
        if (lang == "tr") { if (label == "i") return "Ä°"; if (label == "Ä±") return "I" }
        val up = label.uppercase()
        return if (up.length == 1) up else label
    }

    fun build(
        lang: String,
        page: Page,
        numberRow: Boolean,
        field: FieldKind,
        showGlobe: Boolean,
        utilityRow: Boolean = true,
        stripButtons: Set<KeyType> = emptySet(),
    ): KeyboardLayout {
        val dock = Prefs.DOCK_BUTTONS.filter { it !in stripButtons }.map { Key(it, width = Key.FLEX) }
        fun free(t: KeyType) = t !in stripButtons
        val rows = mutableListOf<KeyRow>()
        val units = when (lang) {
            "tr" -> 12f
            "de", "ar", "da", "cs", "hi" -> 11f
            else -> 10f
        }
        if (numberRow && page != Page.NUMBER_PAD) rows += KeyRow(numberRowKeys(lang), isNumberRow = true)

        when (page) {
            Page.NUMBER_PAD -> {
                fun digit(c: Char) = Key(KeyType.CHAR, c.toString(), 1.2f,
                    if (lang == "ar") listOf("Ù Ù¡Ù¢Ù£Ù¤Ù¥Ù¦Ù§Ù¨Ù©"[c.digitToInt()].toString()) else emptyList())
                rows += KeyRow(listOf(Key(KeyType.CHAR, "+", 0.9f)) + "123".map(::digit) + Key(KeyType.BACKSPACE, width = 1.5f))
                rows += KeyRow(listOf(Key(KeyType.CHAR, "-", 0.9f)) + "456".map(::digit) + Key(KeyType.CHAR, "@", 1.5f, listOf("/", "\\")))
                rows += KeyRow(listOf(Key(KeyType.TO_SYMBOLS, "#+=", 0.9f)) + "789".map(::digit) + Key(KeyType.CHAR, "#", 1.5f, listOf("*", "%")))
                rows += KeyRow(listOf(Key(KeyType.TO_LETTERS, "ABC", 0.9f), Key(KeyType.CHAR, ".", 1.2f), digit('0'), Key(KeyType.CHAR, ",", 1.2f), Key(KeyType.RETURN, width = 1.5f)))
                if (utilityRow && dock.isNotEmpty()) rows += KeyRow(dock, isUtility = true)
                if (!utilityRow) listOf(KeyType.GLOBE, KeyType.MIC).filter(::free).map { Key(it, width = Key.FLEX) }
                    .takeIf { it.isNotEmpty() }?.let { rows += KeyRow(it, isUtility = true) }
                return KeyboardLayout(rows, 6f, rtl = lang == "ar", lang = lang)
            }
            Page.LETTERS -> {
                val lr = letterRows[lang] ?: letterRows.getValue("en")
                // Arabic rows are written right-to-left, so flip them for left-to-right placement.
                fun row(i: Int) = if (lang == "ar") chars(lr[i], lang).reversed() else chars(lr[i], lang)
                rows += KeyRow(row(0))
                rows += KeyRow(row(1))
                if (lang in NO_SHIFT_KEY) {
                    rows += KeyRow(row(2) + Key(KeyType.BACKSPACE, width = 2f))
                } else {
                    val side = maxOf(1.25f, (units - chars(lr[2], lang).size) / 2f)
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
                rows += KeyRow(chars("_ \\ | ~ < > â¬ Â£ Â¥ â¢", lang))
                rows += punctuationRow(Key(KeyType.TO_SYMBOLS, "123"), units, lang)
                if (numberRow) rows.add(1, KeyRow(chars("âµ â¦ Â¢ Â§ Â° ` Â¿ Â¡ Â« Â»", lang)))
            }
        }

        // Bottom row: 123 Â· , Â· space Â· . Â· return  (emoji moves to the glass capsule below)
        val bottom = mutableListOf<Key>()
        bottom += if (page == Page.LETTERS) Key(KeyType.TO_NUMBER_PAD, "123", 1.25f)
        else Key(KeyType.TO_LETTERS, if (lang == "ar") "Ø£Ø¨" else "ABC", 1.25f)
        if (!utilityRow && free(KeyType.EMOJI)) bottom += Key(KeyType.EMOJI, width = 1.1f)
        // The globe only appears when there's more than one language to switch between.
        if ((showGlobe || !utilityRow) && free(KeyType.GLOBE)) bottom += Key(KeyType.GLOBE, width = 1.1f)
        if (!utilityRow && free(KeyType.MIC)) bottom += Key(KeyType.MIC, width = 1.1f)
        val comma = when (lang) { "ar" -> "Ø"; "zh" -> "ï¼"; else -> "," }
        val left = when (field) {
            FieldKind.EMAIL -> Key(KeyType.CHAR, "@", 1f)
            FieldKind.URL -> Key(KeyType.CHAR, "/", 1f, listOf("\\", ":"))
            FieldKind.TEXT -> Key(KeyType.CHAR, comma, 1f, listOf(";", ":", "-", "\"", if (lang == "ar") "," else "Ø"))
        }
        val period = when (field) {
            FieldKind.TEXT -> when (lang) {
                "zh" -> Key(KeyType.CHAR, "ã", 1f, listOf(".", "ï¼", "ï¼", "ã", "â¦"))
                "hi" -> Key(KeyType.CHAR, "à¥¤", 1f, listOf(".", "?", "!", "à¥¥", "â¦"))
                else -> Key(KeyType.CHAR, ".", 1f, listOf("â¦", "?", "!", "'", if (lang == "ar") "Ø" else "Â¿"))
            }
            else -> Key(KeyType.CHAR, ".", 1f, listOf(".com", ".net", ".org", ".gh"))
        }
        bottom += left
        bottom += Key(KeyType.SPACE, spaceLabel(lang), Key.FLEX)
        bottom += period
        bottom += Key(KeyType.RETURN, width = 2f)
        rows += KeyRow(bottom)

        // Settings, emoji, globe, mic, clipboard, translator and iBoard AI share the capsule under the keys.
        if (utilityRow && dock.isNotEmpty()) rows += KeyRow(dock, isUtility = true)

        return KeyboardLayout(rows, units, rtl = lang == "ar", lang = lang)
    }

    private fun punctuationRow(left: Key, units: Float, lang: String): KeyRow {
        val p = chars(". , ? ! '", lang).map { it.copy(width = 1.4f) }
        val side = (units - 7f) / 2f
        return KeyRow(listOf(left.copy(width = side)) + p + Key(KeyType.BACKSPACE, width = side))
    }

    private fun numberRowKeys(lang: String): List<Key> {
        val sym = listOf("!", "@", "#", "\$", "%", "^", "&", "*", "(", ")")
        val arabicDigits = "Ù¡Ù¢Ù£Ù¤Ù¥Ù¦Ù§Ù¨Ù©Ù "
        return "1234567890".mapIndexed { i, c ->
            val alts = mutableListOf(sym[i])
            if (lang == "ar") alts += arabicDigits[i].toString()
            if (lang == "hi") alts += "à¥§à¥¨à¥©à¥ªà¥«à¥¬à¥­à¥®à¥¯à¥¦"[i].toString()
            when (c) {
                '1' -> alts += listOf("Â¹", "Â½", "â", "Â¼")
                '2' -> alts += listOf("Â²", "â")
                '3' -> alts += listOf("Â³", "Â¾")
                '0' -> alts += listOf("Â°", "â")
            }
            Key(KeyType.CHAR, c.toString(), 1f, alts)
        }
    }

    private fun spaceLabel(lang: String) = when (lang) {
        "es" -> "espacio"
        "fr" -> "espace"
        "pt" -> "espaÃ§o"
        "de" -> "Leerzeichen"
        "ar" -> "ÙØ³Ø§ÙØ©"
        "tr" -> "boÅluk"
        "it" -> "spazio"
        "pl" -> "spacja"
        "da" -> "mellemrum"
        "cs" -> "mezera"
        "hi" -> "à¤¸à¥à¤ªà¥à¤¸"
        "ko" -> "ì¤íì´ì¤"
        "zh" -> "ç©ºæ ¼"
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
            "de" -> listOf("Ãffnen", "Suchen", "Senden", "Weiter", "Fertig")
            "ar" -> listOf("Ø§ÙØªÙØ§Ù", "Ø¨Ø­Ø«", "Ø¥Ø±Ø³Ø§Ù", "Ø§ÙØªØ§ÙÙ", "ØªÙ")
            "tr" -> listOf("git", "ara", "gÃ¶nder", "sonraki", "bitti")
            "it" -> listOf("vai", "cerca", "invia", "avanti", "fine")
            "pl" -> listOf("idÅº", "szukaj", "wyÅlij", "dalej", "gotowe")
            "da" -> listOf("gÃ¥", "sÃ¸g", "send", "nÃ¦ste", "OK")
            "cs" -> listOf("jdi", "hledat", "odeslat", "dalÅ¡Ã­", "hotovo")
            "hi" -> listOf("à¤à¤¾à¤à¤", "à¤à¥à¤à¥à¤", "à¤­à¥à¤à¥à¤", "à¤à¤à¤²à¤¾", "à¤¹à¥ à¤à¤¯à¤¾")
            "ko" -> listOf("ì´ë", "ê²ì", "ë³´ë´ê¸°", "ë¤ì", "ìë£")
            "zh" -> listOf("åå¾", "æç´¢", "åé", "ä¸ä¸é¡¹", "å®æ")
            else -> listOf("go", "search", "send", "next", "done")
        }
        return words[idx]
    }

    private val latinAlts: Map<String, List<String>> = mapOf(
        "a" to listOf("Ã ", "Ã¡", "Ã¢", "Ã¤", "Ã£", "Ã¥", "Ã¦", "Ä", "Ä"),
        "c" to listOf("Ã§", "Ä", "Ä"),
        "d" to listOf("Ä"),
        "e" to listOf("Ã©", "Ã¨", "Ãª", "Ã«", "Ä", "Ä", "Ä", "Ä"),
        "g" to listOf("Ä"),
        "i" to listOf("Ã­", "Ã¬", "Ã®", "Ã¯", "Ä«", "Ä¯", "Ä±"),
        "n" to listOf("Ã±", "Å", "Å"),
        "o" to listOf("Ã³", "Ã²", "Ã´", "Ã¶", "Ãµ", "Å", "Ã¸", "Å"),
        "r" to listOf("Å"),
        "t" to listOf("Å¥"),
        "u" to listOf("Ãº", "Ã¹", "Ã»", "Ã¼", "Å«", "Å¯"),
        "s" to listOf("Ã", "Å", "Å¡", "Å"),
        "y" to listOf("Ã¿", "Ã½"),
        "z" to listOf("Å¾", "Åº", "Å¼"),
        "l" to listOf("Å"),
        "." to listOf("â¦"),
        "," to listOf("Ø"),
        "?" to listOf("Â¿", "Ø"),
        "!" to listOf("Â¡"),
        "'" to listOf("\"", "â", "â", "â", "â", "Â«", "Â»"),
        "\"" to listOf("â", "â", "â", "Â«", "Â»"),
        "-" to listOf("â", "â", "â¢"),
        "\$" to listOf("âµ", "â¬", "Â£", "Â¥", "â¦", "Â¢", "â¹"),
        "&" to listOf("Â§"),
        "/" to listOf("\\"),
        "%" to listOf("â°"),
    )

    private val arabicAlts: Map<String, List<String>> = mapOf(
        "Ø§" to listOf("Ø£", "Ø¥", "Ø¢", "Ù±"),
        "Ù" to listOf("Ø¦", "Ù"),
        "Ù" to listOf("Ø¤"),
        "Ù" to listOf("Ø©"),
        "Ø¡" to listOf("Ø¦", "Ø¤", "Ø£", "Ø¥"),
        "Ù" to listOf("Ú¯"),
        "Ù" to listOf("Ú¤"),
        "Ø¬" to listOf("Ú"),
        "Ø¨" to listOf("Ù¾"),
    )

    private val hindiAlts: Map<String, List<String>> = mapOf(
        "à¥" to listOf("à¤"), "à¥" to listOf("à¤"), "à¤¾" to listOf("à¤"), "à¥" to listOf("à¤"), "à¥" to listOf("à¤"),
        "à¥" to listOf("à¤", "à¥", "à¤"), "à¥" to listOf("à¤", "à¥"), "à¤¿" to listOf("à¤"), "à¥" to listOf("à¤"),
        "à¥" to listOf("à¥", "à¤", "à¤¼"), "à¤¬" to listOf("à¤­"), "à¤¹" to listOf("à¤"), "à¤" to listOf("à¤", "à¤à¤¼"),
        "à¤¦" to listOf("à¤§"), "à¤" to listOf("à¤", "à¤à¥à¤", "à¤à¤¼"), "à¤¡" to listOf("à¤¢", "à¤¡à¤¼", "à¤¢à¤¼"), "à¤ª" to listOf("à¤«", "à¤«à¤¼"),
        "à¤°" to listOf("à¤", "à¥", "à¤¤à¥à¤°", "à¤¶à¥à¤°"), "à¤" to listOf("à¤", "à¤à¥à¤·", "à¤à¤¼", "à¤à¤¼"), "à¤¤" to listOf("à¤¥", "à¤¤à¥à¤°"),
        "à¤" to listOf("à¤"), "à¤" to listOf("à¤ "), "à¤" to listOf("à¤", "à¤"), "à¤¨" to listOf("à¤£", "à¤", "à¤"),
        "à¤¸" to listOf("à¤·"), "à¤¶" to listOf("à¤·", "à¤¶à¥à¤°"), "à¤²" to listOf("à¤³"), "à¤¯" to listOf("à¤¯à¤¼"),
        "à¤" to listOf("à¤", "à¤à¤", "à¤à¤", "à¥"),
    )

    private fun alternates(label: String, lang: String): List<String> {
        if (lang == "ar") arabicAlts[label]?.let { return it }
        if (lang == "hi") return hindiAlts[label] ?: emptyList()
        if (lang == "ko") return emptyList()
        val base = latinAlts[label] ?: return emptyList()
        // Put the accents that matter most for the language first.
        val pref = when (lang) {
            "fr" -> "Ã©Ã¨ÃªÃ«Ã Ã¢Ã§Ã®Ã¯Ã´ÅÃ¹Ã»Ã¼"
            "es" -> "Ã¡Ã©Ã­Ã³ÃºÃ±Ã¼"
            "pt" -> "Ã£Ã¡Ã¢Ã Ã§Ã©ÃªÃ­Ã³Ã´ÃµÃº"
            "de" -> "Ã¤Ã¶Ã¼Ã"
            "tr" -> "Ã§ÄÄ±Ã¶ÅÃ¼"
            "it" -> "Ã Ã¨Ã©Ã¬Ã²Ã¹"
            "pl" -> "ÄÄÄÅÅÃ³ÅÅºÅ¼"
            "da" -> "Ã¦Ã¸Ã¥Ã©"
            "cs" -> "Ã¡ÄÄÃ©ÄÃ­ÅÃ³ÅÅ¡Å¥ÃºÅ¯Ã½Å¾"
            else -> ""
        }
        return base.sortedBy { c -> pref.indexOf(c).let { if (it < 0) 99 else it } }
    }
}

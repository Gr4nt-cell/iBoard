package com.gr4nt.iboard

enum class KeyType { CHAR, SHIFT, BACKSPACE, TO_NUMBER_PAD, TO_SYMBOLS, TO_LETTERS, TO_MORE_SYMBOLS, EMOJI, GLOBE, MIC, SPACE, RETURN, CLIPBOARD, SPACER, SETTINGS, TRANSLATE }

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
        "es" to listOf("q w e r t y u i o p", "a s d f g h j k l ñ", "z x c v b n m"),
        "pt" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "fr" to listOf("a z e r t y u i o p", "q s d f g h j k l m", "w x c v b n '"),
        "de" to listOf("q w e r t z u i o p ü", "a s d f g h j k l ö ä", "y x c v b n m"),
        "ar" to listOf("ض ص ق ف غ ع ه خ ح ج", "ش س ي ب ل ا ت ن م ك ة", "ء ظ ط ذ د ز ر و ى"),
        "tr" to listOf("q w e r t y u ı o p ğ ü", "a s d f g h j k l ş i", "z x c v b n m ö ç"),
        "it" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "pl" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
        "da" to listOf("q w e r t y u i o p å", "a s d f g h j k l æ ø", "z x c v b n m"),
        "cs" to listOf("q w e r t z u i o p ú", "a s d f g h j k l ů", "y x c v b n m"),
        "hi" to listOf("ौ ै ा ी ू ब ह ग द ज ड", "ो े ् ि ु प र क त च ट", "ं म न व ल स य श अ"),
        "ko" to listOf("ㅂ ㅈ ㄷ ㄱ ㅅ ㅛ ㅕ ㅑ ㅐ ㅔ", "ㅁ ㄴ ㅇ ㄹ ㅎ ㅗ ㅓ ㅏ ㅣ", "ㅋ ㅌ ㅊ ㅍ ㅠ ㅜ ㅡ"),
        "zh" to listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m"),
    )

    /** Languages without capital letters: no auto-capitalization. Hindi has no shift key at all. */
    val NO_CASE = setOf("ar", "hi", "ko", "zh")
    val NO_SHIFT_KEY = setOf("ar", "hi")

    /** Korean double consonants and diphthongs on shift. */
    private val koreanShift = mapOf(
        "ㅂ" to "ㅃ", "ㅈ" to "ㅉ", "ㄷ" to "ㄸ", "ㄱ" to "ㄲ", "ㅅ" to "ㅆ", "ㅐ" to "ㅒ", "ㅔ" to "ㅖ",
    )

    /** What a letter key types (and shows) with shift on. */
    fun shifted(label: String, lang: String): String {
        if (label.length != 1) return label
        if (lang == "ko") return koreanShift[label] ?: label
        if (lang == "hi" || lang == "ar") return label
        if (lang == "tr") { if (label == "i") return "İ"; if (label == "ı") return "I" }
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
                    if (lang == "ar") listOf("٠١٢٣٤٥٦٧٨٩"[c.digitToInt()].toString()) else emptyList())
                rows += KeyRow(listOf(Key(KeyType.CHAR, "+", 0.9f)) + "123".map(::digit) + Key(KeyType.BACKSPACE, width = 1.5f))
                rows += KeyRow(listOf(Key(KeyType.CHAR, "-", 0.9f)) + "456".map(::digit) + Key(KeyType.CHAR, "/", 1.5f))
                rows += KeyRow(listOf(Key(KeyType.TO_SYMBOLS, "#+=", 0.9f)) + "789".map(::digit) + Key(KeyType.CHAR, "*", 1.5f))
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
                rows += KeyRow(chars("_ \\ | ~ < > € £ ¥ •", lang))
                rows += punctuationRow(Key(KeyType.TO_SYMBOLS, "123"), units, lang)
                if (numberRow) rows.add(1, KeyRow(chars("₵ ₦ ¢ § ° ` ¿ ¡ « »", lang)))
            }
        }

        // Bottom row: 123 · , · space · . · return  (emoji moves to the glass capsule below)
        val bottom = mutableListOf<Key>()
        bottom += if (page == Page.LETTERS) Key(KeyType.TO_NUMBER_PAD, "123", 1.25f)
        else Key(KeyType.TO_LETTERS, if (lang == "ar") "أب" else "ABC", 1.25f)
        if (!utilityRow && free(KeyType.EMOJI)) bottom += Key(KeyType.EMOJI, width = 1.1f)
        // The globe only appears when there's more than one language to switch between.
        if ((showGlobe || !utilityRow) && free(KeyType.GLOBE)) bottom += Key(KeyType.GLOBE, width = 1.1f)
        if (!utilityRow && free(KeyType.MIC)) bottom += Key(KeyType.MIC, width = 1.1f)
        val comma = when (lang) { "ar" -> "،"; "zh" -> "，"; else -> "," }
        val left = when (field) {
            FieldKind.EMAIL -> Key(KeyType.CHAR, "@", 1f)
            FieldKind.URL -> Key(KeyType.CHAR, "/", 1f, listOf("\\", ":"))
            FieldKind.TEXT -> Key(KeyType.CHAR, comma, 1f, listOf(";", ":", "-", "\"", if (lang == "ar") "," else "،"))
        }
        val period = when (field) {
            FieldKind.TEXT -> when (lang) {
                "zh" -> Key(KeyType.CHAR, "。", 1f, listOf(".", "？", "！", "、", "…"))
                "hi" -> Key(KeyType.CHAR, "।", 1f, listOf(".", "?", "!", "॥", "…"))
                else -> Key(KeyType.CHAR, ".", 1f, listOf("…", "?", "!", "'", if (lang == "ar") "؟" else "¿"))
            }
            else -> Key(KeyType.CHAR, ".", 1f, listOf(".com", ".net", ".org", ".gh"))
        }
        bottom += left
        bottom += Key(KeyType.SPACE, spaceLabel(lang), Key.FLEX)
        bottom += period
        bottom += Key(KeyType.RETURN, width = 2f)
        rows += KeyRow(bottom)

        // Settings, emoji, globe, mic, clipboard and translator share one Liquid Glass capsule under the keys.
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
        val arabicDigits = "١٢٣٤٥٦٧٨٩٠"
        return "1234567890".mapIndexed { i, c ->
            val alts = mutableListOf(sym[i])
            if (lang == "ar") alts += arabicDigits[i].toString()
            if (lang == "hi") alts += "१२३४५६७८९०"[i].toString()
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
        "tr" -> "boşluk"
        "it" -> "spazio"
        "pl" -> "spacja"
        "da" -> "mellemrum"
        "cs" -> "mezera"
        "hi" -> "स्पेस"
        "ko" -> "스페이스"
        "zh" -> "空格"
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
            "tr" -> listOf("git", "ara", "gönder", "sonraki", "bitti")
            "it" -> listOf("vai", "cerca", "invia", "avanti", "fine")
            "pl" -> listOf("idź", "szukaj", "wyślij", "dalej", "gotowe")
            "da" -> listOf("gå", "søg", "send", "næste", "OK")
            "cs" -> listOf("jdi", "hledat", "odeslat", "další", "hotovo")
            "hi" -> listOf("जाएँ", "खोजें", "भेजें", "अगला", "हो गया")
            "ko" -> listOf("이동", "검색", "보내기", "다음", "완료")
            "zh" -> listOf("前往", "搜索", "发送", "下一项", "完成")
            else -> listOf("go", "search", "send", "next", "done")
        }
        return words[idx]
    }

    private val latinAlts: Map<String, List<String>> = mapOf(
        "a" to listOf("à", "á", "â", "ä", "ã", "å", "æ", "ā", "ą"),
        "c" to listOf("ç", "ć", "č"),
        "d" to listOf("ď"),
        "e" to listOf("é", "è", "ê", "ë", "ē", "ė", "ę", "ě"),
        "g" to listOf("ğ"),
        "i" to listOf("í", "ì", "î", "ï", "ī", "į", "ı"),
        "n" to listOf("ñ", "ń", "ň"),
        "o" to listOf("ó", "ò", "ô", "ö", "õ", "œ", "ø", "ō"),
        "r" to listOf("ř"),
        "t" to listOf("ť"),
        "u" to listOf("ú", "ù", "û", "ü", "ū", "ů"),
        "s" to listOf("ß", "ś", "š", "ş"),
        "y" to listOf("ÿ", "ý"),
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

    private val hindiAlts: Map<String, List<String>> = mapOf(
        "ौ" to listOf("औ"), "ै" to listOf("ऐ"), "ा" to listOf("आ"), "ी" to listOf("ई"), "ू" to listOf("ऊ"),
        "ो" to listOf("ओ", "ॉ", "ऑ"), "े" to listOf("ए", "ॅ"), "ि" to listOf("इ"), "ु" to listOf("उ"),
        "्" to listOf("ृ", "ऋ", "़"), "ब" to listOf("भ"), "ह" to listOf("ः"), "ग" to listOf("घ", "ग़"),
        "द" to listOf("ध"), "ज" to listOf("झ", "ज्ञ", "ज़"), "ड" to listOf("ढ", "ड़", "ढ़"), "प" to listOf("फ", "फ़"),
        "र" to listOf("ऋ", "ृ", "त्र", "श्र"), "क" to listOf("ख", "क्ष", "ख़", "क़"), "त" to listOf("थ", "त्र"),
        "च" to listOf("छ"), "ट" to listOf("ठ"), "ं" to listOf("ँ", "ः"), "न" to listOf("ण", "ङ", "ञ"),
        "स" to listOf("ष"), "श" to listOf("ष", "श्र"), "ल" to listOf("ळ"), "य" to listOf("य़"),
        "अ" to listOf("आ", "अं", "अः", "ॐ"),
    )

    private fun alternates(label: String, lang: String): List<String> {
        if (lang == "ar") arabicAlts[label]?.let { return it }
        if (lang == "hi") return hindiAlts[label] ?: emptyList()
        if (lang == "ko") return emptyList()
        val base = latinAlts[label] ?: return emptyList()
        // Put the accents that matter most for the language first.
        val pref = when (lang) {
            "fr" -> "éèêëàâçîïôœùûü"
            "es" -> "áéíóúñü"
            "pt" -> "ãáâàçéêíóôõú"
            "de" -> "äöüß"
            "tr" -> "çğıöşü"
            "it" -> "àèéìòù"
            "pl" -> "ąćęłńóśźż"
            "da" -> "æøåé"
            "cs" -> "áčďéěíňóřšťúůýž"
            else -> ""
        }
        return base.sortedBy { c -> pref.indexOf(c).let { if (it < 0) 99 else it } }
    }
}

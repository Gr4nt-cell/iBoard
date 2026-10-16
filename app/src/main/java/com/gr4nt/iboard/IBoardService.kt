package com.gr4nt.iboard

import android.content.Context
import android.content.Intent
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gr4nt.iboard.clipboard.ClipboardPanel
import com.gr4nt.iboard.clipboard.ClipboardStore
import com.gr4nt.iboard.emoji.EmojiPanel
import com.gr4nt.iboard.predict.AiPredictor
import com.gr4nt.iboard.predict.Dictation
import com.gr4nt.iboard.predict.Dictionary
import com.gr4nt.iboard.predict.Suggester
import com.gr4nt.iboard.predict.Suggestion
import com.gr4nt.iboard.predict.UserModel
import com.gr4nt.iboard.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IBoardService : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var clipStore: ClipboardStore
    private lateinit var ai: AiPredictor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var root: FrameLayout? = null
    private var keyboard: KeyboardView? = null
    private var emojiPanel: EmojiPanel? = null
    private var clipPanel: ClipboardPanel? = null
    private var kbTheme: KeyboardTheme? = null
    private var builtSignature = ""

    private var lang = "en"
    private var page = Page.LETTERS
    private var shift = ShiftState.OFF
    private var fieldKind = FieldKind.TEXT
    private var password = false
    private var incognito = false
    private var noAutoCorrect = false
    private var ownCaps = true

    private var suggester: Suggester? = null
    private var shown: List<Suggestion> = emptyList()
    private var pendingAutoCorrect: String? = null
    private var lastAutoCorrect: Triple<String, String, String>? = null // original, replacement, separator
    private var lastSpaceAt = 0L
    private var spaceAutoInserted = false
    private var pastedClip: String? = null
    private var aiJob: Job? = null
    private var lastContext = ""

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        ai = AiPredictor(prefs)
        clipStore = ClipboardStore(this).apply {
            enabled = prefs.clipboardHistory
            onChanged = { clipPanel?.refresh() }
            start()
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Settings changed while the keyboard is on screen (e.g. the test box): rebuild it live.
        keyboard?.post {
            val night = KeyboardTheme.isNight(this, prefs)
            if (isInputViewShown && prefs.visualSignature(night) != builtSignature) {
                setInputView(onCreateInputView())
                updateLayout()
                refresh()
            }
        }
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        voice?.destroy()
        cancelOffline()
        voiceWorker.execute { whisper?.close(); whisper = null; rewriter.close() }
        voiceWorker.shutdown()
        clipStore.stop()
        suggester?.user?.save()
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val t = KeyboardTheme.resolve(this, prefs)
        kbTheme = t
        com.gr4nt.iboard.emoji.EmojiImages.configure(this, prefs.emojiStyle)
        val frame = FrameLayout(this)
        // A see-through gradient: the app shows behind the keyboard.
        frame.background = t.backgroundDrawable().apply { alpha = (prefs.bgOpacity.coerceIn(0, 100) * 255) / 100 }
        window?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val kv = KeyboardView(this, this).apply {
            theme = t
            showPreview = prefs.keyPopup
            look = KeyboardView.Look(
                solid = prefs.keyStyle != "glass",
                radiusDp = prefs.keyRadius,
                heightPct = prefs.keyHeight,
                textPct = prefs.keyTextSize,
                uppercase = prefs.uppercaseLabels,
                spaceLabel = prefs.spaceLabel,
                stripIcons = prefs.stripIcons,
            )
        }
        frame.addView(kv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, nav)
            insets
        }
        root = frame
        keyboard = kv
        emojiPanel = null
        clipPanel = null
        builtSignature = prefs.visualSignature(t.dark)
        return frame
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        clipStore.enabled = prefs.clipboardHistory
        val night = KeyboardTheme.isNight(this, prefs)
        if (prefs.visualSignature(night) != builtSignature) setInputView(onCreateInputView())

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        password = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        fieldKind = when {
            cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> FieldKind.EMAIL
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            else -> FieldKind.TEXT
        }
        incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        noAutoCorrect = password || fieldKind != FieldKind.TEXT ||
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
            variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME ||
            variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
        val appCaps = info.inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        ownCaps = appCaps == 0 && cls == InputType.TYPE_CLASS_TEXT && fieldKind == FieldKind.TEXT && !password &&
            variation in setOf(
                InputType.TYPE_TEXT_VARIATION_NORMAL,
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            )

        page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) Page.SYMBOLS else Page.LETTERS
        shift = ShiftState.OFF
        lastAutoCorrect = null
        spaceAutoInserted = false
        endEmojiSearch()
        closePanels()
        setLanguage(prefs.currentLanguage, rebuild = false)
        updateLayout()
        refresh()
        keyboard?.animateIn()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        aiJob?.cancel()
        stopVoice()
        endEmojiSearch()
        transformJob?.cancel()
        closeMenu()
        keyboard?.cancelTouches()
        suggester?.user?.save()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        keyboard?.removeCallbacks(refreshRunnable)
        keyboard?.post(refreshRunnable)
    }

    private val refreshRunnable = Runnable { refresh() }

    // ------------------------------------------------------------ layout & language

    private fun setLanguage(code: String, rebuild: Boolean) {
        lang = code
        suggester = loaded[code]
        if (suggester == null) {
            scope.launch {
                val s = withContext(Dispatchers.Default) { loadSuggester(applicationContext, code) }
                if (lang == code) { suggester = s; refresh() }
            }
        }
        if (rebuild) { updateLayout(); refresh() }
    }

    private fun updateLayout() {
        val kv = keyboard ?: return
        val info = currentInputEditorInfo
        kv.layout = Layouts.build(
            lang, page, prefs.numberRow, fieldKind, prefs.languages.size > 1,
            utilityRow = prefs.utilityRow, voice = prefs.voiceTyping,
        )
        kv.languageLabel = Prefs.languageLabel(lang)
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val label = if (noEnterAction) null else Layouts.returnLabel(lang, action)
        kv.returnLabel = label
        kv.returnIcon = if (label != null && action == EditorInfo.IME_ACTION_SEARCH) Icons.Kind.SEARCH else null
        kv.returnAccent = label != null && action in setOf(
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
        )
        kv.shift = if (page == Page.LETTERS) shift else ShiftState.OFF
        kv.aiActive = ai.isConfigured && !incognito && !password
    }

    private fun setPage(p: Page) {
        page = p
        updateLayout()
        if (p == Page.LETTERS) refreshShift()
    }

    // ------------------------------------------------------------ KeyboardView.Listener

    override fun onKey(key: Key) {
        if (menu != Menu.NONE && key.type != KeyType.AI) closeMenu()
        if (emojiQuery != null) {
            when (key.type) {
                KeyType.CHAR -> { emojiQuery!!.append(key.label.lowercase()); searchEmoji() }
                KeyType.SPACE -> { emojiQuery!!.append(' '); searchEmoji() }
                KeyType.RETURN, KeyType.EMOJI -> { endEmojiSearch(); showEmoji() }
                KeyType.TO_SYMBOLS -> setPage(Page.SYMBOLS)
                KeyType.TO_LETTERS -> setPage(Page.LETTERS)
                KeyType.TO_MORE_SYMBOLS -> setPage(Page.MORE_SYMBOLS)
                KeyType.GLOBE -> nextLanguage()
                else -> {}
            }
            return
        }
        when (key.type) {
            KeyType.CHAR -> typeText(key.label)
            KeyType.SPACE -> onSpace()
            KeyType.RETURN -> onReturn()
            KeyType.TO_SYMBOLS -> setPage(Page.SYMBOLS)
            KeyType.TO_LETTERS -> setPage(Page.LETTERS)
            KeyType.TO_MORE_SYMBOLS -> setPage(Page.MORE_SYMBOLS)
            KeyType.EMOJI -> showEmoji()
            KeyType.GLOBE -> nextLanguage()
            KeyType.CLIPBOARD -> showClipboard()
            KeyType.MIC -> toggleVoice()
            KeyType.AI -> toggleAi()
            KeyType.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            else -> {}
        }
    }

    override fun onText(text: String) {
        val q = emojiQuery
        if (q != null) { q.append(text.lowercase()); searchEmoji(); return }
        typeText(text)
    }

    override fun onShift(doubleTap: Boolean) {
        if (lang == "ar") return
        shift = when {
            doubleTap -> ShiftState.LOCKED
            shift == ShiftState.OFF -> ShiftState.ON
            else -> ShiftState.OFF
        }
        keyboard?.shift = shift
    }

    override fun onBackspace(repeat: Int) {
        if (menu != Menu.NONE) closeMenu()
        emojiQuery?.let { q ->
            if (q.isNotEmpty()) { q.setLength(q.length - 1); searchEmoji() }
            return
        }
        val ic = currentInputConnection ?: return
        spaceAutoInserted = false
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1); return
        }
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        // Undo the last autocorrect, like tapping the original word on iOS.
        val lac = lastAutoCorrect
        if (repeat == 0 && lac != null && before.endsWith(lac.second + lac.third)) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(lac.second.length + lac.third.length, 0)
            ic.commitText(lac.first, 1)
            ic.endBatchEdit()
            suggester?.user?.trust(lac.first)
            lastAutoCorrect = null
            return
        }
        lastAutoCorrect = null
        if (before.isEmpty()) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            return
        }
        if (repeat > 14) {
            // Holding delete speeds up to whole words.
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
            ic.deleteSurroundingText(maxOf(1, before.length - i), 0)
            return
        }
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(before)
        val end = before.length
        val start = bi.preceding(end).takeIf { p -> p != BreakIterator.DONE } ?: (end - 1)
        ic.deleteSurroundingText(maxOf(1, end - start), 0)
    }

    override fun onGlobeLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        if (prefs.haptics) keyboard?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onSuggestion(index: Int) {
        val s = shown.getOrNull(index) ?: return
        val ic = currentInputConnection ?: return
        val word = currentWord(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
        feedback(KeyType.CHAR)
        ic.beginBatchEdit()
        when (s.kind) {
            Suggestion.Kind.CLIP -> {
                ic.commitText(s.text, 1)
                pastedClip = s.text
            }
            Suggestion.Kind.PHRASE -> {
                if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
                ic.commitText(s.text + " ", 1)
                spaceAutoInserted = true
            }
            else -> {
                if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
                ic.commitText(s.text + " ", 1)
                spaceAutoInserted = true
                if (s.kind == Suggestion.Kind.LITERAL) suggester?.user?.trust(s.text)
                learn(s.text)
            }
        }
        ic.endBatchEdit()
        lastAutoCorrect = null
        if (page != Page.LETTERS) setPage(Page.LETTERS)
    }

    override fun onClipboardButton() {
        feedback(KeyType.CHAR)
        showClipboard()
    }

    override fun onAiButton() {
        feedback(KeyType.CHAR)
        if (!ai.isConfigured) {
            Toast.makeText(this, "Add an AI API key in iBoard settings to turn on AI predictions", Toast.LENGTH_LONG).show()
        } else ai.lastError?.let {
            Toast.makeText(this, "AI: $it", Toast.LENGTH_LONG).show()
        }
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onKeyFeedback(key: Key) = feedback(key.type)

    /** ✨ in the capsule: turns AI predictions on/off, or opens settings if no API key is set yet. */
    // ------------------------------------------------------------ ✨ AI menu: predictions, translate, fix grammar

    private enum class Menu { NONE, AI, TRANSLATE, UNDO }
    private var menu = Menu.NONE
    private var undo: Pair<String, String>? = null // original text, AI result
    private var transformJob: Job? = null

    private val translateTargets = listOf(
        "EN" to "English", "ES" to "Spanish", "FR" to "French", "PT" to "Portuguese", "DE" to "German",
        "AR" to "Arabic", "中文" to "Simplified Chinese", "HI" to "Hindi", "TWI" to "Twi (Akan)",
    )

    /** ✨ in the capsule opens the AI menu (or settings if no API key is set yet). */
    private fun toggleAi() {
        if (!prefs.aiConsent) {
            // Prominent disclosure first: nothing is sent until the user agrees.
            startActivity(com.gr4nt.iboard.settings.ConsentActivity.intent(this, com.gr4nt.iboard.settings.ConsentActivity.MODE_AI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (prefs.aiKey.isEmpty()) {
            Toast.makeText(this, "Add an AI API key in iBoard settings to use AI features", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (menu == Menu.AI) { closeMenu(); return }
        showMenu(Menu.AI)
    }

    private fun showMenu(m: Menu) {
        menu = m
        val kv = keyboard ?: return
        when (m) {
            Menu.AI -> {
                kv.chipAccent = if (prefs.aiEnabled) 0 else -1
                kv.chips = listOf(if (prefs.aiEnabled) "Predict: On" else "Predict: Off", "Translate", "Fix Grammar", "✕")
            }
            Menu.TRANSLATE -> {
                kv.chipAccent = -1
                kv.chips = listOf("‹") + translateTargets.map { it.first }
            }
            Menu.UNDO -> {
                kv.chipAccent = 1
                kv.chips = listOf("↶ Original", "Done")
            }
            Menu.NONE -> kv.chips = null
        }
    }

    private fun closeMenu() {
        menu = Menu.NONE
        undo = null
        keyboard?.chips = null
    }

    override fun onChip(index: Int) {
        feedback(KeyType.CHAR)
        when (menu) {
            Menu.AI -> when (index) {
                0 -> {
                    val on = !prefs.aiEnabled
                    prefs.sp.edit().putBoolean(Prefs.AI_ENABLED, on).apply()
                    showMenu(Menu.AI)
                    refresh()
                }
                1 -> showMenu(Menu.TRANSLATE)
                2 -> runTransform("Correct the spelling, grammar and punctuation. Do not change the meaning or language.", "Fixing…")
                else -> closeMenu()
            }
            Menu.TRANSLATE -> {
                if (index == 0) { showMenu(Menu.AI); return }
                val (_, language) = translateTargets.getOrNull(index - 1) ?: return
                runTransform("Translate the text into $language. If it is already in $language, return it unchanged.",
                    "Translating to $language…")
            }
            Menu.UNDO -> {
                if (index == 0) undoTransform()
                closeMenu()
            }
            Menu.NONE -> {}
        }
    }

    /** Sends the selected text (or the whole field) to the AI and replaces it with the result. */
    private fun runTransform(instruction: String, status: String) {
        val ic = currentInputConnection ?: return
        if (password) { closeMenu(); return }
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val before = ic.getTextBeforeCursor(8000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(8000, 0)?.toString().orEmpty()
        val source = selected.ifEmpty { before + after }
        if (source.isBlank()) {
            Toast.makeText(this, "Type or select some text first", Toast.LENGTH_SHORT).show()
            closeMenu(); return
        }
        val kv = keyboard ?: return
        kv.chips = null
        kv.statusText = status
        kv.aiBusy = true
        transformJob?.cancel()
        transformJob = scope.launch {
            var err: String? = null
            val result = try { ai.transform(source, instruction) } catch (e: Exception) { err = e.message; null }
            kv.aiBusy = false
            kv.statusText = null
            val conn = currentInputConnection
            if (result.isNullOrBlank() || conn == null) {
                Toast.makeText(this@IBoardService, "AI couldn't do that: ${err?.take(120) ?: "no response"}", Toast.LENGTH_LONG).show()
                closeMenu(); return@launch
            }
            conn.beginBatchEdit()
            if (selected.isNotEmpty()) {
                conn.commitText(result, 1)
            } else {
                // Replace the whole field: text before and after the cursor.
                val b = conn.getTextBeforeCursor(8000, 0)?.length ?: 0
                val a = conn.getTextAfterCursor(8000, 0)?.length ?: 0
                conn.deleteSurroundingText(b, a)
                conn.commitText(result, 1)
            }
            conn.endBatchEdit()
            undo = source to result
            showMenu(Menu.UNDO)
        }
    }

    private fun undoTransform() {
        val (original, result) = undo ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(8000, 0)?.toString().orEmpty()
        if (!before.endsWith(result)) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(result.length, 0)
        ic.commitText(original, 1)
        ic.endBatchEdit()
    }

    override fun onEmojiResult(text: String) {
        feedback(KeyType.CHAR)
        currentInputConnection?.commitText(text, 1)
        com.gr4nt.iboard.emoji.EmojiHistory(this).record(text)
    }

    override fun onEmojiSearchClose() {
        feedback(KeyType.CHAR)
        endEmojiSearch()
        showEmoji()
    }

    // ------------------------------------------------------------ emoji search

    private var emojiQuery: StringBuilder? = null
    private var emojiSearchJob: Job? = null

    private fun startEmojiSearch() {
        closePanels()
        emojiQuery = StringBuilder()
        aiJob?.cancel()
        page = Page.LETTERS
        shift = ShiftState.OFF
        updateLayout()
        keyboard?.emojiQuery = ""
        searchEmoji()
    }

    private fun searchEmoji() {
        val q = emojiQuery?.toString() ?: return
        keyboard?.emojiQuery = q
        emojiSearchJob?.cancel()
        emojiSearchJob = scope.launch {
            val results = withContext(Dispatchers.Default) {
                if (q.isBlank()) com.gr4nt.iboard.emoji.EmojiHistory(this@IBoardService).recents(10)
                else com.gr4nt.iboard.emoji.EmojiData.search(this@IBoardService, q)
            }
            if (emojiQuery?.toString() == q) keyboard?.emojiResults = results
        }
    }

    private fun endEmojiSearch() {
        emojiQuery = null
        emojiSearchJob?.cancel()
        keyboard?.emojiResults = emptyList()
        keyboard?.emojiQuery = null
    }

    // ------------------------------------------------------------ typing

    private fun typeText(raw: String) {
        val ic = currentInputConnection ?: return
        var text = raw
        if (page == Page.LETTERS && shift != ShiftState.OFF && text.length == 1) {
            val up = text.uppercase()
            if (up.length == 1) text = up
        }
        val isPunct = text.length == 1 && text[0] in ".,!?;:)"
        ic.beginBatchEdit()
        if (isPunct) {
            // Punctuation finishes the word: autocorrect it, and pull back a space we inserted.
            applyAutoCorrect(ic, text)?.let { ic.endBatchEdit(); afterType(text); return }
            if (spaceAutoInserted) {
                val b = ic.getTextBeforeCursor(1, 0)
                if (b == " ") ic.deleteSurroundingText(1, 0)
            }
            val word = currentWord(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
            if (word.isNotEmpty()) learn(word)
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()
        lastAutoCorrect = null
        afterType(text)
    }

    private fun afterType(text: String) {
        spaceAutoInserted = false
        if (shift == ShiftState.ON && text.any { it.isLetter() }) {
            shift = ShiftState.OFF
            keyboard?.shift = shift
        }
        if (page != Page.LETTERS && text == "'") setPage(Page.LETTERS)
    }

    /** Replaces the word before the cursor with the pending correction, then adds [separator]. */
    private fun applyAutoCorrect(ic: android.view.inputmethod.InputConnection, separator: String): String? {
        if (!prefs.autoCorrect || noAutoCorrect) return null
        val target = pendingAutoCorrect ?: return null
        val word = currentWord(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
        if (word.isEmpty() || word == target) return null
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(target + separator, 1)
        lastAutoCorrect = Triple(word, target, separator)
        pendingAutoCorrect = null
        learn(target)
        return target
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(3, 0)?.toString() ?: ""
        if (prefs.doubleSpacePeriod && now - lastSpaceAt < 650 && before.length >= 2 &&
            before.last() == ' ' && before[before.length - 2].isLetterOrDigit()
        ) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            lastSpaceAt = 0L
            lastAutoCorrect = null
            spaceAutoInserted = false
            return
        }
        lastSpaceAt = now
        ic.beginBatchEdit()
        val corrected = applyAutoCorrect(ic, " ")
        if (corrected == null) {
            val word = currentWord(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
            if (word.isNotEmpty()) learn(word)
            ic.commitText(" ", 1)
            lastAutoCorrect = null
        }
        ic.endBatchEdit()
        spaceAutoInserted = false
        if (page != Page.LETTERS) setPage(Page.LETTERS)
    }

    private fun onReturn() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val word = currentWord(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
        if (word.isNotEmpty()) learn(word)
        lastAutoCorrect = null
        spaceAutoInserted = false
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val multiline = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if ((opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else if (multiline) {
            ic.commitText("\n", 1)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun learn(word: String) {
        if (!prefs.learnWords || incognito || password) return
        val s = suggester ?: return
        val before = currentInputConnection?.getTextBeforeCursor(120, 0)?.toString() ?: ""
        val tokens = before.trimEnd().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // The word may already be committed; the one before it is the context.
        val prev = if (tokens.lastOrNull()?.trimEnd('.', ',', '!', '?') == word) tokens.getOrNull(tokens.size - 2)
        else tokens.lastOrNull()
        s.user.learn(word, prev?.trim { !it.isLetter() })
    }

    // ------------------------------------------------------------ suggestions

    private fun currentWord(before: String): String {
        var i = before.length
        while (i > 0) {
            val c = before[i - 1]
            if (c.isLetter() || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
                (c == '\'' && i > 1 && before[i - 2].isLetter())
            ) i-- else break
        }
        return before.substring(i).trimStart('\'')
    }

    private fun refresh() {
        if (emojiQuery != null) return
        refreshShift()
        refreshSuggestions()
    }

    private fun refreshShift() {
        val kv = keyboard ?: return
        if (page != Page.LETTERS || lang == "ar" || shift == ShiftState.LOCKED) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        val caps = if (!prefs.autoCap) false else if (ownCaps) {
            val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: ""
            val t = before.trimEnd(' ')
            t.isEmpty() || t.last() in ".!?\n" && before.endsWith(" ") || before.endsWith("\n") || before.isEmpty()
        } else {
            ic.getCursorCapsMode(info.inputType) != 0
        }
        shift = if (caps) ShiftState.ON else ShiftState.OFF
        kv.shift = shift
    }

    private fun refreshSuggestions() {
        val kv = keyboard ?: return
        val ic = currentInputConnection ?: return
        if (password) {
            show(emptyList()); return
        }
        val before = ic.getTextBeforeCursor(320, 0)?.toString() ?: ""
        val word = currentWord(before)
        val head = before.substring(0, before.length - word.length)
        val tokens = head.trimEnd().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val prev = tokens.lastOrNull()?.trim { !it.isLetter() }?.takeIf { it.isNotEmpty() }
        val trimmedHead = head.trimEnd()
        val sentenceStart = trimmedHead.isEmpty() || trimmedHead.last() in ".!?\n"

        val s = suggester
        val local: List<Suggestion>
        pendingAutoCorrect = null
        if (word.isNotEmpty()) {
            if (s == null) local = listOf(Suggestion(word, Suggestion.Kind.LITERAL)) else {
                val r = s.forWord(word, prev, prefs.autoCorrect && !noAutoCorrect, sentenceStart)
                pendingAutoCorrect = r.autoCorrect
                local = r.items
            }
        } else {
            local = s?.nextWords(prev, sentenceStart) ?: emptyList()
        }

        val clip = if (word.isEmpty() && prefs.clipboardHistory) clipStore.recent(90_000)
            ?.takeIf { it.text != pastedClip && !before.endsWith(it.text) } else null

        lastContext = before
        val aiOn = ai.isConfigured && !incognito && fieldKind == FieldKind.TEXT
        kv.aiActive = aiOn
        val cached = if (aiOn) ai.cached(before, lang) else null
        show(merge(word, local, cached, clip))

        aiJob?.cancel()
        kv.aiBusy = false
        if (aiOn && cached == null && (word.isNotEmpty() || before.isNotBlank())) {
            aiJob = scope.launch {
                delay(260)
                kv.aiBusy = true
                val p = ai.predict(before, word, lang)
                kv.aiBusy = false
                if (p != null && lastContext == before) {
                    show(merge(word, local, p, clip))
                }
            }
        }
    }

    private fun merge(
        word: String,
        local: List<Suggestion>,
        aiPred: AiPredictor.Prediction?,
        clip: com.gr4nt.iboard.clipboard.ClipItem?,
    ): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        fun has(t: String) = out.any { it.text.equals(t, ignoreCase = true) && it.kind != Suggestion.Kind.PHRASE }
        val aiWords = aiPred?.words.orEmpty().map { Suggester.applyCase(word, it) }

        if (word.isNotEmpty() && aiWords.isEmpty()) return local.take(3)
        if (word.isNotEmpty()) {
            val sg = suggester
            var auto = pendingAutoCorrect
            val aiTop = aiWords.firstOrNull()
            if (aiTop != null && sg != null && prefs.autoCorrect && !noAutoCorrect) {
                if (aiTop.equals(word, true)) {
                    auto = null // the model says the typed word fits the sentence
                } else {
                    val typedValid = sg.dictionary.contains(word) || sg.user.knows(word)
                    val dist = Dictionary.distance(word.lowercase(), aiTop.lowercase(), sg.neighbors, 2.5f)
                    val isCompletion = Dictionary.normalize(aiTop).startsWith(Dictionary.normalize(word))
                    // Context-aware correction: trust the model when its word is a near miss of what was typed.
                    // A real word is only replaced for a one-key slip ("form" → "from"), never a different word.
                    val maxDist = if (typedValid) 0.75f else 2.0f
                    if (dist <= maxDist && !isCompletion && sg.dictionary.contains(aiTop)) auto = aiTop
                }
            }
            pendingAutoCorrect = auto
            if (auto != null) {
                out += Suggestion(auto, if (auto == aiTop) Suggestion.Kind.AI else Suggestion.Kind.WORD, autoCorrect = true)
                out += Suggestion(word, Suggestion.Kind.LITERAL)
            } else {
                aiWords.firstOrNull { !it.equals(word, true) }?.let { out += Suggestion(it, Suggestion.Kind.AI) }
                local.firstOrNull { it.kind == Suggestion.Kind.LITERAL }?.let { if (!has(it.text)) out += it }
            }
            aiWords.drop(1).forEach { if (!has(it) && !it.equals(word, true) && out.size < 3) out += Suggestion(it, Suggestion.Kind.AI) }
            local.forEach { if (!has(it.text) && out.size < 3 && !it.autoCorrect) out += it.copy(autoCorrect = false) }
        } else {
            clip?.let { out += Suggestion(it.text.replace('\n', ' '), Suggestion.Kind.CLIP) }
            aiWords.forEach { if (!has(it) && out.size < 2) out += Suggestion(it, Suggestion.Kind.AI) }
            local.forEach { if (!has(it.text) && out.size < 3) out += it }
        }
        val phrase = aiPred?.phrase.orEmpty()
        if (phrase.contains(' ') && clip == null) {
            val p = Suggester.applyCase(word.ifEmpty { phrase }, phrase)
            val idx = 2
            if (out.size > idx) out[idx] = Suggestion(p, Suggestion.Kind.PHRASE) else out += Suggestion(p, Suggestion.Kind.PHRASE)
        }
        return out.take(3)
    }

    private fun show(list: List<Suggestion>) {
        shown = list
        keyboard?.suggestions = list
    }

    // ------------------------------------------------------------ panels

    private fun panelHeight(): Int = keyboard?.let { maxOf(it.height, it.desiredHeight().toInt()) } ?: 0

    private fun showEmoji() {
        val frame = root ?: return
        val t = kbTheme ?: return
        closePanels()
        val panel = EmojiPanel(this, t, panelHeight(), object : EmojiPanel.Listener {
            override fun onEmoji(text: String) {
                currentInputConnection?.commitText(text, 1)
                spaceAutoInserted = false
            }
            override fun onBackspace(repeat: Int) = this@IBoardService.onBackspace(minOf(repeat, 10))
            override fun onAbc() = closePanels()
            override fun onFeedback() = feedback(KeyType.CHAR)
            override fun onSearch() = startEmojiSearch()
        })
        emojiPanel = panel
        keyboard?.visibility = View.GONE
        frame.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight().coerceAtLeast(1)))
    }

    private fun showClipboard() {
        val frame = root ?: return
        val t = kbTheme ?: return
        closePanels()
        val h = panelHeight()
        val panel = ClipboardPanel(this, t, h, clipStore,
            onPaste = { text ->
                feedback(KeyType.CHAR)
                currentInputConnection?.commitText(text, 1)
                pastedClip = text
                closePanels()
            },
            onClose = { closePanels() })
        clipPanel = panel
        keyboard?.visibility = View.GONE
        frame.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
    }

    private fun closePanels() {
        emojiPanel?.let { root?.removeView(it) }
        clipPanel?.let { root?.removeView(it) }
        emojiPanel = null
        clipPanel = null
        keyboard?.visibility = View.VISIBLE
    }

    private fun nextLanguage() {
        val langs = prefs.languages
        if (langs.size <= 1) {
            switchToNextInputMethod(false)
            return
        }
        val next = langs[(langs.indexOf(lang) + 1) % langs.size]
        prefs.currentLanguage = next
        suggester?.user?.save()
        setLanguage(next, rebuild = true)
        keyboard?.flashSpaceLabel()
    }

    // ------------------------------------------------------------ feedback

    private fun feedback(type: KeyType) {
        val kv = keyboard
        if (prefs.haptics && kv != null) {
            @Suppress("DEPRECATION")
            kv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        if (prefs.sound) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val fx = when (type) {
                KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.RETURN -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            am.playSoundEffect(fx, -1f)
        }
    }

    // ------------------------------------------------------------ voice typing

    private var voice: android.speech.SpeechRecognizer? = null
    private var listening = false   // the user has dictation switched on (until they tap 🎤 again or go quiet)
    private var dictationChain: Job? = null

    private fun toggleVoice() {
        if (listening) { stopVoice(); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(com.gr4nt.iboard.settings.ConsentActivity.intent(this, com.gr4nt.iboard.settings.ConsentActivity.MODE_MIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (prefs.offlineDictation && models.active(com.gr4nt.iboard.voice.ModelKind.SPEECH, prefs.speechModel) != null &&
            com.gr4nt.iboard.voice.WhisperLib.available()
        ) {
            startOffline(); return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "No speech recognition service on this phone. Download Offline Dictation in iBoard settings.",
                Toast.LENGTH_LONG).show()
            return
        }
        listening = true
        keyboard?.listening = true
        keyboard?.statusText = "Starting…"
        listenOnce()
    }

    /** One recognition pass; keeps going pass after pass, like iOS, until the user stops or stays quiet. */
    private fun listenOnce() {
        val r = voice ?: android.speech.SpeechRecognizer.createSpeechRecognizer(this).also { voice = it }
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { if (listening) keyboard?.statusText = "Listening…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (listening) keyboard?.statusText = "…" }
            override fun onError(error: Int) {
                if (error != android.speech.SpeechRecognizer.ERROR_NO_MATCH &&
                    error != android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    error != android.speech.SpeechRecognizer.ERROR_CLIENT
                ) Toast.makeText(this@IBoardService, "Voice typing error ($error)", Toast.LENGTH_SHORT).show()
                endVoice() // silence or failure ends dictation
            }
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) commitDictation(text)
                if (listening) keyboard?.postDelayed({ if (listening) listenOnce() }, 150) else endVoice()
            }
            override fun onPartialResults(partial: android.os.Bundle?) {
                val text = partial?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank() && listening) keyboard?.statusText = text
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val tag = when (lang) {
            "es" -> "es-ES"; "fr" -> "fr-FR"; "pt" -> "pt-BR"; "de" -> "de-DE"; "ar" -> "ar"; else -> "en-US"
        }
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Ask the recognizer for automatic punctuation and formatting (Android 13+, ignored elsewhere).
            .putExtra("android.speech.extra.ENABLE_FORMATTING", "quality")
            .putExtra("android.speech.extra.HIDE_PARTIAL_TRAILING_PUNCTUATION", true)
            .putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
            .putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2000L)
        r.startListening(intent)
    }

    /**
     * Punctuates a dictated segment (spoken commands, capitals, end marks) and, when AI is set up,
     * lets the model polish punctuation with the surrounding sentence as context. Segments are
     * committed strictly in the order they were spoken.
     */
    private fun commitDictation(raw: String) {
        val prev = dictationChain
        dictationChain = scope.launch {
            prev?.join()
            val ic = currentInputConnection ?: return@launch
            val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: ""
            var out = Dictation.format(raw, lang, before)
            if (prefs.aiConsent && prefs.aiKey.isNotEmpty() && !password && !incognito) {
                keyboard?.statusText = "Punctuating…"
                val context = before.takeLast(300)
                val polished = kotlinx.coroutines.withTimeoutOrNull(4000) {
                    try {
                        ai.transform(out, "The text is dictated speech that will be inserted after: «$context». " +
                            "Fix its punctuation and capitalization, and correct obvious speech-recognition mistakes using that context. " +
                            "Keep the same words, meaning and language. Return only the corrected dictated text, without the context.")
                    } catch (_: Exception) { null }
                }
                if (!polished.isNullOrBlank() && polished.length < out.length * 2 + 20) out = polished
                if (listening) keyboard?.statusText = "Listening…" else keyboard?.statusText = null
            }
            val conn = currentInputConnection ?: return@launch
            val last = conn.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (last.isNotEmpty() && !last.last().isWhitespace() && out.firstOrNull()?.let { it.isLetterOrDigit() } == true) out = " $out"
            conn.commitText(if (out.endsWith("\n")) out else "$out ", 1)
        }
    }

    private fun stopVoice() {
        if (offlineActive) { cancelOffline(); return }
        val wasListening = listening
        listening = false
        if (wasListening) voice?.stopListening() // final results still arrive and get committed
        keyboard?.listening = false
        keyboard?.statusText = null
    }

    private fun endVoice() {
        listening = false
        keyboard?.listening = false
        keyboard?.statusText = null
    }

    // ------------------------------------------------------------ offline dictation (Whisper + Rambler)

    private val models by lazy { com.gr4nt.iboard.voice.ModelStore(this) }
    private val rewriter by lazy { com.gr4nt.iboard.voice.RamblerRewriter(applicationContext) }
    private val voiceWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var whisper: com.gr4nt.iboard.voice.WhisperLib.Model? = null
    private var chunker: com.gr4nt.iboard.voice.PauseChunker? = null
    private val offlineText = StringBuilder()
    @Volatile private var offlineActive = false
    private var offlineSession = 0
    private val releaseRewriter = Runnable { voiceWorker.execute { rewriter.close() } }

    private fun startOffline() {
        val speech = models.active(com.gr4nt.iboard.voice.ModelKind.SPEECH, prefs.speechModel) ?: return
        val session = ++offlineSession
        offlineActive = true
        listening = true
        synchronized(offlineText) { offlineText.setLength(0) }
        keyboard?.listening = true
        keyboard?.statusText = if (whisper?.path == models.file(speech).path) "Listening…" else "Loading offline speech…"
        val main = android.os.Handler(mainLooper)
        voiceWorker.execute {
            val path = models.file(speech).path
            if (whisper?.path != path) {
                whisper?.close()
                whisper = com.gr4nt.iboard.voice.WhisperLib.load(path)
            }
            if (whisper == null) {
                main.post {
                    Toast.makeText(this, "Couldn't load the offline speech model. Try downloading it again.", Toast.LENGTH_LONG).show()
                    endVoice(); offlineActive = false
                }
                return@execute
            }
            val c = com.gr4nt.iboard.voice.PauseChunker(
                onChunk = { samples -> voiceWorker.execute { transcribeChunk(samples, session) } },
                onLevel = {},
                onLongSilence = { main.post { if (offlineSession == session) stopOffline() } },
            )
            main.post {
                if (!offlineActive || offlineSession != session) return@post
                if (c.start()) {
                    chunker = c
                    keyboard?.statusText = "Listening… speak naturally, tap 🎤 when done"
                } else {
                    Toast.makeText(this, "Microphone is busy", Toast.LENGTH_SHORT).show()
                    endVoice(); offlineActive = false
                }
            }
        }
    }

    /** Runs on the voice worker: speech → text for one chunk, shown live in the bar. */
    private fun transcribeChunk(samples: FloatArray, session: Int) {
        val model = whisper ?: return
        if (session != offlineSession) return
        val context = synchronized(offlineText) { offlineText.takeLast(200).toString() }
        val text = model.transcribe(samples, lang, (com.gr4nt.iboard.voice.WhisperLib.promptFor(lang) + " " + context).trim())
        if (text.isBlank()) return
        val shown = synchronized(offlineText) {
            if (offlineText.isNotEmpty()) offlineText.append(' ')
            offlineText.append(text)
            offlineText.toString()
        }
        keyboard?.post { if (session == offlineSession && (offlineActive || listening)) keyboard?.statusText = shown }
    }

    /** User tapped 🎤 again (or went quiet): finish transcribing, clean up the ramble, insert it. */
    private fun stopOffline() {
        if (!offlineActive) return
        offlineActive = false
        listening = false
        keyboard?.listening = false
        keyboard?.statusText = "Transcribing…"
        val session = offlineSession
        val before = currentInputConnection?.getTextBeforeCursor(400, 0)?.toString() ?: ""
        val c = chunker
        chunker = null
        val main = android.os.Handler(mainLooper)
        voiceWorker.execute {
            c?.stop() // flushes the last chunk onto this same worker queue
            voiceWorker.execute {
                if (session != offlineSession) return@execute
                val raw = synchronized(offlineText) { offlineText.toString().trim() }
                if (raw.isBlank()) { main.post { keyboard?.statusText = null }; return@execute }
                val plain = com.gr4nt.iboard.predict.Dictation.format(raw, lang, before)
                var finalText = com.gr4nt.iboard.voice.RambleRules.clean(raw, lang, before)
                val rewriteModel = if (prefs.ramblerRewrite) models.active(com.gr4nt.iboard.voice.ModelKind.REWRITE, prefs.rewriteModel) else null
                if (rewriteModel != null) {
                    main.post { keyboard?.statusText = "Polishing…" }
                    rewriter.rewrite(models.file(rewriteModel).path, finalText, lang)?.let { finalText = it }
                    main.removeCallbacks(releaseRewriter)
                    main.postDelayed(releaseRewriter, 120_000) // free the model's memory when idle
                }
                main.post {
                    keyboard?.statusText = null
                    if (session != offlineSession) return@post
                    insertDictated(finalText, plain)
                }
            }
        }
    }

    private fun insertDictated(text: String, original: String) {
        val ic = currentInputConnection ?: return
        val last = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        var out = text
        if (last.isNotEmpty() && !last.last().isWhitespace() && out.firstOrNull()?.isLetterOrDigit() == true) out = " $out"
        if (!out.endsWith("\n")) out += " "
        ic.commitText(out, 1)
        if (original.trim() != text.trim()) {
            // Offer the word-for-word transcript, like Rambler's "original" option.
            undo = (if (out.startsWith(" ")) " " else "") + original.trim() + " " to out
            showMenu(Menu.UNDO)
        }
    }

    private fun cancelOffline() {
        offlineActive = false
        offlineSession++
        val c = chunker
        chunker = null
        voiceWorker.execute { c?.stop() }
        endVoice()
    }

    companion object {
        private val loaded = HashMap<String, Suggester>()

        @Synchronized
        fun loadSuggester(context: Context, code: String): Suggester {
            loaded[code]?.let { return it }
            val dict = Dictionary.load(context, code)
            val rows = Layouts.letterRows[code] ?: Layouts.letterRows.getValue("en")
            val s = Suggester(dict, UserModel(context, code), Suggester.neighborsFor(rows))
            loaded[code] = s
            return s
        }

        /** Called from settings after "clear learned words". */
        fun resetLearned(context: Context) {
            synchronized(this) {
                Prefs.ALL_LANGS.forEach { code ->
                    loaded[code]?.user?.clear() ?: UserModel(context, code).clear()
                }
            }
        }
    }
}

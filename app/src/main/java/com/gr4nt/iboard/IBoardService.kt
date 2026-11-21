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
import com.gr4nt.iboard.ai.GeminiNano
import com.gr4nt.iboard.predict.Suggestion
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var root: FrameLayout? = null
    private var floatingHost: FloatingKeyboardHost? = null
    private var liveBlur: LiveKeyboardBlur? = null
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
    private var editorSession: EditorSessionKey? = null
    private var builtLayoutKey = ""
    private var incognito = false
    private var noAutoCorrect = false
    private var ownCaps = true

    private var predictJob: Job? = null
    private var shown: List<Suggestion> = emptyList()
    private var pendingAutoCorrect: String? = null
    private var lastAutoCorrect: Triple<String, String, String>? = null // original, replacement, separator
    private var lastSpaceAt = 0L
    private var spaceAutoInserted = false
    private var pastedClip: String? = null

    // Session IDs isolate the active editor. Late results never reach another field.
    @Volatile private var voiceSession = 0L
    private var speech: com.gr4nt.iboard.voice.NanoSpeech? = null

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun voiceMessage(message: String?) {
        if (message != null && keyboard?.voiceStatus != message) keyboard?.announceForAccessibility(message)
        keyboard?.voiceStatus = message
        keyboard?.voiceActive = message != null
    }

    private fun cancelVoice() {
        speech?.let { it.cancel(); speech = null; currentInputConnection?.finishComposingText() }
        voiceSession++
        voiceMessage(null)
    }

    private fun toggleVoice() {
        finishComposer()
        speech?.let { it.stop(); voiceMessage("Finishing… Tap mic to cancel"); speech = null; return }
        if (password || emojiQuery != null || translateBuf != null) { toast("Dictation is unavailable here"); return }
        if (!prefs.sp.getBoolean("voice_typing", true)) { toast("Enable dictation in Settings"); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, com.gr4nt.iboard.voice.MicrophonePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        startVoice()
    }

    /** Dictation with Gemini Nano speech recognition (AICore): words appear as you speak. */
    private fun startVoice() {
        val session = ++voiceSession
        val connection = currentInputConnection ?: return
        var prefix = ""
        var composing = false
        fun prefixNow(): String {
            val preceding = connection.getTextBeforeCursor(1, 0)?.lastOrNull()
            return if (preceding != null && !preceding.isWhitespace()) " " else ""
        }
        fun live() = session == voiceSession && currentInputConnection === connection
        val recognizer = com.gr4nt.iboard.voice.NanoSpeech(scope, lang,
            onStatus = { msg -> if (live()) voiceMessage(msg) },
            onPartial = { text ->
                if (live()) {
                    if (!composing) { prefix = prefixNow(); composing = true }
                    connection.setComposingText(prefix + text, 1)
                }
            },
            onFinal = { text ->
                if (live() && text.isNotBlank()) {
                    if (!composing) prefix = prefixNow()
                    connection.setComposingText(prefix + text.trim() + " ", 1)
                    connection.finishComposingText()
                    composing = false
                    refresh()
                }
            },
            onEnd = { error ->
                if (session == voiceSession) {
                    if (composing) connection.finishComposingText()
                    speech = null
                    voiceMessage(null)
                    if (error != null) toast(error)
                }
            })
        speech = recognizer
        voiceMessage("Starting dictation…")
        recognizer.start()
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        clipStore = ClipboardStore(this).apply {
            enabled = prefs.clipboardHistory
            onChanged = { clipPanel?.refresh() }
            start()
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!prefs.sp.getBoolean("voice_typing", true)) cancelVoice()
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
        cancelVoice()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        liveBlur?.close()
        clipStore.stop()
        translator?.close()
        GeminiNano.close()
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
            floatingMode = prefs.floatingKeyboard
            showPreview = prefs.keyPopup
            look = KeyboardView.Look(
                solid = prefs.keyStyle != "glass",
                radiusDp = prefs.keyRadius,
                heightPct = prefs.keyHeight,
                textPct = prefs.keyTextSize,
                uppercase = prefs.uppercaseLabels,
                spaceLabel = prefs.spaceLabel,
                stripButtons = prefs.stripButtons,
            )
        }
        frame.addView(kv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val nav = if (prefs.floatingKeyboard) 0 else insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, nav)
            insets
        }
        liveBlur?.close()
        liveBlur = window?.window?.let { LiveKeyboardBlur(this, it, frame, t, prefs.bgOpacity) }
        root = frame
        keyboard = kv
        emojiPanel = null
        clipPanel = null
        builtSignature = prefs.visualSignature(t.dark)
        floatingHost = if (prefs.floatingKeyboard) FloatingKeyboardHost(this, frame, t.dark, prefs.sp) {
            prefs.sp.edit().putBoolean(Prefs.FLOATING_KEYBOARD, false).apply()
        } else null
        configureKeyboardWindow()
        return floatingHost ?: frame
    }

    private fun configureKeyboardWindow() {
        window?.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                if (floatingHost != null) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        configureKeyboardWindow()
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val host = floatingHost ?: return
        val decor = window?.window?.decorView ?: return
        // A floating keyboard overlays the editor instead of resizing it. Only the card consumes touches.
        outInsets.contentTopInsets = decor.height
        outInsets.visibleTopInsets = decor.height
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        val bounds = host.touchBounds()
        outInsets.touchableRegion.set(bounds)
    }

    // Keep the app visible on phones and tablets; never switch to the old fullscreen extract editor.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        cancelVoice()
        super.onStartInputView(info, restarting)
        clipStore.enabled = prefs.clipboardHistory
        val night = KeyboardTheme.isNight(this, prefs)
        val visualChanged = prefs.visualSignature(night) != builtSignature
        if (visualChanged) setInputView(onCreateInputView())
        val session = EditorSessionKey(info.packageName, info.fieldId, info.fieldName, info.inputType, info.imeOptions)
        val sameField = restarting && session.sameField(editorSession)
        if (password && InputSessionPolicy.preserve(restarting, editorSession, session, !visualChanged && keyboard?.layout != null)) {
            // Preserve symbols, manual shift and key positions on secure-editor restarts.
            if (password) { pendingAutoCorrect = null; show(emptyList()); keyboard?.stopEntranceAnimation() }
            return
        }
        editorSession = session

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
        keyboard?.showPreview = prefs.keyPopup && !password
        keyboard?.removeCallbacks(refreshRunnable)
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
            cls == InputType.TYPE_CLASS_DATETIME) Page.NUMBER_PAD else Page.LETTERS
        shift = ShiftState.OFF
        lastAutoCorrect = null
        spaceAutoInserted = false
        endEmojiSearch()
        endTranslate()
        closePanels()
        setLanguage(prefs.currentLanguage, rebuild = false)
        updateLayout()
        refresh()
        if (!password && !sameField) keyboard?.animateIn() else keyboard?.stopEntranceAnimation()
    }

    override fun onFinishInput() {
        cancelVoice()
        clearComposer()
        editorSession = null
        keyboard?.removeCallbacks(refreshRunnable)
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelVoice()
        endTranslate()
        super.onFinishInputView(finishingInput)
        endEmojiSearch()
        keyboard?.cancelTouches()
        closeAiMenu()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The user moved the cursor away from the text being composed: keep it as typed.
        if (composing && (candidatesEnd < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            clearComposer()
        }
        keyboard?.removeCallbacks(refreshRunnable)
        if (!password) keyboard?.post(refreshRunnable)
    }

    private val refreshRunnable = Runnable { refresh() }

    // ------------------------------------------------------------ Korean and Chinese composition

    private val hangul = com.gr4nt.iboard.input.HangulComposer()
    private val pinyin = StringBuilder()
    private var pinyinCands: List<com.gr4nt.iboard.input.PinyinEngine.Candidate> = emptyList()
    private var pinyinEngine: com.gr4nt.iboard.input.PinyinEngine? = null
    private val composing get() = hangul.isActive || pinyin.isNotEmpty()

    /** Languages where predictions are offered but words are never changed automatically. */
    private val noAutoCorrectLangs = setOf("hi", "ko", "zh")

    private fun clearComposer() {
        hangul.reset()
        pinyin.setLength(0)
        pinyinCands = emptyList()
        keyboard?.candidates = emptyList()
    }

    /** Ends Korean/Chinese composition. Chinese commits its best candidates when [convert]. */
    private fun finishComposer(convert: Boolean = false) {
        if (!composing) return
        val ic = currentInputConnection
        if (pinyin.isNotEmpty() && convert && ic != null) {
            var guard = 0
            while (pinyin.isNotEmpty() && pinyinCands.isNotEmpty() && guard++ < 12) applyCandidate(ic, pinyinCands[0])
        }
        ic?.finishComposingText()
        clearComposer()
    }

    private fun updateCandidates() {
        val engine = pinyinEngine ?: com.gr4nt.iboard.input.PinyinEngine.load(applicationContext).also { pinyinEngine = it }
        pinyinCands = engine.candidates(pinyin.toString())
        keyboard?.candidates = pinyinCands.map { it.text }
    }

    private fun applyCandidate(ic: android.view.inputmethod.InputConnection, c: com.gr4nt.iboard.input.PinyinEngine.Candidate) {
        val rest = pinyin.substring(minOf(c.consumes, pinyin.length))
        ic.commitText(c.text, 1)
        pinyin.setLength(0); pinyin.append(rest)
        if (rest.isNotEmpty()) { ic.setComposingText(rest, 1); updateCandidates() }
        else { pinyinCands = emptyList(); keyboard?.candidates = emptyList() }
    }

    override fun onCandidate(index: Int) {
        if (aiMenu) { feedback(KeyType.CHAR); runAiTool(index); return }
        val c = pinyinCands.getOrNull(index) ?: return
        val ic = currentInputConnection ?: return
        feedback(KeyType.CHAR)
        ic.beginBatchEdit()
        applyCandidate(ic, c)
        ic.endBatchEdit()
    }

    /** Handles a key in Korean or Chinese. Returns true if it was used for composition. */
    private fun compose(ic: android.view.inputmethod.InputConnection, text: String): Boolean {
        if (page != Page.LETTERS) return false
        if (lang == "ko" && com.gr4nt.iboard.input.HangulComposer.isJamo(text)) {
            val (commit, comp) = hangul.input(text[0])
            ic.beginBatchEdit()
            if (commit.isNotEmpty()) ic.commitText(commit, 1)
            if (comp.isNotEmpty()) ic.setComposingText(comp, 1)
            ic.endBatchEdit()
            return true
        }
        if (lang == "zh" && text.length == 1 && text[0].lowercaseChar() in 'a'..'z') {
            pinyin.append(text[0].lowercaseChar())
            ic.setComposingText(pinyin, 1)
            updateCandidates()
            return true
        }
        // Anything else ends the syllable (Korean) or converts the pinyin (Chinese) first.
        finishComposer(convert = true)
        return false
    }

    // ------------------------------------------------------------ layout & language

    private fun setLanguage(code: String, rebuild: Boolean) {
        finishComposer()
        lang = code
        if (code == "zh" && pinyinEngine == null) scope.launch {
            val e = withContext(Dispatchers.Default) { com.gr4nt.iboard.input.PinyinEngine.load(applicationContext) }
            pinyinEngine = e
        }
        if (rebuild) { updateLayout(); refresh() }
    }

    private fun updateLayout() {
        val kv = keyboard ?: return
        val info = currentInputEditorInfo
        val layoutKey = listOf(lang, page, prefs.numberRow, fieldKind, prefs.languages.size > 1,
            prefs.utilityRow, prefs.stripButtons.joinToString()).joinToString("|")
        if (kv.layout == null || layoutKey != builtLayoutKey) {
            kv.layout = Layouts.build(
                lang, page, prefs.numberRow, fieldKind, prefs.languages.size > 1,
                utilityRow = prefs.utilityRow,
                stripButtons = prefs.stripButtons.toSet(),
            )
            builtLayoutKey = layoutKey
        }
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
    }

    private fun setPage(p: Page) {
        page = p
        updateLayout()
        if (p == Page.LETTERS) refreshShift()
    }

    // ------------------------------------------------------------ KeyboardView.Listener

    override fun onKey(key: Key) {
        if (key.type == KeyType.MIC) { toggleVoice(); return }
        if (speech != null) cancelVoice()
        // The translate key opens Gemini Nano's tools: translate, fix grammar, rewrite…
        if (key.type == KeyType.TRANSLATE) {
            when {
                translateBuf != null -> endTranslate()
                aiMenu -> closeAiMenu()
                else -> openAiMenu()
            }
            return
        }
        if (aiMenu) closeAiMenu()
        translateBuf?.let { buf ->
            when (key.type) {
                KeyType.CHAR -> {
                    var c = key.label
                    if (page == Page.LETTERS && shift != ShiftState.OFF && c.length == 1) c = Layouts.shifted(c, lang)
                    buf.append(c)
                    if (shift == ShiftState.ON) { shift = ShiftState.OFF; keyboard?.shift = shift }
                    translateSoon()
                }
                KeyType.SPACE -> { buf.append(' '); translateSoon() }
                KeyType.RETURN -> { if (buf.isEmpty()) onReturn() else commitTranslation() }
                KeyType.TO_NUMBER_PAD -> setPage(Page.NUMBER_PAD)
                KeyType.TO_SYMBOLS -> setPage(Page.SYMBOLS)
                KeyType.TO_LETTERS -> setPage(Page.LETTERS)
                KeyType.TO_MORE_SYMBOLS -> setPage(Page.MORE_SYMBOLS)
                KeyType.GLOBE -> nextLanguage()
                KeyType.EMOJI -> { endTranslate(); showEmoji() }
                KeyType.CLIPBOARD -> { endTranslate(); showClipboard() }
                KeyType.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                else -> {}
            }
            return
        }
        if (emojiQuery != null) {
            when (key.type) {
                KeyType.CHAR -> { emojiQuery!!.append(key.label.lowercase()); searchEmoji() }
                KeyType.SPACE -> { emojiQuery!!.append(' '); searchEmoji() }
                KeyType.RETURN, KeyType.EMOJI -> { endEmojiSearch(); showEmoji() }
                KeyType.TO_NUMBER_PAD -> setPage(Page.NUMBER_PAD)
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
            KeyType.TO_NUMBER_PAD -> setPage(Page.NUMBER_PAD)
            KeyType.TO_SYMBOLS -> setPage(Page.SYMBOLS)
            KeyType.TO_LETTERS -> setPage(Page.LETTERS)
            KeyType.TO_MORE_SYMBOLS -> setPage(Page.MORE_SYMBOLS)
            KeyType.EMOJI -> showEmoji()
            KeyType.GLOBE -> nextLanguage()
            KeyType.CLIPBOARD -> showClipboard()
            KeyType.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            else -> {}
        }
    }

    override fun onText(text: String) {
        translateBuf?.let { it.append(text); translateSoon(); return }
        val q = emojiQuery
        if (q != null) { q.append(text.lowercase()); searchEmoji(); return }
        typeText(text)
    }

    override fun onShift(doubleTap: Boolean) {
        if (lang in Layouts.NO_SHIFT_KEY) return
        shift = when {
            doubleTap -> ShiftState.LOCKED
            shift == ShiftState.OFF -> ShiftState.ON
            else -> ShiftState.OFF
        }
        keyboard?.shift = shift
    }

    override fun onBackspace(repeat: Int) {
        translateBuf?.let { buf ->
            if (buf.isNotEmpty()) {
                val bi = BreakIterator.getCharacterInstance(); bi.setText(buf.toString())
                val start = bi.preceding(buf.length).takeIf { it != BreakIterator.DONE } ?: (buf.length - 1)
                buf.setLength(maxOf(0, if (repeat > 14) buf.trimEnd().lastIndexOf(' ').coerceAtLeast(0) else start))
                translateSoon()
                return
            }
        }
        emojiQuery?.let { q ->
            if (q.isNotEmpty()) { q.setLength(q.length - 1); searchEmoji() }
            return
        }
        val ic = currentInputConnection ?: return
        spaceAutoInserted = false
        if (hangul.isActive) {
            hangul.backspace()
            val comp = hangul.composing
            ic.setComposingText(comp, 1)
            if (comp.isEmpty()) { ic.finishComposingText(); hangul.reset() }
            return
        }
        if (pinyin.isNotEmpty()) {
            pinyin.setLength(pinyin.length - 1)
            ic.setComposingText(pinyin, 1)
            if (pinyin.isEmpty()) { ic.finishComposingText(); clearComposer() } else updateCandidates()
            return
        }
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
        finishComposer()
        val word = currentWord(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
        feedback(KeyType.CHAR)
        ic.beginBatchEdit()
        when (s.kind) {
            Suggestion.Kind.CLIP -> {
                ic.commitText(s.text, 1)
                pastedClip = s.text
            }
            else -> {
                if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
                ic.commitText(s.text + " ", 1)
                spaceAutoInserted = true
            }
        }
        ic.endBatchEdit()
        lastAutoCorrect = null
        if (page != Page.LETTERS) setPage(Page.LETTERS)
    }

    override fun onStripButton(type: KeyType) {
        feedback(KeyType.CHAR)
        onKey(Key(type))
    }

    override fun onKeyFeedback(key: Key) = feedback(key.type)



    /** Types an emoji as text. */
    private fun commitEmoji(text: String) {
        finishComposer()
        currentInputConnection?.commitText(text, 1)
    }

    override fun onEmojiResult(text: String) {
        feedback(KeyType.CHAR)
        commitEmoji(text)
        com.gr4nt.iboard.emoji.EmojiHistory(this).record(text)
    }

    override fun onEmojiSearchClose() {
        feedback(KeyType.CHAR)
        endEmojiSearch()
        showEmoji()
    }

    // ------------------------------------------------------------ translator

    private var translateBuf: StringBuilder? = null
    private var translateJob: Job? = null
    private var translateStatus: String? = null
    private var detected: String? = null
    private var translator: com.gr4nt.iboard.translate.TranslatorEngine? = null
    private val tFrom get() = prefs.sp.getString(com.gr4nt.iboard.translate.TranslatorEngine.PREF_FROM, null) ?: lang
    private val tTo get() = prefs.sp.getString(com.gr4nt.iboard.translate.TranslatorEngine.PREF_TO, null) ?: if (lang == "en") "es" else "en"

    private fun showTranslateBar() {
        val buf = translateBuf ?: run { keyboard?.translate = null; return }
        val from = if (tFrom == com.gr4nt.iboard.translate.TranslatorEngine.DETECT && detected != null)
            "${com.gr4nt.iboard.translate.TranslatorEngine.short(detected!!)} ✓" else com.gr4nt.iboard.translate.TranslatorEngine.short(tFrom)
        keyboard?.translate = KeyboardView.Translate(from, com.gr4nt.iboard.translate.TranslatorEngine.short(tTo), buf.toString(), translateStatus)
    }

    private fun startTranslate() {
        finishComposer()
        if (password) { toast("Translation is unavailable in password fields"); return }
        endEmojiSearch(); closePanels()
        currentInputConnection?.finishComposingText()
        translateBuf = StringBuilder()
        translateStatus = null; detected = null
        if (translator == null) translator = com.gr4nt.iboard.translate.TranslatorEngine(applicationContext)
        if (page != Page.LETTERS) setPage(Page.LETTERS)
        showTranslateBar()
        prepareTranslator()
    }

    /** Downloads the language packs ahead of typing, so the first words translate straight away. */
    private fun prepareTranslator() {
        val engine = translator ?: return
        val from = tFrom.takeIf { it != com.gr4nt.iboard.translate.TranslatorEngine.DETECT } ?: return
        val to = tTo
        scope.launch {
            try {
                if (!engine.isDownloaded(from, to)) {
                    translateStatus = "Getting Gemini Nano…"
                    showTranslateBar()
                    engine.download(from, to)
                    translateStatus = null
                    showTranslateBar()
                    translateSoon()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                translateStatus = if (GeminiNano.state == GeminiNano.State.UNAVAILABLE) "Needs Gemini Nano (AICore)" else "Getting Gemini Nano failed"
                showTranslateBar()
            }
        }
    }

    private fun translateSoon() {
        showTranslateBar()
        val buf = translateBuf ?: return
        val text = buf.toString()
        val connection = currentInputConnection ?: return
        translateJob?.cancel()
        if (text.isBlank()) { connection.setComposingText("", 1); detected = null; showTranslateBar(); return }
        translateJob = scope.launch {
            delay(250)
            val engine = translator ?: return@launch
            try {
                var from = tFrom
                if (from == com.gr4nt.iboard.translate.TranslatorEngine.DETECT) {
                    from = engine.detect(text) ?: detected ?: "en"
                    detected = from
                }
                val to = tTo
                if (!engine.isDownloaded(from, to)) {
                    translateStatus = "Getting Gemini Nano…"
                    showTranslateBar()
                    engine.download(from, to)
                }
                val out = engine.translate(text, from, to)
                if (translateBuf?.toString() != text || currentInputConnection !== connection) return@launch
                translateStatus = null
                connection.setComposingText(out, 1)
                showTranslateBar()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                translateStatus = if (GeminiNano.state == GeminiNano.State.UNAVAILABLE) "Needs Gemini Nano" else "Translation failed"
                showTranslateBar()
            }
        }
    }

    /** Keeps the translated text in the app and starts a new sentence in the translator. */
    private fun commitTranslation() {
        translateJob?.cancel()
        val buf = translateBuf ?: return
        val text = buf.toString()
        val engine = translator
        val connection = currentInputConnection ?: return
        scope.launch {
            // Make sure the latest text is translated before it is committed.
            try {
                if (engine != null && text.isNotBlank()) {
                    val from = if (tFrom == com.gr4nt.iboard.translate.TranslatorEngine.DETECT) (engine.detect(text) ?: detected ?: "en") else tFrom
                    if (engine.isDownloaded(from, tTo)) connection.setComposingText(engine.translate(text, from, tTo) + " ", 1)
                }
            } catch (_: Exception) {}
            connection.finishComposingText()
            translateBuf?.setLength(0)
            detected = null
            showTranslateBar()
        }
    }

    private fun endTranslate() {
        translateJob?.cancel()
        if (translateBuf == null) return
        currentInputConnection?.finishComposingText()
        translateBuf = null
        translateStatus = null
        keyboard?.translate = null
        refresh()
    }

    override fun onTranslateBar(action: Int) {
        feedback(KeyType.CHAR)
        when (action) {
            0 -> pickLanguage(source = true)
            2 -> pickLanguage(source = false)
            1 -> {
                val from = if (tFrom == com.gr4nt.iboard.translate.TranslatorEngine.DETECT) (detected ?: lang) else tFrom
                setPair(tTo, from)
            }
            3 -> endTranslate()
        }
    }

    private fun setPair(from: String, to: String) {
        prefs.sp.edit().putString(com.gr4nt.iboard.translate.TranslatorEngine.PREF_FROM, from)
            .putString(com.gr4nt.iboard.translate.TranslatorEngine.PREF_TO, to).apply()
        detected = null; translateStatus = null
        showTranslateBar()
        prepareTranslator()
        translateSoon()
    }

    private fun pickLanguage(source: Boolean) {
        val all = com.gr4nt.iboard.translate.TranslatorEngine.languages
        // The keyboard's own languages first, then everything else.
        val mine = prefs.languages.filter { it in all }
        val codes = (if (source) listOf(com.gr4nt.iboard.translate.TranslatorEngine.DETECT) else emptyList()) + mine + all.filter { it !in mine }
        val names = codes.map { com.gr4nt.iboard.translate.TranslatorEngine.name(it) }.toTypedArray()
        val current = codes.indexOf(if (source) tFrom else tTo)
        val ctx = android.view.ContextThemeWrapper(this,
            if (kbTheme?.dark == true) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle(if (source) "Translate from" else "Translate to")
            .setSingleChoiceItems(names, current) { d, which ->
                d.dismiss()
                val pick = codes[which]
                if (source) setPair(pick, if (pick == tTo) tFrom.takeIf { it != com.gr4nt.iboard.translate.TranslatorEngine.DETECT } ?: "en" else tTo)
                else setPair(if (pick == tFrom) tTo else tFrom, pick)
            }
            .setNegativeButton("Cancel", null)
            .create()
        val token = keyboard?.windowToken ?: return
        dialog.window?.let { w ->
            w.attributes = w.attributes.apply { this.token = token; type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG }
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dialog.show()
    }

    // ------------------------------------------------------------ emoji search

    private var emojiQuery: StringBuilder? = null
    private var emojiSearchJob: Job? = null

    private fun startEmojiSearch() {
        closePanels()
        emojiQuery = StringBuilder()
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
        if (page == Page.LETTERS && shift != ShiftState.OFF && text.length == 1) text = Layouts.shifted(text, lang)
        if (compose(ic, text)) { lastAutoCorrect = null; afterType(text); return }
        if (password) {
            ic.commitText(text, 1)
            lastAutoCorrect = null
            afterType(text)
            return
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
        if (!password && page != Page.LETTERS && text == "'") setPage(Page.LETTERS)
    }

    /** Replaces the word before the cursor with the pending correction, then adds [separator]. */
    private fun applyAutoCorrect(ic: android.view.inputmethod.InputConnection, separator: String): String? {
        if (!prefs.autoCorrect || noAutoCorrect || lang in noAutoCorrectLangs) return null
        val target = pendingAutoCorrect ?: return null
        val word = currentWord(ic.getTextBeforeCursor(64, 0)?.toString() ?: "")
        if (word.isEmpty() || word == target) return null
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(target + separator, 1)
        lastAutoCorrect = Triple(word, target, separator)
        pendingAutoCorrect = null
        return target
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        // Chinese: space picks the first candidate, like iOS.
        if (pinyin.isNotEmpty()) { ic.beginBatchEdit(); finishComposer(convert = true); ic.endBatchEdit(); return }
        finishComposer()
        if (password) {
            ic.commitText(" ", 1)
            lastAutoCorrect = null
            spaceAutoInserted = false
            return
        }
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
            ic.commitText(" ", 1)
            lastAutoCorrect = null
        }
        ic.endBatchEdit()
        spaceAutoInserted = false
        if (page != Page.LETTERS) setPage(Page.LETTERS)
    }

    private fun onReturn() {
        val ic = currentInputConnection ?: return
        // Chinese: return keeps the letters as typed.
        if (pinyin.isNotEmpty()) { finishComposer(); return }
        finishComposer()
        val info = currentInputEditorInfo
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

    // ------------------------------------------------------------ suggestions

    private fun currentWord(before: String): String {
        var i = before.length
        while (i > 0) {
            val c = before[i - 1]
            if (c.isLetter() || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt() ||
                (c in "'’" && i > 1 && before[i - 2].isLetter())
            ) i-- else break
        }
        return before.substring(i).trimStart('\'', '’')
    }

    private fun refresh() {
        if (emojiQuery != null || translateBuf != null) return
        refreshShift()
        refreshSuggestions()
    }

    private fun refreshShift() {
        val kv = keyboard ?: return
        if (password || page != Page.LETTERS || lang in Layouts.NO_CASE || shift == ShiftState.LOCKED) return
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
        keyboard ?: return
        val ic = currentInputConnection ?: return
        predictJob?.cancel()
        pendingAutoCorrect = null
        if (password || lang == "zh") { show(emptyList()); return }
        val before = ic.getTextBeforeCursor(320, 0)?.toString() ?: ""
        val word = currentWord(before)
        val head = before.substring(0, before.length - word.length)
        val clip = if (word.isEmpty() && prefs.clipboardHistory) clipStore.recent(90_000)
            ?.takeIf { it.text != pastedClip && !before.endsWith(it.text) } else null
        val base = buildList {
            if (clip != null) add(Suggestion(clip.text, Suggestion.Kind.CLIP))
            if (word.isNotEmpty()) add(Suggestion(word, Suggestion.Kind.LITERAL))
        }
        // While Gemini Nano works out the next words, keep earlier ones that still match what is typed.
        val keep = if (word.isEmpty()) emptyList() else shown.filter {
            it.kind == Suggestion.Kind.WORD && it.text.startsWith(word, ignoreCase = true) && !it.text.equals(word, ignoreCase = true)
        }
        show((base + keep).take(3))
        if (!prefs.sp.getBoolean(PREF_AI_PREDICT, true) || GeminiNano.state == GeminiNano.State.UNAVAILABLE) return
        if (word.isEmpty() && head.isBlank()) return
        val session = editorSession
        predictJob = scope.launch {
            delay(if (word.isEmpty()) 220 else 120)
            val words = GeminiNano.predict(head, word, lang)
            if (editorSession != session || currentInputConnection !== ic) return@launch
            val ai = words.filter { w -> !w.equals(word, ignoreCase = true) && base.none { it.text.equals(w, ignoreCase = true) } }
                .map { Suggestion(it, Suggestion.Kind.WORD) }
            show((base + ai).take(3))
        }
    }

    // ------------------------------------------------------------ Gemini Nano tools

    private var aiMenu = false
    private var aiJob: Job? = null
    private val aiTools = GeminiNano.Tool.values().toList()

    /** Shows Translate and the writing tools on the suggestion bar. */
    private fun openAiMenu() {
        finishComposer()
        if (password) { toast("AI tools are off in password fields"); return }
        endEmojiSearch(); closePanels()
        aiMenu = true
        keyboard?.candidates = listOf("Translate") + aiTools.map { it.label }
        scope.launch {
            when (GeminiNano.refresh()) {
                GeminiNano.State.UNAVAILABLE -> {
                    closeAiMenu()
                    toast("Gemini Nano isn't available on this phone. It needs a supported phone with AICore and a locked bootloader.")
                }
                GeminiNano.State.DOWNLOADABLE, GeminiNano.State.DOWNLOADING ->
                    toast("Gemini Nano isn't installed yet. Open iBoard Settings › Gemini Nano to get it.")
                else -> {}
            }
        }
    }

    private fun closeAiMenu() {
        if (!aiMenu) return
        aiMenu = false
        keyboard?.candidates = emptyList()
    }

    /** Runs a writing tool on the selected text, or on everything in the field when nothing is selected. */
    private fun runAiTool(index: Int) {
        closeAiMenu()
        if (index == 0) { startTranslate(); return }
        val tool = aiTools.getOrNull(index - 1) ?: return
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val extracted = if (selected == null) ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) else null
        val text = selected ?: extracted?.text?.toString()
            ?: ((ic.getTextBeforeCursor(4000, 0)?.toString() ?: "") + (ic.getTextAfterCursor(4000, 0)?.toString() ?: ""))
        if (text.isBlank()) { toast("Type or select some text first"); return }
        val session = editorSession
        aiJob?.cancel()
        keyboard?.voiceStatus = "${tool.label}…"
        aiJob = scope.launch {
            val out = GeminiNano.apply(tool, text)
            keyboard?.voiceStatus = null
            if (editorSession != session || currentInputConnection !== ic) return@launch
            if (out.isNullOrBlank()) {
                toast(if (GeminiNano.state == GeminiNano.State.AVAILABLE) "Gemini Nano couldn't do that. Try a shorter text."
                    else "Gemini Nano isn't ready on this phone")
                return@launch
            }
            ic.beginBatchEdit()
            if (selected == null) {
                if (extracted?.text != null) {
                    val start = extracted.startOffset
                    ic.setSelection(start, start + extracted.text.length)
                } else {
                    val beforeLen = ic.getTextBeforeCursor(4000, 0)?.length ?: 0
                    val afterLen = ic.getTextAfterCursor(4000, 0)?.length ?: 0
                    ic.deleteSurroundingText(beforeLen, afterLen)
                }
            }
            ic.commitText(out, 1)
            ic.endBatchEdit()
        }
    }

    private fun show(list: List<Suggestion>) {
        shown = list
        keyboard?.suggestions = list
    }

    // ------------------------------------------------------------ panels

    private fun panelHeight(): Int = keyboard?.let { maxOf(it.height, it.desiredHeight().toInt()) } ?: 0

    private fun showEmoji() {
        finishComposer()
        val frame = root ?: return
        val t = kbTheme ?: return
        closePanels()
        val panel = EmojiPanel(this, t, panelHeight(), object : EmojiPanel.Listener {
            override fun onEmoji(text: String) {
                commitEmoji(text)
                spaceAutoInserted = false
            }
            override fun onBackspace(repeat: Int) = this@IBoardService.onBackspace(minOf(repeat, 10))
            override fun onAbc() = closePanels()
            override fun onFeedback() = feedback(KeyType.CHAR)
            override fun onSearch() = startEmojiSearch()
            override fun onMedia(entry: com.gr4nt.iboard.emoji.MediaLibrary.Entry) {
                if (!com.gr4nt.iboard.emoji.MediaLibrary.send(this@IBoardService, entry)) {
                    val what = if (entry.gif) "GIFs" else "stickers"
                    Toast.makeText(this@IBoardService, "This app doesn't accept $what from the keyboard here", Toast.LENGTH_SHORT).show()
                }
            }
        })
        emojiPanel = panel
        keyboard?.visibility = View.GONE
        frame.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight().coerceAtLeast(1)))
    }

    private fun showClipboard() {
        finishComposer()
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

    companion object {
        /** Settings switch for Gemini Nano word prediction. */
        const val PREF_AI_PREDICT = "nano_predict"
    }
}

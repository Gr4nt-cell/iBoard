package com.gr4nt.iboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.gr4nt.iboard.BuildConfig
import com.gr4nt.iboard.IBoardService
import com.gr4nt.iboard.Icons
import com.gr4nt.iboard.Prefs
import kotlinx.coroutines.launch

/** iBoard settings, laid out like the iOS Settings app: grouped cards, colored icons, pushed sub-pages. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var sp: SharedPreferences
    private lateinit var p: IosPalette
    private lateinit var container: FrameLayout
    private val stack = ArrayList<View>()
    private var statusText: TextView? = null
    private var enableRow: TextView? = null
    private var useRow: TextView? = null
    private val d get() = resources.displayMetrics.density

    // iOS system colors for the icon tiles
    private val indigo = 0xFF5856D6.toInt()
    private val pink = 0xFFFF2D55.toInt()
    private val blue = 0xFF007AFF.toInt()
    private val teal = 0xFF30B0C7.toInt()
    private val orange = 0xFFFF9500.toInt()
    private val green = 0xFF34C759.toInt()
    private val purple = 0xFFAF52DE.toInt()
    private val gray = 0xFF8E8E93.toInt()
    private val red = 0xFFFF3B30.toInt()
    private val yellow = 0xFFFFCC00.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ draws edge-to-edge on its own; older versions need the opt-in.
        if (android.os.Build.VERSION.SDK_INT < 35) WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this)
        sp = prefs.sp
        val night = com.gr4nt.iboard.KeyboardTheme.isNight(this, prefs)
        p = IosPalette(com.gr4nt.iboard.KeyboardTheme.isNight(this, prefs))
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night

        container = FrameLayout(this).apply { background = GlassBackdrop(com.gr4nt.iboard.KeyboardTheme.resolve(this@SettingsActivity, prefs).gradient) }
        setContentView(container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        push(mainPage(), animate = false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.size > 1) pop() else finish()
            }
        })
    }

    // ------------------------------------------------------------ navigation

    private fun push(page: View, animate: Boolean = true) {
        stack.lastOrNull()?.let { prev ->
            if (animate) prev.animate().translationX(-container.width * 0.3f).alpha(0.6f).setDuration(260).start()
        }
        container.addView(page)
        stack += page
        if (animate) {
            page.translationX = container.width.toFloat()
            page.animate().translationX(0f).setDuration(260).start()
        }
    }

    private fun pop() {
        val top = stack.removeAt(stack.lastIndex)
        top.animate().translationX(container.width.toFloat()).setDuration(240).withEndAction { container.removeView(top) }.start()
        val prev = stack.last()
        // Refresh values on the page we return to.
        if (stack.size == 1) {
            val fresh = mainPage()
            fresh.translationX = -container.width * 0.3f
            container.removeView(prev)
            container.addView(fresh, 0)
            stack[0] = fresh
            fresh.animate().translationX(0f).alpha(1f).setDuration(240).start()
            (fresh as? ScrollView)?.post { fresh.scrollTo(0, mainScroll) }
        } else prev.animate().translationX(0f).alpha(1f).setDuration(240).start()
    }

    private var mainScroll = 0

    /** Copies a font the user picks into private storage and switches the emoji style to it. */
    private val pickFont = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val dest = com.gr4nt.iboard.emoji.EmojiImages.customFontFile(this@SettingsActivity)
                    val tmp = java.io.File(dest.path + ".tmp")
                    contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    // Make sure Android can actually load it before switching over.
                    android.graphics.Typeface.createFromFile(tmp)
                    tmp.renameTo(dest)
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                sp.edit().putString(Prefs.EMOJI_STYLE, "custom").putLong("emoji_font_stamp", System.currentTimeMillis()).apply()
                toast("Emoji font imported")
            } else toast("That file isn't a font Android can use")
            refreshMain()
        }
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        background = GlassBackdrop(com.gr4nt.iboard.KeyboardTheme.resolve(this@SettingsActivity, prefs).gradient)
        addView(content)
    }

    /** A sub-page with a "‹ Back" button and a centered title, like a pushed iOS screen. */
    private fun subPage(title: String, build: IosList.() -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GlassBackdrop(com.gr4nt.iboard.KeyboardTheme.resolve(this@SettingsActivity, prefs).gradient) }
        val bar = FrameLayout(this)
        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), 0, (12 * d).toInt(), 0)
            addView(IconView(context, Icons.Kind.BACK, p.blue), LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()))
            addView(TextView(context).apply {
                text = "Settings"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(p.blue)
            })
            setOnClickListener { pop() }
        }
        bar.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(p.label)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        back.bringToFront()
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()))
        val list = IosList(this, p)
        list.build()
        col.addView(scroll(list.root), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun choicePage(title: String, entries: List<String>, values: List<String>, key: String, def: String, footer: String? = null) {
        lateinit var page: View
        fun build(): View = subPage(title) {
            section(footer = footer) {
                val current = sp.getString(key, def)
                entries.forEachIndexed { i, label ->
                    checkRow(label, values[i] == current) {
                        sp.edit().putString(key, values[i]).apply()
                        this@SettingsActivity.updateAppearance()
                        val fresh = build()
                        container.removeView(page); container.addView(fresh); stack[stack.lastIndex] = fresh; page = fresh
                    }
                }
            }
        }
        page = build()
        push(page)
    }

    private fun label(entries: List<String>, values: List<String>, value: String?): String =
        entries.getOrNull(values.indexOf(value)) ?: entries.first()

    // ------------------------------------------------------------ main page

    private fun mainPage(): View {
        val l = IosList(this, p)
        l.largeTitle("Settings")
        l.root.addView(TextView(this).apply {
            text = "Your keyboard, beautifully personal."
            setTextColor(p.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(22f), 0, dp(22f), dp(4f))
        })
        hero(l)

        l.section(footer = "Type here to try iBoard. Settings below apply to the keyboard instantly.") {
            custom(EditText(this@SettingsActivity).apply {
                hint = "Try iBoard here…"
                setTextColor(p.label)
                setHintTextColor(p.secondary)
                background = null
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            })
        }

        val themeE = listOf("Automatic", "Light", "Dark"); val themeV = listOf("system", "light", "dark")
        val gradE = listOf("Blossom", "Ocean", "Sunset", "Graphite", "Classic iOS Grey")
        val gradV = listOf("blossom", "ocean", "sunset", "graphite", "classic")
        val styleE = listOf("iOS 27 (Solid)", "Liquid Glass"); val styleV = listOf("solid", "glass")
        val accE = listOf("Blue", "Green", "Purple", "Pink", "Orange", "Teal", "Graphite")
        val accV = listOf("blue", "green", "purple", "pink", "orange", "teal", "graphite")

        l.section("Appearance") {
            navRow(Icons.Kind.SUN, indigo, "Appearance", label(themeE, themeV, prefs.theme)) {
                choicePage("Appearance", themeE, themeV, Prefs.THEME, "system")
            }
            navRow(Icons.Kind.PALETTE, pink, "Background", label(gradE, gradV, prefs.gradient)) {
                choicePage("Background", gradE, gradV, Prefs.GRADIENT, "blossom", "The gradient behind the keys.")
            }
            sliderRow(Icons.Kind.DROP, teal, "Background Opacity", 0, 100, 5, prefs.bgOpacity, "%") { putInt(Prefs.BG_OPACITY, it) }
            navRow(Icons.Kind.KEYCAP, blue, "Key Style", label(styleE, styleV, prefs.keyStyle)) {
                choicePage("Key Style", styleE, styleV, Prefs.KEY_STYLE, "glass")
            }
            navRow(Icons.Kind.RETURN, orange, "Return Key Color", label(accE, accV, prefs.accentColor)) {
                choicePage("Return Key Color", accE, accV, Prefs.ACCENT_COLOR, "blue")
            }
        }

        l.section("Offline intelligence", "Both modules are built in. No internet connection, account or model download is needed.") {
            switchRow(Icons.Kind.MIC, blue, "Offline Dictation", sp.getBoolean("voice_typing", true),
                subtitle = "Whisper Base · 6 keyboard languages") { putBool("voice_typing", it) }
            navRow(Icons.Kind.MIC, teal, "Microphone Access",
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "Allowed" else "Off",
                subtitle = "Tap the mic beside the globe to start") {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                else startActivity(Intent(this@SettingsActivity, com.gr4nt.iboard.voice.MicrophonePermissionActivity::class.java))
            }
            navRow(Icons.Kind.BOOK, indigo, "Word Prediction", "On-device",
                subtitle = "Trained context model + personal vocabulary") {
                AlertDialog.Builder(this@SettingsActivity).setTitle("On-device word prediction")
                    .setMessage("Built-in models use the last two words to rank completions and predict the next word. Keyboard-aware corrections and your optional learned vocabulary work together in all six languages. Personal learning stays on this phone; reset it below.\n\nContext models trained on Tatoeba contributor sentences (CC BY 2.0 FR). https://tatoeba.org/en/downloads")
                    .setPositiveButton("Done", null).show()
            }
        }

        val hasCustom = com.gr4nt.iboard.emoji.EmojiImages.customFontFile(this).exists()
        val emoE = mutableListOf("iOS-style (Built-in)"); val emoV = mutableListOf("bundled")
        if (hasCustom) { emoE += "Imported Font"; emoV += "custom" }
        l.section("Emoji", "An iOS-style emoji font is built in. No extra emoji download is needed. You can import a different font entirely offline.") {
            navRow(Icons.Kind.SMILEY, yellow, "Emoji Style", label(emoE, emoV, prefs.emojiStyle)) {
                choicePage("Emoji Style", emoE, emoV, Prefs.EMOJI_STYLE, "bundled",
                    "The built-in font is included in the app. Imported Font uses the .ttf you picked.")
            }
            actionRow(if (hasCustom) "Replace Emoji Font…" else "Import Emoji Font…", p.blue, Icons.Kind.TEXT_AA, orange) {
                pickFont.launch(arrayOf("*/*"))
            }
            if (hasCustom) actionRow("Remove Imported Font", p.red) {
                com.gr4nt.iboard.emoji.EmojiImages.customFontFile(this@SettingsActivity).delete()
                if (prefs.emojiStyle == "custom") sp.edit().putString(Prefs.EMOJI_STYLE, "bundled").apply()
                toast("Imported font removed")
                refreshMain()
            }
        }

        l.section("Keys") {
            sliderRow(Icons.Kind.HEIGHT, green, "Key Height", 80, 135, 5, prefs.keyHeight, "%") { putInt(Prefs.KEY_HEIGHT, it) }
            sliderRow(Icons.Kind.TEXT_AA, blue, "Letter Size", 80, 130, 5, prefs.keyTextSize, "%") { putInt(Prefs.KEY_TEXT_SIZE, it) }
            sliderRow(Icons.Kind.CORNER, purple, "Corner Roundness", 0, 20, 1, prefs.keyRadius, "") { putInt(Prefs.KEY_RADIUS, it) }
            switchRow(Icons.Kind.SHIFT_ON, gray, "Uppercase Labels", prefs.uppercaseLabels) { putBool(Prefs.UPPERCASE_LABELS, it) }
            switchRow(Icons.Kind.NUM, indigo, "Number Row", prefs.numberRow) { putBool(Prefs.NUMBER_ROW, it) }
            switchRow(Icons.Kind.KEYCAP, teal, "Character Preview", prefs.keyPopup) { putBool(Prefs.KEY_POPUP, it) }
            switchRow(Icons.Kind.KEYBOARD, gray, "Label on Space Bar", prefs.spaceLabel) { putBool(Prefs.SPACE_LABEL, it) }
        }

        l.section("Icon Capsule", "Settings, emoji, languages, microphone and clipboard. All typing and predictions work offline.") {
            switchRow(Icons.Kind.CAPSULE, blue, "Icon Capsule", prefs.utilityRow) { putBool(Prefs.UTILITY_ROW, it) }
            switchRow(Icons.Kind.CLIPBOARD, purple, "Buttons on Suggestion Bar", prefs.stripIcons) { putBool(Prefs.STRIP_ICONS, it) }
        }

        l.section("Typing") {
            switchRow(Icons.Kind.SHIFT, green, "Auto-Capitalization", prefs.autoCap) { putBool(Prefs.AUTO_CAP, it) }
            switchRow(Icons.Kind.CHECK, blue, "Auto-Correction", prefs.autoCorrect) { putBool(Prefs.AUTO_CORRECT, it) }
            switchRow(Icons.Kind.TEXT_AA, orange, "“.” Shortcut", prefs.doubleSpacePeriod) { putBool(Prefs.DOUBLE_SPACE, it) }
            switchRow(Icons.Kind.BOOK, indigo, "Learn My Words", prefs.learnWords) { putBool(Prefs.LEARN, it) }
            switchRow(Icons.Kind.WAVE, pink, "Haptic Feedback", prefs.haptics) { putBool(Prefs.HAPTICS, it) }
            switchRow(Icons.Kind.SPEAKER, gray, "Key Sounds", prefs.sound) { putBool(Prefs.SOUND, it) }
        }
        l.section(footer = "Double-tapping the space bar inserts a period. Learned words stay on this phone.") {
            actionRow("Reset Learned Words", p.red) {
                confirm("Reset learned words?", "Predictions will forget the words and phrases you've typed.", "Reset") {
                    IBoardService.resetLearned(this@SettingsActivity)
                    toast("Learned words cleared")
                }
            }
        }

        val langs = prefs.languages.joinToString(", ") { Prefs.languageLabel(it) }
        l.section("Languages", "Tap 🌐 on the keyboard to switch. Long-press 🌐 to choose another keyboard app.") {
            navRow(Icons.Kind.GLOBE, blue, "Keyboards", langs) { languagesPage() }
        }

        l.section("Clipboard") {
            switchRow(Icons.Kind.CLIPBOARD, yellow, "Clipboard History", prefs.clipboardHistory,
                subtitle = "Keeps your last 40 copies") { putBool(Prefs.CLIP_HISTORY, it) }
        }

        l.section("Privacy", "Typing, prediction and emoji work entirely on this phone. No internet permission. The microphone is used only when you start dictation.") {
            navRow(Icons.Kind.EYE_OFF, blue, "Privacy Policy", null) { AlertDialog.Builder(this@SettingsActivity).setTitle("Offline privacy").setMessage("iBoard has no internet permission. Microphone access is optional and used only for dictation started by you. Audio is processed in memory and is not saved. Dictionaries, emoji, corrections and learned words remain on this phone. Password fields disable suggestions; incognito fields do not teach the dictionary. Clipboard history can be disabled and learned words can be reset here.").setPositiveButton("Done", null).show() }
        }

        l.section("App", "When hidden, open these settings from Settings › System › Keyboard › iBoard.") {
            switchRow(Icons.Kind.EYE_OFF, gray, "Hide App Icon", sp.getBoolean(Prefs.HIDE_ICON, false)) { hide ->
                putBool(Prefs.HIDE_ICON, hide)
                packageManager.setComponentEnabledSetting(
                    ComponentName(this@SettingsActivity, "com.gr4nt.iboard.Launcher"),
                    if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                toast(if (hide) "Icon hidden" else "Icon restored")
            }
        }

        l.root.addView(TextView(this).apply {
            text = "iBoard ${BuildConfig.VERSION_NAME}\nWord lists: FrequencyWords · Context: Tatoeba\nSpeech: OpenAI Whisper / whisper.cpp · Emoji: bundled font"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(p.secondary)
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(28f), dp(20f), 0)
        })

        val sv = scroll(l.root)
        sv.setOnScrollChangeListener { _, _, y, _, _ -> mainScroll = y }
        updateStatus()
        return sv
    }

    /** App-icon card with setup status and the two setup steps, like the Apple ID card. */
    private fun hero(l: IosList) {
        l.section {
            val row = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            }
            row.addView(AppIcon(this@SettingsActivity), LinearLayout.LayoutParams(dp(60f), dp(60f)).apply { marginEnd = dp(14f) })
            val texts = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@SettingsActivity).apply {
                text = "iBoard Keyboard"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(p.label)
            })
            statusText = TextView(this@SettingsActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(p.secondary)
            }
            texts.addView(statusText)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            custom(row)
            enableRow = actionRow("Enable iBoard", p.blue, Icons.Kind.KEYBOARD, green) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            useRow = actionRow("Switch to iBoard", p.blue, Icons.Kind.GLOBE, blue) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
    }

    private fun languagesPage() {
        lateinit var page: View
        fun build(): View = subPage("Keyboards") {
            section(footer = "Choose the keyboards you type in. Each has its own dictionary and predictions.") {
                val chosen = prefs.languages.toMutableSet()
                Prefs.ALL_LANGS.forEach { code ->
                    checkRow("${Prefs.languageLabel(code)}  ·  ${Prefs.languageName(code)}", code in chosen) {
                        if (code in chosen && chosen.size == 1) { toast("Keep at least one keyboard"); return@checkRow }
                        if (code in chosen) chosen -= code else chosen += code
                        sp.edit().putStringSet(Prefs.LANGUAGES, chosen.toSet()).apply()
                        val fresh = build()
                        container.removeView(page); container.addView(fresh); stack[stack.lastIndex] = fresh; page = fresh
                    }
                }
            }
        }
        page = build()
        push(page)
    }

    // ------------------------------------------------------------ helpers

    private fun updateAppearance() {
        p = IosPalette(com.gr4nt.iboard.KeyboardTheme.isNight(this, prefs))
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !p.dark
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !p.dark
        if (::container.isInitialized) container.background = GlassBackdrop(com.gr4nt.iboard.KeyboardTheme.resolve(this, prefs).gradient)
    }

    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Float) = (v * d).toInt()

    private fun confirm(title: String, msg: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action) { _, _ -> onYes() }
            .show()
    }

    private fun editText(title: String, key: String, password: Boolean = false, url: Boolean = false) {
        val input = EditText(this).apply {
            setText(sp.getString(key, "") ?: "")
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or when {
                password -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                url -> InputType.TYPE_TEXT_VARIATION_URI
                else -> 0
            }
            setSelection(text.length)
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input)
        }
        AlertDialog.Builder(this).setTitle(title).setView(frame)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                sp.edit().putString(key, input.text.toString().trim()).apply()
                refreshMain()
            }
            .show()
    }

    private fun refreshMain() {
        if (stack.size != 1) return
        val fresh = mainPage()
        container.removeView(stack[0]); container.addView(fresh); stack[0] = fresh
        (fresh as? ScrollView)?.post { fresh.scrollTo(0, mainScroll) }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) updateAppearance()
        // Pick up changes made elsewhere (consent screen, keyboard ✨ button).
        if (stack.size == 1 && ::container.isInitialized) refreshMain()
        updateStatus()

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    private fun updateStatus() {
        val st = statusText ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        st.text = when {
            current -> "Active and ready"
            enabled -> "Step 2: switch to iBoard"
            else -> "Step 1: enable iBoard"
        }
        enableRow?.apply { text = if (enabled) "Enabled ✓" else "Enable iBoard"; setTextColor(if (enabled) p.secondary else p.blue) }
        useRow?.apply { text = if (current) "In Use ✓" else "Switch to iBoard"; setTextColor(if (current) p.secondary else p.blue) }
    }

    /** The iBoard icon: gradient squircle with a little keyboard. */
    private class AppIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            paint.shader = LinearGradient(0f, 0f, w, w, intArrayOf(0xFFF2B8E6.toInt(), 0xFFC9B6F2.toInt(), 0xFF9CC3F5.toInt()),
                null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, w, w, w * 0.23f, w * 0.23f, paint)
            paint.shader = null
            Icons.draw(canvas, Icons.Kind.KEYBOARD, w / 2f, w / 2f, w * 0.62f, 0xFFFFFFFF.toInt())
        }
    }

}

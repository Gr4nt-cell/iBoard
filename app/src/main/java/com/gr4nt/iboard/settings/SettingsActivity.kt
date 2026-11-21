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
    private val mediaRows = HashMap<com.gr4nt.iboard.emoji.MediaLibrary.Pack, TextView>()
    private val mediaWatcher: () -> Unit = { mediaRows.forEach { (pack, v) -> v.text = mediaValue(pack) } }

    private fun mediaValue(pack: com.gr4nt.iboard.emoji.MediaLibrary.Pack): String {
        val lib = com.gr4nt.iboard.emoji.MediaLibrary
        val pc = lib.progress(pack)
        return when {
            pc != null -> "$pc%"
            lib.installed(this, pack) -> "On phone ✓"
            else -> "Get · ${pack.mb} MB"
        }
    }
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
        // Ask AICore once, then show the Gemini Nano status on the main page.
        if (com.gr4nt.iboard.ai.GeminiNano.state == com.gr4nt.iboard.ai.GeminiNano.State.UNKNOWN) lifecycleScope.launch {
            com.gr4nt.iboard.ai.GeminiNano.refresh()
            if (stack.size == 1) {
                val scrollY = (stack[0] as? ScrollView)?.scrollY ?: 0
                val fresh = mainPage()
                container.removeView(stack[0]); container.addView(fresh, 0); stack[0] = fresh
                (fresh as? ScrollView)?.post { fresh.scrollTo(0, scrollY) }
            }
        }

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

    /** Gemini Nano status, download and the switches for what it does. */
    private fun nanoPage() {
        lateinit var page: View
        var progress: Int? = null
        var busy = false
        fun stateText(st: com.gr4nt.iboard.ai.GeminiNano.State) = when (st) {
            com.gr4nt.iboard.ai.GeminiNano.State.AVAILABLE -> "Ready ✓"
            com.gr4nt.iboard.ai.GeminiNano.State.DOWNLOADING -> progress?.let { "Downloading $it%" } ?: "Downloading…"
            com.gr4nt.iboard.ai.GeminiNano.State.DOWNLOADABLE -> "Not installed"
            com.gr4nt.iboard.ai.GeminiNano.State.UNAVAILABLE -> "Not supported"
            com.gr4nt.iboard.ai.GeminiNano.State.UNKNOWN -> "Checking…"
        }
        fun build(): View = subPage("Gemini Nano") {
            val st = com.gr4nt.iboard.ai.GeminiNano.state
            section(footer = when (st) {
                com.gr4nt.iboard.ai.GeminiNano.State.UNAVAILABLE -> "This phone can't run Gemini Nano. It needs a supported phone (for example Pixel 9 or later, Galaxy S25 or later, or a recent Xiaomi, OPPO, vivo or Honor flagship) with Google's AICore system app and a locked bootloader. Without it, iBoard types normally without AI." +
                    (com.gr4nt.iboard.ai.GeminiNano.lastError?.let { "\n\nDetails: $it" } ?: "")
                else -> "Gemini Nano is Google's on-device model. Android's AICore installs and updates it; iBoard never ships or uploads it, and your text stays on this phone."
            }) {
                navRow(Icons.Kind.SPARKLE, purple, "Status", stateText(st)) {
                    if (busy || st == com.gr4nt.iboard.ai.GeminiNano.State.AVAILABLE) return@navRow
                    busy = true
                    lifecycleScope.launch {
                        val fresh = com.gr4nt.iboard.ai.GeminiNano.refresh()
                        if (fresh == com.gr4nt.iboard.ai.GeminiNano.State.DOWNLOADABLE || fresh == com.gr4nt.iboard.ai.GeminiNano.State.DOWNLOADING) {
                            toast("Getting Gemini Nano. Wi-Fi is recommended.")
                            val ok = com.gr4nt.iboard.ai.GeminiNano.download { pct -> progress = pct; lifecycleScope.launch { refreshNano() } }
                            toast(if (ok) "Gemini Nano is ready" else "Download failed: ${com.gr4nt.iboard.ai.GeminiNano.lastError ?: "try again later"}")
                        }
                        busy = false
                        refreshNano()
                    }
                }
            }
            section("Uses", "Word prediction suggests completions, corrections and next words. The ✨ tools sit behind the translate key: Translate, Fix grammar, Friendly, Professional, Shorter, Longer, Rephrase, Emojify and Summarize. Dictation uses on-device speech recognition.") {
                switchRow(Icons.Kind.BOOK, indigo, "Word Prediction", sp.getBoolean(IBoardService.PREF_AI_PREDICT, true)) { putBool(IBoardService.PREF_AI_PREDICT, it) }
                switchRow(Icons.Kind.MIC, blue, "Dictation", sp.getBoolean("voice_typing", true)) { putBool("voice_typing", it) }
            }
        }
        refreshNano = {
            if (stack.lastOrNull() === page) {
                val fresh = build()
                container.removeView(page); container.addView(fresh); stack[stack.lastIndex] = fresh; page = fresh
            }
        }
        page = build()
        push(page)
        lifecycleScope.launch { com.gr4nt.iboard.ai.GeminiNano.refresh(); refreshNano() }
    }
    private var refreshNano: () -> Unit = {}

    private var stripRow: TextView? = null

    private fun stripValue(): String = prefs.stripButtons.size.let { if (it == 0) "None" else "$it" }

    private fun buttonName(t: com.gr4nt.iboard.KeyType) = when (t) {
        com.gr4nt.iboard.KeyType.SETTINGS -> "Settings"
        com.gr4nt.iboard.KeyType.EMOJI -> "Emoji"
        com.gr4nt.iboard.KeyType.GLOBE -> "Languages"
        com.gr4nt.iboard.KeyType.MIC -> "Dictation"
        com.gr4nt.iboard.KeyType.CLIPBOARD -> "Clipboard"
        else -> "AI Tools (Gemini Nano)"
    }

    private fun buttonIcon(t: com.gr4nt.iboard.KeyType) = when (t) {
        com.gr4nt.iboard.KeyType.SETTINGS -> Icons.Kind.GEAR
        com.gr4nt.iboard.KeyType.EMOJI -> Icons.Kind.EMOJI
        com.gr4nt.iboard.KeyType.GLOBE -> Icons.Kind.GLOBE
        com.gr4nt.iboard.KeyType.MIC -> Icons.Kind.MIC
        com.gr4nt.iboard.KeyType.CLIPBOARD -> Icons.Kind.CLIPBOARD
        else -> Icons.Kind.SPARKLE
    }

    /** Choose which of the six capsule buttons sit on the suggestion bar instead. */
    private fun stripButtonsPage() {
        push(subPage("Suggestion Bar") {
            section(footer = "Turn a button on to move it from the icon capsule to the suggestion bar; turn it off to put it back in the capsule. Two fit on the suggestion bar: the first sits on the left, the second on the right.") {
                val tints = listOf(gray, orange, blue, red, yellow, indigo)
                Prefs.DOCK_BUTTONS.forEachIndexed { i, t ->
                    lateinit var sw: IosSwitch
                    sw = switchRow(buttonIcon(t), tints[i], buttonName(t), t in prefs.stripButtons,
                        subtitle = null) { on ->
                        if (on && prefs.stripButtons.size >= Prefs.MAX_STRIP_BUTTONS) {
                            sw.isOn = false
                            toast("Only two buttons fit on the suggestion bar. Turn one off first.")
                            return@switchRow
                        }
                        prefs.setOnStrip(t, on)
                        stripRow?.text = stripValue()
                    }
                }
            }
        })
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

        l.section("Gemini Nano", "Prediction, dictation, translation and writing tools all run on Gemini Nano on this phone. Phones without Gemini Nano type normally without AI.") {
            navRow(Icons.Kind.SPARKLE, purple, "Gemini Nano", when (com.gr4nt.iboard.ai.GeminiNano.state) {
                com.gr4nt.iboard.ai.GeminiNano.State.AVAILABLE -> "Ready"
                com.gr4nt.iboard.ai.GeminiNano.State.UNAVAILABLE -> "Not supported"
                com.gr4nt.iboard.ai.GeminiNano.State.UNKNOWN -> null
                else -> "Not installed"
            }, subtitle = "Status, download and features") { nanoPage() }
            navRow(Icons.Kind.MIC, teal, "Microphone Access",
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "Allowed" else "Off",
                subtitle = "Tap the mic beside the globe to dictate") {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                else startActivity(Intent(this@SettingsActivity, com.gr4nt.iboard.voice.MicrophonePermissionActivity::class.java))
            }
        }

        l.section("Emoji", "Apps show typed emoji with your phone's own emoji font.") {
            navRow(Icons.Kind.SMILEY, orange, "AURA Emoji", "Built in") {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("AURA Emoji")
                    .setMessage("iBoard's own emoji, generated in the AURA style. The picker shows the emoji drawn so far; more are added with each update.")
                    .setPositiveButton("Done", null).show()
            }
        }

        l.section("Stickers & GIFs", "AURA stickers and animated GIFs for the Stickers and GIFs tabs on the emoji keyboard. Downloaded once from iBoard's GitHub page, then they work offline. Tap one to send it; most chat apps accept them.") {
            mediaRows.clear()
            com.gr4nt.iboard.emoji.MediaLibrary.Pack.values().forEach { pack ->
                val lib = com.gr4nt.iboard.emoji.MediaLibrary
                mediaRows[pack] = navRow(if (pack == com.gr4nt.iboard.emoji.MediaLibrary.Pack.GIFS) Icons.Kind.SPARKLE else Icons.Kind.SMILEY,
                    if (pack == com.gr4nt.iboard.emoji.MediaLibrary.Pack.GIFS) pink else purple, pack.title, mediaValue(pack)) {
                    when {
                        lib.progress(pack) != null -> toast("Downloading…")
                        lib.installed(this@SettingsActivity, pack) -> confirm(pack.title, "Remove ${pack.title} from this phone? You can download them again any time.", "Remove") {
                            lib.remove(this@SettingsActivity, pack)
                        }
                        else -> lib.download(this@SettingsActivity, pack) { ok ->
                            toast(if (ok) "${pack.title} are ready on the emoji keyboard" else "Download failed. Check your connection.")
                        }
                    }
                }
            }
        }

        l.section("Keyboard Position", "Floating mode lets you drag the keyboard by its top handle. Tap Dock on the floating keyboard to return it to the bottom of the screen.") {
            switchRow(Icons.Kind.KEYBOARD, blue, "Floating Keyboard", prefs.floatingKeyboard) { putBool(Prefs.FLOATING_KEYBOARD, it) }
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

        l.section("Icon Capsule", "Settings, emoji, languages, microphone, clipboard and ✨ AI tools. Each button sits either in the capsule under the keys or on the suggestion bar.") {
            switchRow(Icons.Kind.CAPSULE, blue, "Icon Capsule", prefs.utilityRow) { putBool(Prefs.UTILITY_ROW, it) }
            stripRow = navRow(Icons.Kind.CLIPBOARD, purple, "Buttons on Suggestion Bar", stripValue()) { stripButtonsPage() }
        }

        l.section("Typing", "Double-tapping the space bar inserts a period.") {
            switchRow(Icons.Kind.SHIFT, green, "Auto-Capitalization", prefs.autoCap) { putBool(Prefs.AUTO_CAP, it) }
            switchRow(Icons.Kind.TEXT_AA, orange, "“.” Shortcut", prefs.doubleSpacePeriod) { putBool(Prefs.DOUBLE_SPACE, it) }
            switchRow(Icons.Kind.WAVE, pink, "Haptic Feedback", prefs.haptics) { putBool(Prefs.HAPTICS, it) }
            switchRow(Icons.Kind.SPEAKER, gray, "Key Sounds", prefs.sound) { putBool(Prefs.SOUND, it) }
        }
        val langs = prefs.languages.joinToString(", ") { Prefs.languageLabel(it) }
        l.section("Languages", "Tap 🌐 on the keyboard to switch. Long-press 🌐 to choose another keyboard app.") {
            navRow(Icons.Kind.GLOBE, blue, "Keyboards", langs) { languagesPage() }
        }

        l.section("Clipboard") {
            switchRow(Icons.Kind.CLIPBOARD, yellow, "Clipboard History", prefs.clipboardHistory,
                subtitle = "Keeps your last 40 copies") { putBool(Prefs.CLIP_HISTORY, it) }
        }

        l.section("Privacy", "Typing, prediction, dictation, translation and the writing tools run on this phone with Gemini Nano. Nothing you type or say is uploaded. The internet is used only for the optional stickers and GIFs.") {
            navRow(Icons.Kind.EYE_OFF, blue, "Privacy Policy", null) { AlertDialog.Builder(this@SettingsActivity).setTitle("Privacy").setMessage("iBoard's AI features use Gemini Nano, which runs inside Android's AICore system service on this phone. Your text and voice are processed on the phone and are not sent to iBoard or to Google by iBoard. Android installs and updates Gemini Nano itself. iBoard connects to the internet only to download the optional stickers and GIFs. Microphone access is optional and used only for dictation you start; audio is not saved. Password fields turn suggestions and AI tools off. Clipboard history can be turned off here.").setPositiveButton("Done", null).show() }
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
            text = "iBoard ${BuildConfig.VERSION_NAME}\nAI: Gemini Nano (on-device, via Android AICore) · Emoji: AURA Emoji"
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
            section(footer = "Choose the keyboards you type in. Gemini Nano predicts in each of them.") {
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

    override fun onStart() {
        super.onStart()
        com.gr4nt.iboard.emoji.MediaLibrary.watch(mediaWatcher)
        mediaWatcher()
    }

    override fun onStop() {
        com.gr4nt.iboard.emoji.MediaLibrary.unwatch(mediaWatcher)
        super.onStop()
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

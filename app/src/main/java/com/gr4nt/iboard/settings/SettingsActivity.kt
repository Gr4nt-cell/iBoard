package com.gr4nt.iboard.settings

import android.content.Context
import android.content.Intent
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.gr4nt.iboard.BuildConfig
import com.gr4nt.iboard.IBoardService
import com.gr4nt.iboard.Prefs
import com.gr4nt.iboard.predict.AiPredictor
import kotlinx.coroutines.launch

/** Setup steps, a place to try the keyboard, and all settings. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var enableBtn: Button
    private lateinit var switchBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val d = resources.displayMetrics.density
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (night) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
        val subColor = if (night) 0xB3FFFFFF.toInt() else 0x991C1C1E.toInt()
        val cardColor = if (night) 0x26FFFFFF else 0xB3FFFFFF.toInt()

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (8 * d).toInt())
        }
        header.addView(TextView(this).apply {
            text = "iBoard"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(textColor)
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = 18 * d }
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
        }
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(subColor)
        }
        card.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        enableBtn = pillButton("1. Enable") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        switchBtn = pillButton("2. Switch to iBoard") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        buttons.addView(enableBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * d).toInt() })
        buttons.addView(switchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * d).toInt()
        })
        val test = EditText(this).apply {
            hint = "Try iBoard here…"
            setTextColor(textColor)
            setHintTextColor(subColor)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
        }
        card.addView(test, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * d).toInt()
        })
        header.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * d).toInt()
        })
        column.addView(header)

        val container = FrameLayout(this).apply { id = View.generateViewId() }
        column.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(container.id, SettingsFragment()).commit()
        }
    }

    private fun pillButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply {
            setColor(0xFF007AFF.toInt()); cornerRadius = 22 * resources.displayMetrics.density
        }
        setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    private fun updateStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        status.text = when {
            current -> "✓ iBoard is your keyboard. Type below to try it, and switch on AI predictions further down."
            enabled -> "Step 2: choose iBoard as your keyboard."
            else -> "Step 1: turn on iBoard in your phone's keyboard settings."
        }
        enableBtn.alpha = if (enabled) 0.5f else 1f
        switchBtn.alpha = if (current) 0.5f else 1f
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(com.gr4nt.iboard.R.xml.prefs, rootKey)
        val prefs = Prefs(requireContext())

        findPreference<EditTextPreference>(Prefs.AI_KEY)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSingleLine()
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val k = p.text.orEmpty().trim()
                if (k.isEmpty()) "Not set — get one from console.anthropic.com or your provider"
                else "••••••" + k.takeLast(4)
            }
        }
        findPreference<EditTextPreference>(Prefs.AI_MODEL)?.apply {
            setOnBindEditTextListener { it.setSingleLine() }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val m = p.text.orEmpty().trim()
                when {
                    m.isNotEmpty() -> m
                    prefs.aiProvider == "anthropic" -> "${Prefs.DEFAULT_CLAUDE_MODEL} (default)"
                    else -> "Required — e.g. the model name your provider lists"
                }
            }
        }
        findPreference<EditTextPreference>(Prefs.AI_BASE_URL)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.setSingleLine()
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                p.text.orEmpty().trim().ifEmpty { "https://api.openai.com/v1" }
            }
        }
        findPreference<Preference>("ai_test")?.setOnPreferenceClickListener { pref ->
            pref.summary = "Testing…"
            lifecycleScope.launch {
                pref.summary = AiPredictor(Prefs(requireContext())).test()
            }
            true
        }
        findPreference<Preference>("clear_learned")?.setOnPreferenceClickListener {
            IBoardService.resetLearned(requireContext())
            Toast.makeText(requireContext(), "Learned words cleared", Toast.LENGTH_SHORT).show()
            true
        }
        findPreference<androidx.preference.MultiSelectListPreference>(Prefs.LANGUAGES)?.apply {
            summaryProvider = Preference.SummaryProvider<androidx.preference.MultiSelectListPreference> { p ->
                val chosen = Prefs.ALL_LANGS.filter { it in p.values }
                if (chosen.isEmpty()) "English" else chosen.joinToString(" · ") { Prefs.languageLabel(it) } +
                    if (chosen.size > 1) "\nTap 🌐 on the keyboard to switch" else ""
            }
            setOnPreferenceChangeListener { _, value ->
                @Suppress("UNCHECKED_CAST")
                val ok = (value as? Set<String>)?.isNotEmpty() == true
                if (!ok) Toast.makeText(requireContext(), "Keep at least one language", Toast.LENGTH_SHORT).show()
                ok
            }
        }
        findPreference<Preference>("about")?.summary =
            "Version ${BuildConfig.VERSION_NAME}\nWord lists: FrequencyWords (hermitdave) · Emoji data: unicode-emoji-json"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(0)
        listView?.clipToPadding = false
    }
}

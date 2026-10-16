package com.gr4nt.iboard.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gr4nt.iboard.Icons
import com.gr4nt.iboard.Prefs

/**
 * Prominent disclosure shown before iBoard sends any text to an AI provider, or before it asks
 * for the microphone. Nothing is enabled until the user taps the agree button.
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val mode get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_AI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val p = IosPalette(night)
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night

        val ai = mode == MODE_AI
        val provider = if (prefs.aiProvider == "anthropic") "Anthropic (Claude)" else "your OpenAI-compatible service (${hostOf(prefs.aiBaseUrl)})"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(28f), dp(24f), dp(24f))
        }
        col.addView(IconTile(this, if (ai) Icons.Kind.SPARKLE else Icons.Kind.MIC, if (ai) 0xFFAF52DE.toInt() else 0xFFFF3B30.toInt()),
            LinearLayout.LayoutParams(dp(64f), dp(64f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18f) })
        col.addView(TextView(this).apply {
            text = if (ai) "Before you turn on AI features" else "Voice typing uses your microphone"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(p.label)
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = if (ai) "AI word prediction, Translate and Fix Grammar send some of what you type to an AI service. Please read how this works."
            else "When you tap 🎤, iBoard listens and turns your speech into text."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(p.secondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(10f), 0, dp(18f))
        })

        val points = if (ai) listOf(
            Icons.Kind.KEYBOARD to "What is sent: for predictions, up to the last few hundred characters before your cursor; for Translate and Fix Grammar, the text you selected or the whole text field.",
            Icons.Kind.GLOBE to "Where it goes: directly from this phone to $provider, using your own API key. It never passes through an iBoard server.",
            Icons.Kind.EYE_OFF to "Never sent: anything typed in password fields or in fields that ask for no personalized learning, such as incognito tabs.",
            Icons.Kind.BOOK to "The provider handles the text under its own privacy policy. You can turn AI off or withdraw consent any time in Settings.",
        ) else listOf(
            Icons.Kind.MIC to "Speech is recognised by your phone's speech service (for example Google's), under that service's privacy policy.",
            Icons.Kind.EYE_OFF to "iBoard does not record or keep audio. It only listens while the 🎤 button is active.",
        )
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(p.card); cornerRadius = 16 * d }
            setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
        }
        points.forEachIndexed { i, (icon, textValue) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12f), 0, dp(12f))
            }
            row.addView(IconView(this, icon, p.blue), LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(14f); topMargin = dp(2f) })
            row.addView(TextView(this).apply {
                text = textValue
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(p.label)
                setLineSpacing(0f, 1.1f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i > 0) card.addView(android.view.View(this).apply { setBackgroundColor(p.separator) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (0.5f * d).toInt())).apply { marginStart = dp(38f) })
            card.addView(row)
        }
        col.addView(card)

        col.addView(TextView(this).apply {
            text = "Read the full Privacy Policy"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(p.blue)
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
            setOnClickListener { openPrivacyPolicy(this@ConsentActivity) }
        })

        val scroll = ScrollView(this).apply { addView(col); isFillViewport = true }
        val agree = TextView(this).apply {
            text = if (ai) "Agree and Turn On" else "Continue"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(p.blue); cornerRadius = 14 * d }
            setOnClickListener { agree() }
        }
        val decline = TextView(this).apply {
            text = "Not Now"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(p.blue)
            gravity = Gravity.CENTER
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(12f))
            addView(agree, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)))
            addView(decline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)).apply { topMargin = dp(4f) })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun agree() {
        if (mode == MODE_AI) {
            prefs.sp.edit().putBoolean(Prefs.AI_CONSENT, true).putBoolean(Prefs.AI_ENABLED, true).apply()
            Toast.makeText(this, if (prefs.aiKey.isEmpty()) "Now add your API key in Settings › AI Predictions" else "AI features are on ✨",
                Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
        } else {
            prefs.sp.edit().putBoolean(Prefs.MIC_CONSENT, true).apply()
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                setResult(RESULT_OK); finish()
            } else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 9)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (ok) "Microphone allowed — tap 🎤 to dictate" else "Voice typing needs the microphone", Toast.LENGTH_LONG).show()
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun hostOf(url: String) = try { Uri.parse(url).host ?: url } catch (_: Exception) { url }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_AI = "ai"
        const val MODE_MIC = "mic"
        const val PRIVACY_URL = "https://claude.ai/artifact/Wp16Q6BFnsxhLtRE26MpLA"

        fun intent(context: Context, mode: String) =
            Intent(context, ConsentActivity::class.java).putExtra(EXTRA_MODE, mode)

        fun openPrivacyPolicy(context: Context) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(context, PRIVACY_URL, Toast.LENGTH_LONG).show()
            }
        }
    }
}

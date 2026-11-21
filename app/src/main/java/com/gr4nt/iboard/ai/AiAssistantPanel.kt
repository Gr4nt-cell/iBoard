package com.gr4nt.iboard.ai

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gr4nt.iboard.Icons
import com.gr4nt.iboard.KeyboardTheme
import com.gr4nt.iboard.emoji.IconButton

/** On-keyboard writing assistant. Text is only applied after an explicit user tap. */
@SuppressLint("ViewConstructor")
class AiAssistantPanel(
    context: android.content.Context,
    private val theme: KeyboardTheme,
    panelHeight: Int,
    private val source: String,
    private val listener: Listener,
) : FrameLayout(context) {
    interface Listener {
        fun onGenerate(action: OnDeviceAiEngine.Action, source: String)
        fun onApply(text: String)
        fun onPrepareModel()
        fun onClose()
    }

    private val d = resources.displayMetrics.density
    private val status = TextView(context)
    private val result = TextView(context)
    private val actions = ArrayList<TextView>()
    private val apply = pill("Apply result", theme.accent, theme.accentText)

    init {
        background = theme.backgroundDrawable()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = IconButton(context, Icons.Kind.BACK, theme.keyText, theme.specialFill)
        close.setOnClickListener { listener.onClose() }
        header.addView(close, LinearLayout.LayoutParams((42 * d).toInt(), (38 * d).toInt()))
        header.addView(TextView(context).apply {
            text = "iBoard AI"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(theme.keyText)
            setPadding((10 * d).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val prepare = pill("Prepare model", theme.specialFill, theme.keyText)
        prepare.setOnClickListener { listener.onPrepareModel() }
        header.addView(prepare)
        body.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()))

        body.addView(TextView(context).apply {
            text = "Select text first to replace it; otherwise Apply inserts at the cursor. Gemini Nano runs on this phone."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(theme.keySubText)
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
        })
        body.addView(cardText(if (source.isBlank()) "Select text first, or type a sentence before opening iBoard AI." else source))

        val rowOne = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(OnDeviceAiEngine.Action.PROOFREAD, OnDeviceAiEngine.Action.IMPROVE, OnDeviceAiEngine.Action.EXTEND)
            .forEach { action -> rowOne.addView(actionButton(action), weighted()) }
        body.addView(rowOne, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()).apply {
            topMargin = (8 * d).toInt()
        })
        val rowTwo = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(OnDeviceAiEngine.Action.EMOJIFY, OnDeviceAiEngine.Action.GREETING).forEach { action ->
            rowTwo.addView(actionButton(action), weighted())
        }
        body.addView(rowTwo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()).apply {
            topMargin = (6 * d).toInt()
        })

        status.text = "Checking Gemini Nano support…"
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.setTextColor(theme.keySubText)
        status.setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        body.addView(status)
        body.addView(cardText("Your text"))
        result.text = "Generated text will appear here."
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        result.setTextColor(theme.keyText)
        result.setTextIsSelectable(true)
        result.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        result.background = rounded(theme.panelCard, theme.keyStroke)
        body.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * d).toInt()
        })
        apply.isEnabled = false
        apply.alpha = 0.5f
        apply.setOnClickListener { listener.onApply(result.text.toString()) }
        body.addView(apply, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (42 * d).toInt()).apply {
            topMargin = (8 * d).toInt()
        })

        val scroll = ScrollView(context).apply { addView(body) }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight.coerceAtLeast(1)))
    }

    fun setStatus(message: String) {
        status.text = message
    }

    fun setBusy(busy: Boolean) {
        actions.forEach { it.isEnabled = !busy }
        status.text = if (busy) "Generating on this phone…" else status.text
    }

    fun setResult(text: String) {
        result.text = text
        val usable = text.isNotBlank() && text != "Generated text will appear here."
        apply.isEnabled = usable
        apply.alpha = if (usable) 1f else 0.5f
    }

    private fun actionButton(action: OnDeviceAiEngine.Action) = pill(action.title, theme.specialFill, theme.keyText).also { button ->
        actions += button
        button.setOnClickListener {
            if (source.isBlank()) {
                setStatus("Select text first, or type a sentence before opening iBoard AI.")
            } else {
                setBusy(true)
                setResult("")
                listener.onGenerate(action, source)
            }
        }
    }

    private fun cardText(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(theme.keyText)
        maxLines = 4
        setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        background = rounded(theme.panelCard, theme.keyStroke)
    }

    private fun pill(text: String, fill: Int, foreground: Int) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(foreground)
        background = rounded(fill, theme.keyStroke)
        isClickable = true
        isFocusable = true
        setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = 14 * d
        if (stroke != 0) setStroke((1 * d).toInt().coerceAtLeast(1), stroke)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        marginEnd = (5 * d).toInt()
    }
}

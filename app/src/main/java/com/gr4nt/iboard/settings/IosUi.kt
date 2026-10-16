package com.gr4nt.iboard.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.gr4nt.iboard.Icons

/** Colors of iOS's grouped Settings lists, in light and dark. */
class IosPalette(val dark: Boolean) {
    val background = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    val card = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    val pressed = if (dark) 0xFF2C2C2E.toInt() else 0xFFE5E5EA.toInt()
    val separator = if (dark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
    val label = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val secondary = if (dark) 0xFF8E8E93.toInt() else 0xFF6D6D72.toInt()
    val tertiary = if (dark) 0xFF48484A.toInt() else 0xFFC4C4C6.toInt()
    val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val green = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    val switchOff = if (dark) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()
}

/** Builds iOS-style grouped sections: header, rounded card of rows with inset separators, footer. */
class IosList(private val context: Context, val p: IosPalette) {
    private val d = context.resources.displayMetrics.density
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, (40 * d).toInt())
    }
    private var card: LinearLayout? = null
    private var rowsInCard = 0

    fun dp(v: Float) = (v * d).toInt()

    fun largeTitle(text: String) {
        root.addView(TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(p.label)
            setPadding(dp(20f), dp(8f), dp(20f), dp(6f))
        })
    }

    fun section(header: String? = null, footer: String? = null, build: IosList.() -> Unit) {
        if (header != null) {
            root.addView(TextView(context).apply {
                text = header.uppercase()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(p.secondary)
                letterSpacing = 0.02f
                setPadding(dp(36f), dp(22f), dp(20f), dp(7f))
            })
        } else root.addView(View(context), LinearLayout.LayoutParams(1, dp(22f)))
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(p.card); cornerRadius = 14 * d }
            clipToOutline = true
        }
        root.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16f), 0, dp(16f), 0)
        })
        card = c
        rowsInCard = 0
        build()
        card = null
        if (footer != null) {
            root.addView(TextView(context).apply {
                text = footer
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(p.secondary)
                setLineSpacing(0f, 1.1f)
                setPadding(dp(36f), dp(7f), dp(28f), 0)
            })
        }
    }

    /** Adds any view as its own row inside the current card. */
    fun custom(view: View, separatorInset: Float = 16f) {
        val c = card ?: return
        if (rowsInCard > 0) addSeparator(c, separatorInset)
        c.addView(view)
        rowsInCard++
    }

    private fun addSeparator(c: LinearLayout, inset: Float) {
        c.addView(View(context).apply { setBackgroundColor(p.separator) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (0.5f * d).toInt())).apply {
                marginStart = dp(inset)
            })
    }

    private fun baseRow(icon: Icons.Kind?, tint: Int, title: String, titleColor: Int = p.label): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46f)
            setPadding(dp(16f), dp(6f), dp(14f), dp(6f))
        }
        if (icon != null) {
            row.addView(IconTile(context, icon, tint), LinearLayout.LayoutParams(dp(29f), dp(29f)).apply { marginEnd = dp(14f) })
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(titleColor)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row to texts
    }

    private fun inset(icon: Icons.Kind?) = if (icon != null) 59f else 16f

    private fun pressable(v: View) {
        v.background = RippleDrawable(ColorStateList.valueOf(p.pressed), null, GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()) })
        v.isClickable = true
    }

    fun switchRow(icon: Icons.Kind?, tint: Int, title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit): IosSwitch {
        val (row, texts) = baseRow(icon, tint, title)
        subtitle?.let { addSubtitle(texts, it) }
        val sw = IosSwitch(context, p).apply { isOn = checked; this.onChange = onChange }
        row.addView(sw, LinearLayout.LayoutParams(dp(51f), dp(31f)).apply { marginStart = dp(10f) })
        row.setOnClickListener { sw.toggle() }
        custom(row, inset(icon))
        return sw
    }

    /** A row with a grey value and a chevron, like "Wallpaper  >". */
    fun navRow(icon: Icons.Kind?, tint: Int, title: String, value: String?, onClick: () -> Unit): TextView {
        val (row, _) = baseRow(icon, tint, title)
        val v = TextView(context).apply {
            text = value ?: ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(p.secondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(170f)
        }
        row.addView(v)
        row.addView(IconView(context, Icons.Kind.CHEVRON, p.tertiary), LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginStart = dp(6f) })
        pressable(row)
        row.setOnClickListener { onClick() }
        custom(row, inset(icon))
        return v
    }

    fun actionRow(title: String, color: Int, icon: Icons.Kind? = null, tint: Int = 0, onClick: () -> Unit): TextView {
        val (row, texts) = baseRow(icon, tint, title, color)
        pressable(row)
        row.setOnClickListener { onClick() }
        custom(row, inset(icon))
        return texts.getChildAt(0) as TextView
    }

    /** A checkmark row for choice pages. */
    fun checkRow(title: String, checked: Boolean, onClick: () -> Unit) {
        val (row, _) = baseRow(null, 0, title)
        val check = IconView(context, Icons.Kind.CHECK, p.blue)
        check.visibility = if (checked) View.VISIBLE else View.INVISIBLE
        row.addView(check, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        pressable(row)
        row.setOnClickListener { onClick() }
        custom(row, 16f)
    }

    fun sliderRow(icon: Icons.Kind?, tint: Int, title: String, min: Int, max: Int, step: Int, value: Int, suffix: String, onChange: (Int) -> Unit) {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8f))
        }
        val (row, _) = baseRow(icon, tint, title)
        val valueView = TextView(context).apply {
            text = "$value$suffix"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(p.secondary)
        }
        row.addView(valueView)
        col.addView(row)
        val seek = SeekBar(context).apply {
            this.max = (max - min) / step
            progress = (value - min) / step
            progressTintList = ColorStateList.valueOf(p.blue)
            progressBackgroundTintList = ColorStateList.valueOf(p.switchOff)
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
                setStroke(maxOf(1, (0.8f * d).toInt()), 0x33000000)
                setSize(dp(26f), dp(26f))
            }
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                    valueView.text = "${min + prog * step}$suffix"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) = onChange(min + sb.progress * step)
            })
        }
        col.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32f)).apply {
            marginStart = dp(if (icon != null) 50f else 6f); marginEnd = dp(6f)
        })
        custom(col, inset(icon))
    }

    private fun addSubtitle(texts: LinearLayout, s: String) {
        texts.addView(TextView(context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(p.secondary)
        })
    }
}

/** The rounded colored square with a white glyph that leads every iOS Settings row. */
@SuppressLint("ViewConstructor")
class IconTile(context: Context, private val kind: Icons.Kind, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val r = width * 0.23f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        Icons.draw(canvas, kind, width / 2f, height / 2f, width * 0.62f, 0xFFFFFFFF.toInt())
    }
}

@SuppressLint("ViewConstructor")
class IconView(context: Context, private val kind: Icons.Kind, private val color: Int) : View(context) {
    override fun onDraw(canvas: Canvas) {
        Icons.draw(canvas, kind, width / 2f, height / 2f, minOf(width, height) * 0.8f, color)
    }
}

/** An iOS toggle: green track, white knob that slides. */
@SuppressLint("ViewConstructor")
class IosSwitch(context: Context, private val p: IosPalette) : View(context) {
    var onChange: (Boolean) -> Unit = {}
    var isOn = false
        set(v) { field = v; pos = if (v) 1f else 0f; invalidate() }
    private var pos = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }

    init {
        isClickable = true
        setOnClickListener { toggle() }
    }

    fun toggle() {
        val target = !isOn
        val from = pos
        isOnSilently(target)
        ValueAnimator.ofFloat(from, if (target) 1f else 0f).apply {
            duration = 180
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onChange(target)
    }

    private fun isOnSilently(v: Boolean) {
        val keep = pos
        isOn = v
        pos = keep
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        track.color = blend(p.switchOff, p.green, pos)
        canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, track)
        val r = h / 2f - 2 * resources.displayMetrics.density
        val cx = h / 2f + (w - h) * pos
        canvas.drawCircle(cx, h / 2f + 1.5f * resources.displayMetrics.density, r, shadow)
        canvas.drawCircle(cx, h / 2f, r, knob)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

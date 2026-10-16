package com.gr4nt.iboard.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gr4nt.iboard.Icons
import com.gr4nt.iboard.KeyboardTheme
import com.gr4nt.iboard.withAlpha
import java.util.concurrent.Executors

/**
 * iOS-style emoji keyboard: "Search Emoji" field, horizontally paged grid with section titles,
 * category bar with ABC and delete, long-press skin tones. Emoji are drawn with bundled 3D art.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val theme: KeyboardTheme,
    private val panelHeight: Int,
    private val listener: Listener,
) : FrameLayout(context) {

    interface Listener {
        fun onEmoji(text: String)
        fun onBackspace(repeat: Int)
        fun onAbc()
        fun onFeedback()
        fun onSearch()
    }

    private val d = resources.displayMetrics.density
    private val history = EmojiHistory(context)
    private val columns = 8
    private val grid = RecyclerView(context)
    private val categoryBar = CategoryBar(context)
    private val adapter = Adapter()
    private val layoutManager = GridLayoutManager(context, columns)
    private var items: List<Item> = emptyList()
    private var sectionStarts = IntArray(9)
    private val toneOverlay = LinearLayout(context)
    private val main = Handler(Looper.getMainLooper())
    private val message = TextView(context)
    private val tabs = TabPill(context)

    /** A grid entry: a section title (spans the row) or one emoji. */
    private data class Item(val emoji: Emoji?, val category: Int, val header: String? = null)

    init {
        val catH = (46 * d).toInt()
        val searchH = (46 * d).toInt()
        val bottomH = (58 * d).toInt()
        val gridH = panelHeight - catH - searchH

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Category icons across the top; the active one sits in a soft rounded bubble.
        categoryBar.onPick = { cat -> scrollToCategory(cat) }
        column.addView(categoryBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, catH).apply {
            setMargins((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), 0)
        })

        // Wide "Search" field
        val search = SearchPill(context)
        search.setOnClickListener { listener.onFeedback(); listener.onSearch() }
        column.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, searchH).apply {
            setMargins((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        })

        // Vertically scrolling 8-column grid with section titles; the bottom bar floats over it.
        val body = FrameLayout(context)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (items.getOrNull(position)?.header != null) columns else 1
        }
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), bottomH)
        grid.clipToPadding = false
        grid.itemAnimator = null
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = syncCategory()
        })
        body.addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        message.apply {
            gravity = Gravity.CENTER
            setTextColor(theme.keySubText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, bottomH)
            visibility = GONE
        }
        body.addView(message, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bottom = FrameLayout(context)
        val abc = CircleButton(context, null, "ABC").apply {
            setOnClickListener { listener.onFeedback(); listener.onAbc() }
        }
        bottom.addView(abc, LayoutParams((48 * d).toInt(), (48 * d).toInt(), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = (10 * d).toInt()
        })
        tabs.onTab = { i -> selectTab(i) }
        bottom.addView(tabs, LayoutParams((240 * d).toInt(), (46 * d).toInt(), Gravity.CENTER))
        val del = CircleButton(context, Icons.Kind.BACKSPACE, null)
        del.setOnTouchListener(RepeatListener())
        bottom.addView(del, LayoutParams((48 * d).toInt(), (48 * d).toInt(), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = (10 * d).toInt()
        })
        body.addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomH, Gravity.BOTTOM))

        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridH))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight))

        toneOverlay.apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(theme.bubbleFill); cornerRadius = 16 * d }
            elevation = 10 * d
            visibility = GONE
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        }
        addView(toneOverlay, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        loadData()
    }

    private fun selectTab(i: Int) {
        listener.onFeedback()
        tabs.selected = i
        if (i == 0) {
            grid.visibility = VISIBLE; message.visibility = GONE
        } else {
            grid.visibility = INVISIBLE; message.visibility = VISIBLE
            message.text = if (i == 1) "GIFs are coming soon" else "Stickers are coming soon"
        }
    }

    private fun loadData() {
        Executors.newSingleThreadExecutor().execute {
            val cats = EmojiData.categories(context)
            val recents = history.recents()
            main.post { build(cats, recents) }
        }
    }

    private fun build(cats: List<List<Emoji>>, recents: List<String>) {
        val list = ArrayList<Item>()
        val starts = IntArray(9)
        val lookup = HashMap<String, Emoji>()
        cats.forEach { c -> c.forEach { lookup[it.text] = it } }
        fun section(index: Int, emoji: List<Emoji>) {
            starts[index] = list.size
            list += Item(null, index, EmojiData.CATEGORY_NAMES[index])
            emoji.forEach { list += Item(it, index) }
        }
        val recentEmoji = recents.map { r -> lookup[r] ?: Emoji(r, false, "") }
        if (recentEmoji.isNotEmpty()) section(0, recentEmoji) else starts[0] = 0
        cats.forEachIndexed { i, c -> section(i + 1, c) }
        items = list
        sectionStarts = starts
        adapter.notifyDataSetChanged()
        categoryBar.active = if (recentEmoji.isEmpty()) 1 else 0
    }

    private fun scrollToCategory(cat: Int) {
        listener.onFeedback()
        if (tabs.selected != 0) selectTab(0)
        layoutManager.scrollToPositionWithOffset(sectionStarts[cat], 0)
        categoryBar.active = cat
    }

    private fun syncCategory() {
        val pos = layoutManager.findFirstVisibleItemPosition()
        val cat = items.getOrNull(pos)?.category ?: return
        if (cat != categoryBar.active) categoryBar.active = cat
    }

    private fun shown(e: Emoji): String {
        val tone = if (e.skin) history.tone(e.text)?.takeIf { it.isNotEmpty() } else null
        return EmojiData.withTone(e.text, tone)
    }

    private fun commit(text: String) {
        listener.onFeedback()
        listener.onEmoji(text)
        history.record(text)
    }

    private fun showTones(anchor: View, e: Emoji) {
        toneOverlay.removeAllViews()
        val options = listOf<String?>(null) + EmojiData.TONES
        val size = (46 * d).toInt()
        for (t in options) {
            val v = EmojiCell(context).apply {
                square = false
                emoji = EmojiData.withTone(e.text, t)
                setOnClickListener {
                    history.setTone(e.text, t)
                    toneOverlay.visibility = GONE
                    commit(emoji)
                    adapter.notifyDataSetChanged()
                }
            }
            toneOverlay.addView(v, LinearLayout.LayoutParams(size, size))
        }
        val loc = IntArray(2)
        val me = IntArray(2)
        anchor.getLocationInWindow(loc)
        getLocationInWindow(me)
        val w = size * options.size + (8 * d).toInt()
        val h = size + (8 * d).toInt()
        val x = (loc[0] - me[0] + anchor.width / 2 - w / 2).coerceIn(0, maxOf(0, width - w))
        var y = loc[1] - me[1] - h
        if (y < 0) y = loc[1] - me[1] + anchor.height
        toneOverlay.translationX = x.toFloat()
        toneOverlay.translationY = y.toFloat()
        toneOverlay.visibility = VISIBLE
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (toneOverlay.visibility == VISIBLE && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val inside = ev.x >= toneOverlay.translationX && ev.x <= toneOverlay.translationX + toneOverlay.width &&
                ev.y >= toneOverlay.translationY && ev.y <= toneOverlay.translationY + toneOverlay.height
            if (!inside) {
                toneOverlay.visibility = GONE
                return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position].header != null) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v: View = if (viewType == 1) TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(theme.keySubText)
                letterSpacing = 0.04f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding((8 * d).toInt(), (6 * d).toInt(), 0, 0)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (32 * d).toInt())
            } else EmojiCell(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (item.header != null) {
                (holder.itemView as TextView).text = item.header.lowercase().replaceFirstChar { it.uppercase() }
                return
            }
            val v = holder.itemView as EmojiCell
            val e = item.emoji ?: return
            v.emoji = shown(e)
            v.contentDescription = e.name
            v.setOnClickListener { commit(v.emoji) }
            if (e.skin) v.setOnLongClickListener { showTones(it, e); true } else v.setOnLongClickListener(null)
        }
    }

    private inner class RepeatListener : OnTouchListener {
        private var count = 0
        private val repeat = object : Runnable {
            override fun run() {
                count++
                listener.onBackspace(count)
                main.postDelayed(this, 70)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    count = 0; v.isPressed = true
                    listener.onFeedback(); listener.onBackspace(0)
                    main.postDelayed(repeat, 420)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false; main.removeCallbacks(repeat)
                }
            }
            return true
        }
    }

    /** One emoji in a square cell; a light rounded highlight while pressed. */
    private inner class EmojiCell(context: Context) : View(context) {
        var emoji: String = ""
            set(v) { field = v; invalidate() }
        var square = true
        private val press = Paint(Paint.ANTI_ALIAS_FLAG)

        init { isClickable = true }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!square) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, w)
        }

        override fun onDraw(canvas: Canvas) {
            if (emoji.isEmpty()) return
            if (isPressed) {
                press.color = if (theme.dark) 0x26FFFFFF else 0x14000000
                val r = 12 * d
                canvas.drawRoundRect(2 * d, 2 * d, width - 2 * d, height - 2 * d, r, r, press)
            }
            EmojiImages.draw(context, canvas, emoji, width / 2f, height / 2f, minOf(width, height) * 0.74f)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged(); invalidate()
        }
    }

    /** Wide rounded "Search" field. Tapping it opens search above the letter keys. */
    private inner class SearchPill(context: Context) : View(context) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17 * resources.displayMetrics.scaledDensity }

        init { isClickable = true }

        override fun onDraw(canvas: Canvas) {
            val h = height - 8 * d
            val top = 4 * d
            bg.color = if (theme.dark) 0x2EFFFFFF else 0x1F767680
            canvas.drawRoundRect(0f, top, width.toFloat(), top + h, h / 2f, h / 2f, bg)
            val cy = top + h / 2f
            Icons.draw(canvas, Icons.Kind.SEARCH, 22 * d, cy, 17 * d, theme.keySubText)
            text.color = theme.keySubText
            canvas.drawText("Search", 42 * d, cy - (text.descent() + text.ascent()) / 2f, text)
        }
    }

    /** Glass circle with an icon or a short label (ABC, delete). */
    private inner class CircleButton(context: Context, private val kind: Icons.Kind?, private val label: String?) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 14 * resources.displayMetrics.scaledDensity
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        init { isClickable = true }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f - 2 * d
            paint.color = if (theme.dark) 0x55000000 else 0x22000000
            canvas.drawCircle(width / 2f, height / 2f + 1.5f * d, r, paint)
            paint.color = if (isPressed) theme.keyFillPressed else (if (theme.dark) 0xE63A3A3C.toInt() else 0xF2FFFFFF.toInt())
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            if (kind != null) Icons.draw(canvas, kind, width / 2f, height / 2f, r * 0.95f, theme.keyText)
            if (label != null) {
                text.color = theme.keyText
                canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2f, text)
            }
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged(); invalidate()
        }
    }

    /** Floating "Emoji · GIFs · Stickers" pill with the selected tab highlighted. */
    private inner class TabPill(context: Context) : View(context) {
        var selected = 0
            set(v) { field = v; invalidate() }
        var onTab: (Int) -> Unit = {}
        private val labels = listOf("Emoji", "GIFs", "Stickers")
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 16 * resources.displayMetrics.scaledDensity
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            paint.color = if (theme.dark) 0x55000000 else 0x22000000
            canvas.drawRoundRect(0f, 1.5f * d, w, h, h / 2f, h / 2f, paint)
            paint.color = if (theme.dark) 0xE63A3A3C.toInt() else 0xF2FFFFFF.toInt()
            canvas.drawRoundRect(0f, 0f, w, h - 1.5f * d, h / 2f, h / 2f, paint)
            val seg = w / labels.size
            labels.forEachIndexed { i, l ->
                if (i == selected) {
                    paint.color = if (theme.dark) 0x33FFFFFF else 0x14000000
                    val inset = 4 * d
                    canvas.drawRoundRect(seg * i + inset, inset, seg * (i + 1) - inset, h - 1.5f * d - inset,
                        (h - 2 * inset) / 2f, (h - 2 * inset) / 2f, paint)
                }
                text.color = if (i == selected) theme.keyText else theme.keySubText
                text.typeface = Typeface.create(if (i == selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                canvas.drawText(l, seg * i + seg / 2f, (h - 1.5f * d) / 2f - (text.descent() + text.ascent()) / 2f, text)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                onTab((e.x / (width / labels.size.toFloat())).toInt().coerceIn(0, labels.size - 1))
            }
            return true
        }
    }

    /** Category icons across the top; the active one sits in a rounded bubble. */
    private inner class CategoryBar(context: Context) : View(context) {
        var active = 1
            set(v) { field = v; invalidate() }
        var onPick: (Int) -> Unit = {}
        private val kinds = listOf(
            Icons.Kind.CLOCK, Icons.Kind.SMILEY, Icons.Kind.PAW, Icons.Kind.FOOD, Icons.Kind.BALL,
            Icons.Kind.CAR, Icons.Kind.BULB, Icons.Kind.SYMBOLS, Icons.Kind.FLAG,
        )
        private val sel = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width / kinds.size.toFloat()
            val cy = height / 2f
            kinds.forEachIndexed { i, k ->
                val cx = w * i + w / 2f
                if (i == active) {
                    sel.color = if (theme.dark) 0x33FFFFFF else 0x14000000
                    val r = minOf(w, height.toFloat()) * 0.46f
                    canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, r * 0.8f, r * 0.8f, sel)
                }
                val color = if (i == active) theme.keyText else theme.keySubText.withAlpha(0.8f)
                Icons.draw(canvas, k, cx, cy, minOf(w * 0.55f, 21 * d), color)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                onPick((e.x / (width / kinds.size.toFloat())).toInt().coerceIn(0, kinds.size - 1))
            }
            return true
        }
    }
}

/** A small rounded button that draws one of the [Icons]. */
@SuppressLint("ViewConstructor")
class IconButton(context: Context, private val kind: Icons.Kind, private val color: Int, bg: Int?) : View(context) {
    private val d = resources.displayMetrics.density

    init {
        if (bg != null) background = GradientDrawable().apply { setColor(bg); cornerRadius = 9 * d }
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        Icons.draw(canvas, kind, width / 2f, height / 2f, minOf(width, height) * 0.55f, if (isPressed) color.withAlpha(0.5f) else color)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged(); invalidate()
    }
}

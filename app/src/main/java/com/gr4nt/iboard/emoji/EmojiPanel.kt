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
    private val rows = 5
    private val title = TextView(context)
    private val grid = RecyclerView(context)
    private val categoryBar = CategoryBar(context)
    private val adapter = Adapter()
    private val layoutManager = GridLayoutManager(context, rows, GridLayoutManager.HORIZONTAL, false)
    private var cells: List<Cell> = emptyList()
    private var sectionStarts = IntArray(9)
    private val toneOverlay = LinearLayout(context)
    private val main = Handler(Looper.getMainLooper())

    private data class Cell(val emoji: Emoji?, val category: Int)

    init {
        val searchH = (44 * d).toInt()
        val bottomBarH = (44 * d).toInt()
        val titleH = (22 * d).toInt()
        val gridH = panelHeight - searchH - bottomBarH - titleH

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // "Search Emoji" pill, like iOS
        val search = SearchPill(context)
        search.setOnClickListener { listener.onFeedback(); listener.onSearch() }
        column.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, searchH).apply {
            setMargins((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), 0)
        })

        title.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(theme.keySubText)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), 0, 0, 0)
            text = EmojiData.CATEGORY_NAMES[1]
        }
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, titleH))

        adapter.cellSize = gridH / rows
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        grid.clipToPadding = false
        grid.itemAnimator = null
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = syncCategory()
        })
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridH))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val abc = TextView(context).apply {
            text = "ABC"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(theme.keyText)
            gravity = Gravity.CENTER
            setOnClickListener { listener.onFeedback(); listener.onAbc() }
        }
        bar.addView(abc, LinearLayout.LayoutParams((52 * d).toInt(), (36 * d).toInt()).apply {
            marginStart = (4 * d).toInt()
        })
        categoryBar.onPick = { cat -> scrollToCategory(cat) }
        bar.addView(categoryBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val del = IconButton(context, Icons.Kind.BACKSPACE, theme.keyText, null)
        del.setOnTouchListener(RepeatListener())
        bar.addView(del, LinearLayout.LayoutParams((52 * d).toInt(), (36 * d).toInt()).apply {
            marginEnd = (4 * d).toInt()
        })
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomBarH))
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

    private fun loadData() {
        Executors.newSingleThreadExecutor().execute {
            val cats = EmojiData.categories(context)
            val recents = history.recents()
            main.post { build(cats, recents) }
        }
    }

    private fun build(cats: List<List<Emoji>>, recents: List<String>) {
        val list = ArrayList<Cell>()
        val starts = IntArray(9)
        val lookup = HashMap<String, Emoji>()
        cats.forEach { c -> c.forEach { lookup[it.text] = it } }
        fun section(index: Int, items: List<Emoji>) {
            starts[index] = list.size
            items.forEach { list += Cell(it, index) }
            while (list.size % rows != 0) list += Cell(null, index)
        }
        val recentEmoji = recents.map { r -> lookup[r] ?: Emoji(r, false, "") }
        section(0, if (recentEmoji.isEmpty()) cats[0].take(20) else recentEmoji)
        cats.forEachIndexed { i, c -> section(i + 1, c) }
        cells = list
        sectionStarts = starts
        adapter.notifyDataSetChanged()
        val firstCat = if (recents.isEmpty()) 1 else 0
        layoutManager.scrollToPositionWithOffset(starts[firstCat], 0)
        categoryBar.active = firstCat
        title.text = EmojiData.CATEGORY_NAMES[firstCat]
    }

    private fun scrollToCategory(cat: Int) {
        listener.onFeedback()
        layoutManager.scrollToPositionWithOffset(sectionStarts[cat], 0)
        categoryBar.active = cat
        title.text = EmojiData.CATEGORY_NAMES[cat]
    }

    private fun syncCategory() {
        val pos = layoutManager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
            ?: layoutManager.findFirstVisibleItemPosition()
        val cat = cells.getOrNull(pos)?.category ?: return
        if (cat != categoryBar.active) {
            categoryBar.active = cat
            title.text = EmojiData.CATEGORY_NAMES[cat]
        }
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
        var cellSize = 0
        override fun getItemCount() = cells.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = EmojiCell(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams((cellSize * 1.06f).toInt(), cellSize)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView as EmojiCell
            val e = cells[position].emoji
            if (e == null) {
                v.emoji = ""; v.setOnClickListener(null); v.setOnLongClickListener(null); v.isClickable = false
                return
            }
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

    /** One emoji, drawn with 3D art (or the font for flags), slightly shrunk while pressed. */
    private inner class EmojiCell(context: Context) : View(context) {
        var emoji: String = ""
            set(v) { field = v; invalidate() }

        init { isClickable = true }

        override fun onDraw(canvas: Canvas) {
            if (emoji.isEmpty()) return
            val size = minOf(width, height) * if (isPressed) 0.66f else 0.74f
            EmojiImages.draw(context, canvas, emoji, width / 2f, height / 2f, size)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged(); invalidate()
        }
    }

    /** The rounded "Search Emoji" field. Tapping it opens search above the letter keys. */
    private inner class SearchPill(context: Context) : View(context) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16 * resources.displayMetrics.scaledDensity }

        init { isClickable = true }

        override fun onDraw(canvas: Canvas) {
            val h = height - 6 * d
            val top = 3 * d
            bg.color = if (theme.dark) 0x33FFFFFF else 0xCCFFFFFF.toInt()
            canvas.drawRoundRect(0f, top, width.toFloat(), top + h, h / 2f, h / 2f, bg)
            val cy = top + h / 2f
            Icons.draw(canvas, Icons.Kind.SEARCH, 22 * d, cy, 16 * d, theme.keySubText)
            text.color = theme.keySubText
            canvas.drawText("Search Emoji", 38 * d, cy - (text.descent() + text.ascent()) / 2f, text)
        }
    }

    /** Grey category icons along the bottom; the active one sits in a soft circle. */
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
                    sel.color = if (theme.dark) 0x40FFFFFF else 0x1A000000
                    canvas.drawCircle(cx, cy, minOf(w, height.toFloat()) * 0.44f, sel)
                }
                val color = if (i == active) theme.keyText else theme.keySubText.withAlpha(0.85f)
                Icons.draw(canvas, k, cx, cy, minOf(w * 0.55f, 18 * d), color)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_MOVE) {
                val i = (e.x / (width / kinds.size.toFloat())).toInt().coerceIn(0, kinds.size - 1)
                if (i != active || e.actionMasked == MotionEvent.ACTION_UP) onPick(i)
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

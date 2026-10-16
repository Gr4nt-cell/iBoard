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

/** An iOS-style emoji keyboard: horizontally scrolling pages, category bar, skin tones. */
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
        val bottomBarH = (44 * d).toInt()
        val titleH = (26 * d).toInt()
        val gridH = panelHeight - bottomBarH - titleH

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(theme.keySubText)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (4 * d).toInt(), 0, 0)
            text = EmojiData.CATEGORY_NAMES[1]
        }
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, titleH))

        val cellSize = gridH / rows
        adapter.cellSize = cellSize
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
            setTextColor(theme.keyText)
            gravity = Gravity.CENTER
            background = pill(theme.specialFill)
            setOnClickListener { listener.onFeedback(); listener.onAbc() }
        }
        bar.addView(abc, LinearLayout.LayoutParams((54 * d).toInt(), (34 * d).toInt()).apply {
            marginStart = (6 * d).toInt()
        })
        categoryBar.onPick = { cat -> scrollToCategory(cat) }
        bar.addView(categoryBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val del = IconButton(context, Icons.Kind.BACKSPACE, theme.keyText, theme.specialFill)
        del.setOnTouchListener(RepeatListener())
        bar.addView(del, LinearLayout.LayoutParams((54 * d).toInt(), (34 * d).toInt()).apply {
            marginEnd = (6 * d).toInt()
        })
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomBarH))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight))

        toneOverlay.apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill(theme.bubbleFill, 14f)
            elevation = 8 * d
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
        val skinLookup = HashMap<String, Emoji>()
        cats.forEach { c -> c.forEach { skinLookup[it.text] = it } }
        fun section(index: Int, items: List<Emoji>) {
            starts[index] = list.size
            items.forEach { list += Cell(it, index) }
            // Each category begins on a fresh column, like iOS.
            while (list.size % rows != 0) list += Cell(null, index)
        }
        val recentEmoji = recents.map { r -> skinLookup[r] ?: Emoji(r, false, "") }
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

    private fun commit(e: Emoji) {
        val tone = if (e.skin) history.tone(e.text)?.takeIf { it.isNotEmpty() } else null
        val text = EmojiData.withTone(e.text, tone)
        listener.onFeedback()
        listener.onEmoji(text)
        history.record(text)
    }

    private fun showTones(anchor: View, e: Emoji) {
        toneOverlay.removeAllViews()
        val options = listOf<String?>(null) + EmojiData.TONES
        val size = (44 * d).toInt()
        for (t in options) {
            val v = TextView(context).apply {
                text = EmojiData.withTone(e.text, t)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                gravity = Gravity.CENTER
                setOnClickListener {
                    history.setTone(e.text, t)
                    toneOverlay.visibility = GONE
                    listener.onFeedback()
                    val out = EmojiData.withTone(e.text, t)
                    listener.onEmoji(out)
                    history.record(out)
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

    private fun pill(color: Int, r: Float = 9f) = GradientDrawable().apply {
        setColor(color); cornerRadius = r * d
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var cellSize = 0
        override fun getItemCount() = cells.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, cellSize * 0.62f)
                includeFontPadding = false
                layoutParams = RecyclerView.LayoutParams((cellSize * 1.08f).toInt(), cellSize)
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            val e = cells[position].emoji
            if (e == null) {
                tv.text = ""; tv.setOnClickListener(null); tv.setOnLongClickListener(null); tv.background = null
                return
            }
            val tone = if (e.skin) history.tone(e.text)?.takeIf { it.isNotEmpty() } else null
            tv.text = EmojiData.withTone(e.text, tone)
            tv.contentDescription = e.name
            tv.setOnClickListener { commit(e) }
            if (e.skin) tv.setOnLongClickListener { showTones(it, e); true } else tv.setOnLongClickListener(null)
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

    /** Row of grey category icons at the bottom, with the active one highlighted. */
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
                    sel.color = theme.keyFill
                    canvas.drawCircle(cx, cy, minOf(w, height.toFloat()) * 0.42f, sel)
                }
                val color = if (i == active) theme.keyText else theme.keySubText.withAlpha(0.8f)
                Icons.draw(canvas, k, cx, cy, minOf(w * 0.55f, 19 * d), color)
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

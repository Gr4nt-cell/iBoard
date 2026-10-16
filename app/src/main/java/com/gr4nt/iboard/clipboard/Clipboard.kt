package com.gr4nt.iboard.clipboard

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gr4nt.iboard.Icons
import com.gr4nt.iboard.KeyboardTheme
import com.gr4nt.iboard.emoji.IconButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ClipItem(val text: String, val time: Long, val pinned: Boolean)

/** Clipboard history: watches the system clipboard while the keyboard is running. */
class ClipboardStore(private val context: Context) {
    private val file = File(context.filesDir, "clipboard.json")
    private val items = ArrayList<ClipItem>()
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var enabled = true
    var onChanged: (() -> Unit)? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

    init {
        load()
    }

    fun start() = manager.addPrimaryClipChangedListener(listener)
    fun stop() = manager.removePrimaryClipChangedListener(listener)

    fun all(): List<ClipItem> = items.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time })

    /** The newest clip if it was copied in the last [maxAgeMs]. */
    fun recent(maxAgeMs: Long): ClipItem? =
        items.maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time < maxAgeMs }

    fun capture() {
        if (!enabled) return
        try {
            val clip = manager.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            if (Build.VERSION.SDK_INT >= 33 && desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
            val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim() ?: return
            if (text.isEmpty() || text.length > 5000) return
            val existing = items.indexOfFirst { it.text == text }
            val pinned = existing >= 0 && items[existing].pinned
            if (existing >= 0) items.removeAt(existing)
            items += ClipItem(text, System.currentTimeMillis(), pinned)
            trim(); save(); onChanged?.invoke()
        } catch (_: Exception) {
        }
    }

    fun togglePin(item: ClipItem) {
        val i = items.indexOf(item)
        if (i >= 0) { items[i] = item.copy(pinned = !item.pinned); save() }
    }

    fun remove(item: ClipItem) {
        items.remove(item); save()
    }

    fun clearUnpinned() {
        items.removeAll { !it.pinned }; save()
    }

    private fun trim() {
        val unpinned = items.filter { !it.pinned }.sortedBy { it.time }
        if (unpinned.size > 40) items.removeAll(unpinned.take(unpinned.size - 40).toSet())
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += ClipItem(o.getString("t"), o.optLong("at"), o.optBoolean("p"))
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(JSONObject().put("t", it.text).put("at", it.time).put("p", it.pinned)) }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}

/** The clipboard screen shown in place of the keys. */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val theme: KeyboardTheme,
    panelHeight: Int,
    private val store: ClipboardStore,
    private val onPaste: (String) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val list = RecyclerView(context)
    private val empty = TextView(context)
    private val adapter = Adapter()

    init {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), 0, (10 * d).toInt(), 0)
        }
        val back = IconButton(context, Icons.Kind.BACK, theme.keyText, theme.specialFill)
        back.setOnClickListener { onClose() }
        header.addView(back, LinearLayout.LayoutParams((40 * d).toInt(), (32 * d).toInt()))
        val title = TextView(context).apply {
            text = "Clipboard"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(theme.keyText)
            setPadding((12 * d).toInt(), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clear = TextView(context).apply {
            text = "Clear"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(theme.accent)
            setPadding((10 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            setOnClickListener { store.clearUnpinned(); refresh() }
        }
        header.addView(clear)
        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt()))

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (8 * d).toInt())
        list.clipToPadding = false
        val body = FrameLayout(context)
        body.addView(list)
        empty.apply {
            text = "Text you copy will show up here.\nTap an item to paste it, pin the ones you reuse."
            gravity = Gravity.CENTER
            setTextColor(theme.keySubText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        body.addView(empty)
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight - (48 * d).toInt()))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight))
        refresh()
    }

    fun refresh() {
        adapter.items = store.all()
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.items.isEmpty()) VISIBLE else GONE
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<ClipItem> = emptyList()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(theme.panelCard); cornerRadius = 12 * d }
                setPadding((12 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt())
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (6 * d).toInt() }
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = holder.itemView as LinearLayout
            row.removeAllViews()
            val item = items[position]
            val tv = TextView(row.context).apply {
                text = item.text
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.keyText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val pin = IconButton(row.context, Icons.Kind.PIN, if (item.pinned) theme.accent else theme.keySubText, null)
            pin.setOnClickListener { store.togglePin(item); refresh() }
            row.addView(pin, LinearLayout.LayoutParams((36 * d).toInt(), (32 * d).toInt()))
            val del = IconButton(row.context, Icons.Kind.TRASH, theme.keySubText, null)
            del.setOnClickListener { store.remove(item); refresh() }
            row.addView(del, LinearLayout.LayoutParams((36 * d).toInt(), (32 * d).toInt()))
            row.setOnClickListener { onPaste(item.text) }
        }
    }
}

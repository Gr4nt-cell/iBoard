package com.gr4nt.iboard.emoji

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

/**
 * Typed emoji are drawn by the receiving app with the phone's own emoji font. To keep the AURA
 * look, apps that accept images from keyboards (WhatsApp, Telegram, Messages, Signal…) get the
 * emoji as a small PNG sticker instead. Other apps still get the normal emoji character.
 */
object EmojiSticker {
    const val PREF = "emoji_stickers"
    private const val SIZE = 256
    private val MIME = arrayOf("image/png")

    /** True when this text field accepts PNG images from the keyboard. */
    fun supported(info: EditorInfo?): Boolean {
        if (info == null) return false
        return EditorInfoCompat.getContentMimeTypes(info).any {
            ClipDescription.compareMimeTypes("image/png", it)
        }
    }

    /** Sends [emoji] as an AURA sticker. Returns false if it couldn't, so the caller types it as text. */
    fun send(context: Context, ic: InputConnection?, info: EditorInfo?, emoji: String): Boolean {
        if (ic == null || info == null || !supported(info) || !EmojiImages.has(emoji)) return false
        return runCatching {
            val file = render(context, emoji) ?: return false
            val uri = FileProvider.getUriForFile(context, context.packageName + ".emoji", file)
            val content = InputContentInfoCompat(uri, ClipDescription(emoji, MIME), null)
            val flags = if (Build.VERSION.SDK_INT >= 25) InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
            InputConnectionCompat.commitContent(ic, info, content, flags, null)
        }.getOrDefault(false)
    }

    private fun render(context: Context, emoji: String): File? {
        val dir = File(context.cacheDir, "emoji").apply { mkdirs() }
        val file = File(dir, EmojiImages.key(emoji) + ".png")
        if (file.isFile && file.length() > 0) return file
        EmojiImages.init(context)
        val typeface = EmojiImages.typeface ?: return null
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
            textSize = SIZE * 0.84f
        }
        Canvas(bmp).drawText(emoji, SIZE / 2f, SIZE / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        val tmp = File(dir, file.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return if (tmp.renameTo(file)) file else null
    }
}

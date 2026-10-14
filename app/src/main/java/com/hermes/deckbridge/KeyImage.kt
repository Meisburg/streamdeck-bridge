package com.hermes.deckbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * Renders the deck's 72x72 key images.
 *
 * Design intent (Jon's doctrine: don't make the user think): ONE label per
 * key, high contrast, wrapped and auto-sized to fit, no decoration beyond a
 * small index number so a key can be identified even when its macro is empty.
 *
 * Rendered on the phone and pushed to the device as JPEG via a 0x07 output
 * report. The deck displays what we send; it does not render text itself.
 */
object KeyImage {

    /** Key face size in pixels for the MK.2. */
    const val SIZE = 72

    private val BG = Color.parseColor("#101418")
    private val FG = Color.parseColor("#F2F5F7")
    private val DIM = Color.parseColor("#5A6570")
    private val ACCENT = Color.parseColor("#4EA1FF")

    /**
     * Render a key face.
     *
     * @param label   main text, wrapped to at most 3 lines
     * @param index   key index, drawn small in the corner
     * @param active  true when the macro has content worth showing
     */
    fun render(label: String, index: Int, active: Boolean = true): ByteArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        val text = if (label.isBlank()) "—" else label
        val maxWidth = SIZE - 12

        // Auto-size: start large, shrink until it fits in <= 3 lines.
        var textSize = 20f
        val lines = mutableListOf<String>()
        while (textSize >= 8f) {
            lines.clear()
            lines.addAll(wrap(text, maxWidth, textSize))
            if (lines.size <= 3) break
            textSize -= 1f
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) FG else DIM
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val lineHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent
        val totalHeight = lineHeight * lines.size
        val startY = (SIZE - totalHeight) / 2f - paint.fontMetrics.ascent
        val cx = SIZE / 2f

        lines.forEachIndexed { i, line ->
            c.drawText(line, cx, startY + i * lineHeight, paint)
        }

        // Small index marker, so an unlabelled key is still identifiable.
        val idx = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            textSize = 11f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        c.drawText(
            "${index + 1}",
            SIZE - 5f,
            SIZE - 5f,
            idx
        )

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /**
     * Rotate a JPEG 180 degrees.
     *
     * REQUIRED before upload: every model in the Stream Deck image family
     * displays the image upside down unless it is rotated 180 degrees first.
     * Skipping this makes the device accept the report and render nothing
     * useful, which looks exactly like a failed write.
     */
    fun rotate180(jpeg: ByteArray): ByteArray {
        val src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return jpeg
        val m = android.graphics.Matrix().apply { postRotate(180f) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
        src.recycle()
        if (rotated !== src) rotated.recycle()
        return out.toByteArray()
    }

    /** Greedy word wrap, breaking long tokens that cannot fit alone. */
    private fun wrap(text: String, maxWidth: Int, textSize: Float): List<String> {
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rect = Rect()
        val out = mutableListOf<String>()

        text.split(' ').forEach { word ->
            if (out.isEmpty()) {
                out.add(word)
                return@forEach
            }
            val candidate = out.last() + " " + word
            measure.getTextBounds(candidate, 0, candidate.length, rect)
            if (rect.width() <= maxWidth) {
                out[out.size - 1] = candidate
            } else {
                out.add(word)
            }
        }
        return out.ifEmpty { listOf(text) }
    }
}

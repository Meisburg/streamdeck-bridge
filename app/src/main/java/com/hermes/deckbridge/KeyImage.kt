package com.hermes.deckbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/**
 * Renders the deck's 72x72 key images.
 *
 * ONE LABEL PER KEY, matching the on-screen grid. The physical keys and the app's
 * keys are the same 15 positions, so a key that reads "To tickets" under your
 * thumb has to read "To tickets" under your finger. Everything here mirrors the
 * screen key: the same rounded-square face, the same colour pair, the same
 * normal-weight centred text, and the same "shrink until it fits" rule.
 *
 * WHAT WAS WRONG BEFORE. This renderer was written independently of the grid and
 * drifted from it in four visible ways:
 *
 *   1. BOLD text, where the screen uses the default weight. Bold is ~8% wider per
 *      glyph, so a label that fit on screen wrapped differently here.
 *   2. An accent-coloured index number in the corner of EVERY key, which the
 *      screen has no equivalent of. It competed with the label for a 72px square
 *      and read as content rather than as a key identifier.
 *   3. A hand-rolled greedy wrap. The screen wraps with StaticLayout, the platform
 *      text engine, so the two disagreed about where any given label breaks.
 *   4. A starting size of 20f on a 72px face, which is proportionally much larger
 *      than the screen's ceiling. Most labels therefore hit the shrink loop and
 *      landed on a smaller size than the screen used.
 *
 * The fix for drift is not to re-tune the constants; it is to remove the second
 * implementation of the layout rule. Wrapping and fitting now go through the same
 * engine the screen uses.
 *
 * Rendered on the phone and pushed as JPEG via a 0x07 output report. The deck
 * displays what we send; it does not render text itself.
 */
object KeyImage {

    /** Key face size in pixels for the MK.2. */
    const val SIZE = 72

    // Same palette as the screen's key_face.xml, so a key is the same object in
    // both places. #2A2F3A fill for a live key, #1A1E26 for an empty one.
    private val FACE = Color.parseColor("#2A2F3A")
    private val FACE_EMPTY = Color.parseColor("#1A1E26")
    private val BORDER = Color.parseColor("#3A404E")
    private val BORDER_EMPTY = Color.parseColor("#333A47")
    private val FG = Color.parseColor("#E8EAED")
    private val DIM = Color.parseColor("#5A6570")

    /** Corner radius as a fraction of the side, matching the screen's 14dp/90dp. */
    private const val CORNER_FRACTION = 0.155f

    /** Matches the screen's own label ceiling. */
    private const val MAX_TEXT = 18f

    /** Matches the screen's floor, below which text stops being readable. */
    private const val MIN_TEXT = 8f

    /** Matches the screen's line allowance. */
    private const val MAX_LINES = 3

    /**
     * Render a key face.
     *
     * @param label   main text, wrapped to at most [MAX_LINES] lines
     * @param index   key index, kept for the caller's logging and ordering
     * @param active  true when the macro has content worth showing
     */
    fun render(label: String, index: Int, active: Boolean = true): ByteArray =
        renderBitmap(label, active).let { bmp ->
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bmp.recycle()
            out.toByteArray()
        }

    /**
     * Draw a key face, unrotated and uncompressed.
     *
     * Split out from [render] so the same drawing code can be written to a PNG and
     * inspected. A key image is 72x72 and there is no way to tell by reading code
     * whether text is centred in it; the previous attempt at centring was reasoned
     * out incorrectly and only caught by looking at the hardware. Being able to pull
     * the exact bitmap the deck will receive turns that into a local check.
     */
    fun renderBitmap(label: String, active: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // The face, drawn rather than left as flat black, so the image matches the
        // screen's rounded key instead of reading as a bare LCD.
        val pad = 1.5f
        val face = RectF(pad, pad, SIZE - pad, SIZE - pad)
        val radius = SIZE * CORNER_FRACTION
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (active) FACE else FACE_EMPTY
        }
        c.drawRoundRect(face, radius, radius, fill)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            strokeCap = Paint.Cap.ROUND
            color = if (active) BORDER else BORDER_EMPTY
        }
        c.drawRoundRect(face, radius, radius, stroke)

        val text = if (label.isBlank()) "—" else label
        // Text is laid out across the FULL key width so the layout's own centring
        // puts it in the middle of the square. Inset only by a couple of pixels for
        // the border stroke: the rounded corners do not eat glyphs at this size, and
        // reserving 8px a side made even a one-character label sit visibly left of
        // centre.
        val insetting = 3
        val layoutWidth = SIZE - insetting * 2

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) FG else DIM
            // ALIGN_CENTER is set on the layout, not the paint. Setting Paint.Align
            // here as well double-centres: StaticLayout centres within its width, and
            // then the paint shifts each line again by half its own advance, which is
            // what pushed labels off-centre by different amounts depending on their
            // length.
            textAlign = Paint.Align.LEFT
            // DEFAULT weight, matching the screen. Bold here is what made the two
            // surfaces wrap the same label onto different numbers of lines.
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val size = fitSize(text, paint, layoutWidth)
        paint.textSize = size

        // The platform's own line breaker, same as the screen's buttons use, so a
        // label wraps identically in both places.
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(MAX_LINES)
            .setIncludePad(false)
            .build()

        // Centre vertically on the layout's own height. includePad(false) keeps the
        // box tight to the glyphs, so this centres the visible text rather than the
        // font's full ascent/descent box.
        val top = (SIZE - layout.height) / 2f

        c.save()
        c.translate(insetting.toFloat(), top)
        layout.draw(c)
        c.restore()

        return bmp
    }

    /**
     * Largest text size, between [MIN_TEXT] and [MAX_TEXT], at which the label lays
     * out in [MAX_LINES] lines without dropping characters.
     *
     * Same binary search the screen performs, for the same reason: measuring the
     * widest single word is not enough, because a label whose every word fits can
     * still wrap past the line budget. getEllipsisCount is what separates "fits in
     * three lines" from "was cut to three lines" -- lineCount alone reports exactly
     * MAX_LINES in both cases once maxLines clamps it.
     */
    private fun fitSize(text: String, paint: TextPaint, maxWidth: Int): Float {
        fun fits(px: Float): Boolean {
            paint.textSize = px
            val l = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, maxWidth)
                .setMaxLines(MAX_LINES)
                .build()
            for (line in 0 until l.lineCount) {
                if (l.getEllipsisCount(line) > 0) return false
            }
            return true
        }

        var lo = MIN_TEXT
        var hi = MAX_TEXT
        repeat(10) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }
        return lo
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
}

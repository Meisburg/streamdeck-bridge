package com.hermes.deckbridge

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persisted deck settings. Currently just backlight brightness.
 *
 * WHY this exists: brightness was hardcoded to 60 in two places (bringUp and
 * wake). The panel is powered from the phone's battery over USB, so a user who
 * wants to conserve charge had no way to dim it. Worse, because wake() is called
 * on every re-arm and the re-arm loop fires often on this flaky USB link, any
 * one-off brightness write would have been stomped within seconds. So the value
 * has to be persisted AND every brightness send has to read from here rather
 * than from a literal.
 *
 * Range matches the hardware: 0x00..0x64 (0..100). 0 turns the backlight off
 * entirely, which is a legitimate "I am not looking at this right now" mode and
 * the most aggressive battery saving available on the panel side.
 *
 * JSON next to commands.json, for the same reason: trivially editable and
 * pushable over adb when scripting a device from a workstation.
 */
class DeckSettings(private val ctx: Context) {

    private val file: File get() = File(ctx.filesDir, "settings.json")

    /** Backlight brightness, 0..100. Default 60 is what the app used before. */
    fun brightness(): Int = runCatching {
        if (file.exists()) JSONObject(file.readText()).optInt("brightness", DEFAULT)
        else DEFAULT
    }.getOrDefault(DEFAULT).coerceIn(0, 100)

    fun setBrightness(percent: Int) {
        val v = percent.coerceIn(0, 100)
        runCatching {
            val o = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            o.put("brightness", v)
            file.writeText(o.toString())
        }
    }

    /**
     * Whether key images are rotated 180 degrees before upload.
     *
     * Defaults to TRUE: this hardware renders an unrotated face upside down. Proven
     * by observation rather than inherited -- with the flip off, every label on the
     * panel was inverted, and restoring it corrected them.
     *
     * Kept as a setting rather than a hardcoded call because the original version was
     * a hardcoded call whose justification could not be checked (no image had ever
     * reached the panel), and the only way to settle it was to change it and look. A
     * setting makes that a two-second experiment instead of a rebuild.
     */
    fun rotateImages(): Boolean = runCatching {
        if (file.exists()) JSONObject(file.readText()).optBoolean("rotateImages", true)
        else true
    }.getOrDefault(true)

    fun setRotateImages(on: Boolean) {
        runCatching {
            val o = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            o.put("rotateImages", on)
            file.writeText(o.toString())
        }
    }

    companion object {
        const val DEFAULT = 60

        /**
         * Read whether to rotate key images, without holding a DeckTransport
         * reference. Used from the transport's own send path, which has only a
         * Context.
         */
        fun rotateImages(ctx: Context): Boolean = DeckSettings(ctx).rotateImages()

        /**
         * Read the brightness without holding a DeckTransport reference.
         * Used from services that only have a Context.
         */
        fun brightnessOf(ctx: Context): Int = DeckSettings(ctx).brightness()

        /**
         * Named presets, cheapest first. Ordered so the UI and any future deck
         * key cycle through them in a predictable direction.
         */
        val PRESETS = listOf(
            0 to "Off",
            20 to "Dim",
            40 to "Low",
            60 to "Normal",
            100 to "Bright",
        )

        /** Human label for an arbitrary 0..100 value; exact presets keep their name. */
        fun describe(percent: Int): String {
            val named = PRESETS.firstOrNull { it.first == percent }?.second
            return if (named != null) "$named ($percent%)" else "$percent%"
        }
    }
}

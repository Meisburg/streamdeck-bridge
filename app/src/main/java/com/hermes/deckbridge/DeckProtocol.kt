package com.hermes.deckbridge

/**
 * Stream Deck HID protocol, implemented to the official Elgato HID API spec
 * (docs.elgato.com/streamdeck/hid/general), verified against live hardware:
 * Elgato Stream Deck MK.2, model 20GAA9902, PID 0x006D, sn AL33J2C06045,
 * attached to a Pixel 9 Pro.
 *
 * THREE CHANNELS, and using the wrong one silently does nothing:
 *
 *   INPUT REPORT   (device -> host)  max 512 bytes
 *     +0x00 UINT8   Report ID = 0x01
 *     +0x01 UINT8   Command   = 0x00 for key state
 *     +0x02 UINT16  payload length (little endian) = number of keys
 *     +0x04 UINT8[] one byte per key: 0x00 = depressed, 0x01 = pressed
 *     Host POLLS this, roughly every 50ms.
 *
 *   OUTPUT REPORT  (host -> device)  max 1024 bytes, zero padded
 *     +0x00 UINT8   Report ID
 *     +0x01 UINT8   Command
 *     +0x02 BYTE[]  payload
 *
 *   FEATURE REPORT (control transfer)  max 32 bytes, zero padded
 *     +0x00 UINT8   Report ID (0x03 for setters, 0x04-0x0A getters)
 *     +0x01 UINT8   Command
 *     +0x02 BYTE[]  payload
 *
 * NOTE: brightness and key-colour are FEATURE reports, not output reports.
 * Getting that wrong is a silent no-op on real hardware.
 */
object DeckProtocol {

    // ---- Device identity -------------------------------------------------
    const val VENDOR_ID = 0x0FD9
    const val PRODUCT_ID_MK2_REV = 0x006D   // 20GAA9902, this device
    const val PRODUCT_ID_MK2_ORIG = 0x0060  // 20GAA9901
    val SUPPORTED_PIDS = setOf(PRODUCT_ID_MK2_REV, PRODUCT_ID_MK2_ORIG)

    // ---- Report geometry -------------------------------------------------
    const val INPUT_REPORT_SIZE = 512
    const val OUTPUT_REPORT_SIZE = 1024
    const val FEATURE_REPORT_SIZE = 32

    /** Recommended poll period from the spec. */
    const val POLL_PERIOD_MS = 50L

    // ---- Grid ------------------------------------------------------------
    const val COLS = 5
    const val ROWS = 3
    const val KEY_COUNT = COLS * ROWS

    // ---- Report IDs ------------------------------------------------------
    const val INPUT_REPORT_ID = 0x01
    const val SETTER_FEATURE_REPORT_ID = 0x03
    const val GET_SERIAL_REPORT_ID = 0x06
    const val GET_UNIT_INFO_REPORT_ID = 0x08

    // ---- Input command ---------------------------------------------------
    const val INPUT_CMD_KEY_STATE = 0x00

    // ---- Setter feature commands ----------------------------------------
    const val FEATURE_CMD_SHOW_LOGO = 0x02
    const val FEATURE_CMD_FILL_LCD_COLOR = 0x05
    const val FEATURE_CMD_FILL_KEY_COLOR = 0x06
    const val FEATURE_CMD_BRIGHTNESS = 0x08
    const val FEATURE_CMD_SLEEP_DURATION = 0x0D

    // ---- USB control transfer constants ---------------------------------
    /** HID GET_REPORT, device-to-host, standard, interface. */
    const val CTRL_IN_GET_REPORT = 0xA1
    /** HID SET_REPORT, host-to-device, class, interface. */
    const val CTRL_OUT_SET_REPORT = 0x21
    const val CTRL_REQ_GET_REPORT = 0x01
    const val CTRL_REQ_SET_REPORT = 0x09
    /** wValue high byte = report type: 1 Input, 2 Output, 3 Feature. */
    const val REPORT_TYPE_INPUT = 0x01
    const val REPORT_TYPE_OUTPUT = 0x02
    const val REPORT_TYPE_FEATURE = 0x03

    // =====================================================================
    // INPUT DECODING
    // =====================================================================

    /** Raw key-state bytes from an input report, or null if not a key event. */
    fun keyStateBytes(report: ByteArray): ByteArray? {
        if (report.size < 4) return null
        val reportId = report[0].toInt() and 0xFF
        val command = report[1].toInt() and 0xFF
        val len = (report[2].toInt() and 0xFF) or ((report[3].toInt() and 0xFF) shl 8)

        // Small read (1 + KEY_COUNT): byte 0 is the report id and the key bytes
        // follow immediately. This is the layout python-elgato-streamdeck reads
        // and it is what the MK.2 actually returns for a 16-byte request.
        if (report.size == 1 + KEY_COUNT) {
            return report.copyOfRange(1, report.size)
        }

        if (reportId == INPUT_REPORT_ID &&
            command == INPUT_CMD_KEY_STATE &&
            len > 0
        ) {
            val avail = report.size - 4
            val n = minOf(len, avail, KEY_COUNT)
            return if (n > 0) report.copyOfRange(4, 4 + n) else null
        }
        return null
    }

    /**
     * Decode pressed keys from an input report.
     *
     * Per spec each byte is 0x00 depressed or 0x01 pressed for the
     * corresponding key index.
     */
    fun pressedKeys(report: ByteArray): Set<Int> {
        val states = keyStateBytes(report) ?: return emptySet()
        val out = mutableSetOf<Int>()
        states.forEachIndexed { i, b ->
            if ((b.toInt() and 0xFF) == 0x01) out.add(i)
        }
        return out
    }

    fun describe(report: ByteArray): String {
        val id = report.getOrNull(0)?.let { "%02X".format(it.toInt() and 0xFF) } ?: "--"
        val cmd = report.getOrNull(1)?.let { "%02X".format(it.toInt() and 0xFF) } ?: "--"
        val states = keyStateBytes(report)
        return if (states == null) {
            "id=$id cmd=$cmd (not a key event)"
        } else {
            val pressed = mutableListOf<Int>()
            states.forEachIndexed { i, b ->
                if ((b.toInt() and 0xFF) == 0x01) pressed.add(i)
            }
            "id=$id cmd=$cmd keys=${states.size} pressed=$pressed"
        }
    }

    fun keyLabel(index: Int): String {
        val row = index / COLS
        val col = index % COLS
        return "r${row}c${col}"
    }

    // =====================================================================
    // OUTPUT REPORTS (images, 1024 bytes)
    // =====================================================================

    /** Output report ID for image/command output reports on this family. */
    const val OUTPUT_REPORT_ID = 0x02
    const val OUTPUT_CMD_SET_KEY_IMAGE = 0x07

    /**
     * Build chunked output reports for a key image.
     *
     * Header layout, per the Elgato XL/image-capable spec:
     *   +0x00 UINT8   Report ID = 0x02
     *   +0x01 UINT8   Command   = 0x07 (update key image)
     *   +0x02 UINT8   Key index
     *   +0x03 UINT8   Transfer is Done flag (0x01 on the last chunk)
     *   +0x04 UINT16  Chunk contents size (little endian)
     *   +0x06 UINT16  Chunk index, zero based (little endian)
     *   +0x08 BYTE[]  JPEG data
     *
     * The whole report is padded with zeroes to 1024 bytes.
     *
     * CRITICAL: images must be rotated 180 degrees before upload on every
     * model in this family, or the device accepts the report and shows
     * nothing. That is applied by the caller via [rotate180].
     */
    fun buildKeyImageReports(keyIndex: Int, jpeg: ByteArray): List<ByteArray> {
        val headerSize = 8
        val chunkSize = OUTPUT_REPORT_SIZE - headerSize
        val chunks = mutableListOf<ByteArray>()

        var page = 0
        var remaining = jpeg.size

        while (remaining > 0) {
            val thisLength = minOf(remaining, chunkSize)
            val bytesSent = page * chunkSize
            val isLast = if (thisLength == remaining) 0x01 else 0x00

            val r = ByteArray(OUTPUT_REPORT_SIZE)
            r[0] = OUTPUT_REPORT_ID.toByte()
            r[1] = OUTPUT_CMD_SET_KEY_IMAGE.toByte()
            r[2] = (keyIndex and 0xFF).toByte()
            r[3] = isLast.toByte()
            r[4] = (thisLength and 0xFF).toByte()
            r[5] = ((thisLength shr 8) and 0xFF).toByte()
            r[6] = (page and 0xFF).toByte()
            r[7] = ((page shr 8) and 0xFF).toByte()

            System.arraycopy(jpeg, bytesSent, r, headerSize, thisLength)
            chunks.add(r)

            remaining -= thisLength
            page++
        }

        if (chunks.isEmpty()) {
            val r = ByteArray(OUTPUT_REPORT_SIZE)
            r[0] = OUTPUT_REPORT_ID.toByte()
            r[1] = OUTPUT_CMD_SET_KEY_IMAGE.toByte()
            r[2] = (keyIndex and 0xFF).toByte()
            r[3] = 0x01
            chunks.add(r)
        }
        return chunks
    }

    /**
     * Reset the key image stream.
     *
     * A partially-written previous image corrupts the next one, so this blank
     * 1024-byte report is sent before any image sequence.
     */
    fun buildResetKeyStream(): ByteArray =
        ByteArray(OUTPUT_REPORT_SIZE).also { it[0] = OUTPUT_REPORT_ID.toByte() }

    /** Convenience: single-report image for small JPEGs. */
    fun buildKeyImageReport(keyIndex: Int, jpeg: ByteArray): ByteArray =
        buildKeyImageReports(keyIndex, jpeg).first()

    // =====================================================================
    // FEATURE REPORTS (32 bytes, control transfer)
    // =====================================================================

    private fun feature(): ByteArray = ByteArray(FEATURE_REPORT_SIZE)

    /** Show the boot logo. Feature 0x03 / cmd 0x02. */
    fun buildShowLogo(): ByteArray = feature().also {
        it[0] = SETTER_FEATURE_REPORT_ID.toByte()
        it[1] = FEATURE_CMD_SHOW_LOGO.toByte()
    }

    /** Fill the whole LCD with a colour. Feature 0x03 / cmd 0x05. */
    fun buildFillLcd(r: Int, g: Int, b: Int): ByteArray = feature().also {
        it[0] = SETTER_FEATURE_REPORT_ID.toByte()
        it[1] = FEATURE_CMD_FILL_LCD_COLOR.toByte()
        it[2] = r.toByte(); it[3] = g.toByte(); it[4] = b.toByte()
    }

    /** Fill one key with a colour. Feature 0x03 / cmd 0x06. */
    fun buildFillKey(keyIndex: Int, r: Int, g: Int, b: Int): ByteArray = feature().also {
        it[0] = SETTER_FEATURE_REPORT_ID.toByte()
        it[1] = FEATURE_CMD_FILL_KEY_COLOR.toByte()
        it[2] = keyIndex.toByte()
        it[3] = r.toByte(); it[4] = g.toByte(); it[5] = b.toByte()
    }

    /** Backlight brightness 0x00..0x64. Feature 0x03 / cmd 0x08. */
    fun buildBrightness(percent: Int): ByteArray = feature().also {
        it[0] = SETTER_FEATURE_REPORT_ID.toByte()
        it[1] = FEATURE_CMD_BRIGHTNESS.toByte()
        it[2] = percent.coerceIn(0, 100).toByte()
    }

    /**
     * Brightness as an OUTPUT report (interrupt endpoint).
     *
     * ANDROID NOTE: controlTransfer to this HID interface fails with -1 on
     * Pixel 9 Pro even with permission granted and the interface claimed,
     * while bulkTransfer on the interrupt endpoint works. Feature reports are
     * therefore unusable there. The same command bytes are valid as an output
     * report, which travels the channel that does work.
     */
    fun buildBrightnessOutput(percent: Int): ByteArray =
        ByteArray(OUTPUT_REPORT_SIZE).also {
            it[0] = SETTER_FEATURE_REPORT_ID.toByte()
            it[1] = FEATURE_CMD_BRIGHTNESS.toByte()
            it[2] = percent.coerceIn(0, 100).toByte()
        }

    /** Show logo as an OUTPUT report, for platforms where controlTransfer fails. */
    fun buildShowLogoOutput(): ByteArray =
        ByteArray(OUTPUT_REPORT_SIZE).also {
            it[0] = SETTER_FEATURE_REPORT_ID.toByte()
            it[1] = FEATURE_CMD_SHOW_LOGO.toByte()
        }

    /** Sleep duration as an OUTPUT report. */
    fun buildSleepOutput(seconds: Int): ByteArray =
        ByteArray(OUTPUT_REPORT_SIZE).also {
            it[0] = SETTER_FEATURE_REPORT_ID.toByte()
            it[1] = FEATURE_CMD_SLEEP_DURATION.toByte()
            it[2] = (seconds and 0xFF).toByte()
            it[3] = ((seconds shr 8) and 0xFF).toByte()
            it[4] = ((seconds shr 16) and 0xFF).toByte()
            it[5] = ((seconds shr 24) and 0xFF).toByte()
        }

    /** Fill key colour as an OUTPUT report. */
    fun buildFillKeyOutput(keyIndex: Int, r: Int, g: Int, b: Int): ByteArray =
        ByteArray(OUTPUT_REPORT_SIZE).also {
            it[0] = SETTER_FEATURE_REPORT_ID.toByte()
            it[1] = FEATURE_CMD_FILL_KEY_COLOR.toByte()
            it[2] = keyIndex.toByte()
            it[3] = r.toByte(); it[4] = g.toByte(); it[5] = b.toByte()
        }

    /** Sleep mode idle duration in seconds; 0 disables. Feature 0x03 / cmd 0x0D. */
    fun buildSleepDuration(seconds: Int): ByteArray = feature().also {
        it[0] = SETTER_FEATURE_REPORT_ID.toByte()
        it[1] = FEATURE_CMD_SLEEP_DURATION.toByte()
        it[2] = (seconds and 0xFF).toByte()
        it[3] = ((seconds shr 8) and 0xFF).toByte()
        it[4] = ((seconds shr 16) and 0xFF).toByte()
        it[5] = ((seconds shr 24) and 0xFF).toByte()
    }

    /** Request report buffer for a getter (serial, unit info, firmware). */
    fun buildGetterRequest(reportId: Int): ByteArray = feature().also {
        it[0] = reportId.toByte()
    }

    /** Parse a serial-number response. */
    fun parseSerial(buf: ByteArray): String? {
        if (buf.size < 3) return null
        if ((buf[0].toInt() and 0xFF) != GET_SERIAL_REPORT_ID) return null
        val len = buf[1].toInt() and 0xFF
        if (len <= 0 || 2 + len > buf.size) return null
        return String(buf, 2, len, Charsets.US_ASCII).trim()
    }

    /** Parse a unit-info response into rows/cols/key size. */
    data class UnitInfo(val rows: Int, val cols: Int, val keyW: Int, val keyH: Int)

    fun parseUnitInfo(buf: ByteArray): UnitInfo? {
        if (buf.size < 0x0B) return null
        if ((buf[0].toInt() and 0xFF) != GET_UNIT_INFO_REPORT_ID) return null
        val rows = buf[1].toInt() and 0xFF
        val cols = buf[2].toInt() and 0xFF
        val w = (buf[3].toInt() and 0xFF) or ((buf[4].toInt() and 0xFF) shl 8)
        val h = (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8)
        return UnitInfo(rows, cols, w, h)
    }
}

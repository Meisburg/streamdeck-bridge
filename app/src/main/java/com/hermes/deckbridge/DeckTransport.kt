package com.hermes.deckbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB transport for the Stream Deck MK.2 (0x006D) on Android. No root.
 *
 * ARCHITECTURE: every byte that touches the device goes through ONE coroutine
 * on a single-threaded dispatcher, driven by a queue.
 *
 * This matters. The deck exposes one interrupt IN endpoint and one interrupt
 * OUT endpoint with no flow control. Earlier versions of this class let setup,
 * image upload and polling each call into the connection from different
 * threads. The device then balked partway through a burst: the first several
 * key images would land and the rest would fail, and feature writes would time
 * out. That is endpoint contention, not a protocol error, and no amount of
 * header tweaking fixes it. Serializing does.
 *
 * Channels, per the Elgato HID spec:
 *   SETUP  -> feature reports, controlTransfer, 32B, report ID 0x03
 *   INPUT  -> polled reads on the interrupt IN endpoint, 512B
 *   IMAGES -> output reports on the interrupt OUT endpoint, 1024B
 */
class DeckTransport(private val ctx: Context) {

    companion object {
        private const val TAG = "DeckTransport"
        const val ACTION_USB_PERMISSION = "com.hermes.deckbridge.USB_PERMISSION"

        /** Per-poll read timeout. Short: we poll on the spec cadence. */
        private const val READ_TIMEOUT_MS = 250

        /**
         * Pause after a failed (-1) read.
         *
         * A -1 is expected on this endpoint, so this is throttling, not error
         * handling. Without it, a failing read spins the loop at full speed and
         * burns a core for as long as the panel stays quiet.
         */
        private const val READ_RETRY_DELAY_MS = 40L

        /** Delay between image writes, so a burst does not saturate the endpoint. */
        private const val INTER_WRITE_DELAY_MS = 12L

        private const val WRITE_TIMEOUT_MS = 800
    }

    sealed class State {
        object Disconnected : State()
        data class Connected(val product: String, val serial: String) : State()
        data class Error(val message: String) : State()
    }

    private val usbManager: UsbManager =
        ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * I/O coroutines.
     *
     * Two dedicated single-thread dispatchers rather than one. The reader's
     * bulkTransfer blocks its thread for up to READ_TIMEOUT_MS, so sharing a
     * single-threaded dispatcher with the writer would starve the writer
     * completely: queued commands would never be sent. The endpoints are
     * inherently half-duplex (one IN, one OUT) so one thread each is both
     * sufficient and safe.
     */
    private val readDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val readScope = CoroutineScope(readDispatcher + Job())
    private val writeScope = CoroutineScope(writeDispatcher + Job())

    private var device: UsbDevice? = null
    private var iface: UsbInterface? = null
    private var connection: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private var readerJob: Job? = null
    private var workerJob: Job? = null

    /** Requests are queued and consumed by the single I/O worker. */
    private sealed class Op {
        data class Write(val report: ByteArray, val label: String) : Op()
        data class Feature(val report: ByteArray, val label: String) : Op()
        object Idle : Op()
    }

    private val queue = Channel<Op>(capacity = Channel.UNLIMITED)

    private val _keyEvents = MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 128)
    val keyEvents: SharedFlow<Pair<Int, Boolean>> = _keyEvents

    private val _rawReports = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val rawReports: SharedFlow<String> = _rawReports

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _lastBitmap = MutableStateFlow("")
    val lastBitmap: StateFlow<String> = _lastBitmap

    private val _diag = MutableStateFlow("")
    val diag: StateFlow<String> = _diag

    private var previousDown: Set<Int> = emptySet()
    private var polls = 0L
    private var reports = 0L

    /**
     * Invoked from the reader when the handle is proven dead (repeated -1
     * reads). Drops the stale connection and asks for permission again.
     */
    private val onHandleDead: (() -> Unit)? = {
        writeScope.launch {
            Log.w(TAG, "recovering from dead handle")
            runCatching { connection?.close() }
            connection = null
            opening.set(false)
            readerJob?.cancel()
            _state.value = State.Error("USB link died; reconnecting")
            delay(300)
            requestPermissionIfNeeded()
        }
        Unit
    }

    private val opening = AtomicBoolean(false)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                Log.i(TAG, "USB permission granted")
                open()
            } else {
                _state.value = State.Error("USB permission denied")
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    private val started = AtomicBoolean(false)

    fun start() {
        // Register the permission receiver exactly once per process.
        if (started.compareAndSet(false, true)) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(permissionReceiver, filter)
            }
        }
        // Always (re-)attempt the connection. The USB grant is lost on reinstall
        // and on process restart, and a stale "already started" flag must never
        // stop us from asking again or the reader never begins.
        ensureConnected()
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            runCatching { ctx.unregisterReceiver(permissionReceiver) }
        }
        readerJob?.cancel()
        workerJob?.cancel()
        runCatching { connection?.close() }
        connection = null
        opening.set(false)
        _state.value = State.Disconnected
    }

    /** Connect if a deck is present and we are not already connected. */
    fun ensureConnected() {
        val found = findDeck()
        if (found == null) {
            Log.i(TAG, "ensureConnected: no deck present")
            _state.value = State.Disconnected
            return
        }
        device = found

        // A non-null connection is NOT proof of a usable link. The USB grant is
        // dropped on reinstall, on process restart, and sometimes on replug,
        // leaving a stale handle that returns -1 on every read forever. Re-check
        // BOTH the permission and the handle's file descriptor every time.
        val havePermission = usbManager.hasPermission(found)
        val fd = connection?.fileDescriptor ?: -1
        val handleAlive = fd >= 0

        if (connection != null && havePermission && handleAlive) {
            return
        }
        if (connection != null && (!havePermission || !handleAlive)) {
            Log.w(TAG, "ensureConnected: stale handle (perm=$havePermission fd=$fd); reopening")
            runCatching { connection?.close() }
            connection = null
            opening.set(false)
            readerJob?.cancel()
            workerJob?.cancel()
            rearm()
        }

        if (havePermission) {
            Log.i(TAG, "ensureConnected: permission held, opening")
            open()
        } else {
            Log.i(TAG, "ensureConnected: requesting USB permission")
            _state.value = State.Error("Waiting for USB permission")
            askPermission(found)
        }
    }

    fun requestPermissionIfNeeded() {
        val found = findDeck()
        if (found == null) {
            _state.value = State.Disconnected
            return
        }
        device = found
        if (usbManager.hasPermission(found)) open() else askPermission(found)
    }

    private fun askPermission(dev: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), flags
        )
        usbManager.requestPermission(dev, pi)
    }

    fun findDeck(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { d ->
            d.vendorId == DeckProtocol.VENDOR_ID &&
                d.productId in DeckProtocol.SUPPORTED_PIDS
        }

    // =====================================================================
    // Open
    // =====================================================================

    private fun open() {
        val dev = device ?: return

        // Single-flight. The permission broadcast and onResume both call this,
        // and a second caller would contend with the first over the endpoints.
        if (!opening.compareAndSet(false, true)) {
            Log.i(TAG, "open() skipped: already connecting or connected")
            return
        }

        val target = (0 until dev.interfaceCount)
            .map { dev.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: dev.getInterface(0)
        iface = target

        val inEp = (0 until target.endpointCount)
            .map { target.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        val outEp = (0 until target.endpointCount)
            .map { target.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }

        if (inEp == null) {
            _state.value = State.Error("No interrupt IN endpoint")
            opening.set(false)
            return
        }
        epIn = inEp
        epOut = outEp

        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            _state.value = State.Error("openDevice returned null (permission lost?)")
            opening.set(false)
            return
        }

        // VERIFY THE HANDLE IS REAL.
        //
        // A non-null UsbDeviceConnection is NOT proof of a usable link. When the
        // USB grant has lapsed, openDevice() can still hand back an object whose
        // file descriptor is closed, and every bulkTransfer on it then returns
        // -1 forever. That produced a "connected" app that received nothing, and
        // it looked exactly like flaky hardware.
        //
        // fileDescriptor == -1 means no real fd: drop the handle and ask for
        // permission again rather than sitting in a fake-connected state.
        if (conn.fileDescriptor < 0) {
            Log.w(TAG, "openDevice gave a dead handle (fd=${conn.fileDescriptor}); re-requesting")
            runCatching { conn.close() }
            connection = null
            opening.set(false)
            _state.value = State.Error("USB handle invalid; re-requesting permission")
            askPermission(dev)
            return
        }

        if (!conn.claimInterface(target, true) && !conn.claimInterface(target, false)) {
            conn.close()
            connection = null
            opening.set(false)
            _state.value = State.Error("Could not claim HID interface")
            return
        }
        connection = conn
        Log.i(TAG, "handle ok fd=${conn.fileDescriptor}")

        val serial = runCatching { dev.serialNumber }.getOrNull() ?: "unknown"
        Log.i(
            TAG,
            "Opened ${dev.productName} sn=$serial iface=${target.id} " +
                "epIn=0x${"%02X".format(inEp.address)} " +
                "epOut=${outEp?.let { "0x%02X".format(it.address) } ?: "none"}"
        )

        // Start the single I/O worker, then hand it the bring-up sequence.
        startWorker(conn)
        bringUp()
        startReader(conn)
    }

    // =====================================================================
    // The single I/O worker
    // =====================================================================

    private fun startWorker(conn: UsbDeviceConnection) {
        workerJob?.cancel()
        workerJob = writeScope.launch {
            while (isActive) {
                val op = queue.receive()
                when (op) {
                    is Op.Feature -> {
                        val ok = setFeatureInternal(conn, op.report)
                        Log.i(TAG, "feature '${op.label}' ${if (ok) "ok" else "FAILED"}")
                    }
                    is Op.Write -> {
                        val ok = writeOutputInternal(conn, op.report)
                        Log.i(TAG, "write '${op.label}' ${if (ok) "ok" else "FAILED"}")
                    }
                    Op.Idle -> { /* yield point */ }
                }
            }
        }
    }

    /**
     * Bring the panel up.
     *
     * ORDER MATTERS and was taken from the working elgato-streamdeck Rust
     * driver (OpenDeck's backend). Its reset() does:
     *     1. hid.write(reset_key_stream_packet)      <- OUTPUT report first
     *     2. hid.send_feature_report(reset_packet)   <- FEATURE report second
     *
     * An earlier version of this code sent the feature reports first and the
     * key-stream reset last, which did not wake the panel: it accepted every
     * command and then reported no key state.
     */
    private fun bringUp() {
        // 1. Reset the key stream on the OUTPUT channel.
        queue.trySend(Op.Write(DeckProtocol.buildResetKeyStream(), "resetKeyStream"))
        // 2. Feature reset (show logo) on the FEATURE channel.
        queue.trySend(Op.Feature(DeckProtocol.buildShowLogo(), "reset"))
        // 3. Brightness wake, then disable sleep so it keeps reporting.
        queue.trySend(Op.Feature(DeckProtocol.buildBrightness(60), "brightness60"))
        queue.trySend(Op.Feature(DeckProtocol.buildSleepDuration(0), "sleepOff"))
    }

    // =====================================================================
    // Low-level transfers (worker thread only)
    // =====================================================================

    private fun setFeatureInternal(conn: UsbDeviceConnection, buf: ByteArray): Boolean = try {
        conn.controlTransfer(
            DeckProtocol.CTRL_OUT_SET_REPORT,
            DeckProtocol.CTRL_REQ_SET_REPORT,
            DeckProtocol.REPORT_TYPE_FEATURE shl 8,
            0, buf, buf.size, WRITE_TIMEOUT_MS
        ) >= 0
    } catch (t: Throwable) {
        Log.w(TAG, "setFeature threw: ${t.message}")
        false
    }

    private fun writeOutputInternal(conn: UsbDeviceConnection, report: ByteArray): Boolean {
        val ep = epOut ?: return false
        val viaBulk = try {
            conn.bulkTransfer(ep, report, report.size, WRITE_TIMEOUT_MS)
        } catch (t: Throwable) {
            -1
        }
        if (viaBulk >= 0) return true
        return try {
            conn.controlTransfer(
                DeckProtocol.CTRL_OUT_SET_REPORT,
                DeckProtocol.CTRL_REQ_SET_REPORT,
                DeckProtocol.REPORT_TYPE_OUTPUT shl 8,
                0, report, report.size, WRITE_TIMEOUT_MS
            ) >= 0
        } catch (t: Throwable) {
            false
        }
    }

    // =====================================================================
    // Reader (also serialized: it yields between polls)
    // =====================================================================

    private fun startReader(conn: UsbDeviceConnection) {
        readerJob?.cancel()
        val ep = epIn ?: run {
            Log.w(TAG, "startReader: no IN endpoint")
            return
        }
        val connFd = conn.fileDescriptor
        if (connFd < 0) {
            Log.w(TAG, "startReader: refusing to start, conn fd=$connFd")
            return
        }
        val epAddr = ep.address

        readerJob = readScope.launch {
            Log.i(TAG, "reader started ep=0x${"%02X".format(epAddr)} connFd=$connFd")

            // READ VIA bulkTransfer, AND TOLERATE -1.
            //
            // This is deliberately the approach that WORKED, restored after an
            // attempt to "fix" it made things worse. History, because it matters:
            //
            //   * The deck's endpoints are INTERRUPT (type 3). The Android docs
            //     say interrupt transfers are asynchronous-only, and in practice
            //     bulkTransfer on this endpoint returns -1 rather than blocking
            //     and returning data.
            //   * That -1 storm looked like the bug, so the reader was rewritten
            //     around UsbRequest.queue() + requestWait(). THAT path SIGSEGVs
            //     inside libusbhost.so at usb_request_wait+188 on this device --
            //     both the timeout and the no-arg overloads. It kills the reader
            //     thread and, with it, the app. A flaky reader was traded for a
            //     fatal one.
            //   * Key input DID work on this hardware with the bulkTransfer loop:
            //     the log shows real "keys down=[n]" events and macros firing.
            //     See STATUS.md, "Key detection | real keys down=[n] events".
            //
            // So: read with bulkTransfer, treat -1 as "nothing this cycle" rather
            // than as an error, and re-arm (re-send the wake sequence) after a
            // sustained run of failures. Never call requestWait() here.
            val readSize = DeckProtocol.INPUT_REPORT_SIZE
            val buf = ByteArray(readSize)

            var consecutiveFails = 0
            var firstResults = 0

            while (isActive) {
                polls++
                if (polls % 40 == 0L) {
                    Log.i(TAG, "poll #$polls (reports=$reports)")
                }

                val n = try {
                    conn.bulkTransfer(ep, buf, readSize, READ_TIMEOUT_MS)
                } catch (t: Throwable) {
                    Log.w(TAG, "read threw: ${t::class.java.simpleName} ${t.message}")
                    -1
                }

                // DIAGNOSTIC: try the HID GET_REPORT control path on the same
                // cycle. bulkTransfer is documented as invalid for INTERRUPT
                // endpoints and returns -1 on every call here, so if key state is
                // readable at all it is through this route.
                if (polls <= 6L) {
                    val ctrl = try {
                        conn.controlTransfer(
                            DeckProtocol.CTRL_IN_GET_REPORT,
                            DeckProtocol.CTRL_REQ_GET_REPORT,
                            DeckProtocol.REPORT_TYPE_INPUT shl 8,
                            0,
                            buf,
                            readSize,
                            READ_TIMEOUT_MS
                        )
                    } catch (t: Throwable) {
                        -999
                    }
                    Log.i(
                        TAG,
                        "diag poll=$polls bulk=$n ctrlGETREPORT=$ctrl" +
                            (if (ctrl > 0) " data=" + buf.take(minOf(ctrl, 16))
                                .joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) } else "")
                    )
                }

                if (n > 0) {
                    consecutiveFails = 0
                    reports++
                    if (firstResults < 5) {
                        firstResults++
                        Log.i(TAG, "first-read #$firstResults -> $n bytes (ep=" + epAddr.toString(16) + ")")
                    }
                    val hex = buf.take(minOf(n, 20)).joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
                    Log.i(TAG, "read[$n] $hex")
                    handleInput(buf.copyOf(n), "intr")
                    continue
                }

                // n == 0 is a clean timeout: no key event pending. Normal.
                if (n == 0) {
                    consecutiveFails = 0
                    continue
                }

                // n == -1: transfer failed. Not fatal, but if it persists the panel
                // has stopped reporting and needs waking.
                consecutiveFails++
                if (consecutiveFails == 10 || consecutiveFails == 40 || consecutiveFails == 120) {
                    Log.w(TAG, "read failed x$consecutiveFails (-1); re-arming")
                    bringUp()
                }
                delay(READ_RETRY_DELAY_MS)
            }
        }
    }

    private fun handleInput(report: ByteArray, channel: String) {
        val desc = DeckProtocol.describe(report)
        _lastBitmap.value = desc
        _rawReports.tryEmit(desc)

        val down = DeckProtocol.pressedKeys(report)
        val pressed = down - previousDown
        val released = previousDown - down

        if (pressed.isNotEmpty() || released.isNotEmpty()) {
            Log.i(TAG, "keys down=$down (via $channel)")
        }
        pressed.forEach { _keyEvents.tryEmit(it to true) }
        released.forEach { _keyEvents.tryEmit(it to false) }
        previousDown = down
    }

    // =====================================================================
    // Public API (queues work, never touches the device directly)
    // =====================================================================

    fun setBrightness(percent: Int) {
        queue.trySend(Op.Write(DeckProtocol.buildBrightnessOutput(percent), "brightness$percent"))
    }

    fun fillKey(keyIndex: Int, r: Int, g: Int, b: Int) {
        queue.trySend(Op.Write(DeckProtocol.buildFillKeyOutput(keyIndex, r, g, b), "fillKey$keyIndex"))
    }

    /** Queue one key image. Returns immediately; writes happen on the worker. */
    fun sendKeyImage(keyIndex: Int, jpeg: ByteArray) {
        val rotated = KeyImage.rotate180(jpeg)
        DeckProtocol.buildKeyImageReports(keyIndex, rotated).forEachIndexed { i, r ->
            queue.trySend(Op.Write(r, "img$keyIndex/$i"))
        }
    }

    /** Clear every key to black. */
    fun clearAllKeys() {
        for (k in 0 until DeckProtocol.KEY_COUNT) {
            queue.trySend(Op.Write(DeckProtocol.buildFillKeyOutput(k, 0, 0, 0), "clear$k"))
        }
    }

    /**
     * Re-arm key reporting.
     *
     * Called after connect and periodically thereafter. A wedged key stream is
     * the observed failure mode: the device answers polls with zero-length
     * payloads and never reports again, and re-sending the reset plus a
     * brightness write brings it back without restarting the app.
     */
    fun rearm() {
        queue.trySend(Op.Feature(DeckProtocol.buildBrightness(60), "rearm-brightness"))
        queue.trySend(Op.Write(DeckProtocol.buildResetKeyStream(), "rearm-reset"))
        Log.i(TAG, "rearm queued")
    }

    /** Total input reports received since connect. Used to detect a dead stream. */
    fun reportCount(): Long = reports

    /** Queue depth is not exposed; this reports whether the link exists. */
    fun isConnected(): Boolean = connection != null
}

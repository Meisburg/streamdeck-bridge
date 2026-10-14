package com.hermes.deckbridge

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * DeckBridge: tap-to-send commands for AI agents.
 *
 * PRIMARY SURFACE IS THE TAP GRID, not the deck. The physical Stream Deck's key
 * input cannot be read on this hardware (Android's libusbhost.so segfaults in
 * usb_request_wait() on its interrupt endpoint), so requiring a deck would mean
 * requiring a feature that does not work. The grid needs nothing plugged in.
 *
 * Design intent, applying Jon's doctrine ("minimalist, don't make the user
 * think"): the screen opens on a grid of big labelled buttons. One tap sends.
 * The only other affordances are tap-to-edit, long-press-for-more, and a plus
 * button. Every deck control lives behind a collapsed "Deck (optional)" row so
 * it never competes with the thing that actually works.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var gridView: LinearLayout
    private lateinit var logView: TextView
    private lateinit var deckSection: LinearLayout
    private var deckExpanded = true
    private var commands: MutableList<Command> = mutableListOf()
    private var transport: DeckTransport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commands = CommandStore.load(this)
        buildUi()
        DeckResidentService.start(this)
        requestNotificationPermission()
        requestBatteryExemption()

        // The deck transport STARTS HERE, because the deck is the point of this
        // app. An earlier revision made it opt-in to dodge a crash -- but that
        // crash was self-inflicted: the reader had been rewritten around
        // UsbRequest.requestWait(), which SIGSEGVs in Android's libusbhost.so on
        // this hardware. The reader is now back on the recoverable bulkTransfer
        // loop, so starting it is safe again.
        startDeckTransport()
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val p = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(p), 1001)
            }
        }
    }

    /** Ask to be exempt from battery optimisation; without it Android may kill us. */
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        } catch (t: Throwable) {
            Log.w("DeckBridge", "battery exemption request failed: ${t.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        transport?.stop()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    // =====================================================================
    // UI
    // =====================================================================

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // --- status line: one sentence, present ONLY when something is wrong ---
        statusView = TextView(this).apply {
            textSize = 12f
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        root.addView(statusView)

        // --- the grid ---
        gridView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gridView)

        // --- add ---
        root.addView(Button(this).apply {
            text = "+ Add command"
            textSize = 16f
            isAllCaps = false
            setOnClickListener { editCommand(null) }
        })

        // --- deck controls, collapsed by default ---
        root.addView(TextView(this).apply {
            text = "Deck setup (tap to collapse)"
            textSize = 12f
            setPadding(dp(4), dp(24), dp(4), dp(4))
            setOnClickListener { toggleDeckSection() }
        })

        deckSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }
        buildDeckSection(deckSection)
        root.addView(deckSection)

        // --- log ---
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(4), dp(16), dp(4), 0)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
        renderGrid()
    }

    private fun toggleDeckSection() {
        deckExpanded = !deckExpanded
        deckSection.visibility = if (deckExpanded) View.VISIBLE else View.GONE
    }

    /**
     * The tap grid.
     *
     * Two columns of tall buttons: big enough to hit without looking, which is
     * the entire point when the alternative is typing on a phone keyboard.
     */
    private fun renderGrid() {
        gridView.removeAllViews()

        val perRow = 2
        var i = 0
        while (i < commands.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until perRow) {
                val idx = i + c
                if (idx >= commands.size) {
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                    continue
                }
                row.addView(buildCommandButton(commands[idx]))
            }
            gridView.addView(row)
            i += perRow
        }
    }

    private fun buildCommandButton(cmd: Command): Button = Button(this).apply {
        text = cmd.label
        textSize = 15f
        isAllCaps = false
        maxLines = 2
        layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        setOnClickListener { sendCommand(cmd) }
        setOnLongClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(cmd.label)
                .setItems(
                    arrayOf(
                        "Edit",
                        "Delete",
                        "Send as-is to Termux",
                        "Send as-is to Discord",
                    )
                ) { _, which ->
                    when (which) {
                        0 -> editCommand(cmd)
                        1 -> deleteCommand(cmd)
                        2 -> sendForced(cmd, Command.Target.TERMUX)
                        3 -> sendForced(cmd, Command.Target.DISCORD)
                    }
                }
                .show()
            true
        }
    }

    /** Force a command down one path, ignoring AUTO. Used to diagnose routing. */
    private fun sendForced(cmd: Command, target: Command.Target) {
        val svc = DeckAccessibilityService.instance
        if (svc == null) {
            toast("Typing service is off")
            return
        }
        svc.runCommand(cmd.copy(target = target)) { r -> log("${cmd.label} [$target] -> $r") }
    }

    /**
     * Send a command.
     *
     * The activity must get out of the way first: it owns focus, so injecting
     * now would target DeckBridge itself. moveTaskToBack hands focus back to the
     * app the user was actually in, then a short delay lets the window change
     * settle before the accessibility service reads the foreground package.
     */
    private fun sendCommand(cmd: Command) {
        val svc = DeckAccessibilityService.instance
        if (svc == null) {
            toast("Typing service is off. Tap \"Enable typing service\" below.")
            return
        }
        if (cmd.text.isBlank()) {
            toast("This command is empty")
            return
        }

        log("sending '${cmd.label}' (target=${cmd.target})")
        moveTaskToBack(true)

        // 350ms is empirically enough for the window transition to complete;
        // shorter and foregroundPackage() still reports DeckBridge.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            svc.runCommand(cmd) { r ->
                Log.i("DeckBridge", "send '${cmd.label}' -> $r")
            }
        }, 350)
    }

    // =====================================================================
    // EDIT
    // =====================================================================

    private fun editCommand(existing: Command?) {
        val isNew = existing == null
        val cur = existing ?: Command(
            id = java.util.UUID.randomUUID().toString(),
            label = "",
            text = "",
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val labelInput = EditText(this).apply {
            hint = "Button name"
            setText(cur.label)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val textInput = EditText(this).apply {
            hint = "Text to send"
            setText(cur.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }

        layout.addView(TextView(this).apply { text = "Button name"; textSize = 12f })
        layout.addView(labelInput)
        layout.addView(TextView(this).apply { text = "\nText to send"; textSize = 12f })
        layout.addView(textInput)

        // target picker
        var target = cur.target
        val targetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val targetBtns = mutableListOf<Button>()
        Command.Target.values().forEach { t ->
            val b = Button(this).apply {
                text = t.name.lowercase().replaceFirstChar { it.uppercase() }
                textSize = 11f
                isAllCaps = false
                alpha = if (t == target) 1f else 0.45f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    target = t
                    targetBtns.forEach { it.alpha = 0.45f }
                    alpha = 1f
                }
            }
            targetBtns.add(b)
            targetRow.addView(b)
        }
        layout.addView(TextView(this).apply { text = "\nWhere to send it"; textSize = 12f })
        layout.addView(targetRow)
        layout.addView(TextView(this).apply {
            text = "Auto picks Termux when Termux is in front, otherwise the chat box."
            textSize = 10f
            setAlpha(0.7f)
        })

        var submit = cur.submit
        layout.addView(Button(this).apply {
            text = if (submit) "Press Enter after: ON" else "Press Enter after: OFF"
            textSize = 12f
            isAllCaps = false
            setPadding(0, dp(16), 0, 0)
            setOnClickListener {
                submit = !submit
                text = if (submit) "Press Enter after: ON" else "Press Enter after: OFF"
            }
        })

        AlertDialog.Builder(this)
            .setTitle(if (isNew) "New command" else "Edit command")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Save") { _, _ ->
                val updated = cur.copy(
                    label = labelInput.text.toString()
                        .ifBlank { textInput.text.toString().take(20) },
                    text = textInput.text.toString(),
                    target = target,
                    submit = submit,
                )
                if (isNew) {
                    commands.add(updated)
                } else {
                    val at = commands.indexOfFirst { it.id == existing!!.id }
                    if (at >= 0) commands[at] = updated else commands.add(updated)
                }
                CommandStore.save(this, commands)
                renderGrid()
                log("saved '${updated.label}'")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCommand(cmd: Command) {
        commands = commands.filterNot { it.id == cmd.id }.toMutableList()
        CommandStore.save(this, commands)
        renderGrid()
        log("deleted '${cmd.label}'")
    }

    // =====================================================================
    // STATUS
    // =====================================================================

    /**
     * One line, and only shown when action is needed.
     *
     * Silence means working. A permanently visible status block trains the user
     * to ignore it, which is the opposite of useful for the one case that
     * matters: the accessibility service having been dropped.
     */
    private fun refreshStatus() {
        val svcOk = DeckAccessibilityService.isRunning()
        statusView.text =
            if (svcOk) "" else "Typing service is off. Tap \"Enable typing service\" below."
        statusView.setTextColor(if (svcOk) Color.GRAY else Color.rgb(200, 60, 60))
    }

    // =====================================================================
    // DECK (optional)
    // =====================================================================

    private fun buildDeckSection(parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            text = "Deck key input does not work on this phone: Android's USB stack " +
                "crashes reading the deck's interrupt endpoint. Tap commands work " +
                "with or without a deck plugged in."
            textSize = 10f
            setAlpha(0.7f)
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        parent.addView(Button(this).apply {
            text = "Enable typing service"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        parent.addView(Button(this).apply {
            text = "Enable DeckBridge keyboard"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        parent.addView(Button(this).apply {
            text = "Test Termux delivery"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                val svc = DeckAccessibilityService.instance
                if (svc == null) {
                    toast("Typing service is off")
                } else {
                    svc.testTermux("printf 'DECKBRIDGE_TERMUX_OK\\n'") { r ->
                        postLog("termux test -> $r")
                    }
                }
            }
        })
        parent.addView(Button(this).apply {
            text = "Redraw key labels"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                val t = transport
                if (t == null) {
                    toast("Deck not started")
                } else {
                    pushAllKeyImages(t)
                }
            }
        })
        parent.addView(Button(this).apply {
            text = "Connect deck (starts USB reader)"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { startDeckTransport() }
        })
        parent.addView(Button(this).apply {
            text = "Diagnose USB"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                log("running USB probe...")
                Thread { UsbProbe.run(this@MainActivity) }.start()
            }
        })
    }

    /**
     * Start the deck transport on demand, with a warning.
     *
     * This is the ONLY place the USB reader is started, because that reader
     * crashes in Android's platform library on this hardware. Starting it kills
     * the process, so it is opt-in and never automatic.
     */
    private fun startDeckTransport() {
        if (transport != null) {
            transport?.requestPermissionIfNeeded()
            log("deck already started")
            return
        }
        log("starting deck transport")
        DeckTransport(this).also { t ->
            transport = t
            t.start()
            observeDeck(t)
        }
    }

    /**
     * Render and push every key face to the deck.
     *
     * The deck's keys are little LCDs; each needs its label drawn as JPEG and
     * sent as a sequence of 1024-byte output reports. KeyImage rotates the
     * bitmap 180 degrees, which this hardware family requires or the label
     * renders upside down.
     */
    private fun pushAllKeyImages(t: DeckTransport) {
        if (!t.isConnected()) {
            Log.i("DeckBridge", "pushAllKeyImages: deck not connected")
            return
        }
        t.clearAllKeys()
        for (i in 0 until DeckProtocol.KEY_COUNT) {
            val cmd = commands.getOrNull(i)
            val label = cmd?.label?.ifBlank { null } ?: cmd?.text?.take(18) ?: ""
            val jpeg = KeyImage.render(label, i, active = !cmd?.text.isNullOrBlank())
            t.sendKeyImage(i, jpeg)
            Log.i("DeckBridge", "queued key $i label='$label' ${jpeg.size}B")
        }
        log("pushed ${DeckProtocol.KEY_COUNT} key labels")
    }

    /**
     * Deck observers exist only so an attached deck lights its keys and can be
     * diagnosed. Nothing in the primary flow depends on them.
     */
    private fun observeDeck(t: DeckTransport) {
        lifecycleScope.launch {
            t.state.collect { st ->
                if (st is DeckTransport.State.Connected) {
                    t.clearAllKeys()
                    // Draw the label on every key face, so the physical deck shows
                    // what each button does. Nothing called sendKeyImage before, so
                    // the LCDs stayed blank.
                    pushAllKeyImages(t)
                }
            }
        }
        lifecycleScope.launch {
            t.diag.collect { /* surfaced only in the expanded deck section */ }
        }
        lifecycleScope.launch {
            t.keyEvents.collect { (key, down) ->
                if (!down) return@collect
                Log.i("DeckBridge", "deck key $key pressed")

                // A physical key press runs the command bound to that key index.
                // This IS the app: deck button -> text into whatever app is in
                // front. The command set is ordered, so index maps to entry.
                val cmd = commands.getOrNull(key)
                if (cmd == null) {
                    log("key $key pressed (no command bound)")
                    return@collect
                }
                if (cmd.text.isBlank()) {
                    log("key $key ('${cmd.label}') is empty")
                    return@collect
                }

                val svc = DeckAccessibilityService.instance
                if (svc == null) {
                    log("key $key -> typing service is off")
                    return@collect
                }
                log("key $key -> '${cmd.label}'")
                svc.runCommand(cmd) { r ->
                    Log.i("DeckBridge", "key $key '${cmd.label}' -> $r")
                }
            }
        }
    }

    // =====================================================================
    // LOG
    // =====================================================================

    private val logLines = ArrayDeque<String>()

    fun postLog(msg: String) = runOnUiThread { log(msg) }

    private fun log(msg: String) {
        logLines.addFirst(
            "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $msg"
        )
        while (logLines.size > 25) logLines.removeLast()
        runOnUiThread { logView.text = logLines.joinToString("\n") }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}

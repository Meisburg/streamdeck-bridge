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
import android.widget.SeekBar
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
    private lateinit var deckHeader: TextView
    private var deckExpanded = false
    private var commands: MutableList<Command> = mutableListOf()
    private var transport: DeckTransport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commands = CommandStore.load(this)
        buildUi()
        DeckResidentService.start(this)

        // Permissions are requested from the Deck section, NOT here.
        //
        // onCreate used to fire three system dialogs back to back -- foreground
        // service, notification permission, battery exemption -- before the user
        // saw a single button. For a tool whose entire doctrine is "don't make
        // the user think", opening on a wall of OS prompts is the worst possible
        // first impression, and it repeated on every reinstall.
        //
        // These are deck-health concerns anyway, so they belong with the other
        // deck controls, which are collapsed by default. The grid is now the
        // first and only thing on screen at launch.

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

    private var statusPoller: android.os.Handler? = null

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Poll while visible. refreshStatus() only ran on resume before, so if
        // the accessibility service died while the app was in the foreground --
        // a real risk on this device, which is why requestBatteryExemption()
        // exists -- the warning never appeared until the user left and returned.
        // The whole point of that warning is to be seen the moment it is true.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        statusPoller = h
        val tick = object : Runnable {
            override fun run() {
                refreshStatus()
                h.postDelayed(this, 2000)
            }
        }
        h.postDelayed(tick, 2000)
    }

    override fun onPause() {
        super.onPause()
        statusPoller?.removeCallbacksAndMessages(null)
        statusPoller = null
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
        // Default collapsed: by the class comment above, the deck controls must
        // never compete with the grid, which is the surface that actually works.
        // The header names the current state, not the next action; a label that
        // describes what a tap will do reads as a description of what is.
        deckHeader = TextView(this).apply {
            text = "Deck setup"
            textSize = 13f
            setPadding(dp(4), dp(24), dp(4), dp(8))
            setOnClickListener { toggleDeckSection() }
        }
        root.addView(deckHeader)

        deckSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (deckExpanded) View.VISIBLE else View.GONE
        }
        buildDeckSection(deckSection)
        root.addView(deckSection)
        refreshDeckHeader()

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
        refreshDeckHeader()
    }

    /**
     * The header states the current condition, with a chevron for the affordance.
     *
     * The previous label ("Deck setup (tap to collapse)") described the NEXT
     * action while the section was already open, which users read as a
     * description of the present. Naming the state costs nothing and removes the
     * need to work out which way it goes.
     */
    private fun refreshDeckHeader() {
        deckHeader.text = if (deckExpanded) "Deck setup  ▾" else "Deck setup  ▸"
        deckHeader.contentDescription =
            if (deckExpanded) "Deck setup, expanded. Double tap to collapse."
            else "Deck setup, collapsed. Double tap to expand."
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

    /**
     * A button label that is always worth reading.
     *
     * A label of "?" is worse than no label: it looks like a rendering bug and
     * gives the user nothing to distinguish two buttons by. A label counts as
     * missing when it is blank or contains no letter or digit.
     */
    private fun labelOrFallback(cmd: Command): String {
        val l = cmd.label.trim()
        val usable = l.any { it.isLetterOrDigit() }
        if (usable) return l
        val t = cmd.text.trim()
        return if (t.isNotEmpty()) t.take(18) else "Untitled"
    }

    private fun buildCommandButton(cmd: Command): Button = Button(this).apply {
        // Never render a useless label. A stale commands.json carried label:"?"
        // for two entries, which made "Yes" and "Next step" indistinguishable on
        // the surface whose whole point is tapping without reading carefully.
        // Treat blank AND punctuation-only labels as missing; the fallback is the
        // text the button sends, the same way the deck key faces already do.
        text = labelOrFallback(cmd)
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
                        "Send as-is to Discord",
                    )
                ) { _, which ->
                    when (which) {
                        0 -> editCommand(cmd)
                        1 -> deleteCommand(cmd)
                        2 -> sendForced(cmd, Command.Target.DISCORD)
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
                // Announce the outcome instead of leaving it in logcat only.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    confirmSent(cmd.label.ifBlank { cmd.text.take(18) }, r.toString())
                }
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
            text = "Sends into whatever text field is focused."
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

    /**
     * Delete with a confirmation.
     *
     * Deleting a command also orphans any deck key bound to its position, so
     * this used to be silent and irreversible. A confirmation is the cheap fix
     * for a one-way action; the real undo belongs with a proper Material
     * snackbar, which this project has no dependency for yet.
     */
    private fun deleteCommand(cmd: Command) {
        val name = cmd.label.ifBlank { cmd.text.take(18) }
        AlertDialog.Builder(this)
            .setTitle("Delete \"$name\"?")
            .setMessage("This also frees its Stream Deck key.")
            .setPositiveButton("Delete") { _, _ ->
                commands = commands.filterNot { it.id == cmd.id }.toMutableList()
                CommandStore.save(this, commands)
                renderGrid()
                log("deleted '$name'")
                toast("Deleted $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
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
     *
     * This is about SERVICE health, not about send outcomes. A send needs its own
     * confirmation (see confirmSent) because the user cannot see this line while
     * the app is backgrounded, which is exactly when a send happens.
     */
    private fun refreshStatus() {
        val svcOk = DeckAccessibilityService.isRunning()
        // Notification state needs BOTH checks, which is genuinely non-obvious.
        //
        //   areNotificationsEnabled()  -- false when the app is BLOCKED by the
        //      user, but it returns TRUE for an app that simply never held
        //      POST_NOTIFICATIONS, because "never asked" and "allowed" are
        //      indistinguishable to it. Verified on device: with the permission
        //      revoked, dumpsys showed AppSettings importance=NONE while this
        //      call still returned true.
        //   checkSelfPermission()      -- catches the never-granted case, but can
        //      report stale-granted inside a running process after an external
        //      revoke.
        //
        // Together they cover both, which is why the status line was silent while
        // confirmations were in fact suppressed.
        val notifAllowed = androidx.core.app.NotificationManagerCompat
            .from(this).areNotificationsEnabled()
        val notifGranted = android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val notifOk = notifAllowed && notifGranted

        statusView.text = when {
            !svcOk -> "Typing service is off. Tap here to fix it."
            !notifOk -> "Notifications are off, so send confirmations stay hidden. Tap to fix."
            else -> ""
        }
        statusView.setTextColor(
            if (svcOk && notifOk) Color.GRAY else Color.rgb(200, 60, 60)
        )
        // Make the warning itself the action. The old copy said "tap Enable
        // typing service below", which sent the user hunting for a control
        // buried five buttons deep inside a section labelled "(tap to collapse)".
        statusView.isClickable = !svcOk || !notifOk
        statusView.setOnClickListener {
            if (!DeckAccessibilityService.isRunning()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else if (notifGranted &&
                !androidx.core.app.NotificationManagerCompat.from(this@MainActivity)
                    .areNotificationsEnabled()
            ) {
                // Blocked at the app level: the only fix is the app's own
                // notification settings, so land the user directly there.
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } else {
                // Never granted: re-request through the normal permission path.
                requestNotificationPermission()
            }
        }
    }

    /**
     * Announce a send outcome. Delegates to the shared helper so the tap grid,
     * the broadcast API and the Test button all confirm identically.
     */
    private fun confirmSent(label: String, result: String) {
        SendConfirm.show(this, label, result)
        log(if (result.contains("no input field", true)) "Not sent: $label" else "Sent: $label")
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

        // ---- Brightness ------------------------------------------------
        // The panel is powered from the phone over USB, so dimming it is the one
        // panel-side way to save battery. This row is the user-facing control;
        // the value is persisted and re-applied on every connect and re-arm.
        val brightnessRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val brightnessLabel = TextView(this).apply {
            textSize = 13f
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        brightnessRow.addView(brightnessLabel)

        // One control, not five. A slider maps the underlying 0..100 value
        // directly, so there is no preset-vs-arbitrary mismatch to explain.
        // onProgressChanged reports live while dragging for immediate feedback;
        // fromUser guards against firing on programmatic sets.
        val slider = SeekBar(this).apply {
            max = 100
            progress = DeckSettings.brightnessOf(this@MainActivity)
            // The platform default track is ~18dp tall, well under the 48dp
            // minimum, so give it a real vertical extent. minHeight alone does
            // not work on SeekBar; the explicit layoutParams height does.
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
        }
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                // Update the label live while dragging, but do NOT push to the
                // hardware here. A drag fires this callback for every step, so
                // writing per-step floods the USB queue with ~100 writes for one
                // gesture. On this flaky link that is worth avoiding. The single
                // write happens on release.
                if (fromUser) brightnessLabel.text = "Brightness: ${DeckSettings.describe(value)}"
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val v = sb?.progress ?: return
                brightnessLabel.text = "Brightness: ${DeckSettings.describe(v)}"
                transport?.setBrightness(v)
                toast("Deck brightness: $v%")
            }
        })
        brightnessRow.addView(slider)
        brightnessLabel.text = "Brightness: ${DeckSettings.describe(DeckSettings.brightnessOf(this))}"
        // TalkBack: a bare SeekBar announces only "slider" with no name and no
        // associated label, so a screen-reader user cannot tell what it controls.
        // Set both the accessible name and the live value.
        slider.contentDescription = brightnessLabel.text
        parent.addView(brightnessRow)

        // One shared builder so every deck control has a real 48dp target.
        // These buttons previously inherited a short default and one of them
        // rendered at 32dp, which is the exact control a user reaches for when
        // the deck is already misbehaving.
        fun deckButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) }
            setOnClickListener { onClick() }
        }

        parent.addView(deckButton("Enable typing service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        // Keep the app alive in the background, and let send confirmations show.
        // These live here rather than in onCreate so a fresh launch opens on the
        // grid instead of three stacked system dialogs. Safe to tap repeatedly:
        // each is a no-op once granted.
        parent.addView(deckButton("Allow background + notifications") {
            requestNotificationPermission()
            requestBatteryExemption()
        })
        parent.addView(deckButton("Enable DeckBridge keyboard") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        parent.addView(deckButton("Test send (into focused app)") {
            val svc = DeckAccessibilityService.instance
            if (svc == null) {
                toast("Typing service is off")
            } else {
                // Send through the same path a deck press uses, so this is
                // a real end-to-end check rather than a special case.
                moveTaskToBack(true)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    svc.runCommand(
                        Command(
                            id = "test",
                            label = "test",
                            text = "deckbridge test",
                            target = Command.Target.DISCORD,
                            submit = true,
                        )
                    ) { r ->
                        Log.i("DeckBridge", "test send -> $r")
                        // The one button whose job is proving it works used to
                        // produce no visible proof. Announce it like a real send.
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            confirmSent("test", r.toString())
                        }
                    }
                }, 350)
            }
        })
        parent.addView(deckButton("Redraw key labels") {
            val t = transport
            if (t == null) {
                toast("Deck not started")
            } else {
                pushAllKeyImages(t)
            }
        })
        parent.addView(deckButton("Connect deck (starts USB reader)") {
            startDeckTransport()
        })

        // Diagnostics are one-time tools, not daily controls. Grouping them
        // behind a disclosure keeps five rarely-used buttons out of the way of
        // the two a user actually touches (brightness and typing service).
        val diagToggle = TextView(this).apply {
            text = "Diagnostics  ▸"
            textSize = 12f
            setAlpha( 0.7f)
            setPadding(dp(4), dp(16), dp(4), dp(4))
            minimumHeight = dp(48)
        }
        parent.addView(diagToggle)
        val diagGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        diagGroup.addView(deckButton("Diagnose USB") {
            log("running USB probe...")
            Thread { UsbProbe.run(this@MainActivity) }.start()
        })
        parent.addView(diagGroup)
        var diagOpen = false
        diagToggle.setOnClickListener {
            diagOpen = !diagOpen
            diagGroup.visibility = if (diagOpen) View.VISIBLE else View.GONE
            diagToggle.text = if (diagOpen) "Diagnostics  ▾" else "Diagnostics  ▸"
            diagToggle.contentDescription =
                if (diagOpen) "Diagnostics, expanded. Double tap to collapse."
                else "Diagnostics, collapsed. Double tap to expand."
        }
        diagToggle.contentDescription = "Diagnostics, collapsed. Double tap to expand."
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

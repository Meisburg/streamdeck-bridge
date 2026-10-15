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
        // Root fills the window so the grid can claim the leftover height rather
        // than leaving the bottom half empty.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Draw under the status bar so the header is not clipped, but inset the
        // CONTENT so nothing sits beneath the status bar or the camera cutout.
        // Without this the brand row ran under the system icons, which is exactly
        // what the user reported.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(dp(12) + bars.left, dp(12) + bars.top, dp(12) + bars.right, dp(12) + bars.bottom)
            insets
        }

        // --- brand header ---
        root.addView(buildBrandHeader())

        // --- status: a real button, shown only when something needs fixing ---
        statusView = TextView(this).apply {
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            // Padded and backgrounded so it reads as a control. The previous
            // version was bare 12sp text with a click listener attached, which
            // looked exactly like a label and gave no sign it could be tapped.
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }
        root.addView(statusView)

        // --- the key grid ---
        // No weight. Its height comes from the square keys themselves; the old
        // weight=1 was a leftover from when the grid was meant to absorb leftover
        // screen height, which a ScrollView parent cannot provide anyway.
        gridView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(gridView)

        // NOTE: there is deliberately no "+ Add command" button.
        //
        // The grid is the deck's 15 physical keys, not a growable list, so there
        // is no sixteenth slot to add to. Creating a command happens by tapping an
        // empty key (or long-pressing any key), which is also the only place it
        // can possibly go. A separate Add button would have had to ask "which key?"
        // and then fail once the grid was full.

        // --- deck controls, collapsed by default ---
        // Default collapsed: by the class comment above, the deck controls must
        // never compete with the grid, which is the surface that actually works.
        // The header names the current state, not the next action; a label that
        // describes what a tap will do reads as a description of what is.
        //
        // Styled as a filled, bordered bar rather than bare text. As plain 13sp
        // text with a listener attached it gave no hint it could be tapped -- the
        // user's report was that it "just looks like text".
        deckHeader = TextView(this).apply {
            text = "Deck setup"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(Color.parseColor("#1F2430"))
            setTextColor(Color.parseColor("#C7CBD1"))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
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
            setPadding(dp(8), dp(12), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#141821"))
            setTextColor(Color.parseColor("#9AA0A6"))
        }
        root.addView(logView)

        // Scrollable root, and the grid does NOT take a weight.
        //
        // Earlier the root was a plain LinearLayout with the grid weighted, to
        // make the keys fill the screen. That worked while the deck section was
        // collapsed, but expanding it pushed the last two controls past the bottom
        // with nothing to scroll: they were not merely off-screen, they were gone
        // from the view tree entirely and unreachable. Content ended at y=2034 on a
        // 2142px screen.
        //
        // A ScrollView is what makes every control reachable regardless of how much
        // is expanded. The keys stay square because their size comes from the screen
        // width, not from leftover height, so giving up the weight costs nothing.
        setContentView(ScrollView(this).apply {
            addView(root)
            isFillViewport = true
        })
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
     * The key grid: the deck's exact physical layout, 3 rows of 5.
     *
     * This deliberately mirrors the hardware rather than flowing like a list.
     * The user is looking at the deck while tapping, so a screen square in the
     * same position as the physical key removes the translation step entirely:
     * what you see is where you press. That is also why slots are fixed
     * positions rather than a growable list (see CommandStore.padToSlots).
     *
     * Row sections are labelled because the deck has three physical rows and
     * naming them gives the user a way to talk and think about position.
     */
    private fun renderGrid() {
        gridView.removeAllViews()

        val (rows, cols) = DeckProtocol.ROWS to DeckProtocol.COLS

        // Square keys, sized from the WIDTH only.
        //
        // Two failed attempts bracket this. Sizing from width alone rendered
        // correct squares but derived the row height from a "reserved for chrome"
        // estimate; when that estimate was wrong the row clamped the keys into
        // rectangles (measured 74x51). Filling the row HEIGHT instead stretched them
        // into 75x167 slabs. The reliable constraint is width -- five keys must fit
        // across -- so take the side from width, let rows wrap content, and the keys
        // stay square regardless of what else is on screen.
        val metrics = resources.displayMetrics
        val byWidth = (metrics.widthPixels - dp(24)) / cols - dp(7)
        val side = byWidth.coerceAtLeast(dp(48))

        gridView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        gridView.gravity = android.view.Gravity.CENTER_HORIZONTAL

        for (r in 0 until rows) {
            gridView.addView(TextView(this).apply {
                text = "Row ${r + 1}"
                textSize = 11f
                setAlpha(0.6f)
                setPadding(dp(6), if (r == 0) dp(2) else dp(8), dp(4), dp(2))
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                // WRAP_CONTENT width: the keys are fixed squares, so a full-width
                // row would leave slack after the fifth key and push the grid
                // off-centre. Height is WRAP_CONTENT too, so nothing clamps the keys.
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (c in 0 until cols) {
                val idx = r * cols + c
                row.addView(buildKeyButton(idx, commands.getOrNull(idx), side))
            }
            gridView.addView(row)
        }
    }

    /**
     * The brand header: the mark, then the product name.
     *
     * The mark is the user's own artwork (an ic_deck_mark.png in each density
     * bucket), with the black background knocked out to transparency so it sits
     * on any surface. An earlier attempt hand-drew a 5x3 key grid as a vector and
     * it rendered as three bars, because the path data was wrong -- using the
     * supplied image is both correct and simpler.
     *
     * The wordmark is set in the app's sans face at a display weight, with tight
     * tracking so the two words read as one unit rather than two labels.
     */
    private fun buildBrandHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(0), dp(4), dp(10))
        }

        val mark = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_deck_mark)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                setMargins(0, 0, dp(10), 0)
            }
            contentDescription = null   // decorative; the wordmark carries the name
        }
        row.addView(mark)

        row.addView(TextView(this).apply {
            text = "Stream Deck Bridge"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
            setTextColor(Color.parseColor("#E8EAED"))
        })

        return row
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

    /**
     * One key on the grid: a square with rounded corners, like the physical key.
     *
     * Tap EDIT, long-press for the menu. That inversion is deliberate: this is a
     * configuration screen, not a send surface. The deck itself and the broadcast
     * API are how commands actually get sent, and a stray tap here used to fire a
     * command into whatever happened to be focused -- which is the last thing
     * anyone wants while editing. Making tap safe means the destructive-in-the-
     * moment action (sending something unintended) is no longer one slip away.
     */
    private fun buildKeyButton(index: Int, cmd: Command?, side: Int): Button {
        val empty = CommandStore.isEmpty(cmd)
        return Button(this).apply {
            text = if (empty) "Empty" else labelOrFallback(cmd!!)
            textSize = if (empty) 12f else 13f
            isAllCaps = false
            maxLines = 3

            // Rounded key face instead of the default button chrome. A stock
            // Button is a bordered rectangle with a shadow, which reads as a form
            // control; the deck's keys are matte rounded squares, and matching that
            // is the whole point of laying the grid out like the hardware.
            setBackgroundResource(
                if (empty) R.drawable.key_face_empty else R.drawable.key_face
            )
            if (!empty) setTextColor(Color.parseColor("#E8EAED"))

            // Exactly `side` on both axes, so every key is a true square.
            layoutParams = LinearLayout.LayoutParams(side, side).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }

            setOnClickListener { editCommand(if (empty) null else cmd!!, index) }

            setOnLongClickListener {
                val menu = if (empty) arrayOf("Fill this key")
                else arrayOf("Edit", "Clear this key", "Send as-is to Discord")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (empty) "Key ${index + 1} (empty)" else labelOrFallback(cmd!!))
                    .setItems(menu) { _, which ->
                        when {
                            empty -> editCommand(null, index)
                            which == 0 -> editCommand(cmd!!, index)
                            // "Clear" empties the CONTENTS but keeps the slot: the
                            // key is a physical position, not a list row, so it can
                            // never be removed. See CommandStore.padToSlots.
                            which == 1 -> clearSlot(index, cmd!!)
                            else -> sendForced(cmd!!, Command.Target.DISCORD)
                        }
                    }
                    .show()
                true
            }
        }
    }

    /**
     * Blank a key's contents while leaving the key in place.
     *
     * Deliberately named "clear", not "delete": the slot survives because the
     * hardware position survives. The user's own words: the keys "should always
     * stay on the screen even if you remove the text or the commands inside of
     * them."
     */
    private fun clearSlot(index: Int, cmd: Command) {
        AlertDialog.Builder(this)
            .setTitle("Clear \"${labelOrFallback(cmd)}\"?")
            .setMessage("Key ${index + 1} stays on the grid, empty and ready to refill.")
            .setPositiveButton("Clear") { _, _ ->
                val blank = cmd.copy(label = "", text = "")
                commands[index] = blank
                CommandStore.save(this, commands)
                renderGrid()
                refreshKeyFaces()
                log("cleared key ${index + 1}")
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    /**
     * Edit the command in a specific key slot.
     *
     * Takes a slot INDEX rather than just a Command, because the slot is the
     * identity here. Two keys could hold identical commands, so finding "the"
     * command by value would edit the wrong key; and a newly-filled key has no
     * command yet, only a position. Passing the index makes both cases exact.
     *
     * slot = -1 means "no particular key" and falls back to the first empty one.
     */
    private fun editCommand(existing: Command?, slot: Int = -1) {
        val isNew = existing == null
        val cur = existing ?: Command(
            id = if (slot >= 0) "slot$slot" else java.util.UUID.randomUUID().toString(),
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

        // No target picker. It offered AUTO vs Discord, and AUTO already sends to
        // whatever has focus -- which is correct in every case this user has. So
        // the picker asked a question with one real answer, and its only effect was
        // to make a two-field dialog look like a three-decision one. The target
        // stays on the Command for the broadcast API, which sets it explicitly;
        // the UI simply does not ask.

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
                    // Preserve whatever target the slot already had. The dialog no
                    // longer offers the choice, so overwriting it here would silently
                    // reset a value the user (or the adb API) deliberately set.
                    target = cur.target,
                    submit = submit,
                )
                // Write back to the SLOT, never append. The grid is a fixed set of
                // 15 deck positions, so editing key 7 must change key 7; appending
                // would grow the list and desync the screen from the hardware.
                if (slot >= 0 && slot < commands.size) {
                    commands[slot] = updated
                } else if (isNew) {
                    // No slot supplied: fall back to the first empty one.
                    val free = commands.indexOfFirst { CommandStore.isEmpty(it) }
                    if (free >= 0) commands[free] = updated else commands[0] = updated
                }
                CommandStore.save(this, commands)
                renderGrid()
                // Keep the physical panel in step with the screen. Without this,
                // editing a key updated the phone but left the deck showing the
                // old face until the next connect, which reads as "it didn't save".
                refreshKeyFaces()
                log("saved '${updated.label}'")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Re-draw the deck's key faces from the current slots.
     *
     * Called after any edit so the physical panel matches the screen immediately
     * rather than only on the next connect. Cheap when the deck is absent: it
     * returns without doing anything if the transport is null or disconnected.
     */
    private fun refreshKeyFaces() {
        val t = transport ?: return
        if (!t.isConnected()) return
        pushAllKeyImages(t)
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

        // Hidden entirely when healthy, so it occupies no space and cannot be
        // mistaken for decoration. When shown it is a filled, centred, tappable
        // band -- a real button, not a sentence with a listener attached.
        val problem = when {
            !svcOk -> "Typing service is off. Tap here to fix it."
            !notifOk -> "Notifications are off, so send confirmations stay hidden. Tap to fix."
            else -> null
        }
        if (problem == null) {
            statusView.visibility = View.GONE
        } else {
            statusView.visibility = View.VISIBLE
            statusView.text = problem
            statusView.setBackgroundColor(Color.parseColor("#8C2F2F"))
            statusView.setTextColor(Color.WHITE)
            statusView.setOnClickListener {
                if (!DeckAccessibilityService.isRunning()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else if (notifGranted &&
                    !androidx.core.app.NotificationManagerCompat.from(this@MainActivity)
                        .areNotificationsEnabled()
                ) {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                } else {
                    requestNotificationPermission()
                }
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

        // ------------------------------------------------------------------
        // Only controls where the user knows WHEN to press them.
        //
        // The test applied: could someone describe the situation that makes this
        // button the right thing to tap? Three failed it and are gone.
        //
        //   "Reconnect the deck" / "Redraw key labels on the deck" -- these are
        //      already automatic. The transport starts on launch and restarts
        //      itself when the key stream wedges, and editing a key re-pushes the
        //      faces. A button whose job is already handled on its own is not a
        //      control, it is a question mark.
        //   "DeckBridge keyboard (for Termux only)" -- removed. This user does not
        //      use Termux and the keyboard is inert for Discord and Collie.
        //   "Diagnose USB connection" -- removed. It writes to the on-screen log
        //      and tells the user nothing they can act on.
        //
        // What remains is one button with an obvious trigger, plus the brightness
        // slider above it.
        // ------------------------------------------------------------------

        // Each label names the situation, not just the action. "Enable typing
        // service" alone assumes the user knows what a typing service is and when
        // it needs enabling; the bracketed half answers both.
        parent.addView(deckButton("Enable typing service (if text stops sending)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        parent.addView(deckButton("Allow notifications (so send confirmations show)") {
            requestNotificationPermission()
            requestBatteryExemption()
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

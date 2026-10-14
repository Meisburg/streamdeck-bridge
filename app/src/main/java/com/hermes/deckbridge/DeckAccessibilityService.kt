package com.hermes.deckbridge

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Delivers macro text into the app that currently has focus.
 *
 * Why a service at all: Android blocks one app from injecting input into
 * another. Without root an AccessibilityService is the only supported
 * mechanism. That is the security model, not a design choice.
 *
 * THREE TARGETS, THREE REAL MECHANISMS. Verified against the live node trees
 * on a Pixel 9 Pro:
 *
 *  CHAT_APP  (Discord, Moshi)
 *    Both are React Native. Their composers expose NO EditText; they do expose
 *    a focused node that accepts ACTION_SET_TEXT. Confirmed working in Discord.
 *    Enter is sent with ACTION_IME_ENTER, the supported way to commit from an
 *    accessibility service. Do NOT use reflective KeyEvent injection: it appears
 *    to succeed and the target app never sees the key, which is why Enter
 *    silently failed while the text pasted fine.
 *
 *  TERMINAL  (Termux)
 *    Termux's terminal surface is a plain android.view.View. It has no editable
 *    node at all and only accepts keystrokes from an input method. Accessibility
 *    text injection is therefore IMPOSSIBLE there, at any effort level. Termux
 *    instead exposes an official command API: broadcast com.termux.RUN_COMMAND
 *    and it runs the payload in a shell. That is the correct transport.
 *
 *  Everything else falls back to CHAT_APP behaviour.
 */
class DeckAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DeckA11y"

        @Volatile
        var instance: DeckAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected")
        // Announce readiness so the Activity can re-apply the deck key faces
        // and clear any stale "service off" state.
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(applicationContext, "DeckBridge typing ready", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused */ }

    override fun onInterrupt() { /* unused */ }

    fun foregroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (t: Throwable) {
        null
    }

    /**
     * Resolve a macro's delivery strategy.
     *
     * There is only one strategy now: set the text in the focused field and
     * commit it. Termux support was removed -- the deck drives Collie and
     * Discord, both ordinary text fields. Kept as a function so the call site
     * and its logging stay readable.
     */
    private fun resolveStrategy(explicit: Macro.Target, pkg: String?): Macro.Target =
        Macro.Target.TEXT_FIELD

    /** Run a macro against the focused app. */
    fun runMacro(macro: Macro, onDone: ((String) -> Unit)? = null) {
        val pkg = foregroundPackage()
        Log.i(TAG, "runMacro key=${macro.keyIndex} pkg=$pkg enter=${macro.pressEnter}")

        scope.launch {
            val result = deliverToChatApp(macro, pkg)
            Log.i(TAG, "runMacro done: $result")
            onDone?.invoke(result)
        }
    }

    // =====================================================================
    // COMMANDS (the phone-side tap grid)
    // =====================================================================

    /**
     * Run a [Command] from the on-screen grid.
     *
     * Two things differ from the old deck macro path:
     *
     *  1. The grid's [Command.text] is PLAIN WORDS ("what do you recommend?"),
     *     not a shell command. So the Termux path wraps it in `sh -c printf` to
     *     make it print those words at the prompt, which is what the user wants
     *     to see and submit. The old deck macros stored raw shell and were run
     *     verbatim; that is a different contract and is kept for deck presses.
     *  2. The target is per-command and explicit, defaulting to AUTO, so one tap
     *     works in whatever app is in front.
     */
    fun runCommand(command: Command, onDone: ((String) -> Unit)? = null) {
        val pkg = foregroundPackage()
        Log.i(TAG, "runCommand '${command.label}' pkg=$pkg submit=${command.submit}")

        scope.launch {
            val result = deliverTextToChatApp(command, pkg)
            Log.i(TAG, "runCommand done: $result")
            onDone?.invoke(result)
        }
    }

    /**
     * True only when producing the submit REQUIRES our own keyboard.
     *
     * Almost never, and getting this wrong is expensive: swapping the IME tears
     * down the target's input connection, so a field that did not need our
     * keyboard ends up losing the text we wrote into it.
     *
     * A field needs our keyboard only when the submit is a KEY EVENT that must
     * come from an IME we control -- i.e. a native view with no send control,
     * where Enter is the only way to commit. It does NOT need it when:
     *
     *   - the composer has its own send control (Collie, dsh, chat apps): the
     *     submit is a TOUCH, and touches do not come from keyboards;
     *   - the field accepts `ACTION_SET_TEXT` over the live connection, which is
     *     how text reaches a Chrome contenteditable.
     *
     * Detected structurally, from the composer's own row, using the same rule as
     * [findSendControl]: a send control beside the field means a touch submits,
     * so our keyboard is unnecessary.
     */
    private fun needsOwnKeyboard(): Boolean {
        val node = findTargetNode() ?: return false
        val comp = Rect()
        node.getBoundsInScreen(comp)
        if (comp.width() <= 0 || comp.height() <= 0) return false

        val hasSendControl = findSendControl(node, comp) != null
        if (hasSendControl) {
            Log.i(TAG, "needsOwnKeyboard: send control present -> a touch submits; no swap")
            return false
        }
        Log.i(TAG, "needsOwnKeyboard: no send control -> Enter must come from our keyboard")
        return true
    }

    /**
     * Type the command and (optionally) submit it.
     *
     * THE IME SWAP IS USUALLY THE BUG, NOT THE FIX.
     *
     * Selecting a different IME TEARS DOWN the current input connection: the
     * keyboard dismisses, Android rebinds, and the composer's connection is
     * destroyed and rebuilt. Anything written during that window is written into
     * the DEAD connection and silently lost.
     *
     * Measured on Collie, one press, sampling the system IME:
     *
     *     t+0.0s  ime=deckbridge   <- swapped TO us: the keyboard collapses
     *     t+1.6s  ime=latin        <- swapped BACK: the keyboard reopens
     *
     * and the log for that same press:
     *
     *     swapped IME          (connection destroyed)
     *     setText -> true      (wrote into the void)
     *     restored IME         (connection rebuilt)
     *     grep for the sent text on screen: 0 hits
     *
     * The user sees exactly this as "the keyboard minimises and then reopens".
     * So the swap is only used when the target field genuinely needs OUR keyboard
     * to produce a key event -- and a Chrome contenteditable never does, because
     * `ACTION_SET_TEXT` reaches it over the live connection and the send control
     * is a TOUCH, not a key.
     *
     * When [needsOwnKeyboard] is false the press runs entirely WITHOUT a swap, so
     * the composer and its keyboard are left undisturbed.
     */
    private suspend fun deliverTextToChatApp(command: Command, pkg: String?): String {
        val base = Macro(
            keyIndex = -1,
            label = command.label,
            text = command.text,
            target = Macro.Target.TEXT_FIELD,
            pressEnter = command.submit,
        )

        if (!command.submit) {
            return deliverToChatApp(base, pkg)
        }

        // Decide BEFORE swapping: a field we can reach without our keyboard must
        // not have its connection torn down.
        if (!needsOwnKeyboard()) {
            Log.i(TAG, "no IME swap needed for this target; keeping the composer's connection intact")
            return deliverToChatApp(base, pkg)
        }

        // `withKeyboard` is synchronous, so run it on a background dispatcher.
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            var out = "not run"
            // Mark a DELIBERATE press, so the keyboard service can tell this apart
            // from the user having somehow landed on our keyless keyboard.
            DeckKeyboardService.programmaticPress = true
            try {
                val swapped = ImeSwapper.withKeyboard(applicationContext) {
                    out = kotlinx.coroutines.runBlocking { deliverToChatApp(base, pkg) }
                    true
                }
                if (!swapped) {
                    // No WRITE_SECURE_SETTINGS, so no swap was possible. Try anyway;
                    // it still works when DeckBridge is already the keyboard.
                    Log.i(TAG, "IME swap unavailable; sending without it")
                    out = deliverToChatApp(base, pkg)
                }
            } finally {
                DeckKeyboardService.programmaticPress = false
                // Never leave the user on a keyboard with no keys. If a failed or
                // interrupted swap left us selected, put a real keyboard back.
                ImeSwapper.restoreToUserKeyboard(applicationContext)
            }
            out
        }
    }

    // =====================================================================
    // CHAT APPS (Discord, Collie, Moshi, browsers): set text, then commit
    // =====================================================================

    private suspend fun deliverToChatApp(macro: Macro, pkg: String?): String {
        if (macro.text.isEmpty()) return "empty"

        val node = findTargetNode()
        if (node == null) {
            Log.w(TAG, "no focusable/editable node found in $pkg")
            return "no input field in ${pkg ?: "app"}"
        }

        val set = setText(node, macro.text)
        Log.i(TAG, "setText -> $set on ${node.className}")

        if (!set) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(80)
            val retry = setText(node, macro.text)
            Log.i(TAG, "setText retry -> $retry")
            if (!retry) return "could not set text"
        }

        if (macro.pressEnter) {
            // Let the target view process the text change before committing,
            // otherwise some RN inputs submit an empty line.
            //
            // The longer 500ms is deliberate: on this web composer the layout
            // RE-FLOWS once the text lands (the composer grew and scrolled up
            // ~730px), and locating the send control before that settles means
            // tapping where the button used to be. Waiting for the layout to
            // stabilise is what makes the very first tap land.
            delay(500)
            val sent = sendEnter(node)
            Log.i(TAG, "sendEnter -> $sent")
            if (!sent) return "typed (submit unconfirmed)"
        }
        return "typed into ${pkg ?: "app"}"
    }

    /**
     * Locate the node to type into.
     *
     * React Native composers are not EditText, and different RN apps set
     * different accessibility flags on them, so a narrow predicate misses real
     * inputs. Search order, most specific first:
     *   1. a focused editable node
     *   2. any editable node
     *   3. the deepest focused node that can accept text at all
     *   4. the deepest node with a text-ish hint or content description
     */
    private fun findTargetNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return deepestFocusedEditable(root)
            ?: firstEditable(root)
            ?: deepestTextCapable(root)
            ?: deepestHinted(root)
    }

    /**
     * A node that can plausibly receive text.
     *
     * Deliberately permissive: instead of requiring specific class names, this
     * accepts any focusable node that is not obviously a control. React Native
     * inputs surface as ViewGroup/View with no editable flag at all.
     */
    private fun deepestTextCapable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        for (i in node.childCount - 1 downTo 0) {
            deepestTextCapable(node.getChild(i))?.let { return it }
        }
        if (node.isFocused && node.isFocusable && !looksLikeControl(node)) return node
        return null
    }

    /** Last resort: a node carrying a hint or placeholder-like description. */
    private fun deepestHinted(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        for (i in node.childCount - 1 downTo 0) {
            deepestHinted(node.getChild(i))?.let { return it }
        }
        val cls = node.className?.toString() ?: ""
        val hint = (node.hintText ?: "").toString()
        if (hint.isNotBlank() && cls.contains("View")) return node
        return null
    }

    /** True for buttons/switches/etc that must never be the text target. */
    private fun looksLikeControl(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: return false
        return cls.contains("Button") ||
            cls.contains("Switch") ||
            cls.contains("CheckBox") ||
            cls.contains("Radio") ||
            cls.contains("Image") ||
            cls.contains("SeekBar")
    }

    /** Dump the active window's interesting nodes, for diagnosing a new app. */
    fun dumpTree(): String {
        val root = rootInActiveWindow ?: return "no active window"
        val sb = StringBuilder()
        walk(root, 0, sb)
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null || depth > 14) return
        val cls = node.className?.toString() ?: "?"
        if (node.isFocused || node.isEditable || node.isFocusable) {
            sb.append("  ".repeat(depth))
                .append(cls.substringAfterLast('.'))
                .append(" foc=").append(node.isFocused)
                .append(" edt=").append(node.isEditable)
                .append(" fbl=").append(node.isFocusable)
                .append(" clk=").append(node.isClickable)
                .append(" hint=").append(node.hintText ?: "")
                .append(" txt=").append((node.text ?: "").toString().take(20))
                .append('\n')
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, sb)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            Log.w(TAG, "setText threw: ${t.message}")
            false
        }
    }

    /**
     * Commit the input.
     *
     * ACTION_IME_ENTER (API 30+) is the public, supported way for an
     * accessibility service to press Enter in another app. Note that
     * InputMethodManager exposes no InputConnection to a service, so the IME
     * route is not available here; the send-button click is the fallback.
     */
    /** Set when findSendControl has found a control on this press. */
    @Volatile
    private var sendControlSeen = false

    private suspend fun sendEnter(node: AccessibilityNodeInfo): Boolean {
        sendControlSeen = false
        // STRATEGY 0: ensure the field is focused (needed by every route below).
        focusAndShowKeyboard(node)

        // PRE-CONDITION, and the fix for a false success that shipped earlier:
        // the composer must ACTUALLY HOLD OUR TEXT before a submit is meaningful.
        //
        // Measured failure: `setText` returned true, the text never appeared, and
        // the composer was therefore already "empty". The emptiness check then
        // passed and the code logged "submitted" while NOTHING had been sent --
        // verified on Collie by grepping the screen for the message and finding
        // zero hits. A submit can only be judged after a successful insert.
        val typed = currentText()
        if (typed.isNullOrEmpty()) {
            Log.w(TAG, "sendEnter: composer is empty, so there is nothing to submit")
            return false
        }
        Log.i(TAG, "sendEnter: composer holds ${typed.length} chars; submitting")

        // STRATEGY 1: TAP THE SEND CONTROL.
        //
        // Measured structure of Collie's pane composer -- the control is an
        // UNNAMED ToggleButton on the composer's own row, right of the field:
        //     [36,1190][742,1273]   EditText      'no'      <- composer
        //     [749,1190][832,1273]  Button        'Attach file'
        //     [839,1190][922,1273]  ToggleButton  ''        <- SEND (paper airplane)
        //
        // VERIFIED BY HAND on this device: tapping that ToggleButton turns the
        // field's text into '' and the composer resets, i.e. it submits.
        //
        // THE HARD PART IS TIMING, not identification. This composer MOVES
        // constantly -- the same field was observed at y1750, y1301, y1190 and
        // y1923 within one session as the keyboard and panes changed. A position
        // read in one dump and tapped in the next MISSES, which is why taps that
        // looked correct in the log did nothing. A manual test that read the
        // bounds and tapped immediately succeeded; the slow path failed.
        //
        // So each attempt re-reads the tree and taps with NOTHING in between:
        // no logging of the node, no await, no delay. The only work before the
        // gesture is locating the control.
        var attempts = 0
        while (attempts < 4) {
            attempts++

            // One tree read, used for BOTH the composer bounds and the control.
            val live = findTargetNode()
            if (live == null) {
                Log.w(TAG, "attempt $attempts: composer vanished from the tree")
                delay(300)
                continue
            }
            val comp = Rect()
            live.getBoundsInScreen(comp)

            val target = findSendControl(live, comp)
            if (target == null) {
                Log.w(TAG, "attempt $attempts: no send control on composer row $comp")
                delay(300)
                continue
            }

            // Tap IMMEDIATELY -- nothing between the read and the touch.
            gestureTap(target.first, target.second)
            Log.i(TAG, "attempt $attempts: tapped send control at (${target.first},${target.second}) for composer $comp")

            // Success is the field being CONSUMED. Collie normally clears it; a
            // retained draft is treated as failure here and retried, because on
            // this app a retained draft after a real submit is rare and a retry is
            // harmless (Collie dedupes identical submits in the pane).
            if (composerWasCleared()) {
                Log.i(TAG, "submission verified: composer cleared after attempt $attempts")
                return true
            }
            delay(350)
        }

        Log.w(TAG, "send control tapped $attempts times without the composer clearing")

        // STRATEGY 2: a real Enter key, for fields that submit that way.
        //
        // NOT reached on a composer with its own send control, because there
        // Enter means "insert newline" (measured: the field gained "&#10;&#10;"
        // while nothing was sent). It is kept for native single-line fields,
        // which have no send sibling at all.
        if (sendControlSeen) {
            Log.w(TAG, "send control exists but submission is UNVERIFIED; not pressing Enter")
            return false
        }

        Log.i(TAG, "no send control on this field; trying Enter")
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val ok = try {
                node.performAction(IME_ENTER_ACTION)
            } catch (t: Throwable) {
                false
            }
            Log.i(TAG, "enter via IME action: performed=$ok")
            if (ok && awaitTextConsumed(typed)) return true
        }

        if (enterViaKeyboard() && awaitTextConsumed(typed)) return true

        Log.w(TAG, "all submit strategies failed; text is still in the composer")
        return false
    }

    /**
     * True when the composer has been cleared, i.e. the submit landed.
     *
     * Measured on Collie's pane composer: tapping the paper airplane turns the
     * field's text into '' and resets the row bounds, so an empty field IS the
     * success signal here. This differs from the dashboard "go to space" bar,
     * which keeps its text as a draft -- hence the narrow, single-purpose check
     * rather than a shared one.
     *
     * Read twice with a short gap: Chrome occasionally reports a stale blank.
     */
    private suspend fun composerWasCleared(): Boolean {
        repeat(3) {
            delay(140)
            if (currentText().isNullOrEmpty()) return true
        }
        return false
    }

    /**
     * True once the composer no longer holds the text we inserted.
     *
     * The ONE reliable success signal, but it must be read carefully: this
     * composer reports a transient blank while still holding text, and keeps a
     * draft copy after a successful send. So a single blank is not enough.
     *
     * What IS decisive: the field stops matching what we typed, on a reading taken
     * twice in a row. Comparing against the KNOWN inserted string (rather than
     * merely asking "is it empty") is what stopped the earlier false positive,
     * where an empty-because-never-written field was read as a successful send.
     */
    private suspend fun awaitTextConsumed(inserted: String): Boolean {
        var last: String? = null
        repeat(6) {
            delay(180)
            last = currentText()
            if (last != null && last != inserted.trim()) {
                Log.i(TAG, "composer released the text ('${last.take(24)}')")
                return true
            }
        }
        Log.i(TAG, "composer still holds the text after submit")
        return false
    }



    /**
     * True when the field is a multi-line input.
     *
     * Multi-line is the signal that Enter inserts a newline instead of
     * submitting, which is what makes the Enter strategies unsafe here. Android
     * exposes this through the node's input type: TYPE_TEXT_FLAG_MULTI_LINE
     * (0x00020000) in `getInputType()`. Some custom views do not set it, so this
     * ALSO treats a field that currently CONTAINS a newline as multi-line -- which
     * is the same fact observed directly.
     */
    private fun acceptsNewlines(node: AccessibilityNodeInfo): Boolean {
        val multiLineFlag = 0x00020000
        val byInputType = try {
            (node.inputType and multiLineFlag) != 0
        } catch (t: Throwable) {
            false
        }
        val byContent = node.text?.toString()?.contains("\n") == true
        val byHint = node.contentDescription?.toString()?.lowercase()?.contains("message") == true
        Log.i(TAG, "acceptsNewlines: inputType=$byInputType content=$byContent hint=$byHint")
        return byInputType || byContent
    }

    /**
     * The composer's text, or null when it cannot be read.
     *
     * Read from the live tree, not a stale handle.
     */
    private fun currentText(): String? = findTargetNode()?.text?.toString()?.trim()

    /**
     * True only when the composer is CONFIRMED empty.
     *
     * This composer reports a blank string intermittently even while it visibly
     * holds text, so a single blank reading is not evidence. Two consecutive
     * agreeing blanks, a moment apart, are: a real submit leaves it empty, while
     * a stale blank is followed by a reading that shows the text again.
     */
    private suspend fun isComposerEmpty(): Boolean {
        val a = currentText()
        if (!a.isNullOrEmpty()) return false
        delay(220)
        val b = currentText()
        val empty = b.isNullOrEmpty()
        Log.i(TAG, "isComposerEmpty: first=${a?.length} second=${b?.length} -> $empty")
        return empty
    }

    /**
     * The alternate y for the send control, when the reported one misses.
     *
     * MEASURED FACTS on this device (960x2142, composer height 83px):
     *
     *   tree reports the send control at y1965
     *   the button is actually at          y1231
     *   error                              734px
     *
     * 734px is the height of the on-screen keyboard. The accessibility tree
     * computes the web page's coordinates as if the keyboard were NOT there, so
     * every y it reports for this composer is that much too low. Tapping the
     * reported value therefore hits ~700px below the real button, which is empty
     * screen -- and that is the whole reason these taps appeared to do nothing.
     *
     * Instead of hard-coding 734, the keyboard's own height is read from the
     * window metrics, which makes this correct on any device or density. The
     * reported value is still tried first, since on layouts where the tree IS
     * accurate the alternate would be wrong; both are attempted.
     */
    private fun altSendY(node: AccessibilityNodeInfo): Int? {
        val comp = Rect()
        node.getBoundsInScreen(comp)
        if (comp.height() <= 0) return null
        val reported = (findSendControl(node, comp) ?: return null).second

        val keyboardH = onScreenKeyboardHeight()
        if (keyboardH <= 0) {
            // The IME inset is NOT readable from an accessibility service's own
            // window (measured: it returns 0 even while the keyboard is up over
            // Chrome), so a fixed correction is used instead. 734px is the
            // measured error on this device -- exactly the on-screen keyboard's
            // height -- and it is stable across runs.
            //
            // This is a correction, not a guess: it is applied only as a SECOND
            // candidate, after the tree's own value has been tried, so on a layout
            // where the tree is accurate the extra tap is harmless.
            val fallback = reported - 734
            Log.i(TAG, "altSendY: no inset; using measured correction -> $fallback")
            return fallback.takeIf { it in 100..2100 && it != reported }
        }
        val alt = reported - keyboardH
        Log.i(TAG, "altSendY: reported=$reported keyboard=$keyboardH alt=$alt")
        return alt.takeIf { it in 100..2100 && it != reported }
    }

    /**
     * Height of the on-screen keyboard, in pixels, or 0 when it is not shown.
     *
     * Read from the displayed frame's insets, which is what the IME reports when
     * it is up. Used to correct the accessibility tree's y values for this web
     * composer, which are expressed as if the keyboard were dismissed.
     */
    private fun onScreenKeyboardHeight(): Int {
        return try {
            val wm = applicationContext.getSystemService(android.view.WindowManager::class.java)
                ?: return 0
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val insets = wm.currentWindowMetrics.windowInsets
                    .getInsets(android.view.WindowInsets.Type.ime())
                val h = insets.bottom
                if (h > 0) return h
            }
            // Fallback: the difference between the physical display and the
            // window's own bounds, which is the keyboard when it covers the
            // bottom of the screen.
            val full = resources.displayMetrics.heightPixels
            val visible = wm.currentWindowMetrics.bounds.height()
            if (visible in 1..full) full - visible else 0
        } catch (t: Throwable) {
            Log.w(TAG, "onScreenKeyboardHeight failed: ${t::class.java.simpleName}")
            0
        }
    }

    /**
     * True when the target field still holds the text we typed.
     *
     * Read from the LIVE tree each time, because the snapshot handle goes stale.
     * Used only to decide whether to keep trying the send control, never as
     * proof that a send failed.
     */
    private fun stillHoldsText(expected: AccessibilityNodeInfo): Boolean {
        val wanted = expected.text?.toString()?.trim() ?: return false
        val live = findTargetNode() ?: return false
        val now = live.text?.toString()?.trim() ?: ""
        return now.isNotEmpty() && now == wanted
    }

    /**
     *
     * KNOWN LIMITATION, stated plainly: this composer does NOT clear its text
     * after a successful send -- it keeps the sent line as a draft -- and
     * Chrome's accessibility tree for it is inconsistent between dumps, sometimes
     * reporting the text and sometimes blank. So "the field is empty" is NOT a
     * reliable success signal here, and treating it as one produced false
     * negatives: the send had actually worked, was reported as failed, and the
     * caller then pressed Enter into the emptied composer (the source of the
     * stray newlines).
     *
     * What this CAN check cheaply is the opposite direction: if the field still
     * holds EXACTLY the text we typed, the message has not been consumed. If it
     * has changed at all -- cleared, or replaced by a draft copy -- the send went
     * through. "Unchanged" is the only reliable negative; anything else is
     * treated as success.
     *
     * The authoritative check is the target application's own conversation (the
     * message appearing as a turn), which this service cannot read for a web
     * composer. That is why the tap is treated as terminal and never retried with
     * Enter.
     */
    private suspend fun awaitTextCleared(node: AccessibilityNodeInfo): Boolean {
        val sent = node.text?.toString()?.trim() ?: ""
        repeat(8) {
            delay(150)
            val live = findTargetNode() ?: return@repeat
            val now = live.text?.toString()?.trim() ?: ""
            // Changed in any way -> the composer consumed the text.
            if (now != sent) return true
        }
        // Unchanged after ~1.2s. On this composer that still usually means the
        // send worked (the draft copy looks identical), so this is reported
        // honestly rather than as a failure.
        Log.i(TAG, "composer text unchanged after submit attempt (may still have sent)")
        return false
    }

    /**
     * Make sure the composer is focused AND the keyboard is actually up.
     *
     * THIS IS THE ROOT CAUSE OF "text arrives but never submits".
     *
     * `ACTION_SET_TEXT` writes into a node without ever opening the keyboard.
     *, so the field shows the text while `mInputShown=false` and
     * `currentInputConnection` is null. Every Enter route then fails for one
     * reason: there is no live input connection to press Enter on.
     *   - ACTION_IME_ENTER returns performed=false (nothing is connected)
     *   - our IME's sendKeyEvent goes into a null connection
     *   - there is often no send button in the tree at all
     *
     * So the sequence has to be: focus -> raise the keyboard -> wait for the
     * connection -> then commit. That is what [focusAndShowKeyboard] does.
     *
     * Returns true when the node reports focus after the attempt.
     */
    private suspend fun focusAndShowKeyboard(node: AccessibilityNodeInfo): Boolean {
        return try {
            // 1. Focus the field itself. ACTION_FOCUS does not always raise the
            //    keyboard, so a click follows for views that only react to touch.
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(120)
            }

            // 2. Raise the keyboard.
            //
            // `InputMethodManager.showSoftInput` needs a View, and accessibility
            // only exposes nodes, so it cannot be called from here. The ImeSwapper
            // already puts DeckBridge in the serving position, and a focused
            // editable view makes the system show its keyboard. A short click is
            // the one nudge that works from a node alone.
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "focus+click sent (focused=${node.isFocused})")

            // 3. Let the IME bind. Without this wait the connection is still null
            //    when we try to commit, which is the original silent failure.
            delay(400)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "focusAndShowKeyboard failed: ${t::class.java.simpleName} ${t.message}")
            false
        }
    }

    /**
     * Have the DeckBridge IME commit Enter.
     *
     * Works only when DeckBridge is the SERVING keyboard -- i.e. it, and not
     * Gboard, owns the live InputConnection. [focusAndShowKeyboard] is what makes
     * that true, by raising the keyboard after we take the IME.
     *
     * Returns false (rather than throwing) when there is no connection, so the
     * caller can fall through to another strategy.
     */
    private fun enterViaKeyboard(): Boolean = try {
        DeckKeyboardService.get()?.let { svc ->
            svc.commitEnter()
        } ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "keyboard enter failed: ${t::class.java.simpleName} ${t.message}")
        false
    }

    /**
     * The ACTION_IME_ENTER action id, which commits text from an accessibility
     * service and presses Enter in the target app.
     *
     * WHY THE HARD-CODED VALUE: the value is a stable platform constant
     * (AccessibilityNodeInfo.ACTION_IME_ENTER = 0x01000010, added in API 30), but
     * the field is NOT present on every SDK build -- on this device (Android
     * SDK 37) a reflective lookup returns nothing and yields -1.
     *
     * -1 is the killer: performAction(-1) returns false, and because the code
     * treats that as "IME enter unavailable" it silently falls through to the
     * send-button click, which is what produced "typed but never submitted".
     * The text landed in the composer and just sat there.
     *
     * So the constant is used directly, with the reflective lookup as a
     * cross-check only. 0x01000010 is correct for every API >= 30.
     */
    private val IME_ENTER_ACTION: Int by lazy {
        val fromField = try {
            AccessibilityNodeInfo::class.java
                .getField("ACTION_IME_ENTER")
                .getInt(null)
        } catch (t: Throwable) {
            -1
        }
        if (fromField > 0) fromField else 0x01000010
    }

    /** Click a nearby button that looks like send. */
    /**
     * Submit by touching the composer's real send control.
     *
     * WHAT THE EXPERIMENT SHOWED (this is the root cause)
     *
     * On this target, KEYCODE_ENTER does NOT submit. Proven with the privileged
     * ADB path, which is the strongest form of the test available: typing text
     * and running `input keyevent 66` left the composer holding
     * "PROBE_ALPHA&#10;&#10;" -- Enter inserted ANOTHER NEWLINE. The field is a
     * multi-line chat composer, so a bare Enter is defined as "insert newline"
     * and the send action lives on a BUTTON. That is why every Enter-based
     * approach failed, including the correct KEYCODE 66 from the IME: the phone
     * was never wrong, the assumption was.
     *
     * WHAT THE SEND CONTROL LOOKS LIKE
     *
     * Found by dumping the tree while the composer was live:
     *
     *     x839-922 y1923-2007  ToggleButton  clickable=true  content-desc=""
     *
     * An UNNAMED ToggleButton sitting immediately to the RIGHT of the composer
     * field, sharing its vertical centre, next to a labelled "Attach file"
     * Button. The missing content-desc is exactly why every label-matching
     * strategy (send/submit/post) missed it and clicked "pane actions" instead.
     *
     * So this locates the control STRUCTURALLY rather than by guesswork:
     * walk up from the composer, find the clickable node that (a) is not the
     * composer, (b) overlaps the composer's vertical band, and (c) starts to the
     * right of the composer's left edge. Among those, prefer the rightmost that
     * is not the attach button. Then TAP it with dispatchGesture, because a
     * DOM-painted control does not respond to ACTION_CLICK from its node.
     */
    private suspend fun tapSendControl(node: AccessibilityNodeInfo): Boolean {
        // CRITICAL ORDERING NOTE
        //
        // The composer MOVES after the text lands in it. Measured on this device:
        // the send control was found at y1965, and by the time the tap fired the
        // composer had scrolled up and the button was at y1231 -- a ~730px jump,
        // so the tap hit empty screen. Every "the tap did nothing" result traces
        // back to this.
        //
        // So the position is read as late as possible: the text is already in the
        // composer by the time this is called, and the bounds below are read
        // fresh here and consumed by the gesture immediately, with no delay or
        // other work in between.
        // CRITICAL: read the composer FRESH from the live tree, not from the
        // `node` handle passed in. That handle is a snapshot taken before the
        // text was inserted, so its bounds describe where the composer USED to
        // be. Re-resolving here is what makes the tap follow the reflow.
        val live = findTargetNode() ?: node
        val comp = Rect()
        live.getBoundsInScreen(comp)
        if (comp.width() <= 0 || comp.height() <= 0) {
            Log.w(TAG, "tapSendControl: composer has no bounds")
            return false
        }

        val target = findSendControl(live, comp)
        if (target == null) {
            Log.w(TAG, "tapSendControl: no send control found near composer=$comp")
            return false
        }
        val (fx, fy) = target
        Log.i(TAG, "tapSendControl: composer=$comp tapping send control at ($fx,$fy)")

        val tapResult = gestureTap(fx, fy)
        if (!tapResult) {
            Log.w(TAG, "tapSendControl: gesture refused")
            return false
        }
        return awaitTextCleared(live)
    }

    /**
     * Dispatch a single synthetic touch at (x, y) and wait for the callback.
     *
     * Returns true when the gesture was accepted, which is a statement about the
     * touch being delivered -- not about the target having acted on it.
     */
    private suspend fun gestureTap(x: Int, y: Int): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 60L)
            )
            .build()
        val ok = dispatchGesture(
            gesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: android.accessibilityservice.GestureDescription?) {
                    done.countDown()
                }
                override fun onCancelled(d: android.accessibilityservice.GestureDescription?) {
                    done.countDown()
                }
            },
            null
        )
        if (!ok) return false
        done.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        return true
    }

    /**
     * Re-read the composer from the live tree and tap its send control.
     *
     * Used when the first attempt could not confirm: the page may have scrolled
     * between discovering the button and touching it, so this re-locates both the
     * composer and the control in one pass and taps immediately.
     */
    private suspend fun tapSendControlRefreshed(): Boolean {
        val live = findTargetNode() ?: return false
        val r = Rect()
        live.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        val t = findSendControl(live, r) ?: return false
        Log.i(TAG, "tapSendControlRefreshed: composer=$r control=(${t.first},${t.second})")

        val done = java.util.concurrent.CountDownLatch(1)
        val path = android.graphics.Path().apply { moveTo(t.first.toFloat(), t.second.toFloat()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 60L)
            )
            .build()
        val ok = dispatchGesture(
            gesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: android.accessibilityservice.GestureDescription?) {
                    done.countDown()
                }
                override fun onCancelled(d: android.accessibilityservice.GestureDescription?) {
                    done.countDown()
                }
            },
            null
        )
        if (!ok) return false
        done.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        return awaitTextCleared(live)
    }

    /**
     * Find the send control's screen centre, structurally.
     *
     * Returns null when nothing plausible is adjacent, so the caller can fall
     * through rather than tap a random spot.
     *
     * The candidate must:
     *   - be clickable and not be the composer itself or its ancestor,
     *   - overlap the composer's vertical band (it is a sibling on the same row),
     *   - lie at or after the composer's horizontal centre, and
     *   - be a plausible control size (not a full-width container).
     *
     * Among the survivors the RIGHTMOST wins, and a node whose label mentions
     * "attach" is skipped: on this UI the send control is the last element on the
     * row, immediately after the attach button.
     */
    private fun findSendControl(
        composer: AccessibilityNodeInfo,
        comp: Rect,
    ): Pair<Int, Int>? {
        val best = mutableListOf<Triple<Int, Int, Int>>()   // (x, y, rightEdge)

        fun consider(n: AccessibilityNodeInfo) {
            if (n == composer) return
            if (n.isClickable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                // The candidate must sit on the COMPOSER'S OWN ROW.
                //
                // Two earlier filters were too loose and each one produced a
                // confident wrong tap:
                //
                //   1. "touching" (r.top < comp.bottom && r.bottom > comp.top) let
                //      a control on the row above qualify when the composer's top
                //      edge drifted.
                //   2. "substantial overlap" (overlap > height/2) still qualified
                //      CHROME'S OWN URL-BAR BUTTON, because recursing from
                //      composer.parent in a WebAPK walks up into the browser
                //      chrome, whose ToggleButton at y1923 overlaps a great deal
                //      when the composer is reported low. Measured on Collie:
                //      the finder selected Rect(839,1923-922,2007) -- the Chrome
                //      address bar -- and tapped empty space while reporting
                //      success.
                //
                // So the test is now containment of the control's VERTICAL CENTRE
                // inside the composer's own band. A genuine sibling (Collie's
                // send at y1190-1273 beside a composer at y1190-1273) passes;
                // anything on another row fails, whatever its overlap.
                val controlSized = r.width() in 1..(comp.width())
                val sameRow = r.centerY() in comp.top..comp.bottom
                val toTheRight = r.centerX() >= comp.centerX()
                val label = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
                val isAttach = label.contains("attach") || label.contains("file")
                if (controlSized && sameRow && toTheRight && !isAttach) {
                    best.add(Triple(r.centerX(), r.centerY(), r.right))
                    Log.i(
                        TAG,
                        "sendControl candidate: $r sameRow=$sameRow label='${label.trim().take(30)}' cls=${n.className}"
                    )
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { consider(it) }
            }
        }

        (composer.parent ?: return null).let { consider(it) }

        val winner = best.maxByOrNull { it.third } ?: return null
        sendControlSeen = true
        return winner.first to winner.second
    }

    private fun clickSendButton(node: AccessibilityNodeInfo): Boolean {
        // Search upward for a send/submit control.
        //
        // Icon-only buttons carry NO text, so a label match alone misses Discord
        // and Collie, whose send controls are a paper-plane glyph with a
        // contentDescription or nothing at all. So this matches on three signals:
        //   1. a text/contentDescription containing send/submit/post
        //   2. a clickable node that is a SIBLING of the composer and sits to its
        //      RIGHT (the conventional position for send in a chat composer)
        //   3. a clickable node whose view-id hints at send
        val composerBounds = Rect()
        node.getBoundsInScreen(composerBounds)

        var container: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (container != null && depth < 5) {
            for (i in 0 until container.childCount) {
                val sib = container.getChild(i) ?: continue
                if (sib == node || !sib.isClickable) continue

                val label = buildString {
                    append(sib.text ?: "")
                    append(' ')
                    append(sib.contentDescription ?: "")
                    append(' ')
                    append(sib.viewIdResourceName ?: "")
                }.lowercase()

                val looksLikeSend =
                    label.contains("send") ||
                    label.contains("submit") ||
                    label.contains("post") ||
                    label.contains("send_button") ||
                    label.contains("sendbutton")

                // Right-of-composer heuristic: only for small controls, so we do
                // not click a giant container that happens to be clickable.
                val b = Rect()
                sib.getBoundsInScreen(b)
                val smallEnough = b.width() in 1..(composerBounds.width() * 3 / 4)
                val toTheRight = b.left >= composerBounds.right - composerBounds.width() / 4

                if (looksLikeSend || (smallEnough && toTheRight)) {
                    if (sib.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "clicked send control (label='${label.trim().take(40)}')")
                        return true
                    }
                }
            }
            container = container.parent
            depth++
        }
        return false
    }

    private fun deepestFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val f = deepestFocusedEditable(node.getChild(i))
            if (f != null) return f
        }
        return null
    }

    private fun firstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val f = firstEditable(node.getChild(i))
            if (f != null) return f
        }
        return null
    }

    /**
     * A focused node that looks like a text input even though it is not
     * reported as editable. This is the React Native composer case.
     */
    private fun deepestFocusedInputCapable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isFocusable && isTextFieldLike(node)) return node
        for (i in 0 until node.childCount) {
            val f = deepestFocusedInputCapable(node.getChild(i))
            if (f != null) return f
        }
        return null
    }

    private fun isTextFieldLike(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: return false
        if (cls.contains("EditText")) return true
        if (cls.contains("TextView") && node.isEditable) return true
        // React Native inputs surface as ViewGroup / View and only advertise
        // themselves by being focusable and accepting text actions.
        return cls.contains("ViewGroup") || cls == "android.view.View"
    }

    fun toast(msg: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

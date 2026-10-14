package com.hermes.deckbridge

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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

        private val TERMINAL_PACKAGES = setOf("com.termux")

        /** Default shell used for RUN_COMMAND delivery. */
        const val TERMUX_SHELL = "/data/data/com.termux/files/usr/bin/sh"
        const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
        const val TERMUX_ACTION = "com.termux.RUN_COMMAND"

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

    private fun resolveStrategy(explicit: Macro.Target, pkg: String?): Macro.Target =
        when (explicit) {
            Macro.Target.AUTO ->
                if (pkg != null && pkg in TERMINAL_PACKAGES) Macro.Target.TERMINAL
                else Macro.Target.TEXT_FIELD
            else -> explicit
        }

    /** Run a macro against the focused app. */
    fun runMacro(macro: Macro, onDone: ((String) -> Unit)? = null) {
        val pkg = foregroundPackage()
        val strategy = resolveStrategy(macro.target, pkg)
        Log.i(TAG, "runMacro key=${macro.keyIndex} pkg=$pkg strategy=$strategy enter=${macro.pressEnter}")

        scope.launch {
            val result = when (strategy) {
                Macro.Target.TERMINAL -> deliverToTerminal(macro)
                else -> deliverToChatApp(macro, pkg)
            }
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
        val wantsTermux = when (command.target) {
            Command.Target.TERMUX -> true
            Command.Target.DISCORD -> false
            Command.Target.AUTO -> pkg != null && pkg in TERMINAL_PACKAGES
        }
        Log.i(TAG, "runCommand '${command.label}' pkg=$pkg termux=$wantsTermux submit=${command.submit}")

        scope.launch {
            val result = if (wantsTermux) deliverTextToTerminal(command) else deliverTextToChatApp(command, pkg)
            Log.i(TAG, "runCommand done: $result")
            onDone?.invoke(result)
        }
    }

    /**
     * Termux path for a plain-words command.
     *
     * Prefers the visible route: the DeckBridge keyboard is the active IME, so
     * the words appear typed at the prompt and Enter submits them. That is the
     * behaviour a person expects when they tap "what do you recommend?".
     *
     * Falls back to RUN_COMMAND with `printf` so the words still land even when
     * the IME is not active. RUN_COMMAND runs the text AS a shell command, so
     * sending plain words unwrapped would try to EXECUTE them ("what: command
     * not found"), which is exactly the failure this wrapping exists to prevent.
     */
    private suspend fun deliverTextToTerminal(command: Command): String {
        if (command.text.isBlank()) return "empty"

        // Scoped IME swap, same as the deck-macro path: take the keyboard for
        // this press only, then hand it back, so Termux works AND the user's own
        // keyboard is still theirs afterwards.
        val typedOk = ImeSwapper.withKeyboard(applicationContext) {
            DeckKeyboardService.typeText(command.text, command.submit)
        }
        if (typedOk) return "typed into terminal"

        Log.i(TAG, "IME swap unavailable; falling back to RUN_COMMAND printf")
        val escaped = command.text.replace("\\", "\\\\").replace("\"", "\\\"")
        val cmd = if (command.submit) {
            "printf '%s\\n' \"$escaped\""
        } else {
            "printf '%s' \"$escaped\""
        }
        return deliverRawTermux(cmd)
    }

    /** Discord path for a plain-words command. */
    private suspend fun deliverTextToChatApp(command: Command, pkg: String?): String {
        return deliverToChatApp(
            Macro(
                keyIndex = -1,
                label = command.label,
                text = command.text,
                target = Macro.Target.TEXT_FIELD,
                pressEnter = command.submit,
            ),
            pkg
        )
    }

    /**
     * Deliver to a terminal.
     *
     * Uses a SCOPED IME SWAP so visible typing works in Termux without leaving
     * DeckBridge as the user's keyboard afterwards.
     *
     * The history here matters: an earlier version made DeckBridge the active
     * keyboard permanently, which is the only way to type into Termux's custom
     * terminal View, but it silently replaced the user's real keyboard and broke
     * text delivery into Discord and Notes. [ImeSwapper] takes the keyboard for
     * the duration of one press and hands it straight back, so one button press
     * into Termux no longer costs the user their keyboard everywhere else.
     *
     * Falls back to Termux's RUN_COMMAND API in every case where a swap is not
     * possible or the commit fails, so a button always does SOMETHING rather
     * than silently no-op.
     */
    private suspend fun deliverToTerminal(macro: Macro): String {
        if (macro.text.isBlank()) return "empty"

        val typedOk = ImeSwapper.withKeyboard(applicationContext) {
            DeckKeyboardService.typeText(macro.text, macro.pressEnter)
        }
        if (typedOk) return "typed into terminal"

        Log.i(TAG, "IME swap path unavailable; falling back to RUN_COMMAND")
        return deliverToTermux(macro, "com.termux")
    }

    /**
     * Force a Termux delivery regardless of what is focused.
     *
     * Used by the in-app diagnostic, because tapping a button necessarily
     * brings our own activity to the foreground and would otherwise make the
     * strategy resolve to a chat app.
     */
    fun testTermux(text: String, onDone: ((String) -> Unit)? = null) {
        scope.launch {
            val m = Macro(keyIndex = -1, label = "test", text = text, target = Macro.Target.TERMINAL)
            val r = deliverToTermux(m, "com.termux")
            Log.i(TAG, "testTermux -> $r")
            onDone?.invoke(r)
        }
    }

    // =====================================================================
    // TERMUX: official command API
    // =====================================================================

    /**
     * Deliver a macro to Termux by running its text as a shell command.
     *
     * The macro text IS the command. So a macro whose text is
     *     echo "hello"
     * prints hello, and one whose text is
     *     pkg install curl
     * installs curl. Nothing is wrapped or escaped, because the point is to run
     * exactly what the button says.
     *
     * Termux requirements, all mandatory:
     *   - the F-Droid build (the Play Store build lacks the permission)
     *   - allow-external-apps = true in ~/.termux/termux.properties
     *   - our app holds com.termux.permission.RUN_COMMAND
     */
    private fun deliverToTermux(macro: Macro, pkg: String?): String {
        if (macro.text.isBlank()) return "empty"

        // Deck macros store RAW SHELL, and the button is meant to run exactly
        // what it says, so it is passed through verbatim.
        return deliverRawTermux(macro.text.trim())
    }

    /**
     * Send a raw shell command string to Termux via RUN_COMMAND.
     *
     * The caller owns the exact command text: this does no wrapping or escaping.
     * Deck macros pass their own shell; the tap grid passes a `printf` wrapper
     * it built itself.
     */
    private fun deliverRawTermux(command: String): String {
        if (command.isBlank()) return "empty"

        val intent = Intent().apply {
            action = TERMUX_ACTION
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_SHELL)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra(
                "com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home"
            )
            // FOREGROUND mode: open a visible Termux session so the command and
            // its output are watchable in the terminal.
            //
            // Requires Termux to hold "Display over other apps", because
            // Android >= 10 blocks a background app from raising a window.
            // Without that permission the command still runs, but the session
            // only appears after the user taps the Termux notification.
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra(
                "com.termux.RUN_COMMAND_SERVICE",
                "com.termux.app.RunCommandService"
            )
        }

        return try {
            startService(intent)
            Log.i(TAG, "termux run queued: $command")
            "sent to termux"
        } catch (t: Throwable) {
            Log.w(TAG, "termux run failed: ${t::class.java.simpleName} ${t.message}")
            "termux not reachable"
        }
    }

    // =====================================================================
    // CHAT APPS (Discord, Moshi): set text, then commit
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
            delay(120)
            val sent = sendEnter(node)
            Log.i(TAG, "sendEnter -> $sent")
            if (!sent) return "typed (enter failed)"
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
    private suspend fun sendEnter(node: AccessibilityNodeInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val ok = try {
                node.performAction(IME_ENTER_ACTION)
            } catch (t: Throwable) {
                false
            }
            Log.i(TAG, "enter via IME action: performed=$ok (action=0x${Integer.toHexString(IME_ENTER_ACTION)})")
            // ACTION_IME_ENTER reports success on some WebView inputs (Chrome's
            // Collie composer among them) while doing nothing at all: the return
            // value is a lie. Trust it only if the field actually committed.
            if (ok && awaitTextCleared(node)) return true
            if (ok) Log.i(TAG, "IME enter returned true but text stayed; trying the keyboard")
        } else {
            Log.i(TAG, "enter: SDK < 30, IME action unavailable")
        }

        // Next best: have the DeckBridge keyboard emit a real key event. This is
        // the path that actually submits in WebView composers, because they bind
        // to keydown/Enter rather than to the editor action.
        if (enterViaKeyboard()) {
            if (awaitTextCleared(node)) {
                Log.i(TAG, "enter via keyboard ok")
                return true
            }
            Log.i(TAG, "keyboard enter sent but text stayed")
        }

        // Last resort: a nearby button that looks like send.
        if (clickSendButton(node)) {
            Log.i(TAG, "enter via send-button click ok")
            return true
        }

        Log.w(TAG, "all enter strategies failed")
        return false
    }

    /**
     * True once the target field stops reporting the text we typed, i.e. the
     * composer really submitted. WebView inputs clear on send; a field that
     * still holds the text did not submit no matter what the action returned.
     */
    private suspend fun awaitTextCleared(node: AccessibilityNodeInfo): Boolean {
        repeat(10) {
            delay(80)
            // Re-read through the current tree: the node handle we hold is a
            // snapshot and will not show the composer clearing on its own.
            val live = findTargetNode() ?: return@repeat
            if (live.text.isNullOrEmpty()) return true
        }
        return false
    }

    /** Have the DeckBridge IME commit Enter, if it is the active keyboard. */
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
    private fun clickSendButton(node: AccessibilityNodeInfo): Boolean {
        // Search upward a couple of levels for a send/submit control.
        var container: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (container != null && depth < 4) {
            for (i in 0 until container.childCount) {
                val sib = container.getChild(i) ?: continue
                if (!sib.isClickable) continue
                val label = buildString {
                    append(sib.text ?: "")
                    append(' ')
                    append(sib.contentDescription ?: "")
                }.lowercase()
                if (label.contains("send") || label.contains("submit")) {
                    if (sib.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
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

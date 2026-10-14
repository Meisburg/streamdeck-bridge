package com.hermes.deckbridge

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A minimal input method whose only job is to be a channel for simulated typing.
 *
 * WHY THIS EXISTS
 * Termux's terminal is com.termux.view.TerminalView: a plain View with no
 * editable node. It accepts keystrokes ONLY from an input method, verified via
 * `dumpsys input_method` showing it as the served view with an active IME.
 * Neither accessibility text injection nor `adb input text` can reach it, so a
 * deck button cannot make text "appear typed" at the prompt without a component
 * that speaks IME.
 *
 * This class is that component. It renders no UI of its own; it just holds the
 * InputConnection so DeckBridge can commit text into whatever field is focused,
 * including Termux's terminal.
 *
 * USAGE
 * The user selects "DeckBridge keyboard" as their input method (once). After
 * that, a deck button commits its text and a newline through this connection,
 * which reads on screen exactly like someone typing.
 */
class DeckKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "DeckKeyboard"

        @Volatile
        private var instance: DeckKeyboardService? = null

        /**
         * True only while a deck press is deliberately using this keyboard.
         *
         * Set around a press so a start that is NOT part of one can be recognised
         * and undone: this keyboard has no keys, so a user who lands on it cannot
         * type anywhere.
         */
        @Volatile
        var programmaticPress: Boolean = false

        fun isReady(): Boolean = instance?.currentInputConnection != null

        /** The live IME, for callers that need to commit Enter (see commitEnter). */
        fun get(): DeckKeyboardService? = instance

        /**
         * Type [text] into whatever currently has input focus, optionally
         * followed by a newline.
         *
         * Returns false when no input connection is available, which happens
         * when this IME is not the active one.
         */
        fun typeText(text: String, pressEnter: Boolean): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "typeText: no service instance")
                return false
            }
            val ic = svc.currentInputConnection ?: run {
                Log.w(TAG, "typeText: no input connection")
                return false
            }
            return try {
                // commitText writes as if typed. Sent as a single batch so the
                // terminal receives it atomically rather than character by
                // character.
                ic.beginBatchEdit()
                ic.commitText(text, 1)
                if (pressEnter) {
                    ic.commitText("\n", 1)
                    // Some terminal views watch the raw key stream instead, so
                    // send Enter both ways.
                    ic.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                    )
                    ic.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                    )
                }
                ic.endBatchEdit()
                Log.i(TAG, "typeText ok: '$text' enter=$pressEnter")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "typeText failed: ${t::class.java.simpleName} ${t.message}")
                false
            }
        }

        /**
         * Press Enter into the focused field without typing anything, for
         * callers that already set the text themselves (the accessibility
         * service after ACTION_SET_TEXT).
         *
         * WebView composers bind to keydown, so sendKeyEvent is the part that
         * actually submits them; the newline commit is kept for terminals that
         * read the text stream instead.
         */
        fun commitEnter(): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "commitEnter: no service instance")
                return false
            }
            val ic = svc.currentInputConnection ?: run {
                Log.w(TAG, "commitEnter: no input connection")
                return false
            }
            return try {
                ic.beginBatchEdit()
                sendEnterVariants(ic)
                ic.endBatchEdit()
                Log.i(TAG, "commitEnter: sent DOWN+UP (66), NUMPAD_ENTER (160) and newline")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "commitEnter failed: ${t::class.java.simpleName} ${t.message}")
                false
            }
        }

        /**
         * Push Enter through every route a target might listen on.
         *
         * KEYCODE_ENTER is 66 and that IS the correct value; the problem is that
         * `InputConnection.sendKeyEvent` is advisory. Web views and some custom
         * views ignore it, or only look at the DOWN, or bind to the newer
         * keyCode 160 (NUMPAD_ENTER) instead. Sending all of them costs nothing
         * and removes the guesswork:
         *
         *   1. KEYCODE_ENTER DOWN + UP          (66, the standard key)
         *   2. KEYCODE_NUMPAD_ENTER DOWN + UP   (160, what some web code binds)
         *   3. a literal newline commit         (some editors only see text)
         *
         * The batch is closed by the caller so the target sees one edit.
         */
        private fun sendEnterVariants(ic: android.view.inputmethod.InputConnection) {
            // Send ONLY real key events. Deliberately no commitText("\n"):
            //
            // a newline COMMIT inserts a literal newline character, which a chat
            // composer treats as "the user pressed Shift+Enter" -- it grows the
            // message box and never submits. That is why a field ended up holding
            // "text\n\n\n" while nothing ever sent. Enter has to be a KEY, not a
            // character.
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

            // Some web composers only bind the numeric-keypad Enter.
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_ENTER))
        }
    }

    /**
     * Press Enter into the focused field without typing anything, for callers
     * that already set the text themselves (the accessibility service after
     * ACTION_SET_TEXT).
     *
     * WebView composers bind to keydown, so sendKeyEvent is the part that
     * actually submits them; the newline commit is kept for terminals that read
     * the text stream instead.
     */
    fun commitEnter(): Boolean {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "commitEnter: no input connection")
            return false
        }
        return try {
            ic.beginBatchEdit()
            // Real KEY events only -- no commitText("\n").
            //
            // A committed newline is a CHARACTER, and a chat composer reads it as
            // Shift+Enter: the box grows and never sends. The field was found
            // holding "text\n\n\n", which is exactly this bug. Enter must be a key.
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_ENTER))
            ic.endBatchEdit()
            Log.i(TAG, "commitEnter: sent key 66 and 160 (no newline commit)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "commitEnter failed: ${t::class.java.simpleName} ${t.message}")
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "keyboard service created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.i(TAG, "input started for ${info?.packageName ?: "?"}")

        // FAILSAFE: this keyboard has no keys, so a user who lands on it cannot
        // type anywhere. If it is ever started OUTSIDE a programmatic press, hand
        // the keyboard straight back to the user's own IME rather than leaving
        // them stranded with an invisible keyboard.
        if (!programmaticPress) {
            Log.w(TAG, "started without a DeckBridge press; restoring the user's keyboard")
            ImeSwapper.restoreToUserKeyboard(applicationContext)
        }
    }


    /**
     * A SAFETY NET, not a keyboard.
     *
     * This IME exists only to hold an InputConnection so text can be committed
     * programmatically. It is deliberately invisible, which creates a trap: if
     * the user (or a failed swap) leaves DeckBridge selected, NO keyboard appears
     * anywhere on the phone and typing is impossible -- from the user's side and
     * from any tool. Measured on this device: the service was enabled and
     * selectable while the user reported being "blocked from typing manually.",
     * and every injection attempt failed for the same reason.
     *
     * So this view renders a small, unmistakable row that names the problem and
     * offers the way out. It is one line tall: invisible enough not to disturb a
     * programmatic press, visible enough that the user is never stranded without
     * knowing why.
     */
    override fun onCreateInputView(): View {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF202124.toInt())
            setPadding(24, 16, 24, 16)

            addView(TextView(ctx).apply {
                text = "DeckBridge keyboard active — no keys by design."
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
            })

            addView(TextView(ctx).apply {
                text = "  Switch back"
                setTextColor(0xFF8AB4F8.toInt())
                textSize = 13f
                setPadding(16, 0, 0, 0)
                isClickable = true
                setOnClickListener {
                    // Hand the user straight back to a real keyboard. Without this
                    // the only route out is Android Settings, which is not something
                    // to make someone discover mid-task.
                    try {
                        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        imm?.showInputMethodPicker()
                    } catch (t: Throwable) {
                        Log.w(TAG, "could not open the keyboard picker: ${t.message}")
                    }
                }
            })
        }
    }

    /**
     * Report the input view as shown.
     *
     * Returning false here (as an earlier version did) meant the framework never
     * displayed the row above, so a stranded user had NO indication that
     * DeckBridge was their active keyboard. Truthful here means the warning is
     * actually visible.
     */
    override fun onEvaluateInputViewShown(): Boolean = true
}

package com.hermes.deckbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

/**
 * Temporarily makes DeckBridge the active keyboard, types, then hands the
 * keyboard back to whatever the user normally uses.
 *
 * WHY THIS EXISTS
 *
 * Termux's terminal is a plain View with no editable node. It accepts keystrokes
 * ONLY from the active input method. So typing visibly into Termux requires
 * DeckBridge to BE the selected keyboard.
 *
 * But the keyboard selection is a GLOBAL, STICKY system setting. Leaving
 * DeckBridge selected permanently does two bad things:
 *
 *   1. It replaces the user's real keyboard (Futo, Gboard, ...) everywhere, so
 *      the moment a deck button works in Termux, the user cannot type normally
 *      anywhere else.
 *   2. It changes the IME that Discord/Notes are bound to, which is what made
 *      those apps stop receiving input after the Termux path was wired up.
 *
 * That trade-off is the exact bug this class removes. Instead of choosing one
 * app to break, the swap is scoped to a single button press:
 *
 *   save current IME -> select DeckBridge -> type -> wait -> restore previous IME
 *
 * The user's keyboard is only unavailable for the ~1s the swap is in flight.
 *
 * WHY IT IS NOT A "silently switch IME back" HACK
 *
 * Restoring the previous IME is deliberate and bounded: it uses the same
 * `Settings.Secure.DEFAULT_INPUT_METHOD` write that `adb shell ime set` does, and
 * it always restores the value that was read at the start. If the swap fails at
 * any point, [restore] is still safe to call and puts the original back.
 */
object ImeSwapper {

    private const val TAG = "ImeSwap"

    /** DeckBridge's own IME component, matched against the secure setting. */
    private const val OUR_IME = "com.hermes.deckbridge/.DeckKeyboardService"

    /**
     * Time to let the system rebind the input connection after a swap.
     *
     * The IME is re-bound asynchronously, so reading `currentInputConnection`
     * immediately after the write returns null and the text is lost. 450ms was
     * reliable across repeated runs on a Pixel 9 Pro; shorter values intermittently
     * dropped the commit.
     */
    private const val REBIND_DELAY_MS = 450L

    private val main = Handler(Looper.getMainLooper())

    /** The IME that was active before we took over. Null when not swapped. */
    @Volatile
    private var previousIme: String? = null

    /** Current system IME id, or null if it cannot be read. */
    private fun currentIme(ctx: Context): String? = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    } catch (t: Throwable) {
        Log.w(TAG, "could not read current IME: ${t.message}")
        null
    }

    /** True when DeckBridge is already the active keyboard. */
    fun isActive(ctx: Context): Boolean = currentIme(ctx) == OUR_IME

    /**
     * Run [typeAction] with DeckBridge as the keyboard, then restore.
     *
     * If DeckBridge is ALREADY active (the user chose it deliberately, or a
     * previous restore failed) nothing is swapped and nothing is restored, so a
     * deliberate choice is never overridden.
     *
     * Returns true when the swap succeeded and the action ran.
     */
    fun withKeyboard(ctx: Context, typeAction: () -> Boolean): Boolean {
        val alreadyOurs = isActive(ctx)

        if (alreadyOurs) {
            Log.i(TAG, "DeckBridge already active; no swap needed")
            return typeAction()
        }

        val before = currentIme(ctx)
        if (before == null) {
            Log.w(TAG, "cannot read current IME; refusing to swap blindly")
            return false
        }

        if (!setIme(ctx, OUR_IME)) {
            Log.w(TAG, "failed to select DeckBridge IME")
            return false
        }
        previousIme = before
        Log.i(TAG, "swapped IME: $before -> $OUR_IME")

        // Give the system time to bind our InputConnection.
        // wait/notify is used rather than a blocking sleep loop so the caller's
        // thread is not pinned for longer than necessary.
        val lock = Object()
        main.postDelayed({ synchronized(lock) { lock.notifyAll() } }, REBIND_DELAY_MS)
        synchronized(lock) {
            try {
                lock.wait(REBIND_DELAY_MS + 400)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val ok = try {
            typeAction()
        } catch (t: Throwable) {
            Log.w(TAG, "typeAction threw: ${t::class.java.simpleName} ${t.message}")
            false
        }

        restore(ctx)
        return ok
    }

    /**
     * Hand the keyboard back to a real, usable input method.
      *
      * Emergency exit for the case where DeckBridge is (or becomes) the active IME
      * without a press in flight. This keyboard renders no keys, so a user left on
      * it cannot type anywhere, on any app -- measured symptom: "I think the
      * deckbridge keyboard blocks me from manually typing on the phone."
      *
      * Picks a keyboard that is NOT ours, preferring the one the user had before.
      * Never selects ourselves, and never leaves ourselves selected.
      */
     fun restoreToUserKeyboard(ctx: Context) {
         if (!isActive(ctx)) return
         val fallback = firstRealKeyboard(ctx)
         if (fallback == null) {
             Log.w(TAG, "no alternative keyboard found; cannot restore")
             return
         }
         Log.i(TAG, "DeckBridge keyboard is active outside a press; restoring -> $fallback")
         if (!setIme(ctx, fallback)) {
             Log.w(TAG, "FAILED to restore a usable keyboard")
         }
     }

     /**
      * The first enabled keyboard that is not ours, or null.
      *
      * Reads the enabled list the same way `ime list -s` does, so the value chosen
      * is guaranteed selectable.
      */
     private fun firstRealKeyboard(ctx: Context): String? = try {
         val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
         imm?.enabledInputMethodList
             ?.map { "${it.packageName}/${it.serviceName}" }
             ?.firstOrNull { it != OUR_IME }
     } catch (t: Throwable) {
         Log.w(TAG, "could not enumerate keyboards: ${t.message}")
         null
     }

     /** Put the user's own keyboard back.
     *
     * Safe to call at any time and more than once: it only acts when a swap is
     * outstanding, and it clears that flag even if the write fails, so a failure
     * cannot leave the user stranded on the DeckBridge keyboard.
     */
    fun restore(ctx: Context) {
        val target = previousIme ?: return
        previousIme = null

        // Never restore to ourselves; that is not a restore.
        if (target == OUR_IME) {
            Log.i(TAG, "previous IME was DeckBridge; nothing to restore")
            return
        }

        if (setIme(ctx, target)) {
            Log.i(TAG, "restored IME -> $target")
        } else {
            Log.w(TAG, "FAILED to restore IME -> $target; user may need to re-select")
        }
    }

    /**
     * True when DeckBridge holds the privileged permission needed to change the
     * system input method.
     *
     * A normal app install can never hold this: it is signature|privileged, so
     * only an adb grant or a system install provides it. The UI surfaces this so
     * the user knows why Termux falls back to the command API.
     */
    fun canSwap(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Select an input method.
     *
     * Writes the same secure setting `adb shell ime set` writes. Requires
     * WRITE_SECURE_SETTINGS; without it the write throws SecurityException and
     * the caller falls back to a path that needs no swap.
     */
    private fun setIme(ctx: Context, id: String): Boolean = try {
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, id)
        currentIme(ctx) == id
    } catch (t: Throwable) {
        Log.w(TAG, "setIme($id) failed: ${t::class.java.simpleName} ${t.message}")
        false
    }
}

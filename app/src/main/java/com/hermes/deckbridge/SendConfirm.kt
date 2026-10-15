package com.hermes.deckbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * The one place a send outcome is announced.
 *
 * WHY this is shared rather than living in the Activity: the tap grid, the
 * broadcast API (CommandReceiver) and the "Test send" button are three paths to
 * the same action, and a confirmation that only one of them emits is worse than
 * none -- the user learns to trust the notification, then stops seeing it on the
 * path they actually use.
 *
 * WHY a notification rather than a toast or an on-screen line: the app calls
 * moveTaskToBack() before every send, so at the moment the outcome is known the
 * user is looking at a different app. A toast would fire behind it; the status
 * line is not even on screen. The shade is the only surface that is always
 * visible, and IMPORTANCE_LOW means it never makes a sound or steals focus.
 *
 * This exists because the deck is a flaky peripheral on a flaky USB link. When
 * success is silent and failure is silent, the user cannot tell a working tap
 * from a missed one, which is exactly the doubt that makes a tool feel broken.
 */
object SendConfirm {

    private const val CH_ID = "deckbridge-send"
    private const val NOTIFY_ID = 1001

    /** True when the accessibility service reported the text actually landed. */
    private fun isSuccess(result: String): Boolean {
        val r = result.lowercase()
        // Failure strings from DeckAccessibilityService are phrased as
        // "no input field in <pkg>", "could not ...", etc. Success is not a single
        // fixed token, so treat the known failure shapes as failure and anything
        // else as success.
        val looksFailed = r.contains("no input field") ||
            r.contains("could not") ||
            r.contains("failed") ||
            r.contains("not found") ||
            r.contains("no command")
        return !looksFailed
    }

    fun show(ctx: Context, label: String, result: String) {
        val ok = isSuccess(result)
        val name = label.ifBlank { "command" }
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CH_ID,
                        "Send confirmation",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Shows whether a command was sent" }
                )
            }
            val n = NotificationCompat.Builder(ctx, CH_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(if (ok) "Sent" else "Not sent")
                .setContentText(if (ok) name else "$name -- no text field was focused")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setTimeoutAfter(6000)
                .build()
            nm.notify(NOTIFY_ID, n)
        }
    }
}

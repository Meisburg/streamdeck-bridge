package com.hermes.deckbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log

/**
 * Keeps DeckBridge resident.
 *
 * Why this exists: the accessibility service alone does not survive. Android
 * kills a background app process under memory pressure, which tears down the
 * accessibility binding, and the system then reports the service as disabled,
 * so the user has to re-enable it in Settings repeatedly. A foreground service
 * with an ongoing notification is the supported way for a resident helper to
 * stay alive and be restarted automatically after a kill or reboot.
 *
 * This does NO work itself; it exists to keep the process warm so
 * DeckAccessibilityService stays bound and the USB reader keeps polling.
 */
class DeckResidentService : Service() {

    companion object {
        private const val TAG = "DeckResident"
        private const val CHANNEL_ID = "deckbridge_resident"
        private const val NOTIF_ID = 4201
        const val ACTION_START = "com.hermes.deckbridge.RESIDENT_START"

        fun start(ctx: Context) {
            val i = Intent(ctx, DeckResidentService::class.java).apply { action = ACTION_START }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
                Log.i(TAG, "resident service start requested")
            } catch (t: Throwable) {
                Log.w(TAG, "could not start resident service: ${t.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "resident service running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWatching()
        // START_STICKY: Android restarts this after a kill, which is what keeps
        // the accessibility binding available without user intervention.
        return START_STICKY
    }

    private var watch: android.os.Handler? = null
    private var lastBeat = 0L

    /**
     * Whether the repair notification is currently showing.
     *
     * Edges, not levels: the watchdog ticks every four seconds, so posting the
     * notification on every tick would re-alert constantly and make the banner
     * meaningless. This tracks the transition into and out of the down state.
     */
    private var warnedDown = false

    /**
     * Watch for the accessibility service dying, and make it obvious when it does.
     *
     * WHY here and not in MainActivity: the Activity's poller only runs while the
     * app is on screen, which is exactly when the death does NOT matter -- the user
     * is looking at the app and can see the warning. The death that costs them is
     * the one that happens while they are in Discord, phone in hand, pressing a
     * deck key that does nothing. This service is resident the whole time, so it is
     * the only place that can notice.
     *
     * Android will not let an app re-enable its own accessibility service (that
     * would be a malware pattern), so this cannot fix it silently. What it CAN do is
     * put the fix one tap away: bring the app forward with the repair button already
     * on screen, instead of leaving the user to work out why nothing types.
     */
    private fun startWatching() {
        if (watch != null) return
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        watch = h
        val tick = object : Runnable {
            override fun run() {
                val up = DeckAccessibilityService.isRunning()
                // Heartbeat, throttled: proves the watchdog is alive and tells us
                // what it currently believes. Without this the watchdog is
                // unobservable -- which is how a silent failure hides.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastBeat > 60_000) {
                    lastBeat = now
                    Log.i(TAG, "watchdog: service running=$up")
                }
                if (!up) {
                    // Never steal focus. The notification is the repair channel.
                    //
                    // This used to call startActivity to "surface the fix", guarded
                    // only by "is DeckBridge already in front?". That guard cannot
                    // work: foregroundPackage() asks the ACCESSIBILITY SERVICE what is
                    // in front, and the accessibility service being down is precisely
                    // why we are here, so it returns "unknown" and the guard passes
                    // every time. The result was a four-second loop that threw the
                    // user out of Settings > Accessibility -- the one screen where the
                    // toggle they needed actually lives -- faster than they could tap
                    // it. A watchdog that prevents the repair is worse than no
                    // watchdog.
                    //
                    // The service cannot see the foreground app without privileges it
                    // does not have and should not ask for, so it cannot tell "user is
                    // busy" from "user is idle". The honest fix is to stop guessing and
                    // stop interrupting: warn once per outage through the notification,
                    // which is tappable, persistent, and takes no focus.
                    if (!warnedDown) {
                        warnedDown = true
                        Log.i(TAG, "typing service down -- raising the repair notification")
                        notifyServiceDown()
                    }
                } else if (warnedDown) {
                    // Recovery is worth announcing too: otherwise the banner stays on
                    // screen after the problem is gone and stops meaning anything.
                    warnedDown = false
                    Log.i(TAG, "typing service back up -- clearing the repair notification")
                    notifyHealthy()
                }
                h.postDelayed(this, 4000)
            }
        }
        h.postDelayed(tick, 4000)
    }

    override fun onDestroy() {
        watch?.removeCallbacksAndMessages(null)
        watch = null
        super.onDestroy()
    }

    /**
     * Which app is actually in front.
     *
     * getRunningTasks is restricted on modern Android, and this service has no
     * accessibility privileges of its own, so ask the accessibility service for the
     * answer it already needs for routing. Falls back to "unknown" rather than
     * guessing, because a wrong answer here would mean stealing focus from the user
     * mid-sentence.
     */
    private fun foregroundPackage(): String =
        DeckAccessibilityService.instance?.foregroundPackage() ?: "unknown"

    /**
     * The repair notification: the fix, one tap away, without stealing focus.
     *
     * Deep-links straight to this app's Accessibility screen rather than to the app
     * itself. The user's problem is "typing does not work", and the settings screen
     * is the only place that gets fixed; landing them on the app's own home screen
     * would just make them hunt for the path they already know.
     */
    private fun notifyServiceDown() {
        val fix = PendingIntent.getActivity(
            this, 1,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE else 0
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        val n = b.setContentTitle("Typing is off")
            .setContentText("Tap to enable DeckBridge typing")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(fix)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        }
    }

    /** Back to the quiet resident banner once typing works again. */
    private fun notifyHealthy() {
        val m = getSystemService(NotificationManager::class.java)
        runCatching { m.notify(NOTIF_ID, buildNotification()) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "DeckBridge",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps the Stream Deck bridge running"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE else 0
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return b.setContentTitle("DeckBridge active")
            .setContentText("Stream Deck connected")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }
}

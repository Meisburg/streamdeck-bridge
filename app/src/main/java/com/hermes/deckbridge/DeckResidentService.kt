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
                    // Only interrupt if DeckBridge is not already in front: bringing
                    // the app forward is the whole point, but doing it while the user
                    // is already looking at the app would just be a loop.
                    val fg = foregroundPackage()
                    if (fg != packageName) {
                        Log.i(TAG, "typing service down while in $fg -- surfacing the fix")
                        runCatching {
                            startActivity(
                                Intent(this@DeckResidentService, MainActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                    putExtra(MainActivity.EXTRA_SERVICE_DROPPED, true)
                                }
                            )
                        }
                    }
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

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
        // START_STICKY: Android restarts this after a kill, which is what keeps
        // the accessibility binding available without user intervention.
        return START_STICKY
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

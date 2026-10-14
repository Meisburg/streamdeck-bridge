package com.hermes.deckbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the resident service after a reboot or an app update.
 *
 * Without this the accessibility binding is lost on every restart and the user
 * has to re-enable DeckBridge in Settings by hand.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("BootReceiver", "restarting resident service after ${intent.action}")
                DeckResidentService.start(ctx)
            }
        }
    }
}

package com.hermes.deckbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires a saved command by name, triggered from outside the app.
 *
 * Purpose: script the phone from a workstation without touching it.
 *
 *     adb shell am broadcast -a com.hermes.deckbridge.SEND \
 *       -n com.hermes.deckbridge/.CommandReceiver \
 *       --es command status
 *
 * The command is looked up in CommandStore by its id or by its label, matched
 * case-insensitively, so either the stable id ("status") or the on-screen name
 * ("Status") works. Nothing is hard-coded: whatever is in commands.json is what
 * can be triggered.
 *
 * Delivery goes through the same DeckAccessibilityService path the tap grid
 * uses, so this exercises the exact production code, which is also why it is
 * useful as a test harness.
 *
 * A command can also be supplied inline with --es text "...", which sends that
 * literal string with the AUTO target and no persistence. That is the escape
 * hatch for one-off sends.
 */
class CommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DeckCmdReceiver"
        const val ACTION = "com.hermes.deckbridge.SEND"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val svc = DeckAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "typing service not running; cannot send")
            return
        }

        // Inline text wins, so a one-off send needs no saved command.
        val inline = intent.getStringExtra("text")
        if (!inline.isNullOrBlank()) {
            val cmd = Command(
                id = "inline",
                label = "inline",
                text = inline,
                target = parseTarget(intent.getStringExtra("target")),
                submit = intent.getBooleanExtra("submit", true),
            )
            Log.i(TAG, "inline send: '$inline'")
            svc.runCommand(cmd) { r ->
                Log.i(TAG, "inline -> $r")
                // The broadcast path is the ADB/scripting route, but it is also
                // how a send can be driven while the phone is in a pocket, so it
                // confirms like every other path rather than logging only.
                SendConfirm.show(ctx, inline.take(24), r.toString())
            }
            return
        }

        val name = intent.getStringExtra("command") ?: return
        val commands = CommandStore.load(ctx)
        val cmd = commands.firstOrNull {
            it.id.equals(name, ignoreCase = true) || it.label.equals(name, ignoreCase = true)
        }
        if (cmd == null) {
            Log.w(TAG, "no command matches '$name'")
            return
        }
        val override = intent.getStringExtra("target")?.let { parseTarget(it) } ?: cmd.target
        Log.i(TAG, "remote send '${cmd.label}' target=$override")
        svc.runCommand(cmd.copy(target = override)) { r ->
            Log.i(TAG, "remote '${cmd.label}' -> $r")
            SendConfirm.show(ctx, cmd.label, r.toString())
        }
    }

    private fun parseTarget(s: String?): Command.Target = when (s?.lowercase()) {
        "discord", "chat", "text" -> Command.Target.DISCORD
        else -> Command.Target.AUTO
    }
}

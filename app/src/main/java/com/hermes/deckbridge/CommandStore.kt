package com.hermes.deckbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A command: a label and the text it sends to an AI agent.
 *
 * This is deliberately NOT bound to a deck key index any more. The deck's key
 * input is dead on this hardware (Android's libusbhost segfaults reading the
 * interrupt endpoint), so the primary surface is an on-screen tap grid. A
 * command is now just "a thing you press", identified by a stable id rather
 * than by a physical key position.
 */
data class Command(
    val id: String,
    val label: String,
    val text: String,
    val target: Target = Target.AUTO,
    /** Press Enter after the text, submitting it in the target app. */
    val submit: Boolean = true,
) {
    enum class Target {
        /** Detect what is in front and choose the right delivery path. */
        AUTO,

        /** Send as chat text into an editable field (Discord and friends). */
        DISCORD,

    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("text", text)
        put("target", target.name)
        put("submit", submit)
    }

    companion object {
        fun fromJson(o: JSONObject): Command = Command(
            id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            label = o.optString("label", ""),
            text = o.optString("text", ""),
            target = runCatching {
                Target.valueOf(o.optString("target", "AUTO"))
            }.getOrDefault(Target.AUTO),
            submit = o.optBoolean("submit", true),
        )
    }
}

/**
 * Persists the command list as JSON in internal storage.
 *
 * JSON (rather than SharedPreferences) because it is trivially editable and
 * pushable over adb, which matters for scripting the layout from a workstation.
 */
object CommandStore {

    private const val FILE_NAME = "commands.json"

    /**
     * Seed set: what a fresh install starts with.
     *
     * Split deliberately into two halves, because the app does two things and the
     * second one is easy to miss:
     *
     *  1. STEERING prompts -- plain words that keep a running agent moving. These
     *     are universal; they work with any agent.
     *  2. SKILL / COMMAND examples -- a leading slash, which most agent harnesses
     *     (Claude Code, and anything else that reads slash commands or skills)
     *     interprets as "run the named capability". Nothing special is needed to
     *     support this: the text is typed and submitted like any other entry. It is
     *     included here so a new user discovers that the keys can launch named
     *     capabilities, not merely send canned chat.
     *
     * The slash examples are illustrative and harness-specific. Edit them to match
     * whatever your own agent actually answers to; an entry that means nothing to
     * your harness just types a useless line.
     *
     * These are written as the PLAIN WORDS the user wants to send, not as shell
     * wrappers, so the same entry works whether the text lands in a chat box or a
     * terminal.
     */
    private fun defaults(): List<Command> = listOf(
        // --- steering: keep the agent moving ---
        Command("yes", "Yes", "yes"),
        Command("no", "No", "no"),
        Command("recommend", "Recommend", "what do you recommend?"),
        Command("status", "Status", "what is the current status?"),
        Command("next", "Next step", "what is the next step?"),
        Command("continue", "Continue", "continue"),
        Command("explain", "Explain", "explain what you just did in 3 bullets"),
        Command("stop", "Stop", "stop and wait for me"),
        Command("simpler", "Simpler", "make this simpler"),
        Command("retry", "Retry", "that failed, try a different approach"),
        Command("commit", "Commit", "commit this with a good message"),

        // --- skill / command examples: EDIT THESE to match your own agent ---
        Command("review", "Review", "/review"),
        Command("plan", "Plan", "/plan"),
        Command("tests", "Tests", "/test"),
        Command("handoff", "Handoff", "/handoff"),
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** The deck's fixed key count. The screen shows exactly this many slots. */
    const val SLOTS = 15

    /**
     * Force the list to exactly [SLOTS] entries, padding with empty commands.
     *
     * WHY the list is fixed rather than growable: these are not rows in a list,
     * they are POSITIONS ON A PHYSICAL DECK. Key 7 is key 7. If deleting a
     * command removed the slot, every key after it would shift and the labels
     * would no longer line up with the hardware the user is looking at. So a slot
     * is permanent and only its CONTENTS are optional.
     *
     * Slot identity is the index, which is also how deck keys are dispatched
     * (commands.getOrNull(key)). That makes the index the single source of truth
     * for both the screen and the device, so they cannot disagree.
     */
    private fun padToSlots(list: List<Command>): MutableList<Command> {
        val out = list.take(SLOTS).toMutableList()
        while (out.size < SLOTS) {
            out.add(Command(id = "slot${out.size}", label = "", text = ""))
        }
        return out
    }

    fun load(ctx: Context): MutableList<Command> {
        val f = file(ctx)
        if (!f.exists()) {
            val d = padToSlots(defaults())
            save(ctx, d)
            return d
        }
        return runCatching {
            val arr = JSONArray(f.readText())
            padToSlots(
                (0 until arr.length()).map { Command.fromJson(arr.getJSONObject(it)) }
            )
        }.getOrElse { padToSlots(defaults()) }
    }

    /** True when this slot holds nothing the user can send. */
    fun isEmpty(c: Command?): Boolean = c == null || c.text.isBlank()

    fun save(ctx: Context, commands: List<Command>) {
        val arr = JSONArray()
        padToSlots(commands).forEach { arr.put(it.toJson()) }
        file(ctx).writeText(arr.toString(2))
    }
}

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
     * Seed set: the deck is laid out as the engineering flow, read left to right,
     * top to bottom.
     *
     * The keys are not a menu of unrelated macros. They are the phases of building
     * a feature, in the order the phases happen, so the physical position of a key
     * tells you where you are. Row 1 aligns, row 2 builds and ships, row 3 reflects.
     * The middle three slots of row 3 are deliberately blank: a full grid implies
     * the flow has more steps than it does, and empty keys are where the next
     * command that earns a place will go.
     *
     * Alignment with Matt Pocock's skills repo (github.com/mattpocock/skills). The
     * order below matches ask-matt's own recommended flow, as does the phase
     * grouping -- ask-matt, grill-with-docs, to-spec and to-tickets are one
     * continuous context window, which is why they are the four keys of row 1
     * rather than spread across rows.
     *
     * The slash names are harness-specific: each one must match a skill the target
     * agent actually has installed, or the key just types a dead line. Edit them to
     * match your own agent.
     *
     * Entries are the PLAIN WORDS to send, never shell wrappers, so the same key
     * works whether the text lands in a chat box or a terminal.
     */
    private fun defaults(): List<Command> = listOf(
        // --- Row 1: align on what to build (one unbroken context window) ---
        Command("recommend", "Recommend", "what do you recommend?"),
        Command("approved", "Approved", "approved"),
        Command("explain", "Explain", "explain as simply and succinctly as possible"),
        Command("askmatt", "Ask Matt", "/ask-matt"),
        Command("grill", "Grill", "/grill-with-docs"),

        // --- Row 2: spec, ticket, build, review, ship ---
        Command("tospec", "To spec", "/to-spec"),
        Command("totickets", "To tickets", "/to-tickets"),
        Command("implement", "Implement", "/implement-spec"),
        Command("codereview", "Code review", "/code-review"),
        Command("pr", "PR", "/pr"),

        // --- Row 3: reflect, then three empty slots ---
        Command("retro", "Retro", "/retro"),
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

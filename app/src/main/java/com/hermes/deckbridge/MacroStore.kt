package com.hermes.deckbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Macro store. Each deck key maps to a target action.
 *
 * Persisted as JSON on internal storage so it survives rebuilds and can be
 * edited from the phone UI or pushed over adb for scripting.
 */
data class Macro(
    val keyIndex: Int,
    val label: String,
    val text: String,
    val target: Target = Target.AUTO,
    val pressEnter: Boolean = true,
) {
    enum class Target {
        /** Detect foreground app and choose the right injection method. */
        AUTO,

        /** Standard editable text field: ACTION_SET_TEXT then Enter. Works in
         *  Discord and Moshi. */
        TEXT_FIELD,

        /** Terminal emulator with a custom view (Termux): inject key events. */
        TERMINAL,
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", keyIndex)
        put("label", label)
        put("text", text)
        put("target", target.name)
        put("enter", pressEnter)
    }

    companion object {
        fun fromJson(o: JSONObject): Macro = Macro(
            keyIndex = o.optInt("key"),
            label = o.optString("label", ""),
            text = o.optString("text", ""),
            target = runCatching {
                Target.valueOf(o.optString("target", "AUTO"))
            }.getOrDefault(Target.AUTO),
            pressEnter = o.optBoolean("enter", true),
        )
    }
}

object MacroStore {

    private const val FILE_NAME = "macros.json"

    /**
     * Starter set.
     *
     * These are used for BOTH targets, and they have to work as a shell command
     * in Termux while still making sense as chat text in Discord/Moshi. Plain
     * `echo "..."` reads fine in a chat box AND is a valid shell command, so
     * one macro works in either app.
     */
    private fun defaults(): List<Macro> = listOf(
        Macro(0, "Recommend", "echo \"what do you recommend?\""),
        Macro(1, "Yes", "echo \"yes\""),
        Macro(2, "No", "echo \"no\""),
        Macro(3, "Tests", "echo \"run the tests and show me failures\""),
        Macro(4, "Explain", "echo \"explain what you just did in 3 bullets\""),
        Macro(5, "Status", "echo \"what is the current status?\""),
        Macro(6, "Commit", "echo \"commit this with a good message\""),
        Macro(7, "Fix errors", "echo \"fix the errors you just found\""),
        Macro(8, "Plan", "echo \"write a short plan before changing code\""),
        Macro(9, "Review", "echo \"review your own diff for bugs\""),
        Macro(10, "Stop", "echo \"stop and wait for me\""),
        Macro(11, "Retry", "echo \"that failed, try a different approach\""),
        Macro(12, "Simpler", "echo \"make this simpler\""),
        Macro(13, "Docs", "echo \"add docstrings and comments\""),
        Macro(14, "Ship", "echo \"ship it\""),
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context): MutableList<Macro> {
        val f = file(ctx)
        if (!f.exists()) {
            val d = defaults().toMutableList()
            save(ctx, d)
            return d
        }
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Macro.fromJson(arr.getJSONObject(it)) }
                .toMutableList()
                .also { list ->
                    // Backfill any missing keys so the grid is always complete.
                    val have = list.map { it.keyIndex }.toSet()
                    for (i in 0 until DeckProtocol.KEY_COUNT) {
                        if (i !in have) {
                            list.add(Macro(i, DeckProtocol.keyLabel(i), ""))
                        }
                    }
                    list.sortBy { it.keyIndex }
                }
        }.getOrElse { defaults().toMutableList() }
    }

    fun save(ctx: Context, macros: List<Macro>) {
        val arr = JSONArray()
        macros.sortedBy { it.keyIndex }.forEach { arr.put(it.toJson()) }
        file(ctx).writeText(arr.toString(2))
    }
}

package cn.enaium.battalion.game.ui

import cn.enaium.battalion.data.MapScriptConfig
import cn.enaium.battalion.data.parseArgb

/**
 * The mission briefing / dialogue system.
 *
 * Each map script contains a sequence of [cn.enaium.battalion.data.EventConfig]s, each with a
 * condition (e.g. `startGame`, `endGame`) and a list of `chat` responses. Each chat line has
 * a sender name, a message, an avatar id and a colour. This class collects those lines and
 * steps through them one at a time so the renderer can draw a dialogue box.
 *
 * The game shows the `startGame` brief when a mission begins and the `endGame` dialogue when
 * it ends; the player advances each line with a click / key press.
 */
class Dialogue(private val script: MapScriptConfig?) {

    /** One line of dialogue. */
    data class Line(
        val sender: String,
        val message: String,
        val avatar: String,
        val color: Int, // ARGB, e.g. 0xFF00FF00
    )

    private val events = mutableMapOf<String, MutableList<Line>>()

    private val queue = ArrayDeque<Line>()
    private var active = false

    init {
        parse()
    }

    private fun parse() {
        val script = script ?: return
        for (event in script.events) {
            val condition = event.conditions.firstOrNull()?.type ?: continue
            val lines = events.getOrPut(condition) { mutableListOf() }
            for (response in event.responses) {
                if (response.type != "chat") continue
                if (response.message.isNotEmpty()) {
                    lines.add(
                        Line(
                            sender = response.sender,
                            message = response.message,
                            avatar = response.avatar,
                            color = parseArgb(response.color),
                        )
                    )
                }
            }
        }
    }

    /** Whether a dialogue box is currently visible. */
    fun isActive(): Boolean = active

    /** The line currently shown, or null. */
    fun current(): Line? = queue.firstOrNull()

    /** Whether the current event still has more lines after the current one. */
    fun hasMore(): Boolean = queue.size > 1

    /**
     * Starts playing the dialogue for [condition] (e.g. "startGame"). Returns true if there is
     * anything to show, false otherwise.
     */
    fun play(condition: String): Boolean {
        val lines = events[condition] ?: return false
        queue.clear()
        queue.addAll(lines)
        active = queue.isNotEmpty()
        return active
    }

    /** Advances to the next line; dismisses the box after the last one. */
    fun advance() {
        if (queue.isNotEmpty()) queue.removeFirst()
        active = queue.isNotEmpty()
    }

    /** Dismisses the dialogue immediately. */
    fun dismiss() {
        queue.clear()
        active = false
    }
}

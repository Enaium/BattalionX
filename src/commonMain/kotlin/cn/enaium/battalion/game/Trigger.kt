package cn.enaium.battalion.game

/**
 * A lightweight event object. Gameplay events (a unit moved, a turn started, a unit was
 * killed...) are dispatched through the [TriggerManager] to any registered listeners.
 *
 * The base [Trigger] carries just a name; subclasses in the `triggers` package add payload
 * (which unit, which tile, which player, etc.).
 */
open class Trigger(val name: String) {
    companion object {
        const val ERROR = "error"
        const val START_GAME = "startGame"
        const val START_ROUND = "startRound"
        const val END_GAME = "endGame"
        const val CONTROL = "control"
    }
}

/** A listener binding: a [name] that triggers [callback]. */
class TriggerListener(val name: String, val callback: (Trigger) -> Unit)

/**
 * Dispatches [Trigger]s to registered listeners. Triggers are queued and flushed on [update].
 *
 * The original game uses this as its event bus — controllers and the AI subscribe to the
 * triggers they care about (start of turn, unit moved, unit killed...) and react when they
 * fire.
 */
object TriggerManager {
    private val triggers = mutableListOf<Trigger>()
    private val listeners = mutableListOf<TriggerListener>()

    fun add(trigger: Trigger) {
        triggers.add(trigger)
    }

    fun isEmpty(): Boolean = triggers.isEmpty()

    fun addListener(name: String, callback: (Trigger) -> Unit) {
        listeners.add(TriggerListener(name, callback))
    }

    fun removeListener(name: String, callback: (Trigger) -> Unit) {
        listeners.removeAll { it.name == name && it.callback == callback }
    }

    /** Drops every queued trigger and every listener. Used when a new game is created. */
    fun clearAll() {
        triggers.clear()
        listeners.clear()
    }

    /** Dispatches every queued trigger to the matching listeners. */
    fun update() {
        while (triggers.isNotEmpty()) {
            val trigger = triggers.removeAt(0)
            for (listener in listeners) {
                if (listener.name == trigger.name) {
                    listener.callback(trigger)
                }
            }
        }
    }
}
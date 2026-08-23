package cn.enaium.battalion.game

import cn.enaium.battalion.data.EventConfig

/**
 * A scripted event from the map `<script>` block: a set of [Condition]s that, once satisfied,
 * push a sequence of [Response]s onto the [ResponseManager].
 *
 * Ported from the original `com.gamedozer.battalion.game.GameEvent`. Events are parsed from XML
 * (by [ScriptManager]) and may repeat ([repeats] times, or [REPEAT_FOREVER]).
 */
class GameEvent(repeats: Int = REPEAT_ONCE) {

    /** The event repeats forever until some other script removes it. */
    companion object {
        const val REPEAT_FOREVER = -1
        const val REPEAT_ONCE = 1
    }

    private var repeats = repeats
    private var fired = false
    private var conditions = LogicalAndCondition()
    private val responses = mutableListOf<Response>()

    /**
     * The [EventConfig] this event was parsed from (set by [ScriptManager]).
     * Kept so a saved game can re-emit the event definition verbatim.
     */
    var source: EventConfig? = null

    /** Whether the event has finished all of its repetitions. */
    fun isCompleted(): Boolean = repeats == 0

    val isRepeatForever: Boolean get() = repeats == REPEAT_FOREVER

    fun getResponses(): List<Response> = responses

    fun addCondition(condition: Condition) = conditions.add(condition)

    fun addResponse(response: Response) = responses.add(response)

    /** Whether this event has already fired its responses once. */
    fun isFired(): Boolean = fired

    /**
     * The next not-yet-completed response, or null when all are done. Used by the
     * [ResponseManager] to step through the event's responses in order.
     */
    fun nextResponse(): Response? {
        for (r in responses) {
            if (!r.isCompleted()) return r
        }
        return null
    }

    /**
     * Advances the condition state. Once satisfied, this event is queued on the
     * [ResponseManager] (unless it is already firing).
     */
    fun update() {
        if (!isFired()) {
            conditions.update()
            if (conditions.isSatisfied()) {
                fired = true
                ResponseManager.add(this)
            }
        }
    }

    /** Prepares the event to fire again (decrements repeats and resets state). */
    fun reset() {
        fired = false
        if (repeats == REPEAT_FOREVER) return
        --repeats
        if (repeats == 0) return
        conditions.reset()
        for (r in responses) r.reset()
    }

    /** Unregisters every condition's listeners. */
    fun dispose() {
        conditions.dispose()
        for (r in responses) r.dispose()
    }
}

package cn.enaium.battalion.game

import cn.enaium.battalion.data.EventConfig

/**
 * Owns every scripted [GameEvent] loaded from the map's `<script>` block and drives them each
 * frame so their [Condition]s can be evaluated.
 *
 * Ported from the original `com.gamedozer.battalion.game.GameEventManager`.
 */
class GameEventManager {
    private val gameEvents = mutableListOf<GameEvent>()

    fun add(gameEvent: GameEvent) {
        gameEvents.add(gameEvent)
    }

    /**
     * Whether no event is currently mid-sequence (mirrors the original: isEmpty = no event has
     * fired yet). Fired events are removed once their responses drain, so a repeat-once script
     * that will never match (e.g. the other side's defeat speech) does not block the end game.
     */
    fun isEmpty(): Boolean = gameEvents.none { it.isFired() }

    /** Advances every event; removes completed (finished repeating) events. */
    fun update() {
        val iterator = gameEvents.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            e.update()
            if (e.isCompleted()) {
                e.dispose()
                iterator.remove()
            }
        }
    }

    /** Clears all events (used on game reset). */
    fun clear() {
        for (e in gameEvents) e.dispose()
        gameEvents.clear()
    }

    /**
     * Re-emits every live [GameEvent]'s original [EventConfig] (for a saved game).
     * Re-emitting the ORIGINAL definitions keeps the event set intact across a save/load;
     * already-fired one-shot events re-arm on resume.
     */
    fun toConfigs(): List<EventConfig> = gameEvents.mapNotNull { it.source }
}

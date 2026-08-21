package cn.enaium.battalion.game

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

    /** Whether every event has already fired at least once. */
    fun isEmpty(): Boolean = gameEvents.all { it.isFired() }

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
}

package cn.enaium.battalion.game

/**
 * A condition that gatekeeps whether a scripted [GameEvent] should fire.
 *
 * Each condition listens to game [Trigger]s (or polls variables) and exposes [isSatisfied].
 * This is a direct port of the original `com.gamedozer.battalion.game.Condition` interface.
 * Conditions are created from the map `<script>` XML via [ConditionFactory].
 */
interface Condition {
    /** Resets the condition so its parent [GameEvent] can repeat. */
    fun reset()

    /** Whether the condition currently holds. */
    fun isSatisfied(): Boolean

    /** Progresses tick-based conditions (e.g. time-passed). */
    fun update()

    /** Unregisters any trigger listeners. */
    fun dispose()
}

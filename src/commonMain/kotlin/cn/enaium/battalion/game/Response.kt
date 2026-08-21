package cn.enaium.battalion.game

/**
 * A scripted action fired when its parent [GameEvent]'s [Condition]s are satisfied.
 *
 * Ported from the original `com.gamedozer.battalion.game.Response`. Responses are created from
 * the map `<script>` XML via [ResponseFactory] and driven one at a time by the
 * [ResponseManager]. Most fire synchronously: [fire] performs the effect and calls
 * [completeResponse]. Display-heavy responses (chat windows, fades) stay incomplete until the
 * player advances them, mirroring the original.
 */
open class Response {
    private var fired = false
    private var completed = false

    /** Whether the response has been started. */
    fun isFired(): Boolean = fired

    /** Whether the response is fully done. */
    fun isCompleted(): Boolean = completed

    /** Called every frame while the response is incomplete (before [fire] or between). */
    open fun update() {
        completeResponse()
    }

    /** Resets the response so its [GameEvent] can repeat. */
    open fun reset() {
        fired = false
        completed = false
    }

    /** Marks the response as fired when the [ResponseManager] reaches it. */
    open fun fire() {
        fired = true
    }

    /** Marks the response as completely done. */
    fun completeResponse() {
        completed = true
    }

    open fun dispose() {}
}

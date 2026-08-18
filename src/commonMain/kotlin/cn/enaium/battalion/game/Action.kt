package cn.enaium.battalion.game

import cn.enaium.battalion.data.Unit

/**
 * A discrete game action, processed one at a time by the [ActionManager].
 *
 * This is the command pattern at the heart of the game: everything a player or the AI does —
 * move, attack, build, capture, end turn — is an [Action] pushed onto the queue. The manager
 * fires the action ([fire]) once, then calls [update] each frame until [completeAction] is
 * called, at which point the next action starts.
 *
 * In the original this extended the networked event class (Doppleganger) so actions could be
 * synchronized between clients; the port keeps the local-only lifecycle and drops networking.
 */
open class Action {
    var fired: Boolean = false
        private set
    private var completed: Boolean = false
    var isNetworked: Boolean = true
    var isFromNetwork: Boolean = false
    var stateCode: Long = 0

    /** Fires the action: performs its immediate effect. Subclasses override. */
    open fun fire() {
        fired = true
    }

    /** Called every frame while the action is executing (before it completes). */
    open fun update() {
        completeAction()
    }

    /** Marks the action as done so the manager can move on to the next one. */
    fun completeAction() {
        completed = true
    }

    fun isFired(): Boolean = fired

    fun isCompleted(): Boolean = completed

    /** Whether this action involves [aUnit] (used to block further actions on that unit). */
    open fun usesUnit(aUnit: Unit): Boolean = false

    open fun dispose() {}
}
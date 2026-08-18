package cn.enaium.battalion.game.actions

import cn.enaium.battalion.game.Action

/**
 * An action that executes a callback when fired. Used for injecting AI logic into the
 * action queue — the AI's turn starts by queuing a CallbackAction that runs its planning.
 */
class CallbackAction(
    private val callback: () -> Unit,
    private val toStringData: String = "",
) : Action() {
    override fun fire() {
        super.fire()
        callback()
    }

    override fun toString(): String = "[CallbackAction <$toStringData>]"
}
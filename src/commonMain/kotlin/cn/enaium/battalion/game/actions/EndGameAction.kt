package cn.enaium.battalion.game.actions

import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.PlayerTrigger

/**
 * Ends the game. Dispatches an end-of-game trigger to every active controller, then waits for
 * all pending triggers to drain before finalizing the game state.
 */
class EndGameAction : Action() {
    override fun fire() {
        super.fire()
        // Notify every active controller that the game is over.
        for (controller in GameManager.instance.controllers) {
            if (controller != null && controller.team.active) {
                TriggerManager.add(
                    PlayerTrigger(PlayerTrigger.END_GAME, GameManager.instance.getPlayer(controller))
                )
            }
        }
    }

    override fun update() {
        // Finalize only once all pending triggers and actions have drained.
        if (TriggerManager.isEmpty() && ActionManager.isEmpty()) {
            GameManager.instance.endGame()
            super.update()
        }
    }
}
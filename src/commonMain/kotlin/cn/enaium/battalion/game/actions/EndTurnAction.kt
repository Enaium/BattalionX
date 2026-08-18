package cn.enaium.battalion.game.actions

import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.data.MoraleChangeAmounts

/**
 * Ends the current player's turn and advances to the next player.
 *
 * This action handles:
 * - Applying morale penalties (idle delay, lack of attack)
 * - Clearing temporary modifiers and handling cloaking for the player's units
 * - Advancing the turn/round/controller counters (ending the game if rounds are exhausted)
 * - Queuing the next [StartTurnAction]
 */
class EndTurnAction(private val reason: Int) : Action() {
    companion object {
        const val REASON_VOLUNTARY = 0
        const val REASON_SURRENDER = 1
        const val REASON_IDLE = 2
        const val REASON_NO_UNITS = 3
        const val REASON_DEBUG_ENDGAME = 4
    }

    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val controller = gameManager.activeController ?: return
        controller.endTurnCleanup()

        // ---- Morale penalties -------------------------------------------------
        if (gameManager.isMoraleEnabled) {
            if (reason == REASON_IDLE) {
                gameManager.changeMorale(
                    controller,
                    MoraleChangeAmounts.DELAY_OF_GAME,
                    MoraleChangeAmounts.DELAY_OF_GAME_DESCRIPTION
                )
            }

            val turnsBeforeNotAttackingCausesMoraleLose = 2
            if (gameManager.round >= turnsBeforeNotAttackingCausesMoraleLose) {
                val turnsWeCanNotAttackBeforeWeGetAPenalty = 2
                if (controller.turnsSincePlayerAttacked > turnsWeCanNotAttackBeforeWeGetAPenalty) {
                    val moraleLostPerTurn =
                        (controller.turnsSincePlayerAttacked - turnsWeCanNotAttackBeforeWeGetAPenalty) *
                            MoraleChangeAmounts.PER_TURN_LACK_OF_ATTACK_PENALTY
                    gameManager.changeMorale(
                        controller,
                        moraleLostPerTurn,
                        MoraleChangeAmounts.PER_TURN_LACK_OF_ATTACK_PENALTY_DESCRIPTION
                    )
                }
            } else {
                controller.resetTurnsSinceLastAttackCounter()
            }
        }

        // ---- Reset unit temporary state --------------------------------------
        val units = controller.units.toList()
        for (unit in units) {
            unit.unitData.clearTemporaryModifiers()
            // Cloaking units re-cloak at the start of the next turn.
            if (unit.isDeployed() && unit.unitData.hasModifier("Cloaking") && unit.isAvailable()) {
                unit.setCloaked(true, false, false)
            }
        }

        // ---- Advance to the next player --------------------------------------
        do {
            gameManager.turn = gameManager.turn + 1
            gameManager.currentController = gameManager.currentController + 1
            if (gameManager.currentController > gameManager.controllers.size - 1) {
                gameManager.round = gameManager.round + 1
                gameManager.currentController = 0
            }
            if (gameManager.round > gameManager.mapSettings.rounds) {
                ActionManager.add(EndGameAction())
            }
        } while (!gameManager.activeController?.team?.active!!)

        // Queue the start of the next player's turn.
        val start = StartTurnAction()
        start.isNetworked = false
        ActionManager.add(start, toFront = true)
    }
}
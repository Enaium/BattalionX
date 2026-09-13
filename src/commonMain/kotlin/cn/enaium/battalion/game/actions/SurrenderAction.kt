package cn.enaium.battalion.game.actions

import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.ExplosionTrigger
import cn.enaium.battalion.game.triggers.PlayerTrigger

/**
 * Surrenders a player from the game. All their units are destroyed and their structures are
 * released (or removed if Mission Critical). If the surrendering player was the active player,
 * their turn is ended first.
 */
class SurrenderAction(private val player: Int) : Action() {
    override fun update() {
        // No-op in the original
    }

    override fun fire() {
        super.fire()
        val controller = GameManager.instance.getController(player)

        // A null or inactive controller surrenders immediately without ceremony.
        if (controller == null || !controller.team.active) {
            fireImplied()
            completeAction()
            return
        }

        // In the original this waited for a morale animation when demoralized; the port
        // destroys the units directly.
        destroyAllUnits(player)

        // This action is fully synchronous: mark it done so the manager can move on.
        completeAction()
    }

    private fun destroyAllUnits(player: Int) {
        val gameManager = GameManager.instance
        val controller = gameManager.getController(player)
        if (controller == null) return

        // If the surrendering player is the active player, end their turn first.
        if (gameManager.currentController == player) {
            val endTurnAction = EndTurnAction(EndTurnAction.REASON_SURRENDER)
            endTurnAction.isNetworked = false
            ActionManager.add(endTurnAction, toFront = true)
        }

        fireImplied()
    }

    private fun fireImplied() {
        val gameManager = GameManager.instance
        gameManager.surrender(player)

        val controller = gameManager.getController(player)
        if (controller == null) return

        controller.surrender()
        gameManager.addControllerToSurrenderedList(controller)

        // Blast then destroy all the player's units (SWF destroyAllUnits: addExplosion on every
        // unit before removing it, so a surrender is not a silent disappearance).
        val units = controller.units.toList()
        for (unit in units.reversed()) {
            TriggerManager.add(ExplosionTrigger(unit.x, unit.y, big = true))
            gameManager.removeUnit(unit)
        }

        // Release the player's structures, with the same explosion at each one.
        val structures = controller.structures.toList()
        for (structure in structures.reversed()) {
            TriggerManager.add(ExplosionTrigger(structure.x, structure.y, big = true))
            if (structure.terrain.hasModifier("Mission Critical")) {
                gameManager.removeStructure(structure)
            } else {
                structure.controller = null
            }
        }

        TriggerManager.add(PlayerTrigger(PlayerTrigger.SURRENDER, player))
    }
}
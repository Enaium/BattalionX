package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitData
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.BuildUnitTrigger
import cn.enaium.battalion.data.MoraleChangeAmounts

/**
 * Builds a unit on a factory structure owned by the active controller.
 *
 * Validates that the tile is a construction structure, the controller can afford the unit,
 * the unit type is supported by the controller's control structures, and the tile is empty.
 * On success the unit is created, marked as moved/fired (so it cannot act this turn), and the
 * controller's resources are spent.
 */
class BuildUnitAction(
    private val tileX: Int,
    private val tileY: Int,
    private val unitType: String
) : Action() {
    override fun update() {
        // No-op in the original
    }

    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val tile = gameManager.tileAt(tileX, tileY)
        val unitData = UnitManager.get(unitType)

        if (tile == null) {
            updateAndThrowError("No tile at $tileX,$tileY")
            return
        }
        if (unitData == null) {
            updateAndThrowError("No such unit $unitType")
            return
        }

        val controller = tile.controller
        if (controller == null) {
            updateAndThrowError("Structure has no controller")
            return
        }
        if (controller != gameManager.activeController) {
            updateAndThrowError("Only the active controller may build")
            return
        }
        if (controller.getResources() < unitData.getCost(controller)) {
            updateAndThrowError("Controller cannot afford unit")
            return
        }

        // Verify the controller has the required control structures for this unit type
        val ground = controller.controlsGround()
        val sea = controller.controlsSea()
        val air = controller.controlsAir()
        var noBuild = false

        if (unitData.isAir()) {
            if (!air) noBuild = true
        } else if (unitData.isGround()) {
            if (!ground) noBuild = true
        } else if (unitData.isSea()) {
            if (!tile.couldUnitMoveAwayFromThisTile(unitData.name) || !sea) noBuild = true
        } else {
            noBuild = true
        }

        if (unitData.hasModifier("Unbuildable")) noBuild = true

        if (noBuild) {
            updateAndThrowError("Cannot build unit, non supported type: $unitType")
        }

        if (tile.unit != null) {
            updateAndThrowError("Build cannot complete (Unit is on structure)!")
        }

        if (tile.terrain.hasModifier("Construction")) {
            gameManager.activeController?.resetIdleTime()
            fireImplied()
        } else {
            updateAndThrowError("Build cannot complete (Structure cannot build)!")
        }
    }

    private fun fireImplied() {
        val gameManager = GameManager.instance
        val tile = gameManager.tileAt(tileX, tileY)
        val controller = tile?.controller
        val unitData = UnitManager.get(unitType)

        if (tile == null || controller == null || unitData == null) {
            completeAction()
            return
        }

        // Create the new unit
        val newUnit = Unit(unitData, tileX, tileY, controller)
        newUnit.deployed = true
        newUnit.moved = true
        newUnit.fired = true

        // Register the unit with the game and place it on the tile
        gameManager.addUnit(controller, newUnit)
        tile.unit = newUnit
        newUnit.darken()

        // Spend resources and update statistics
        controller.spendResources(unitData.getCost(controller))
        controller.team.addStatistic(Team.RESOURCES_SPENT, unitData.getCost(controller))
        gameManager.changeMorale(controller, MoraleChangeAmounts.UNITS_BUILT, MoraleChangeAmounts.UNITS_BUILT_DESCRIPTION)
        controller.team.addStatistic(Team.UNITS_BUILT, 1)

        when {
            newUnit.unitData.isAir() -> controller.team.addStatistic(Team.AIR_UNITS_BUILT, 1)
            newUnit.unitData.isGround() -> controller.team.addStatistic(Team.GROUND_UNITS_BUILT, 1)
            else -> controller.team.addStatistic(Team.SEA_UNITS_BUILT, 1)
        }

        // Expose stealth units around the new unit
        gameManager.exposeStealthUnitsAround(newUnit)

        // Dispatch build trigger
        TriggerManager.add(BuildUnitTrigger(newUnit))

        gameManager.activeController?.resetIdleTime()
    }

    private fun updateAndThrowError(message: String) {
        completeAction()
        throw Error("Corrupt BuildUnitAction $message")
    }
}
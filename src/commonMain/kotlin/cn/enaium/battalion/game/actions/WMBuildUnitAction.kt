package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.BuildUnitTrigger
import cn.enaium.battalion.data.MoraleChangeAmounts

/**
 * Builds a unit from a Warmachine constructor's resource pool onto a destination tile.
 *
 * Unlike [BuildUnitAction] (which uses the controller's global resources), a Warmachine carries
 * its own resource pool accumulated from extracted ore and scavenged units. This action validates
 * the destination tile is reachable and the constructor can afford the unit, then places the new
 * unit and spends the constructor's pool.
 */
class WMBuildUnitAction(
    private val controllerIndex: Int,
    private val destinationTileX: Int,
    private val destinationTileY: Int,
    private val unitName: String,
    private val builderTileX: Int,
    private val builderTileY: Int
) : Action() {
    private fun err(vararg args: Any): Nothing {
        completeAction()
        throw Error("WMBuildUnitAction Error:" + args.joinToString(" "))
    }

    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val controller = gameManager.getController(controllerIndex)

        if (controller == null) {
            err("Unknown controller number:", controllerIndex)
        }
        if (controller != gameManager.activeController) {
            err("Only the active controller may build")
        }

        val destinationTile = gameManager.tileAt(destinationTileX, destinationTileY)
        if (destinationTile == null) {
            err("Can't retrieve tile at [", destinationTileX, ",", destinationTileY, "]")
        }
        val occupyingUnit = destinationTile.unit
        if (occupyingUnit != null) {
            err("Cannot build on", destinationTile, "because", occupyingUnit, "is in the way")
        }

        val unitData = UnitManager.get(unitName)
        if (unitData == null) {
            err("Unknown unit data:", unitName)
        }

        val builder = gameManager.unitAt(builderTileX, builderTileY)
        if (builder == null) {
            err("Can't find a builder at: ($builderTileX, $builderTileY)")
        }
        if (!builder.baseUnitData.hasModifier("Constructor")) {
            err("Unit", builder, "is not a Constructor")
        }
        if (builder.hasMoved()) {
            err(builder, "has already moved")
        }
        if (builder.resourcePool < unitData.getCost(controller)) {
            err(unitName, "costs", unitData.getCost(controller), "but", builder, "only has $", builder.resourcePool)
        }

        val builderTile = builder.getTile()
        if (builderTile == null) {
            err(builder, "does not have a tile")
        }

        fireImplied()
    }

    private fun fireImplied() {
        val gameManager = GameManager.instance
        val controller = gameManager.getController(controllerIndex) ?: return
        val destinationTile = gameManager.tileAt(destinationTileX, destinationTileY) ?: return
        val unitData = UnitManager.get(unitName) ?: return

        // Create the new unit
        val newUnit = Unit(unitData, destinationTileX, destinationTileY, controller)

        // Validate the destination tile is passable for the new unit
        if (destinationTile.getCost(newUnit, 0, true) > newUnit.getRange()) {
            err(newUnit, "cannot be placed on", destinationTile)
        }

        newUnit.deployed = true
        newUnit.moved = true
        newUnit.fired = true

        gameManager.addUnit(controller, newUnit)
        destinationTile.unit = newUnit
        newUnit.darken()

        // Statistics
        controller.team.addStatistic(Team.UNITS_BUILT, 1)
        when {
            newUnit.unitData.isAir() -> controller.team.addStatistic(Team.AIR_UNITS_BUILT, 1)
            newUnit.unitData.isGround() -> controller.team.addStatistic(Team.GROUND_UNITS_BUILT, 1)
            else -> controller.team.addStatistic(Team.SEA_UNITS_BUILT, 1)
        }

        controller.setMovedAUnitThisTurn(true)
        gameManager.changeMorale(controller, MoraleChangeAmounts.UNITS_BUILT, MoraleChangeAmounts.UNITS_BUILT_DESCRIPTION)
        gameManager.exposeStealthUnitsAround(newUnit)
        TriggerManager.add(BuildUnitTrigger(newUnit))

        // Spend the constructor's resource pool
        val builder = gameManager.unitAt(builderTileX, builderTileY)
        if (builder != null) {
            builder.moved = true
            builder.darken()
            builder.spendResourcesFromPool(unitData.getCost(controller))
        }

        controller.team.addStatistic(Team.RESOURCES_SPENT, unitData.getCost(controller))
        gameManager.activeController?.resetIdleTime()
    }

    override fun update() {
        // No-op in the original
    }
}
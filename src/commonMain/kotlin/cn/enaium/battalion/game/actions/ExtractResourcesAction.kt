package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.ExtractResourcesTrigger
import cn.enaium.battalion.data.MoraleChangeAmounts

/**
 * Extracts resources from an ore deposit tile using a unit with the "Extractor" modifier.
 *
 * Grants the unit's resource pool 500 resources, marks the unit as moved, and downgrades the
 * ore deposit (Enriched -> Ore -> Depleted) by converting the terrain.
 */
class ExtractResourcesAction(
    private val unitX: Int,
    private val unitY: Int
) : Action() {
    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)

        if (unit == null) {
            throw Error("ExtractResourcesAction::Corrupt extract action")
        }
        if (unit.controller != gameManager.activeController) {
            throw Error("ExtractResourcesAction::Only the units controller may extract")
        }
        if (!unit.unitData.hasModifier("Extractor")) {
            throw Error("ExtractResourcesAction::Only units with the Extractor modifier can extract resources")
        }

        val tile = unit.getTile()
        if (tile == null) {
            throw Error("ExtractResourcesAction::Unit does not have a tile. WTF?")
        }
        if (!isResourceTile(tile)) {
            throw Error("ExtractResourcesAction::Tile does not contain resources")
        }

        if (!unit.hasMoved() && !unit.hasFired() || !unit.isDeployed()) {
            gameManager.changeMorale(unit.controller, MoraleChangeAmounts.EXTRACTED_RESOURCES, MoraleChangeAmounts.EXTRACTED_RESOURCES_DESCRIPTION)
            val resourceAmount = 500
            unit.controller?.team?.addStatistic(Team.ORE_EXTRACTED, resourceAmount)
            unit.gainResources(resourceAmount)
            unit.moved = true
            unit.darken()

            // Downgrade the ore deposit tile
            downgradeTile(gameManager.tileAt(unit.x, unit.y))

            gameManager.activeController?.resetIdleTime()
            TriggerManager.add(ExtractResourcesTrigger(unit))
            return
        }

        throw Error("ExtractResourcesAction::Unit cannot complete extract action. (Already moved)")
    }

    override fun usesUnit(aUnit: Unit): Boolean {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)
        return unit == aUnit
    }

    private fun isResourceTile(tile: Tile): Boolean {
        val name = tile.terrain.name
        return name == "Enriched Ore Deposit" || name == "Ore Deposit"
    }

    private fun downgradeTile(tile: Tile?) {
        if (tile == null) return
        when (tile.terrain.name) {
            "Enriched Ore Deposit" -> downgradeTo(tile, "Ore Deposit")
            "Ore Deposit" -> downgradeTo(tile, "Depleted Ore Deposit")
        }
    }

    private fun downgradeTo(tile: Tile, newTileName: String) {
        val convert = ConvertTerrainAction(tile.x, tile.y, newTileName)
        convert.isNetworked = false
        ActionManager.add(convert, toFront = true)
    }
}
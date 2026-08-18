package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.UpgradeUnitTrigger

/**
 * Loads ([upgrade] true) or unloads ([upgrade] false) a unit into its current transport.
 *
 * A unit with the "Transport" modifier (an Albatross Transport or Leviathan Barge) picks up an
 * adjacent friendly ground/short unit, upgrading that unit to ride inside (its effective
 * [Unit.sprite] / [Unit.unitData] swap to the transport's). Unloading restores the passenger.
 *
 * Cost model matches the original: on classic maps loading a unit costs the passenger unit's
 * full cost in resources; non-classic maps charge no resources for loading.
 */
class UpgradeUnitAction(
    private val unitX: Int,
    private val unitY: Int,
    private val upgrade: Boolean,
    private val unitType: String = "",
) : Action() {

    override fun usesUnit(aUnit: Unit): Boolean {
        val unit = GameManager.instance.unitAt(unitX, unitY)
        return unit == aUnit
    }

    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)

        if (unit == null) {
            throw Error("UpgradeUnitAction::Corrupt upgrade action")
        }
        if (unit.controller != gameManager.activeController) {
            throw Error("UpgradeUnitAction::Only the units controller may upgrade the unit")
        }
        if (unit.hasMoved() || unit.hasFired() || !unit.isDeployed()) {
            throw Error("UpgradeUnitAction::Unit cannot complete upgrade action. (Already moved | Fired)")
        }

        unit.setCloaked(false, false, false)

        if (!upgrade) {
            // Unload: only if the transport is stationed on passable terrain for the passenger.
            val tile = gameManager.tileAt(unit.x, unit.y)
            val passengerData = unit.baseUnitData
            if (tile != null && tile.canBeMovedOnBy(Unit(UnitManager.get(passengerData.name) ?: passengerData))) {
                unit.downgrade()
            }
        } else {
            // Load: swap the unit's template to the transport's.
            val newUnit = UnitManager.get(unitType)
            if (newUnit == null) {
                throw Error("UpgradeUnitAction::Unknown unit type: $unitType")
            }
            if (gameManager.map.isClassic) {
                val bUD = unit.baseUnitData
                if (unit.controller?.getResources() ?: 0 < bUD.getCost(unit.controller)) {
                    throw Error("UpgradeUnitAction::Controller cannot afford unit")
                }
                unit.controller?.spendResources(bUD.getCost(unit.controller))
            }
            unit.upgrade(newUnit)
        }

        // The upgrade/downgrade is the unit's action for this turn.
        unit.moved = true
        unit.fired = true
        unit.darken()

        fireImplied(unit)
        gameManager.activeController?.resetIdleTime()
    }

    /** Fires the upgrade trigger and settles the action synchronously. */
    private fun fireImplied(unit: Unit) {
        // The upgrade trigger carries a display name (empty for a downgrade).
        val name = if (upgrade) unitType else ""
        TriggerManager.add(UpgradeUnitTrigger(unit, name, upgrade))
        completeAction()
    }

    override fun update() {
        completeAction()
    }
}

package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.MoraleChangeAmounts
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.RepairUnitTrigger

/**
 * Repairs a damaged friendly unit.
 *
 * The unit must still be able to act this turn (not moved / fired). Repairing marks the unit
 * as spent (`moved = fired = true`) and starts a multi-turn heal: each subsequent
 * [StartTurnAction] applies a heal worth up to a quarter of the unit's health (see
 * [GameManager.healPercentage]) until it is back to full.
 *
 * Cost model matches the original:
 *  - Classic maps: the repair costs resources equal to the heal fraction of the unit's cost.
 *  - Non-classic maps: repairing costs a troop-morale penalty instead of resources.
 */
class RepairUnitAction(
    private val unitX: Int,
    private val unitY: Int,
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
            throw Error("RepairUnitAction::Corrupt repair action")
        }
        if (unit.controller != gameManager.activeController) {
            throw Error("RepairUnitAction::Only the units controller may repair the unit")
        }
        if (unit.unitData.hasModifier("Irreparable")) {
            throw Error("RepairUnitAction::Irreparable units can't repair.")
        }
        // A unit that has already acted this turn (or is not deployed) cannot start a repair.
        if (unit.hasMoved() || unit.hasFired() || !unit.isDeployed()) {
            throw Error("RepairUnitAction::Unit cannot complete repair action. (Already moved)")
        }

        if (unit.getHealth() < unit.unitData.health) {
            // Repair heals up to a quarter of the unit's max health, to be applied over turns.
            val healPerc = gameManager.healPercentage(unit)
            if (gameManager.map.isClassic) {
                // Classic maps pay in resources.
                val cost = (unit.unitData.getCost(unit.controller) * healPerc).toInt()
                if (unit.controller?.getResources() ?: 0 < cost) {
                    throw Error("RepairUnitAction::Units controller cannot afford the repair.")
                }
                unit.controller?.spendResources(cost)
            } else {
                // Non-classic maps pay in morale.
                val moraleRepairCost =
                    unit.unitData.getCost(unit.controller) * healPerc * MoraleChangeAmounts.REPAIR_UNIT
                gameManager.changeMorale(
                    unit.controller,
                    moraleRepairCost,
                    MoraleChangeAmounts.REPAIR_UNIT_DESCRIPTION,
                )
            }
            unit.repairing = true
            // Repairing is the unit's action for the turn — but only when it was actually
            // damaged. The original sets moved/fired/darken inside the health<max branch, so
            // repairing an undamaged unit consumes nothing.
            unit.moved = true
            unit.fired = true
            unit.darken()
        }

        TriggerManager.add(RepairUnitTrigger(unit))
        gameManager.activeController?.resetIdleTime()
    }

    override fun update() {
        completeAction()
    }
}

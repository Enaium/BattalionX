package cn.enaium.battalion.game

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.GameManager

/**
 * Turn-count query helpers for movement and attack planning.
 *
 * Ported from the original `com.gamedozer.battalion.game.DistanceQueries`. These estimate how
 * many turns a unit needs to reach a tile or to get into attack range of a target, accounting
 * for the [MovementCombiner] (multi-turn / amphibious routes).
 */
object DistanceQueries {

    /** Estimates the turns for [aUnit] to walk from [aStart] to [aTarget], via the combiner. */
    fun calculateTurnsToReach(aStart: Tile, aTarget: Tile, aUnit: Unit, aFlags: Int = 0): Int {
        if (aStart === aTarget) return 1
        val game = GameManager.instance
        val interim = game.movementCombiner.getInterimDestination(aStart, aTarget, aUnit, aFlags)
        if (interim != null) {
            return game.movementCombiner.getTurnCount()
        }
        return -1
    }

    /**
     * A cheap heuristic turns-until-in-attack-range estimate, used by the AI to prioritise
     * targets. It places the unit adjacent / in range based on Manhattan distance, falling back
     * to exact pathing for the common single-turn case.
     */
    fun guessTurnsToBeingInAttackRange(aStart: Tile, aTarget: Tile, aUnit: Unit): Int {
        val cellsSeparating = kotlin.math.abs(aStart.x - aTarget.x) + kotlin.math.abs(aStart.y - aTarget.y)
        return if (!aUnit.isRanged()) {
            var turns = kotlin.math.ceil(cellsSeparating / aUnit.getRange()).toInt()
            if (turns == 1) {
                turns = calculateTurnsToBeingInAttackRange(aStart, aTarget, aUnit)
            }
            turns
        } else if (cellsSeparating >= aUnit.getWeaponRangeMin() && cellsSeparating <= aUnit.getWeaponRangeMax()) {
            1
        } else if (aUnit.canMove()) {
            kotlin.math.ceil((cellsSeparating - aUnit.getWeaponRangeMax()) / aUnit.getRange()).toInt() + 1
        } else {
            -1
        }
    }

    /**
     * The exact minimum turns to get [aUnit] into weapon range of [aTarget], or -1 if it cannot.
     */
    fun calculateTurnsToBeingInAttackRange(aStart: Tile, aTarget: Tile, aUnit: Unit): Int {
        val game = GameManager.instance
        val nodesInRange = if (aUnit.isRanged()) {
            Astar.range(aTarget, aUnit.getWeaponRangeMin().toDouble(), aUnit.getWeaponRangeMax().toDouble())
        } else {
            aTarget.connections
        }
        var bestTurnCount = Int.MAX_VALUE
        var bestDestination: Tile? = null
        for (node in nodesInRange) {
            val tile = node as? Tile ?: continue
            if (tile === aStart) return 1
            val occupant = tile.unit
            if (occupant != null && !occupant.isCloaked()) continue
            // A non-empty last tile is fine if it is the unit itself, but an enemy block is not.
            if (occupant != null && occupant !== aUnit) continue
            if (tile.getCost(aUnit, 0, true) > aUnit.getRange()) continue
            val turns = calculateTurnsToReach(aStart, tile, aUnit, Tile.FLAG_IGNORE_LAST_NODE)
            if (turns > 0 && turns < bestTurnCount) {
                bestDestination = tile
                bestTurnCount = turns
            }
        }
        return if (bestDestination != null) bestTurnCount else -1
    }
}

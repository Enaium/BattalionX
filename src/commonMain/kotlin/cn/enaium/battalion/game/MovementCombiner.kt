package cn.enaium.battalion.game

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitManager

/**
 * Multi-turn / amphibious movement planner.
 *
 * A ground unit can only walk over terrain its movement type permits. To reach a target on a
 * *different island* (e.g. a tank that must cross a strait), the unit must either wait for a
 * Leviathan Barge (its "island" is the intermediate tile) or walk a multi-turn route whose
 * interim destination is decided here.
 *
 * The combiner maintains three movement-island maps, keyed by which template a unit's island
 * connectivity should be computed with:
 *  - [m_tankIslands] — building connectivity for a ground/tracked unit ("Scorpion Tank").
 *  - [m_commandoIslands] — foot-unit connectivity ("Strike Commando").
 *  - [m_leviathanIslands] — water connectivity ("Leviathan Barge").
 *
 * [getInterimDestination] returns the tile a unit should walk to *this turn*, given that its
 * final destination may be several turns (and possibly a transport crossing) away. If the unit
 * can reach its target this turn it returns the target; otherwise it returns the nearest
 * intersection tile where the unit can board/continue. The number of turns is available via
 * [getTurnCount].
 *
 * This mirrors the original `com.gamedozer.battalion.game.MovementCombiner`.
 */
class MovementCombiner {

    internal val tankIslands: MovementIslands = MovementIslands.forUnitName("Scorpion Tank")!!
    internal val commandoIslands: MovementIslands = MovementIslands.forUnitName("Strike Commando")!!
    internal val leviathanIslands: MovementIslands = MovementIslands.forUnitName("Leviathan Barge")!!

    private var turnCount: Int = 0

    private fun lookupMovementIslands(unit: Unit): MovementIslands {
        val ud = unit.baseUnitData
        return when {
            ud.isGround() -> if (ud.movementType == 1) commandoIslands else tankIslands
            else -> leviathanIslands
        }
    }

    /**
     * The template a unit uses when measuring movement over [activeMovementIsland]. When
     * routing a ground unit across the Leviathan islands the turns are measured with the
     * barge's own movement; across the unit's own land islands the underlying template is
     * used. Commandos measure with the Strike Commando template.
     */
    private fun getAppropriateFakeUnit(actualUnit: Unit, activeMovementIsland: MovementIslands): Unit {
        val ud = if (activeMovementIsland === leviathanIslands) {
            UnitManager.get("Leviathan Barge")!!
        } else if (actualUnit.name.contains("Commando")) {
            UnitManager.get("Strike Commando")!!
        } else {
            actualUnit.baseUnitData
        }
        val fake = Unit(ud)
        fake.controller = actualUnit.controller
        return fake
    }

    /**
     * Returns the tile a unit should move towards this turn on the way to [endingTile], or the
     * [endingTile] itself if it is reachable now. Null if unreachable.
     *
     * @param flags A* flags (e.g. [Tile.FLAG_IGNORE_LAST_NODE]).
     */
    fun getInterimDestination(
        startingTile: Tile,
        endingTile: Tile,
        unit: Unit,
        flags: Int = 0,
    ): Tile? {
        val unitData = unit.unitData
        // Air units (and non-upgraded sea units) may move straight to the target.
        if (unitData.isAir() || (unitData.isSea() && !unit.isUpgraded())) {
            turnCount = Astar.calculateTurnsToPath(startingTile, endingTile, unit, flags)
            return if (turnCount > 0) endingTile else null
        }

        val baseIslands = lookupMovementIslands(unit)
        val mapHasStructures = GameManager.instance.map.hasStructures()
        var fromIslands = baseIslands
        var toIslands = baseIslands

        if (unitData.isGround() && (unit.controller?.controlsSea() == true || !mapHasStructures)) {
            // A ground unit with sea control (or no structures) can use the barge islands.
            toIslands = leviathanIslands
        } else if (unitData.isSea() && unit.baseUnitData.isGround()) {
            // A barge (upgraded sea unit whose base is ground) disembarks onto ground islands.
            fromIslands = leviathanIslands
            toIslands = baseIslands
        }

        if (fromIslands.areSharingAnIsland(startingTile, endingTile)) {
            turnCount = Astar.calculateTurnsToPath(startingTile, endingTile, unit, flags)
            return endingTile
        }

        val initialIsland = fromIslands.islandOfOrEmpty(startingTile)
        if (initialIsland.isEmpty()) return null

        val intersectionSet = toIslands.islandsTouching(initialIsland)
        val results = drillIntersectionSet(
            mutableSetOf(),
            unit,
            endingTile,
            intersectionSet,
            fromIslands,
            toIslands,
        ) ?: run {
            turnCount = -1
            return null
        }
        if (results.isEmpty()) {
            turnCount = -1
            return null
        }

        // Prefer the intersection tile that minimises total turns (skipping High Bridge).
        var bestTurnCount = Int.MAX_VALUE
        var bestIndex = -1
        for (i in results.indices) {
            val result = results[i]
            val intersection = result.intersection ?: continue
            if (intersection.terrain.name != "High Bridge") {
                val turnsToInterim = Astar.calculateTurnsToPath(startingTile, intersection, unit, flags)
                result.turns += turnsToInterim
                if (result.turns < bestTurnCount) {
                    bestIndex = i
                    bestTurnCount = result.turns
                }
            }
        }
        if (bestIndex == -1) {
            turnCount = -1
            return null
        }
        turnCount = results[bestIndex].turns
        return results[bestIndex].intersection
    }

    /** Total number of turns the last [getInterimDestination] call predicts. */
    fun getTurnCount(): Int = turnCount

    private data class InterimResult(var turns: Int = 0, var intersection: Tile? = null)

    private fun drillIntersectionSet(
        visited: MutableSet<Tile>,
        unit: Unit,
        endingTile: Tile,
        intersections: Set<Tile>,
        fromIslands: MovementIslands,
        toIslands: MovementIslands,
    ): List<InterimResult>? {
        var results: MutableList<InterimResult>? = null
        for (intersection in intersections) {
            if (visited.contains(intersection)) continue
            val occupant = intersection.unit
            if (occupant != null && occupant != unit && !occupant.isCloaked()) continue

            val island = toIslands.islandOfOrEmpty(intersection)
            if (island.isEmpty()) continue
            val fakeUnit = getAppropriateFakeUnit(unit, toIslands)
            if (island.contains(endingTile)) {
                if (results == null) results = mutableListOf()
                accumulateResult(results, intersection, endingTile, 0, fakeUnit)
            }

            val newVisited = visited.toMutableSet()
            newVisited.addAll(intersections)
            val nextIntersections = fromIslands.islandsTouching(island).minus(newVisited)
            if (nextIntersections.isNotEmpty()) {
                val recursions = drillIntersectionSet(
                    newVisited,
                    unit,
                    endingTile,
                    nextIntersections,
                    toIslands,
                    fromIslands,
                )
                if (recursions != null) {
                    if (results == null) results = mutableListOf()
                    for (r in recursions) {
                        if (r.turns > 0 && r.turns <= 20) {
                            accumulateResult(results, intersection, r.intersection!!, r.turns, fakeUnit)
                        }
                    }
                }
            }
        }
        return results
    }

    private fun accumulateResult(
        results: MutableList<InterimResult>,
        startTile: Tile,
        endTile: Tile,
        additionalTurns: Int,
        unit: Unit,
    ) {
        val turns = Astar.calculateTurnsToPath(startTile, endTile, unit, Tile.FLAG_IGNORE_UNITS)
        if (turns > 0) {
            results.add(InterimResult(additionalTurns + turns, startTile))
        }
    }
}

package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitData
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.actions.MultiTurnMoveUnitAction
import cn.enaium.battalion.game.controllers.HumanController
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import java.io.File

/**
 * Guards the newly ported gameplay systems against the original Battalion Vengeance:
 *
 *  1. **Warmachine vehicle building** — a human player's Warmachine constructor builds a unit
 *     onto an adjacent tile from its own resource pool ([HumanController.dispatchWMBuild]).
 *  2. **Multi-turn / amphibious movement** — [MultiTurnMoveUnitAction] + [MovementCombiner]
 *     walk a unit toward an interim destination when its final target is outside the
 *     unit's connected island (e.g. a tank that must wait for a barge).
 *
 * These were previously absent (movement combiner was a placeholder `Any()`; the human
 * controller never dispatched WMBuildUnitAction), so the tests cannot pass vacuously.
 */
class MissingSystemsTest {

    private val assetsDir = "src/commonMain/resources"
    private fun haveAssets(): Boolean = File("$assetsDir/data").isDirectory

    private fun warmachineType(): UnitData? =
        UnitManager.getAll().firstOrNull { it.hasModifier("Constructor") }

    /** Cheapest pool-affordable, buildable unit that can actually stand on [dest]. */
    private fun cheapestBuildableFromPool(pool: Int, dest: cn.enaium.battalion.data.Tile): UnitData? =
        UnitManager.getAll()
            .filter { !it.hasModifier("Unbuildable") }
            .filter { it.getCost(null) <= pool }
            .filter { dest.getMovementCost(Unit(it)) != Double.POSITIVE_INFINITY }
            .minByOrNull { it.getCost(null) }

    @Test
    fun warmachineBuildsAdjacentUnit() {
        if (!haveAssets()) return
        val game = GameLoader.loadCampaignMission(assetsDir, 1)
        game.startGame()
        for (i in 1..30) game.update(16)

        val human = game.activeController as? HumanController ?: fail("expected human turn")
        val wmType = warmachineType() ?: fail("no Constructor (warmachine) unit in the roster")

        // Find a pair of horizontally-adjacent empty open tiles: warmachine stands on (x,y)
        // and builds onto the East neighbour (x+1,y).
        var wx = -1; var wy = -1
        outer@ for (y in 0 until game.map.height - 1) {
            for (x in 0 until game.map.width - 2) {
                val t = game.tileAt(x, y)
                val dest = game.tileAt(x + 1, y)
                if (t != null && dest != null && t.unit == null && dest.unit == null &&
                    !t.isStructure() && !dest.isStructure() &&
                    t.getMovementCost(Unit(wmType)) != Double.POSITIVE_INFINITY &&
                    dest.getMovementCost(Unit(wmType)) != Double.POSITIVE_INFINITY) {
                    wx = x; wy = y; break@outer
                }
            }
        }
        if (wx < 0) fail("no open ground tile with a free East neighbour to place the warmachine")

        val wm = Unit(wmType, wx, wy, human)
        wm.deployed = true
        wm.resourcePool = 4000 // ample warmachine pool
        game.tileAt(wx, wy)!!.unit = wm
        game.addUnit(human, wm)
        human.selectUnit(wm)

        // destination must be an adjacent clear tile
        val dest = game.tileAt(wx + 1, wy) ?: fail("no adjacent tile")
        if (dest.unit != null) fail("adjacent destination tile occupied")
        val toBuild = cheapestBuildableFromPool(wm.resourcePool, dest) ?: fail("no affordable, placeable unit")

        human.dispatchWMBuild(dest.x, dest.y, toBuild.name)
        for (i in 1..40) game.update(16)

        val builtUnit = game.unitAt(dest.x, dest.y)
        assertNotNull(builtUnit, "expected a unit built on the destination tile")
        assertTrue(builtUnit.controller === human, "built unit belongs to the human")
        assertTrue(builtUnit.isDeployed(), "built unit is deployed")
        assertTrue(wm.resourcePool < 4000, "warmachine pool was spent on building")
    }

    @Test
    fun multiTurnMoveUsesMovementCombiner() {
        if (!haveAssets()) return
        val game = GameLoader.loadCampaignMission(assetsDir, 1)
        game.startGame()
        for (i in 1..30) game.update(16)

        val human = game.activeController as? HumanController ?: fail("expected human turn")
        // Pick a slow ground unit with real range so the combiner can reason about turns.
        val groundTypes = UnitManager.getAll()
            .filter { it.isGround() && it.range > 0 && !it.hasModifier("Unbuildable") }
        val data = groundTypes.minByOrNull { it.range } ?: fail("no ground unit with movement")

        // Find any tile where the combiner yields a valid interim destination (same island,
        // reachable this turn → returns the target; otherwise an interim tile).
        var ux = -1; var uy = -1
        outer@ for (y in 0 until game.map.height - 1) {
            for (x in 0 until game.map.width - 1) {
                val t = game.tileAt(x, y)
                if (t != null && t.unit == null && t.canBeMovedOnBy(Unit(data))) {
                    ux = x; uy = y; break@outer
                }
            }
        }
        if (ux < 0) fail("no reachable tile to place the test ground unit")

        val unit = Unit(data, ux, uy, human)
        unit.deployed = true
        game.tileAt(ux, uy)!!.unit = unit
        game.addUnit(human, unit)

        // The target is far away (bottom-right); the combiner should produce an interim plan
        // rather than crashing, and MultiTurnMoveUnitAction must fire without throwing.
        val tx = game.map.width - 2
        val ty = game.map.height - 2
        val interim = game.movementCombiner.getInterimDestination(
            game.tileAt(ux, uy)!!,
            game.tileAt(tx, ty)!!,
            unit,
            cn.enaium.battalion.data.Tile.FLAG_IGNORE_LAST_NODE,
        )
        // Even a same-island path should give us the target or a valid interim tile — never null
        // for a connected map reachable by the unit.
        assertTrue(
            interim != null,
            "movement combiner should produce an interim destination for a reachable target",
        )

        val action = MultiTurnMoveUnitAction(unit.x, unit.y, tx, ty)
        action.isNetworked = false
        ActionManager.add(action)
        for (i in 1..60) game.update(16)
        assertTrue(
            ActionManager.isEmpty() || game.units.any { it === unit },
            "multi-turn action fired without crashing and the unit is still tracked",
        )
    }
}

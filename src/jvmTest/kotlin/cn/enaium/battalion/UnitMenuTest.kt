package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.Trigger
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.controllers.HumanController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import java.io.File

/**
 * Guards the floating unit action menu ([HumanController.raceUnitOptions]) that appears above a
 * selected unit in this port — the original's `unitOptionsButton`/`showUnitMenu` interaction.
 *
 * A selected friendly unit must offer a context-sensitive action set: a damaged ground unit gets
 * a Repair option, a Warmachine (Constructor) gets a Build option, and every menu closes with a
 * Cancel entry. These previously did not exist (only keyboard shortcuts dispatched actions).
 */
class UnitMenuTest {

    private val assetsDir = "src/commonMain/resources"
    private fun haveAssets(): Boolean = File("$assetsDir/data").isDirectory

    private fun newGame(): GameManager {
        val game = GameLoader.loadCampaignMission(assetsDir, 1)
        game.startGame()
        for (i in 1..30) game.update(16)
        return game
    }

    private fun addUnit(game: GameManager, human: HumanController, typeName: String): Unit {
        val data = UnitManager.get(typeName) ?: fail("no unit type $typeName")
        // Place on any open, passable tile.
        var px = -1; var py = -1
        outer@ for (y in 0 until game.map.height - 1) {
            for (x in 0 until game.map.width - 1) {
                val t = game.tileAt(x, y)
                if (t != null && t.unit == null && t.getMovementCost(Unit(data)) != Double.POSITIVE_INFINITY) {
                    px = x; py = y; break@outer
                }
            }
        }
        if (px < 0) fail("no open passable tile for $typeName")
        val unit = Unit(data, px, py, human)
        unit.deployed = true
        game.tileAt(px, py)!!.unit = unit
        game.addUnit(human, unit)
        return unit
    }

    @Test
    fun everyMenuEndsWithCancel() {
        if (!haveAssets()) return
        val game = newGame()
        val human = game.activeController as? HumanController ?: fail("expected human turn")
        // Any unit on the field (or a freshly-buildable one) must expose at least a Cancel.
        val unit = game.units.firstOrNull { it.controller === human }
            ?: addUnit(game, human, "Heavy Commando")
        val options = human.raceUnitOptions(unit)
        assertTrue(options.isNotEmpty(), "menu must never be empty for a selected unit")
        assertTrue(
            options.last().sprite == "unitCancel" && options.last().enabled,
            "the last menu entry must be the enabled Cancel button; got ${options.map { it.sprite }}",
        )
    }

    @Test
    fun damagedUnitOffersRepair() {
        if (!haveAssets()) return
        val game = newGame()
        val human = game.activeController as? HumanController ?: fail("expected human turn")
        val unit = addUnit(game, human, "Heavy Commando")
        unit.setHealth(1.0) // damaged
        val options = human.raceUnitOptions(unit)
        val repair = options.find { it.sprite == "unitRepair" }
        assertTrue(repair != null, "a damaged repairable unit must offer Repair; got ${options.map { it.sprite }}")
        assertTrue(repair!!.enabled, "damaged unit's Repair must be enabled")
    }

    @Test
    fun warmachineBuildAdvancesToDirectionPhase() {
        if (!haveAssets()) return
        val game = newGame()
        val human = game.activeController as? HumanController ?: fail("expected human turn")
        val data = UnitManager.getAll().firstOrNull { it.hasModifier("Constructor") }
            ?: fail("no Constructor unit in roster")
        val unit = addUnit(game, human, data.name)
        unit.resourcePool = 4000
        human.selectUnit(unit)

        assertTrue(human.openBuildMenu(), "a Warmachine with resources must open a build menu")
        val menu = human.buildMenu ?: fail("expected a build menu after openBuildMenu")
        assertEquals(HumanController.BuildSource.WARMACHINE, menu.source,
            "a selected Warmachine opens its own constructor build page")

        // Confirm on the unit page advances to the direction picker (not builds directly).
        val beforeUnits = menu.units.size
        assertTrue(human.confirmBuild(), "confirming a WM build must advance to direction phase")
        val m2 = human.buildMenu ?: fail("build menu must persist for WM direction picker")
        assertEquals(HumanController.BuildPhase.WM_DIRECTION, m2.phase,
            "WM build must move to the direction picker stage")
        assertEquals(beforeUnits, m2.units.size, "menus list unchanged after advancing phase")

        // The direction picker pre-selected a valid adjacent dest in confirmBuild. Completing
        // that phase (with the same dest) must place and spend the build.
        val m3 = human.buildMenu ?: fail("direction picker still open")
        assertTrue(m3.destX >= 0 && m3.destY >= 0, "direction picker pre-selects an adjacent dest")
        val dir = human.warmachineDirectionName(m3.destX - unit.x, m3.destY - unit.y)
        assertTrue(dir != null, "pre-selected dest must be a cardinal neighbour")

        val destUnit = game.unitAt(m3.destX, m3.destY)
        assertTrue(destUnit == null, "pre-selected dest must be empty before the build")

        assertTrue(human.chooseWarmachineDirection(dir, build = true),
            "choosing the pre-selected direction completes the WM build")
        assertTrue(human.buildMenu == null, "build menu closes after a WM direction is chosen")
        // If the action's fire() throws, ActionManager swallows it into a Trigger.ERROR trigger —
        // capture that so we can tell a silent WM-build failure apart from a real one.
        var errored = false
        TriggerManager.addListener(Trigger.ERROR) { errored = true }
        // The dispatched WMBuildUnitAction spends the pool and places the unit when it fires (next update).
        runCatching { for (i in 1..40) game.update(16) }
            .onFailure { throw AssertionError("update threw: ${it.message}", it) }
        assertTrue(!errored, "WM build action must not throw (Trigger.ERROR was dispatched)")
        assertTrue(unit.resourcePool < 4000, "WM resource pool is spent after building " +
            "(pool=${unit.resourcePool})")
        val built = game.unitAt(m3.destX, m3.destY)
        assertTrue(built != null && built.controller === human, "WM build placed a friendly unit")
    }

    @Test
    fun warmachineOffersBuild() {
        if (!haveAssets()) return
        val game = newGame()
        val human = game.activeController as? HumanController ?: fail("expected human turn")
        val data = UnitManager.getAll().firstOrNull { it.hasModifier("Constructor") }
            ?: fail("no Constructor unit in roster")
        val unit = addUnit(game, human, data.name)
        unit.resourcePool = 4000
        val options = human.raceUnitOptions(unit)
        val build = options.find { it.sprite == "unitBuild" }
        assertTrue(build != null, "a Warmachine must offer Build; got ${options.map { it.sprite }}")
        assertTrue(build!!.enabled, "a Warmachine with an open neighbor and resources must allow Build")
    }
}

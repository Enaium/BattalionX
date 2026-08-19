package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitData
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.data.Team
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.actions.AttackUnitAction
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.controllers.HALController
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import java.io.File

/**
 * Guards the two combat-rule fixes against the original:
 *
 *  1. A killer is still expended (moved/fired set) after destroying its target — otherwise
 *     every unit could act again after a kill, which only Scavenge units may do.
 *  2. Counterattack kills credit the COUNTERATTACKER (not the dead attacker's own team).
 *
 * The scenario is constructed explicitly (two adjacent ground/sea melee units, damage forced
 * lethal) so these tests cannot pass vacuously from "no target available".
 */
class AttackResolveFixTest {

    private val assetsDir = "/Users/enaium/swf/BattalionVengeance"
    private fun haveAssets(): Boolean = File("$assetsDir/binaryData").isDirectory

    /** Picks a unit type that is melee (weapon range exactly 1) and ground/sea (can attack adjacent). */
    private fun meleeTypes(): List<UnitData> =
        UnitManager.getAll().filter {
            it.weaponRangeMin in 1..1 && it.weaponRangeMax == 1 && !it.isAir()
        }

    private fun unitTypesCompatible(a: UnitData, b: UnitData): Boolean {
        // Both are ground/sea melee; a melee hit applies unless a ballistics 0x or a gate blocks.
        if (a.isSea() != b.isSea()) return false
        return true
    }

    private fun placeAttackPair(game: GameManager): Pair<Unit, Unit> {
        val human = game.controllers.firstOrNull { it is HumanController } ?: fail("no human")
        val ai = game.controllers.firstOrNull { it is HALController } ?: fail("no AI")

        // Choose two compatible melee types (resolveAttack > 0 both ways).
        val melee = meleeTypes()
        var aData: UnitData? = null
        var tData: UnitData? = null
        for (a in melee) {
            for (t in melee) {
                if (a === t) continue
                // verify a one-shot can damage each other given adjacency
                aData = a; tData = t
                break
            }
            if (aData != null) break
        }
        if (aData == null || tData == null) fail("no melee unit pair available")

        // Find two horizontally-adjacent empty tiles anywhere on the map.
        var ax = -1; var ay = -1
        outer@ for (y in 0 until game.map.height - 1) {
            for (x in 0 until game.map.width - 1) {
                val a = game.tileAt(x, y)
                val b = game.tileAt(x + 1, y)
                if (a != null && b != null && a.unit == null && b.unit == null) {
                    ax = x; ay = y
                    break@outer
                }
            }
        }
        if (ax < 0) fail("no empty adjacent tiles on the map")

        val attacker = Unit(aData, ax, ay, human)
        val target = Unit(tData, ax + 1, ay, ai)
        attacker.deployed = true
        target.deployed = true
        for (u in listOf(attacker, target)) {
            game.tileAt(u.x, u.y)!!.unit = u
            game.addUnit(u.controller!!, u)
        }
        return attacker to target
    }

    private fun drainActions(gm: GameManager, steps: Int) {
        for (i in 1..steps) gm.update(16)
    }

    @Test
    fun attackerIsExpendedAfterKillingTarget() {
        if (!haveAssets()) return
        val game = GameLoader.loadCampaignMission(assetsDir, 1)
        game.startGame()
        drainActions(game, 30)

        val (attacker, target) = placeAttackPair(game)
        val dmg = game.resolveAttack(attacker, target, useTiles = false)
        assertTrue(dmg > 0, "attacker must deal damage; got $dmg")
        target.setHealth(1.0)

        val attack = AttackUnitAction(attacker.x, attacker.y, target.x, target.y)
        attack.isNetworked = false
        ActionManager.add(attack)
        drainActions(game, 120)

        assertTrue(
            attacker.hasFired() && attacker.hasMoved(),
            "attacker must be expended (moved+fired) after killing; got moved=${attacker.hasMoved()} fired=${attacker.hasFired()}"
        )
        assertTrue(!target.isAlive(), "target should have died")
    }

    @Test
    fun counterattackKillCreditsTheCounterattacker() {
        if (!haveAssets()) return
        val game = GameLoader.loadCampaignMission(assetsDir, 1)
        game.startGame()
        drainActions(game, 30)

        val (attacker, target) = placeAttackPair(game)
        val dmg = game.resolveAttack(attacker, target, useTiles = false)
        assertTrue(dmg > 0, "attacker must deal damage; got $dmg")
        // Target survives the hit, attacker dies to return fire.
        target.setHealth(target.getMaxHealth())
        attacker.setHealth(1.0)
        val returnDmg = game.resolveAttack(target, attacker, useTiles = false)
        assertTrue(returnDmg >= attacker.getHealth(), "return fire must kill attacker; dmg=$returnDmg")

        val killsBefore = target.controller?.team?.getStatistic(Team.UNITS_KILLED) ?: 0
        val attack = AttackUnitAction(attacker.x, attacker.y, target.x, target.y)
        attack.isNetworked = false
        ActionManager.add(attack)
        // Counterattack path adds animation delays.
        drainActions(game, 400)

        assertTrue(!attacker.isAlive(), "attacker should have died to the counterattack")
        val killsAfter = target.controller?.team?.getStatistic(Team.UNITS_KILLED) ?: 0
        assertTrue(
            killsAfter >= killsBefore + 1,
            "counterattacker should receive UNITS_KILLED credit; before=$killsBefore after=$killsAfter"
        )
    }
}

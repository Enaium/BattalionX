package cn.enaium.battalion

import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.controllers.HALController
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.data.GameLoader
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import java.io.File

/**
 * Regression test for the AI movement animation: during the enemy (HAL) turn every move must be
 * dispatched as a valid [MoveUnitAction] that walks tile by tile (the unit is observed `isMoving`
 * with advancing progress across several frames) — never a one-frame teleport.
 *
 * The root cause this guards: `HALTurn.findPath` used `Astar.search` (a flood-fill of visited
 * nodes, not an ordered path), so every AI move failed its `canMove` check and the action
 * completed instantly through the error path — no walk animation ever played.
 */
class HalTurnWalkAnimationTest {

    /** The mission used by the manual run instructions in build/description.md. */
    private val assetsDir = "src/commonMain/resources"

    /** True when the unpacked squad assets are present (skips the test otherwise). */
    private fun haveAssets(): Boolean = File("$assetsDir/data").isDirectory

    @Test
    fun aiMovesWalkLikePlayerUnits() {
        if (!haveAssets()) return // no asset dir on this machine: nothing to load
        val game = GameLoader.loadCampaignMission(assetsDir, 2)
        game.startGame()
        // Drain the startup actions (start game / round / turn) so the human's turn is live.
        for (i in 1..30) game.update(16)

        val human = game.activeController as? HumanController ?: fail("expected human turn to start")
        var aiTurnsObserved = 0
        var walkObserved = false
        // Track each unit's walk progress across frames to prove the animation advanced.
        val progressSeen = mutableMapOf<Int, MutableList<Long>>() // walkId -> moveProgressMs samples

        for (i in 1..6000) {
            game.update(16)
            for (u in game.units) {
                if (u.isMoving) progressSeen.getOrPut(u.walkId) { mutableListOf() }.add(u.moveProgressMs)
            }
            if (game.activeController is HALController) {
                // The AI's whole turn: any observed walk means a move animated.
                if (progressSeen.values.any { it.size >= 3 && it.zipWithNext().any { (a, b) -> b > a } }) {
                    walkObserved = true
                }
            }
            if (game.currentController == 0 &&
                game.activeController is HumanController &&
                ActionManager.isEmpty()
            ) {
                if (game.turn >= 2) break
                aiTurnsObserved++
                human.dispatchEndTurn()
            }
        }

        assertTrue(aiTurnsObserved >= 1, "the AI should have played at least one turn")
        assertTrue(
            walkObserved,
            "expected an AI move unit to walk over multiple frames (isMoving with advancing " +
                "progress), but not a single unit animated — progress samples: ${progressSeen.size} units"
        )
    }
}
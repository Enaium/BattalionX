package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.actions.EndTurnAction
import cn.enaium.battalion.game.controllers.HumanController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * Guards the saved-game round trip: [GameManager.toXML] serializes the live match state
 * (turn/round/controller, resources, units with health/moved/fired, structures with chunks),
 * [GameLoader.loadSavedGame] rebuilds a GameManager from that snapshot, and resuming fires
 * START_TURN for the saved controller without re-running the fresh-start chain.
 *
 * Without the save system this test cannot compile-passes: `toXML` / `loadSavedGame` /
 * `resumeFromSave` did not exist.
 */
class SaveGameTest {

    private val assetsDir = "assets"
    private fun haveAssets(): Boolean = File("$assetsDir/data").isDirectory

    @Test
    fun `saved game round-trips state and resumes at the saved controller`() {
        if (!haveAssets()) return
        val original = GameLoader.loadCampaignMission(assetsDir, 0)
        original.startGame()
        for (i in 1..40) original.update(16)  // let the first turn settle

        // Snapshot mid-match.
        val xml = original.toXML()
        assertTrue(xml.startsWith("<game mapurl="), "save must carry the map url")
        assertTrue(xml.contains("<gamestate turn="), "save must carry turn/round/controller")
        assertTrue(xml.contains("<unit "), "save must carry units")
        assertTrue(original.currentController >= 0)

        // Rebuild from the snapshot.
        val restored = GameLoader.loadSavedGame(assetsDir, xml)
        assertTrue(restored.wasLoadedFromSavedGame, "restored game must know it came from a save")
        assertEquals(original.turn, restored.turn, "turn must be restored")
        assertEquals(original.round, restored.round, "round must be restored")
        assertEquals(original.currentController, restored.currentController, "active controller must be restored")
        assertEquals(original.units.size, restored.units.size, "unit count must be restored")
        for (u in original.units) {
            val r = restored.units.firstOrNull { it.baseUnitData.name == u.baseUnitData.name && it.x == u.x && it.y == u.y }
            assertNotNull(r, "restored unit ${u.baseUnitData.name}@${u.x},${u.y} must exist")
            assertEquals(u.getHealth(), r.getHealth(), "health of ${u.baseUnitData.name} must be restored")
            assertEquals(u.moved, r.moved, "moved flag of ${u.baseUnitData.name} must be restored")
        }

        // Resume: START_TURN for the saved controller — the human's controller reaches
        // startCurrentTurn (itIsMyTurn), proving the resume path is live.
        restored.resumeFromSave()
        restored.update(16)
        restored.update(16)
        val active = restored.getController(restored.currentController)
        assertNotNull(active)
        assertTrue(active.itIsMyTurn || restored.gameOver, "resumed controller must be mid-turn")
    }

    @Test
    fun `saved game survives a completed turn and restores the next controller`() {
        if (!haveAssets()) return
        val g = GameLoader.loadCampaignMission(assetsDir, 0)
        g.startGame()
        for (i in 1..40) g.update(16)

        // Let the human end their turn so turn/controller advance beyond the initial state.
        val human = g.activeController as? HumanController
        if (human != null && !g.gameOver) {
            human.dispatchEndTurn()
            for (i in 1..40) g.update(16)
        }
        val turnBefore = g.turn
        val controllerBefore = g.currentController

        val restored = GameLoader.loadSavedGame(assetsDir, g.toXML())
        assertEquals(turnBefore, restored.turn)
        assertEquals(controllerBefore, restored.currentController)
        // The restored game must not have re-run the fresh-start chain (resources would reset).
        val rActive = restored.getController(restored.currentController)
        assertNotNull(rActive)
        assertEquals(g.getController(controllerBefore)?.getResources(), rActive.getResources())
    }
}

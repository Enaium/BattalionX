package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.triggers.ExplosionTrigger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the in-game menu's "Quit (without saving)". The original forfeits the mission
 * (`IngameOptionsMenu.quitAndNoSaveHandler` → `quit()` → `GameManager.surrenderLocalPlayer()`),
 * so quitting must end the match as a DEFEAT with the player's units removed — never drop the
 * match silently and leave the menu with no result.
 */
class QuitSurrenderTest {

    private val assetsDir = "assets"
    private fun haveAssets(): Boolean = File("$assetsDir/data").isDirectory

    @Test
    fun `quitting without saving forfeits the mission`() {
        if (!haveAssets()) return
        val game = GameLoader.loadCampaignMission(assetsDir, 0)
        game.startGame()
        for (i in 1..30) game.update(16)

        val human = game.controllers.filterIsInstance<HumanController>().first()
        assertTrue(human.units.isNotEmpty(), "the human player starts with units on the map")
        assertFalse(game.gameOver, "the match is still running before the quit")
        val unitCount = human.units.size

        // The original blasts every unit and structure on the way out (destroyAllUnits).
        val blasts = mutableListOf<ExplosionTrigger>()
        TriggerManager.addListener(ExplosionTrigger.NAME) { t ->
            (t as? ExplosionTrigger)?.let { blasts.add(it) }
        }

        game.surrenderLocalPlayer()
        var guard = 0
        while (!game.gameOver && guard++ < 400) game.update(16)

        assertTrue(game.gameOver, "quitting must end the match")
        assertFalse(game.playerWon, "quitting forfeits: the results screen must show a defeat")
        assertFalse(human.team.active, "the surrendering team must be out")
        assertTrue(human.units.isEmpty(), "the surrendering team's units must leave the map")
        assertTrue(blasts.size >= unitCount,
            "every surrendered unit must explode (got ${blasts.size} blasts for $unitCount units)")
    }
}

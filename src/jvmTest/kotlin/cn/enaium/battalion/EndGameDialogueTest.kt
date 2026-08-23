package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.game.actions.EndGameAction
import cn.enaium.battalion.game.ui.Dialogue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the end-of-game dialogue: once the match ends (END_GAME fires through
 * [EndGameAction]), the map script's `endGame` events must carry chat lines that
 * [Dialogue.play]("endGame") surfaces — the results screen waits for them.
 */
class EndGameDialogueTest {

    private val assetsDir = "assets"
    private fun haveAssets(): Boolean = File("$assetsDir/data").isDirectory

    @Test
    fun `endGame dialogue lines exist and play after the match ends`() {
        if (!haveAssets()) return
        val game = GameLoader.loadCampaignMission(assetsDir, 0)
        val dlg = Dialogue(game.map.script)

        // Mission 1 has endGame events with chat responses.
        assertTrue(dlg.play("endGame"), "mission must provide end-of-game dialogue")
        assertTrue(dlg.isActive(), "dialogue must be active after play")
        assertTrue(dlg.current()?.message?.isNotEmpty() == true, "first line must have text")

        // Advancing drains the queue, then the box closes.
        var guard = 0
        while (dlg.isActive() && guard++ < 200) dlg.advance()
        assertFalse(dlg.isActive(), "dialogue must close after all lines advance")

        // The real flow: END_GAME triggers the endGame events, then the game ends
        // (EndGameAction.update waits for the trigger/event/response managers to drain).
        game.startGame()
        for (i in 1..30) game.update(16)
        val action = EndGameAction().apply { isNetworked = false }
        cn.enaium.battalion.game.ActionManager.add(action)
        var guard2 = 0
        while (!game.gameOver && guard2++ < 300) game.update(16)
        assertTrue(game.gameOver, "match must end after END_GAME fires")
    }
}

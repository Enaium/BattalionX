package cn.enaium.battalion

import cn.enaium.battalion.game.ui.SaveManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the options / save persistence ([SaveManager]): every toggle (sound / music / dialog),
 * the AI difficulty, medals and the saved game survive a write + reload, and Clear Data wipes
 * them back to defaults.
 */
class SaveManagerTest {

    private val tmpDir: String = createTempDir("battalionx-save-test").absolutePath

    @Test
    fun `option toggles and save round-trip through the JSON store`() {
        SaveManager.bind(tmpDir)
        // Defaults.
        assertTrue(SaveManager.sound)
        assertTrue(SaveManager.music)
        assertTrue(SaveManager.dialog)
        assertEquals(1, SaveManager.aiDifficulty)

        // Flip everything and persist a fake save.
        SaveManager.sound = false
        SaveManager.music = false
        SaveManager.dialog = false
        SaveManager.aiDifficulty = 2
        SaveManager.setMedal("maps/ep3/Mission_1.yimf", "gold")
        SaveManager.savedGame = cn.enaium.battalion.data.SavedGameConfig(
            mapId = "x",
            settings = cn.enaium.battalion.data.MissionSettingsConfig(),
            script = cn.enaium.battalion.data.MapScriptConfig(),
        )

        // Reload from disk (a fresh bind re-reads the file).
        SaveManager.bind(tmpDir)
        assertFalse(SaveManager.sound)
        assertFalse(SaveManager.music)
        assertFalse(SaveManager.dialog)
        assertEquals(2, SaveManager.aiDifficulty)
        assertEquals("gold", SaveManager.medalFor("maps/ep3/Mission_1.yimf"))
        assertEquals("x", SaveManager.savedGame?.mapId)

        // Clear Data wipes the store (defaults restored).
        SaveManager.clearAll()
        assertTrue(SaveManager.sound)
        assertTrue(SaveManager.music)
        assertTrue(SaveManager.dialog)
        assertEquals(1, SaveManager.aiDifficulty)
        assertNull(SaveManager.savedGame)
    }

    @Test
    fun `medals never downgrade`() {
        SaveManager.bind(tmpDir)
        SaveManager.clearAll()
        SaveManager.setMedal("m", "bronze")
        SaveManager.setMedal("m", "silver")
        SaveManager.setMedal("m", "bronze")  // downgrade attempt is ignored
        assertEquals("silver", SaveManager.medalFor("m"))
    }

    /**
     * Vendetta's maps moved from `maps/` into `maps/ep3/`: a save written before the move must
     * keep its medals and its saved game, without touching custom maps or other generations.
     */
    @Test
    fun `legacy root map ids migrate into the generation folder`() {
        // SaveManager.bind expects the ASSETS directory; the store sits beside it.
        val dir = createTempDir("battalionx-save-migrate")
        val assets = File(dir, "assets").apply { mkdirs() }
        File(dir, "battalionx_save.json").writeText(
            """{"maps/Mission_1_medal":"gold","maps/bootcamp/batut_basics_medal":"silver",""" +
            """"maps/ep1/Mission_2_medal":"bronze","maps/mymap_medal":"gold",""" +
            """"saveGame":{"mapId":"Kongregate_Bonus_Map","settings":{},"script":{}}}""",
        )
        SaveManager.bind(assets.absolutePath)

        assertEquals("gold", SaveManager.medalFor("maps/ep3/Mission_1"), "legacy medal migrated")
        assertEquals("none", SaveManager.medalFor("maps/Mission_1"), "legacy key dropped")
        assertEquals("silver", SaveManager.medalFor("maps/bootcamp/batut_basics"), "bootcamp kept")
        assertEquals("bronze", SaveManager.medalFor("maps/ep1/Mission_2"), "ep1 kept")
        assertEquals("gold", SaveManager.medalFor("maps/mymap"), "custom map untouched")
        assertEquals("ep3/Kongregate_Bonus_Map", SaveManager.savedGame?.mapId, "saved game migrated")

        // The migration is written back, so the next load sees the new keys only.
        val text = File(dir, "battalionx_save.json").readText()
        assertTrue(text.contains("maps/ep3/Mission_1_medal"), "new key persisted")
        assertFalse(text.contains("maps/Mission_1_medal"), "old key gone")
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), prefix)
        dir.mkdirs()
        return dir
    }
}

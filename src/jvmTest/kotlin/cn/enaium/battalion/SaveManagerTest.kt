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
 * the AI difficulty, campaign progress, medals and the saved game XML survive a write + reload,
 * and Clear Data wipes them back to defaults.
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
        SaveManager.campaignComplete = 4
        SaveManager.setMedal("maps/ep3/Mission_1.yimf", "gold")
        SaveManager.saveGame = "<game mapurl=\"x\"/>"

        // Reload from disk (a fresh bind re-reads the file).
        SaveManager.bind(tmpDir)
        assertFalse(SaveManager.sound)
        assertFalse(SaveManager.music)
        assertFalse(SaveManager.dialog)
        assertEquals(2, SaveManager.aiDifficulty)
        assertEquals(4, SaveManager.campaignComplete)
        assertEquals("gold", SaveManager.medalFor("maps/ep3/Mission_1.yimf"))
        assertEquals("<game mapurl=\"x\"/>", SaveManager.saveGame)

        // Clear Data wipes the store (defaults restored).
        SaveManager.clearAll()
        assertTrue(SaveManager.sound)
        assertTrue(SaveManager.music)
        assertTrue(SaveManager.dialog)
        assertEquals(1, SaveManager.aiDifficulty)
        assertNull(SaveManager.saveGame)
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

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), prefix)
        dir.mkdirs()
        return dir
    }
}

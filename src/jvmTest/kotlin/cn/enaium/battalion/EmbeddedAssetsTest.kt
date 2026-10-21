package cn.enaium.battalion

import cn.enaium.battalion.data.AssetLoader
import cn.enaium.battalion.data.EmbeddedAssets
import cn.enaium.battalion.editor.EditorModel
import cn.enaium.battalion.mod.ModRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The build embeds `src/commonMain/resources/` into Kotlin sources (cn.enaium.embedresource). A packaged build
 * has no assets directory: [EmbeddedAssets.materialize] must reproduce a tree the data/model
 * layer can read.
 */
class EmbeddedAssetsTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "battalionx-embedded-test")
        .apply { deleteRecursively(); mkdirs() }

    @AfterTest
    fun cleanup() {
        ModRegistry.clear()
        AssetLoader.clearCache()
        tmp.deleteRecursively()
    }

    @Test
    fun `embedded assets materialize into a usable asset directory`() {
        assertTrue(EmbeddedAssets.paths.contains("data/maps/ep3/Mission_1.json"), "map documents are embedded")
        assertTrue(EmbeddedAssets.paths.contains("images/mainframe.png"), "images are embedded")
        assertTrue(EmbeddedAssets.paths.contains("sounds/UIWAV.mp3"), "sounds are embedded")

        val dir = File(tmp, "assets").absolutePath
        assertTrue(EmbeddedAssets.materialize(dir, "test"), "materialize writes the tree")
        assertTrue(File(dir, "data/maps/ep3/Mission_1.json").isFile)
        assertTrue(File(dir, "images/mainframe.png").length() > 0)
        assertTrue(File(dir, "sounds/UIWAV.mp3").length() > 0)
        assertTrue(EmbeddedAssets.materialize(dir, "test"), "the version marker makes repeat calls cheap")

        // The existing loaders see a normal asset directory.
        val editor = EditorModel(dir)
        assertTrue(editor.open("ep3/Mission_1"), "the embedded mission opens")
        assertTrue(editor.cols() == 10 && editor.countPlayers().size >= 2)
    }
}

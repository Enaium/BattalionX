package cn.enaium.battalion

import cn.enaium.battalion.data.AssetLoader
import cn.enaium.battalion.mod.ModRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the mod system end to end on a synthetic assets tree: discovery, load order, JSON-Patch
 * application with last-write-wins, mod-added documents, dependency rejection and the namespaced
 * image/sound layer (including mod-over-base override).
 */
class ModSystemTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "battalionx-mods-test")
        .apply { deleteRecursively(); mkdirs() }

    private val assets: File get() = File(root, "assets")
    private val modsRoot: File get() = File(root, "mods")

    @BeforeTest
    fun isolate() {
        // SaveManager is process-wide: give every test its own store so one test's mod switches
        // never leak into another's.
        val scratch = File(root, "save-assets").apply { mkdirs() }
        cn.enaium.battalion.game.ui.SaveManager.bind(scratch.absolutePath)
        cn.enaium.battalion.game.ui.SaveManager.clearAll()
        ModRegistry.clear()
    }

    @AfterTest
    fun cleanup() {
        ModRegistry.clear()
        root.deleteRecursively()
    }

    /**
     * Switching a mod off must keep it installed but out of both layers: no patches, no
     * resources — and switching it back on must restore them.
     */
    @Test
    fun `a disabled mod contributes nothing until re-enabled`() {
        buildAssets()
        cn.enaium.battalion.game.ui.SaveManager.bind(File(root, "save-assets").apply { mkdirs() }.absolutePath)
        cn.enaium.battalion.game.ui.SaveManager.clearAll()
        cn.enaium.battalion.game.ui.SaveManager.setModEnabled("b.second", false)
        ModRegistry.load(assets.absolutePath, modsRoot.absolutePath)

        assertEquals(listOf("a.first", "d.badpatch"), ModRegistry.mods.map { it.manifest.id })
        assertEquals(3, ModRegistry.allMods.size, "the disabled mod is still listed")
        val patched = AssetLoader.readText(assets.absolutePath, "units/base.json")!!
        assertTrue(patched.contains("Scorpion (A)"), "only the enabled mod patched it: $patched")
        assertTrue(ModRegistry.resources!!.imagePath("override")!!.contains("a_first"),
            "the disabled mod's resource no longer wins")

        cn.enaium.battalion.game.ui.SaveManager.setModEnabled("b.second", true)
        AssetLoader.clearCache()
        ModRegistry.load(assets.absolutePath, modsRoot.absolutePath)
        assertEquals(listOf("a.first", "b.second", "d.badpatch"), ModRegistry.mods.map { it.manifest.id })
        assertTrue(AssetLoader.readText(assets.absolutePath, "units/base.json")!!.contains("Scorpion (B)"),
            "re-enabled mod patches again")
    }

    /**
     * Switching every mod off must leave the mod LIST intact: the screen draws from [ModRegistry.allMods],
     * so an empty list of enabled mods (mods empty, allMods full) is the state that must not read as
     * "no mods installed".
     */
    @Test
    fun `disabling every mod keeps them listed`() {
        buildAssets()
        val assetsDir = File(root, "save-assets").apply { mkdirs() }
        cn.enaium.battalion.game.ui.SaveManager.bind(assetsDir.absolutePath)
        cn.enaium.battalion.game.ui.SaveManager.clearAll()
        for (id in listOf("a.first", "b.second", "d.badpatch")) {
            cn.enaium.battalion.game.ui.SaveManager.setModEnabled(id, false)
        }
        ModRegistry.load(assets.absolutePath, modsRoot.absolutePath)

        assertTrue(ModRegistry.mods.isEmpty(), "nothing is enabled")
        assertEquals(3, ModRegistry.allMods.size, "but all three are still installed and listed")
        assertTrue(ModRegistry.allMods.none { it.enabled }, "every switch reads disabled")
        // With nothing enabled the game falls back to its own content.
        val text = AssetLoader.readText(assets.absolutePath, "units/base.json")!!
        assertTrue(text.contains("\"damage\":35"), "base document served: $text")
        assertTrue(!text.contains("Scorpion (A)") && !text.contains("Scorpion (B)"),
            "no mod patch applied: $text")
    }

    private fun file(path: String, text: String) {
        // Paths are relative to the temp root, so `assets/...` and `mods/...` both work.
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    private fun buildAssets() {
        file("assets/data/units/base.json", """{"name":"Scorpion Tank","damage":35}""")
        file("assets/data/units/other.json", """{"name":"Flak Tank","damage":30}""")
        file("assets/images/base_sprite.png", "base-image")
        file("assets/sounds/base_sound.mp3", "base-sound")

        file(
            "mods/a_first/manifest.json",
            """{"id":"a.first","name":"First Mod","version":"1.0.0","description":"patches base data"}""",
        )
        file(
            "mods/a_first/data/patches/units/base.json",
            """[{"op":"test","path":"/damage","value":35},{"op":"replace","path":"/name","value":"Scorpion (A)"}]""",
        )
        file("mods/a_first/images/override.png", "a-image")
        file("mods/a_first/images/items/crystal.png", "a-crystal")

        file(
            "mods/b_second/manifest.json",
            """{"id":"b.second","name":"Second Mod","version":"2.1.0","loadAfter":["a.first"],
               "icon":"ui/icon"}""",
        )
        file(
            "mods/b_second/data/patches/units/base.json",
            """[{"op":"replace","path":"/name","value":"Scorpion (B)"}]""",
        )
        file(
            "mods/b_second/data/patches/maps/mod_map.json",
            """[{"op":"add","path":"/","value":{"name":"Mod Map","rows":1,"cols":1}}]""",
        )
        file("mods/b_second/images/override.png", "b-image")
        file("mods/b_second/images/ui/icon.png", "b-icon")
        file("mods/b_second/sounds/base_sound.mp3", "b-sound")

        file("mods/c_broken/manifest.json", """{"id":"c.broken","dependencies":[{"id":"missing.mod"}]}""")
        file(
            "mods/d_badpatch/manifest.json",
            """{"id":"d.badpatch"}""",
        )
        file(
            "mods/d_badpatch/data/patches/units/other.json",
            """[{"op":"test","path":"/damage","value":1},{"op":"replace","path":"/damage","value":99}]""",
        )
    }

    @Test
    fun `mods load in order, patch base data and add their own documents`() {
        buildAssets()
        ModRegistry.load(assets.absolutePath, modsRoot.absolutePath)

        // d.badpatch loads too: a patch that fails at read time is skipped, the mod stays enabled.
        assertEquals(listOf("a.first", "b.second", "d.badpatch"), ModRegistry.mods.map { it.manifest.id })

        // Last writer wins across mods, on top of the untouched base document.
        val patched = AssetLoader.readText(assets.absolutePath, "units/base.json")!!
        assertTrue(patched.contains("Scorpion (B)"), "later mod wins: $patched")
        assertTrue(patched.contains("\"damage\":35"), "fields nobody patched survive: $patched")

        // A mod's own document is listed and reported as a mod document.
        assertTrue(AssetLoader.list(assets.absolutePath, "maps").contains("mod_map"))
        assertEquals(listOf("mod_map"), ModRegistry.modDocuments(assets.absolutePath, "maps"))
        assertTrue(AssetLoader.list(assets.absolutePath, "units").containsAll(listOf("base", "other")))

        // The game's own document list is untouched by mods that only patch.
        assertEquals(listOf("base", "other"), AssetLoader.list(assets.absolutePath, "units"))
    }

    @Test
    fun `a failing patch is reported and the base value survives`() {
        buildAssets()
        ModRegistry.load(assets.absolutePath, modsRoot.absolutePath)

        // d.badpatch expects damage 1 but the base ships 30: the patch is dropped, not fatal.
        val other = AssetLoader.readText(assets.absolutePath, "units/other.json")!!
        assertTrue(other.contains("\"damage\":30"), "base document intact: $other")
        assertTrue(ModRegistry.problems.any { it.mod == "d.badpatch" && it.message.contains("failed") },
            "problem reported: ${ModRegistry.problems}")
    }

    @Test
    fun `a mod with a missing dependency is skipped`() {
        buildAssets()
        ModRegistry.load(assets.absolutePath, modsRoot.absolutePath)
        assertTrue(ModRegistry.byId("c.broken") == null, "c.broken must not load")
        assertTrue(ModRegistry.problems.any { it.mod == "c.broken" && it.message.contains("missing dependency") },
            "problem reported: ${ModRegistry.problems}")
    }

    @Test
    fun `resources resolve with mod override, insertion and namespaces`() {
        buildAssets()
        ModRegistry.load(assets.absolutePath, modsRoot.absolutePath)
        val resources = assertNotNull(ModRegistry.resources)

        // Base resource still reachable.
        assertEquals("${assets.absolutePath}/images/base_sprite.png", resources.imagePath("base_sprite"))
        // Both mods ship `override`: the later one wins.
        assertEquals("${modsRoot.absolutePath}/b_second/images/override.png", resources.imagePath("override"))
        // Namespaced ids pin a resource to one mod.
        assertEquals("${modsRoot.absolutePath}/a_first/images/override.png",
            resources.imagePath("a.first:override"))
        // A mod-only, nested id is addressable as-is.
        assertEquals("${modsRoot.absolutePath}/a_first/images/items/crystal.png",
            resources.imagePath("items/crystal"))
        // Sounds: mod file replaces the base sound of the same id.
        assertEquals("${modsRoot.absolutePath}/b_second/sounds/base_sound.mp3",
            resources.soundPath("base_sound"))
        assertNotNull(resources.imagePath("ui/icon"))
    }
}

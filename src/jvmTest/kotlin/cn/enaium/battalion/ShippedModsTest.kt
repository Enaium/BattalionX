package cn.enaium.battalion

import cn.enaium.battalion.data.AssetLoader
import cn.enaium.battalion.mod.ModRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the mods shipped in `mods/` (the sample map, the data patch and the resource
 * override) against the real asset tree: they are the working examples a mod author copies and
 * the fixtures the end-to-end verification plays with.
 */
class ShippedModsTest {

    private val assetsDir = "assets"
    private fun haveAssets(): Boolean = File("$assetsDir/data").isDirectory

    @AfterTest
    fun cleanup() = ModRegistry.clear()

    @Test
    fun `example mods load, patch data and provide resources`() {
        if (!haveAssets()) return
        // The runtime mods directory is git-ignored; the worked examples live in examples/mods.
        ModRegistry.load(assetsDir, "examples/mods")

        // demo_resources loads before sample.map, demo.data after it.
        assertEquals(
            listOf("demo.resources", "sample.map", "demo.data"),
            ModRegistry.mods.map { it.manifest.id },
        )
        assertTrue(ModRegistry.problems.isEmpty(), "no load problems: ${ModRegistry.problems}")

        // The map mod contributes a document of its own.
        assertTrue(AssetLoader.list(assetsDir, "maps").contains("sample"))
        assertEquals(listOf("sample"), ModRegistry.modDocuments(assetsDir, "maps"))
        val modMap = AssetLoader.map(assetsDir, "sample/mod_arena.json")
        assertNotNull(modMap, "the mod's map document must be readable through the data layer")
        assertEquals("Mod Arena", modMap.name)

        // The mod's campaign document is loadable through the data layer, but the generation
        // switch keeps the built-in generations only (mod maps are reached from the Mod Maps
        // button instead).
        val campaigns = cn.enaium.battalion.data.GameLoader.findCampaigns(assetsDir)
        assertEquals(
            cn.enaium.battalion.data.GameLoader.CAMPAIGN_IDS,
            campaigns.map { it.id },
            "mod campaigns stay out of the switch row",
        )
        val modCampaign = cn.enaium.battalion.data.GameLoader.findCampaign(assetsDir, "sample")
        assertEquals("sample/mod_arena", modCampaign?.missions?.single()?.mapId)

        // ... and the map is playable: a mod map is loaded like any skirmish map.
        val game = cn.enaium.battalion.data.GameLoader.loadSkirmish(assetsDir, "sample/mod_arena")
        assertEquals("Mod Arena", game.map.name)
        assertTrue(game.units.isNotEmpty(), "the mod map spawns its units")

        // The data mod patches a base unit's description and adds a unit of its own. (Renaming
        // a base unit is possible too, but the game looks units up by name, so a mod that does
        // it changes every map referencing that unit — the demo deliberately does not.)
        val patched = AssetLoader.unit(assetsDir, "scorpion-tank")
        assertEquals("Scorpion Tank", patched?.name, "the unit keeps its lookups-safe name")
        assertTrue(patched!!.description.contains("demo data mod"), "patched description: ${patched.description}")
        val added = AssetLoader.unit(assetsDir, "crystal-scorpion")
        assertEquals("Crystal Scorpion", added?.name)

        // Resources: the mod's own icon resolves, and its medalBackground overrides the base one.
        val resources = assertNotNull(ModRegistry.resources)
        assertTrue(resources.imagePath("ui/icon")!!.contains("demo_resources"), "mod icon")
        assertTrue(resources.imagePath("medalBackground")!!.contains("demo_resources"),
            "mod image overrides the base sprite of the same id")
        assertTrue(resources.soundPath("ui/demo_click")!!.contains("demo_resources"), "mod sound")
        assertEquals("demo.resources", resources.providerOf("medalBackground")?.manifest?.id)
    }
}

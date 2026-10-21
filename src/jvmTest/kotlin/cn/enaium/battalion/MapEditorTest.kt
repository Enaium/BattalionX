package cn.enaium.battalion

import cn.enaium.battalion.data.AssetLoader
import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.editor.EditorItem
import cn.enaium.battalion.editor.EditorModel
import cn.enaium.battalion.editor.EditorRules
import cn.enaium.battalion.mod.ModRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the map editor's model: opening a level, the original editor's terrain rules (the 3×3
 * identity match and the 5×5 re-join), painting every layer, and saving — including the case
 * where a mod owns the document, which must be written back as the mod's JSON Patch rather than
 * as a game document.
 */
class MapEditorTest {

    private val assetsDir = "src/commonMain/resources"
    private fun haveAssets(): Boolean = File("$assetsDir/data").isDirectory
    private val tmp: File = File(System.getProperty("java.io.tmpdir"), "battalionx-editor-test")
        .apply { deleteRecursively(); mkdirs() }

    @AfterTest
    fun cleanup() {
        ModRegistry.clear()
        AssetLoader.clearCache()
        tmp.deleteRecursively()
    }

    @Test
    fun `a game map opens with its players and its own mode`() {
        if (!haveAssets()) return
        val editor = EditorModel(assetsDir)
        assertTrue(editor.open("ep3/Mission_4"), "a game map opens")
        assertEquals(11, editor.cols())
        assertEquals(10, editor.rows())
        assertEquals("wm_mode", editor.style)
        assertEquals(listOf(1, 2), editor.countPlayers(), "the mission's two teams are found")
        assertNotNull(editor.nameAt(0, 0, 0), "the ground layer is populated")
    }

    @Test
    fun `the terrain rules re-join a coastline like the original`() {
        if (!haveAssets()) return
        val editor = EditorModel(assetsDir)
        assertTrue(editor.newMap("Rules", 10, 10))

        // A map filled with sea matches the all-sea rule of the original sea.xml table: the four
        // directional "no land around" strips (sea_0_nw/ne/sw/se).
        assertTrue(editor.fill("Sea"))
        val allSea = editor.entryAt(5, 5, 0)
        assertNotNull(allSea)
        assertEquals("Sea", allSea.name)
        assertEquals(
            listOf("sea_0_nw", "sea_0_ne", "sea_0_sw", "sea_0_se"),
            allSea.sprites.composite,
            "the first matching sea rule is the all-sea rule",
        )

        // Painting one land tile must re-run the rules over the 5×5 area: the sea tile east of it
        // now borders land and wears a coast piece instead of the open-sea one.
        val eastBefore = editor.entryAt(6, 5, 0)
        assertTrue(editor.place(5, 5, EditorItem("Plains", 0, 0, "plains_v1")))
        assertEquals("Plains", editor.nameAt(5, 5))
        val eastAfter = editor.entryAt(6, 5, 0)
        assertNotNull(eastAfter)
        assertEquals("Sea", eastAfter.name)
        assertNotEquals(eastBefore!!.sprites, eastAfter.sprites, "the neighbour's coast art changed")
    }

    @Test
    fun `every rule terrain fills with real art`() {
        if (!haveAssets()) return
        val rules = EditorRules.load(assetsDir)
        assertTrue(rules.terrains.size >= 18, "the rule document lists every terrain")
        val editor = EditorModel(assetsDir)
        for (terrain in rules.terrains) {
            assertTrue(editor.newMap("Coverage", 10, 10))
            assertTrue(editor.fill(terrain.name), "${terrain.name} fills")
            val entry = editor.entryAt(5, 5, 0)
            assertNotNull(entry, "${terrain.name} keeps a terrain entry")
            assertTrue(
                entry.sprites.single.isNotEmpty() || entry.sprites.variants.isNotEmpty() ||
                    entry.sprites.composite.size == 4,
                "${terrain.name} paints real art: ${entry.sprites}",
            )
        }
    }

    @Test
    fun `painting every layer survives a save and a reload`() {
        if (!haveAssets()) return
        val editor = EditorModel(assetsDir)
        assertTrue(editor.open("ep3/Mission_1"))
        val script = editor.doc!!.script

        assertTrue(editor.placeUnit(2, 2, "Scorpion Tank", 2), "a unit is placed")
        assertTrue(editor.placeBuilding(3, 3, "Factory", 0), "a neutral building is placed")
        assertTrue(editor.place(4, 4, EditorItem("Forest", 0, 0, "forest_v2")), "terrain is placed")
        assertEquals(2, editor.entryAt(2, 2, 2)?.team)
        assertEquals(0, editor.entryAt(3, 3, 1)?.team)
        assertEquals("Forest", editor.nameAt(4, 4))

        val out = File(tmp, "mission_1.json")
        assertTrue(editor.saveAs(out.absolutePath), "save writes the document")
        assertTrue(out.exists() && out.length() > 0)

        val reloaded = EditorModel(assetsDir)
        assertTrue(reloaded.openFile(out.absolutePath), "the saved document parses as a map")
        assertEquals("Scorpion Tank", reloaded.entryAt(2, 2, 2)?.name)
        assertEquals(2, reloaded.entryAt(2, 2, 2)?.team)
        assertEquals("Factory", reloaded.entryAt(3, 3, 1)?.name)
        assertEquals("Forest", reloaded.nameAt(4, 4))
        assertEquals(script, reloaded.doc!!.script, "the map script survives the round trip")
        assertTrue(reloaded.countPlayers().contains(2), "the placed team is counted")
        assertEquals(
            AssetLoader.map(assetsDir, "ep3/Mission_1.json")!!.tiles.size,
            reloaded.doc!!.tiles.size,
            "untouched tiles survive the round trip",
        )
    }

    @Test
    fun `the panels follow the game editor's unit and building lists`() {
        if (!haveAssets()) return
        val editor = EditorModel(assetsDir)
        assertTrue(editor.newMap("Lists", 10, 10))

        // Order and restrictions come from the game's xml/editor/units.xml (English build),
        // not from an alphabetical registry dump.
        val units = editor.unitItems(1)
        assertEquals("Warmachine", units.first().name, "the War Machine opens the list")
        assertEquals(
            listOf("Spider Tank", "Lancer Tank"),
            editor.rules.units.filter { it.restricted }.map { it.name },
            "the game marks the Spider Tank and the Lancer Tank as premium-only",
        )
        assertEquals(
            listOf(
                "Warmachine", "Strike Commando", "Heavy Commando", "Flak Tank",
                "Scorpion Tank", "Stealth Tank", "Spider Tank", "Lancer Tank",
                "Annihilator Tank", "Jammer Truck", "Mortar Truck", "Rocket Truck",
                "Raptor Fighter", "Condor Bomber", "Intrepid", "Hunter Support",
                "Corvette Fighter", "U-Boat", "Battlecruiser", "Blockade", "Turret",
                "Vulture Drone",
            ),
            units.map { it.name }.take(22),
            "the list keeps the original order",
        )
        // Units the editor list does not name (mod units, transports) stay placeable.
        assertTrue(units.any { it.name == "Albatross Transport" }, "unlisted units are appended")

        // The game gates Spider/Lancer behind Premium; the offline port has no accounts and
        // treats the author as unlocked, so they are paintable (unlike the Edit build, which
        // blocks them for everyone).
        assertTrue(editor.place(1, 1, units.first { it.name == "Spider Tank" }))
        assertEquals("Spider Tank", editor.entryAt(1, 1, 2)?.name)

        // The War Machine is a blitz-mode unit; classic maps do not offer it.
        assertTrue(editor.newMap("Classic", 10, 10, classicMode = true))
        assertEquals("classic_mode", editor.style)
        assertTrue(editor.unitItems(1).none { it.name == "Warmachine" })

        // Building order from xml/editor/buildings.xml.
        assertTrue(editor.newMap("Buildings", 10, 10))
        assertEquals(
            listOf(
                "Command Center", "Factory", "Ground Control", "Air Control",
                "Sea Control", "Oil Refinery", "Advanced Oil Refinery", "Oil Rig",
            ),
            editor.buildingItems(1).map { it.name }.take(8),
        )
    }

    @Test
    fun `an authored map starts a match from the editor's document`() {
        if (!haveAssets()) return
        val editor = EditorModel(assetsDir)
        assertTrue(editor.newMap("Editor Test", 10, 10))
        assertTrue(editor.placeUnit(1, 1, "Scorpion Tank", 1))
        assertTrue(editor.placeUnit(8, 8, "Scorpion Tank", 2))
        assertTrue(editor.placeBuilding(2, 1, "Command Center", 1))
        assertTrue(editor.placeBuilding(7, 8, "Command Center", 2))

        // The editor's Test button hands this exact document to the game loader, with the
        // original editor's test settings: no starting resources, medium AI, editor colours.
        val game = GameLoader.loadEditorTest(assetsDir, editor.doc!!)
        assertEquals(10, game.map.width)
        assertEquals(2, game.mapSettings.teams.size)
        assertEquals(0, game.mapSettings.resources)
        assertEquals(listOf("Player 1", "Testo-tron (AI)"), game.mapSettings.teams.map { it.name })
        assertEquals(
            listOf(0xFFFF0000u, 0xFF0000FFu),
            game.mapSettings.teams.map { it.color },
            "the editor's red/blue team colours",
        )
        game.startGame()
        assertEquals(2, game.units.size, "both authored vehicles deploy")
    }

    @Test
    fun `saving a mod map rewrites the mod's patch`() {
        if (!haveAssets()) return
        // The examples live in examples/mods (the runtime mods/ directory is git-ignored).
        ModRegistry.load(assetsDir, "examples/mods")
        val editor = EditorModel(assetsDir)
        assertTrue(editor.open("sample/mod_arena"), "the mod's map opens")
        assertTrue(editor.targetIsModPatch, "the save target is the mod's patch")
        val patchFile = File(editor.targetPath!!)
        assertTrue(patchFile.path.contains("mods/sample_map"), "patch path: ${patchFile.path}")

        // The patch is shipped content, so the test restores it after checking the write.
        val original = patchFile.readText()
        try {
            assertTrue(editor.place(0, 0, EditorItem("Plains", 0, 0, "plains_v1")))
            assertTrue(editor.save(), "the patch is written")

            val patchText = patchFile.readText()
            assertTrue(patchText.trimStart().startsWith("["), "the mod file stays a JSON Patch array")
            assertTrue(patchText.contains("\"op\": \"add\""), "root add op kept")

            // A fresh load through the data layer sees the edit and still reads the mod's map.
            ModRegistry.clear()
            AssetLoader.clearCache()
            ModRegistry.load(assetsDir, "examples/mods")
            val reloaded = AssetLoader.map(assetsDir, "sample/mod_arena.json")
            assertNotNull(reloaded, "the patched map still loads")
            val firstLayer = reloaded.tiles.firstOrNull { it.c == 0 && it.r == 0 }?.layers
                ?.firstOrNull { it.depth == 0 }
            assertNotNull(firstLayer)
            assertEquals("Plains", reloaded.palette[firstLayer.reference].name,
                "the edit round-trips through the mod layer")
            assertEquals("Mod Arena", reloaded.name, "the rest of the document survives")
        } finally {
            patchFile.writeText(original)
            ModRegistry.clear()
            AssetLoader.clearCache()
        }
    }
}

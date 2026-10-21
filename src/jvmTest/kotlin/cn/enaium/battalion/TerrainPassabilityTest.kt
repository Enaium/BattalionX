package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.TileManager
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitData
import cn.enaium.battalion.data.UnitManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards walkability of the game's terrains: impassable tiles must NOT become walkable.
 *
 * This pins the ruleset shipped in `src/commonMain/resources/data/` (a faithful JSON copy of the original SWF
 * `terrain_xml` + `terrainModifiers_xml`), and — critically — the registry import order in
 * [GameLoader.loadAssets]: modifier definitions must be imported BEFORE the terrains/units
 * that reference them, otherwise the FIRST `loadAssets` call registers every terrain and unit
 * WITHOUT its modifiers (`ModifierManager.get` returns null), so Sea/Mountain/Ore lose their
 * `movementCost` block and the whole map becomes walkable.
 */
class TerrainPassabilityTest {

    private fun haveAssets(): Boolean = File("src/commonMain/resources/data").isDirectory

    private fun cost(unitName: String, terrainName: String): Double {
        val data = UnitManager.get(unitName)!!
        return Tile(0, 0, TileManager.get(terrainName)).getMovementCost(Unit(data))
    }

    private fun blocked(unitName: String, terrainName: String): Boolean =
        cost(unitName, terrainName) == Double.POSITIVE_INFINITY

    @Test
    fun `a single loadAssets attaches modifiers to the registries`() {
        if (!haveAssets()) return
        GameLoader.loadAssets("src/commonMain/resources")
        // The order-bug symptom: registry terrain without its Water modifier.
        assertTrue(
            TileManager.get("Sea").modifiers.any { it.name == "Water" },
            "Sea must carry the Water modifier after one loadAssets (modifiers import before terrain)",
        )
        val scorpion = UnitManager.get("Scorpion Tank")!!
        assertEquals("tracked", UnitData.movementTypes[scorpion.movementType])
    }

    @Test
    fun `ground tanks cannot enter water-based or impassable tiles`() {
        if (!haveAssets()) return
        GameLoader.loadAssets("src/commonMain/resources")
        // Water blocks ground: sea, reef, rock formation, archipelago, oil rig.
        for (t in listOf("Sea", "Reef", "Rock Formation", "Archipelago", "Oil Rig")) {
            assertTrue(blocked("Scorpion Tank", t), "tank must not enter $t")
        }
        // Mountain's Precipitous rule blocks wheeled/tracked (only commandos pass at cost 2).
        assertTrue(blocked("Scorpion Tank", "Mountain"), "tracked tank blocked on Mountain (Precipitous)")
        // Impassable blocks everyone.
        assertTrue(blocked("Scorpion Tank", "Volcano"), "tank blocked on Volcano")
    }

    @Test
    fun `ground tanks walk ordinary terrain`() {
        if (!haveAssets()) return
        GameLoader.loadAssets("src/commonMain/resources")
        for (t in listOf("Plains", "Road", "Canyon", "Wasteland", "Shore", "Bridge", "High Bridge", "Factory Base")) {
            assertTrue(!blocked("Scorpion Tank", t), "tank must enter $t")
        }
        // Uneven (wheeled) does not slow tracked; Rugged gives tracked cost 2.
        assertEquals(1.0, cost("Scorpion Tank", "Plains"))
        assertEquals(2.0, cost("Scorpion Tank", "Forest"))
    }

    @Test
    fun `sea units cannot enter ground or extractable tiles`() {
        if (!haveAssets()) return
        GameLoader.loadAssets("src/commonMain/resources")
        // Ground blocks sea units.
        for (t in listOf("Plains", "Road", "Desert", "Canyon")) {
            assertTrue(blocked("Corvette Fighter", t), "ship must not enter $t")
        }
        // Extractable (ore deposits) blocks sea units.
        assertTrue(blocked("Corvette Fighter", "Ore Deposit"), "ship blocked on Ore Deposit")
        assertTrue(blocked("Corvette Fighter", "Enriched Ore Deposit"), "ship blocked on Enriched Ore Deposit")
        // Sea itself and high bridges are navigable.
        assertTrue(!blocked("Corvette Fighter", "Sea"), "ship sails on Sea")
        assertTrue(!blocked("Corvette Fighter", "High Bridge"), "ship under High Bridge")
        // Shore's Shallow rule blocks Massive Hull ships — the arena Corvette carries it.
        assertTrue(blocked("Corvette Fighter", "Shore"), "Massive Hull ship blocked on Shore (Shallow)")
        // A small hull (U-Boat) beaches freely.
        assertTrue(!blocked("U-Boat", "Shore"), "small-hull ship beaches on Shore")
    }

    @Test
    fun `air units are only blocked by impassable`() {
        if (!haveAssets()) return
        GameLoader.loadAssets("src/commonMain/resources")
        assertTrue(blocked("Raptor Fighter", "Volcano"), "air unit blocked on Volcano (Impassable)")
        assertTrue(!blocked("Raptor Fighter", "Sea"), "air unit flies over Sea")
        assertTrue(!blocked("Raptor Fighter", "Plains"), "air unit flies over Plains")
        assertTrue(!blocked("Raptor Fighter", "Ore Deposit"), "air unit flies over Ore Deposit")
    }

    @Test
    fun `commandos are blocked by barbed desert but pass precipitous mountains`() {
        if (!haveAssets()) return
        GameLoader.loadAssets("src/commonMain/resources")
        // Barbed: movementType feet -> infinity (desert is barbed).
        assertTrue(blocked("Strike Commando", "Desert"), "commando blocked on Desert (Barbed)")
        // Precipitous: feet pays 2 instead of being blocked.
        assertEquals(2.0, cost("Strike Commando", "Mountain"))
        assertTrue(blocked("Strike Commando", "Sea"), "commando (ground) blocked on Sea")
    }

    @Test
    fun `mission one keeps its water and mountains impassable for tanks`() {
        if (!haveAssets()) return
        val game = GameLoader.loadCampaignMission("src/commonMain/resources", 0)
        val tank = Unit(UnitManager.get("Scorpion Tank")!!)
        val counts = mutableMapOf<String, Pair<Int, Int>>()
        for (row in game.map.generateTileMap()) {
            for (tile in row) {
                val inf = tile.getMovementCost(tank) == Double.POSITIVE_INFINITY
                val cur = counts.getOrPut(tile.terrain.name) { 0 to 0 }
                counts[tile.terrain.name] = if (inf) cur.first to cur.second + 1 else cur.first + 1 to cur.second
            }
        }
        val sea = counts["Sea"] ?: error("mission has no Sea tiles?")
        assertEquals(0, sea.first, "no tank may stand on Sea")
        assertTrue(sea.second > 0, "mission has Sea tiles")
        val mountain = counts["Mountain"] ?: 0 to 0
        assertEquals(0, mountain.first, "no tank may stand on Mountain (Precipitous)")
        val plains = counts["Plains"] ?: error("mission has no Plains tiles?")
        assertTrue(plains.first > 0, "tanks must reach Plains")
    }
}

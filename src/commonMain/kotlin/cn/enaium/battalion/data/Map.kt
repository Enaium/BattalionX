package cn.enaium.battalion.data

import cn.enaium.battalion.game.Controller
import cn.enaium.battalion.util.XmlNode

/**
 * A parsed map document (a JSON file under `assets/data/maps/` holding an XML-shaped `<map>` tree).
 *
 * The map XML encodes the terrain grid, structures and starting units through a *palette*:
 * each `<entry reference="N">` maps a reference number to a [TileSwatch] (a terrain or unit
 * name plus an image code). Tiles then reference palette entries by number:
 * ```
 * <map style="classic_mode" special="true">
 *   <name>Mission 1</name>
 *   <description>...</description>
 *   <author>...</author>
 *   <rows>12</rows>
 *   <cols>18</cols>
 *   <palette>
 *     <entry reference="0"><name>P1 Warmachine</name><imageCode>@(1;warmachine.png)</imageCode></entry>
 *   </palette>
 *   <tile r="3" c="4">
 *     <layer depth="0" reference="5"/>   <!-- terrain -->
 *     <layer depth="1" reference="9"/>   <!-- structure -->
 *     <layer depth="2" reference="1"/>   <!-- unit -->
 *   </tile>
 *   <script>...</script>
 * </map>
 * ```
 *
 * A unit swatch whose team prefix is "P1".."P4" is owned by that player; team index 0 means
 * neutral. `generateUnits` and `generateStructures` materialize these into live objects.
 */
class GameMap(
    private val xml: XmlNode,
    val mapURL: String,
    val mapName: String,
) {
    val name: String
    val description: String
    val author: String
    val width: Int
    val height: Int
    val isClassic: Boolean
    val isSpecial: Boolean

    val palette: MutableList<TileSwatch> = mutableListOf()

    /** data[row][col] = palette reference of the terrain layer. */
    private val data: Array<Array<Int>>
    private val units = mutableListOf<UnitHelper>()
    private val structures = mutableListOf<UnitHelper>()

    private var scriptNode: XmlNode? = null

    var hasAirControl: Boolean = false
        private set
    var hasGroundControl: Boolean = false
        private set
    var hasSeaControl: Boolean = false
        private set

    class UnitHelper(val swatch: Int, val x: Int, val y: Int)

    init {
        isClassic = xml.attr("style").lowercase() == "classic_mode"
        isSpecial = xml.attr("special").lowercase() == "true"
        name = xml.child("name")?.text ?: ""
        description = xml.child("description")?.text ?: ""
        author = xml.child("author")?.text ?: ""
        height = xml.child("rows")?.text?.toIntOrNull() ?: 0
        width = xml.child("cols")?.text?.toIntOrNull() ?: 0

        // Palette entries.
        xml.child("palette")?.children("entry")?.forEach { entry ->
            val index = entry.attr("reference").toIntOrNull() ?: return@forEach
            val swatch = TileSwatch(stripPlayers(entry.child("name")?.text ?: entry.attr("name")), entry.child("imageCode")?.text ?: entry.attr("imageCode"))
            while (palette.size <= index) palette.add(TileSwatch("", ""))
            palette[index] = swatch
        }
        // Manual swatches for ore deposit variants (the game adds these itself).
        addManualSwatch("Ore Deposit", "?(ore_deposit_v1.png;ore_deposit_v2.png)")
        addManualSwatch("Depleted Ore Deposit", "?(depleted_ore_deposit_v1.png;depleted_ore_deposit_v2.png)")
        addManualSwatch("Enriched Ore Deposit", "?(enriched_ore_deposit_v1.png;enriched_ore_deposit_v2.png)")

        data = Array(height) { Array(width) { 0 } }

        for (tileNode in xml.children("tile")) {
            val ty = tileNode.attr("r").toIntOrNull() ?: continue
            val tx = tileNode.attr("c").toIntOrNull() ?: continue
            for (layer in tileNode.children("layer")) {
                val depth = layer.attr("depth").toIntOrNull() ?: 0
                val reference = layer.attr("reference").toIntOrNull() ?: 0
                when (depth) {
                    0 -> data[ty][tx] = reference
                    1 -> structures.add(UnitHelper(reference, tx, ty))
                    2 -> units.add(UnitHelper(reference, tx, ty))
                }
            }
        }

        scriptNode = xml.children("script").firstOrNull()
    }

    fun getScript(): XmlNode? = scriptNode

    /** Whether the given palette reference is owned (team index > 0). */
    fun ownedBy(col: Int, row: Int): Int {
        val ref = data[row][col]
        return if (ref in palette.indices) palette[ref].getTeam() else 0
    }

    /** The terrain swatch name at (col, row), or empty when out of range. */
    fun terrainNameAt(col: Int, row: Int): String {
        if (row !in data.indices || col !in data[0].indices) return ""
        val ref = data[row][col]
        return if (ref in palette.indices) palette[ref].name else ""
    }

    fun hasStructures(): Boolean = structures.isNotEmpty()

    /**
     * Builds the grid of [Tile]s from the terrain layer, connecting each tile to its four
     * orthogonal neighbours so the A* search can traverse the map.
     */
    fun generateTileMap(): Array<Array<Tile>> {
        val tiles = Array(height) { Array(width) { Tile(0, 0, Terrain("", 1.0, "", "minimap_sea", "undefined")) } }
        for (row in 0 until height) {
            for (col in 0 until width) {
                val ref = data[row][col]
                val swatch = palette[ref]
                val terra = TileManager.get(swatch.name)
                val tile = Tile(col, row, terra)
                // Remember the tile's terrain sprite(s) so the renderer can draw the exact image(s)
                // this palette entry references. For normal tiles this is one key; for `+` swatches
                // (2×2 composites) it is four keys (TL/TR/BL/BR); for `?` swatches it is the set of
                // interchangeable variant textures.
                tile.spriteKind = swatch.kind
                tile.spriteKeys = swatch.imageFiles.map { it.substringBeforeLast('.') }
                // Pick a stable per-tile variant so every such tile is aesthetically distinct
                // yet identical across redraws / look-ups (matches the original's random choice).
                if (tile.spriteKind == TileSwatch.Kind.VARIANT && tile.spriteKeys.size > 1) {
                    // Deterministic pseudo-random spread over the tile's position.
                    val seed = (row * 73856093) xor (col * 19349663)
                    tile.variantIndex = (seed and 0x7fffffff) % tile.spriteKeys.size
                }
                tiles[row][col] = tile
            }
        }
        for (row in 0 until height) {
            for (col in 0 until width) {
                if (col > 0) tiles[row][col].addConnection(tiles[row][col - 1])
                if (col < width - 1) tiles[row][col].addConnection(tiles[row][col + 1])
                if (row > 0) tiles[row][col].addConnection(tiles[row - 1][col])
                if (row < height - 1) tiles[row][col].addConnection(tiles[row + 1][col])
            }
        }
        return tiles
    }

    /**
     * Materializes the starting units described by the unit layer. Each unit swatch must be
     * owned by a player (team index 1..N); its name is looked up in the [UnitManager].
     */
    fun generateUnits(controllers: List<Controller>): List<Unit> {
        val result = mutableListOf<Unit>()
        for (unitHelper in units) {
            if (unitHelper.swatch !in palette.indices) continue
            val p = palette[unitHelper.swatch]
            val team = p.getTeam()
            if (team > 0 && team - 1 < controllers.size) {
                val ud = UnitManager.get(p.name) ?: continue
                val u = Unit(ud, unitHelper.x, unitHelper.y, controllers[team - 1])
                // Default facing faces the unit toward the centre of the map, matching the
                // original `Map.generateUnits` which plays "right_idle" on the left half of the
                // board and "left_idle" on the right half. Units on the left point east (1),
                // units on the right point west (3).
                u.facing = if (unitHelper.x < width / 2) 1 else 3
                u.animFacing = u.facing
                result.add(u)
            }
        }
        return result
    }

    /** Counts the starting units owned by player 1, by unit type. */
    fun getNums(): Map<String, Int> {
        val nums = mutableMapOf("air" to 0, "ground" to 0, "sea" to 0)
        for (unitHelper in units) {
            if (unitHelper.swatch !in palette.indices) continue
            val p = palette[unitHelper.swatch]
            if (p.getTeam() == 1) {
                val ud = UnitManager.get(p.name) ?: continue
                when {
                    ud.isAir() -> nums["air"] = nums["air"]!! + 1
                    ud.isGround() -> nums["ground"] = nums["ground"]!! + 1
                    ud.isSea() -> nums["sea"] = nums["sea"]!! + 1
                }
            }
        }
        return nums
    }

    /**
     * Places structures on the tile map. Structures with an owning team are controlled by that
     * team's controller; "Air/Ground/Sea Control" structures set the corresponding flag.
     */
    fun generateStructures(tileMap: Array<Array<Tile>>, controllers: List<Controller>): List<Tile> {
        val structure = mutableListOf<Tile>()
        for (structHelper in structures) {
            if (structHelper.swatch !in palette.indices) continue
            val tile = tileMap[structHelper.y][structHelper.x]
            val p = palette[structHelper.swatch]
            var controller: Controller? = null
            if (p.getTeam() > 0 && p.getTeam() - 1 < controllers.size) {
                controller = controllers[p.getTeam() - 1]
            }
            val name = p.name
            if (name == "Air Control") hasAirControl = true
            if (name == "Ground Control") hasGroundControl = true
            if (name == "Sea Control") hasSeaControl = true
            val upgrade = TileManager.get(name)
            tile.upgrade(upgrade, controller)
            structure.add(tile)
        }
        return structure
    }

    /** Rewrites the terrain reference at [x],[y] to match a tile that changed name. */
    fun updateReference(x: Int, y: Int, newTileName: String) {
        var reference = -1
        for (i in palette.indices) {
            if (palette[i].name == newTileName) {
                reference = i
                break
            }
        }
        require(reference != -1) { "Map: unknown palette '$newTileName'" }
        data[y][x] = reference
    }

    /** Adds a palette swatch if one with the same name does not already exist. */
    private fun addManualSwatch(newTileName: String, imageCode: String) {
        if (palette.any { it.name == newTileName }) return
        palette.add(TileSwatch(newTileName, imageCode))
    }

    /** Strips "P1 "/"P2 "/"P3 "/"P4 "/"Neutral " prefixes from a name. */
    private fun stripPlayers(aName: String): String {
        var ret = aName.replace("P1 ", "")
        ret = ret.replace("P2 ", "")
        ret = ret.replace("P3 ", "")
        ret = ret.replace("P4 ", "")
        return ret.replace("Neutral ", "")
    }
}
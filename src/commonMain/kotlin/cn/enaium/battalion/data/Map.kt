package cn.enaium.battalion.data

import cn.enaium.battalion.game.Controller

/**
 * A parsed map document (`maps/<Map>.json`, see [MapConfig]).
 *
 * The map encodes the terrain grid, structures and starting units through a *palette*: each
 * entry maps a reference number to a [TileSwatch] (a terrain or unit name plus its sprite
 * set). Tiles then reference palette entries by number, per layer (0 = terrain, 1 = structure,
 * 2 = unit).
 *
 * A unit swatch with team 1..4 is owned by that player; team 0 means neutral.
 * [generateUnits] and [generateStructures] materialize these into live objects.
 */
class GameMap(
    private val cfg: MapConfig,
    val mapURL: String,
    val mapName: String,
) {
    val name: String = cfg.name
    val description: String = cfg.description
    val author: String = cfg.author
    val width: Int = cfg.cols
    val height: Int = cfg.rows
    val isClassic: Boolean = cfg.classicMode
    val isSpecial: Boolean = cfg.special

    /** The map's scripted events and authored state. */
    val script: MapScriptConfig = cfg.script

    val palette: MutableList<TileSwatch> = mutableListOf()

    /** data[row][col] = palette reference of the terrain layer. */
    private val data: Array<IntArray>
    private val units = mutableListOf<UnitHelper>()
    private val structures = mutableListOf<UnitHelper>()

    var hasAirControl: Boolean = false
        private set
    var hasGroundControl: Boolean = false
        private set
    var hasSeaControl: Boolean = false
        private set

    class UnitHelper(val swatch: Int, val x: Int, val y: Int)

    init {
        for (entry in cfg.palette) {
            palette.add(TileSwatch(entry.name, entry.team, entry.sprites.toTerrainSprites()))
        }
        // Manual swatches for ore deposit variants (the game adds these itself).
        addManualSwatch("Ore Deposit", "?(ore_deposit_v1.png;ore_deposit_v2.png)")
        addManualSwatch("Depleted Ore Deposit", "?(depleted_ore_deposit_v1.png;depleted_ore_deposit_v2.png)")
        addManualSwatch("Enriched Ore Deposit", "?(enriched_ore_deposit_v1.png;enriched_ore_deposit_v2.png)")

        data = Array(height) { IntArray(width) }
        for (tile in cfg.tiles) {
            if (tile.r !in 0 until height || tile.c !in 0 until width) continue
            for (layer in tile.layers) {
                when (layer.depth) {
                    0 -> data[tile.r][tile.c] = layer.reference
                    1 -> structures.add(UnitHelper(layer.reference, tile.c, tile.r))
                    2 -> units.add(UnitHelper(layer.reference, tile.c, tile.r))
                }
            }
        }
    }

    /** Whether the given palette reference is owned (team index > 0). */
    fun ownedBy(col: Int, row: Int): Int {
        val ref = data[row][col]
        return if (ref in palette.indices) palette[ref].ownerTeam() else 0
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
                // Remember the tile's terrain sprite(s) so the renderer can draw the exact
                // images this palette entry references: one key for SINGLE tiles, four for
                // COMPOSITE (2×2), a variant set for VARIANT.
                tile.spriteKind = swatch.kind
                tile.spriteKeys = swatch.imageFiles
                // Pick a stable per-tile variant so every such tile is aesthetically distinct
                // yet identical across redraws / look-ups.
                if (tile.spriteKind == TerrainSprites.Kind.VARIANT && tile.spriteKeys.size > 1) {
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
            val team = p.ownerTeam()
            if (team > 0 && team - 1 < controllers.size) {
                val ud = UnitManager.get(p.name) ?: continue
                val u = Unit(ud, unitHelper.x, unitHelper.y, controllers[team - 1])
                // Default facing faces the unit toward the centre of the map: units on the
                // left point east (1), units on the right point west (3).
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
            if (p.ownerTeam() == 1) {
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
            if (p.ownerTeam() > 0 && p.ownerTeam() - 1 < controllers.size) {
                controller = controllers[p.ownerTeam() - 1]
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
        palette.add(TileSwatch(imageCode)) // legacy mini-language for the built-in defaults
    }
}

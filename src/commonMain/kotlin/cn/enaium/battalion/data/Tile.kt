package cn.enaium.battalion.data

import cn.enaium.battalion.game.Controller
import cn.enaium.battalion.game.GameManager

/**
 * A single tile of the map. Extends [Node] so it participates in A* pathfinding.
 *
 * A tile always has a [Terrain]. It may additionally hold a *structure* (a capturable
 * building / factory / ore deposit) and a [Unit] standing on it. Structures are owned by a
 * [Controller]; a tile's controller is the owner of its structure (null = neutral).
 *
 * Tiles also track *capture* state: an enemy Capture unit standing on a structure reduces
 * the tile's [chunks] each turn until it reaches zero, at which point ownership flips.
 */
class Tile(
    val tileX: Int,
    val tileY: Int,
    var terrain: Terrain,
) : Node() {

    var baseTerrain: Terrain = terrain
    var controller: Controller? = null
    var unit: Unit? = null
        set(value) {
            require(unit == null || value == null || unit === value) {
                "Tile: unit already exists on this tile"
            }
            field = value
        }
    var capturer: Unit? = null
        set(value) {
            field = value
            // Clearing the capturer also resets capture progress.
            if (value == null) chunks = GameManager.TILE_CHUNKS
        }
    var hasStructure: Boolean = false
    var resources: Int = 0
    var chunks: Int = GameManager.TILE_CHUNKS

    /**
     * The sprite key(s) of this tile's terrain image, resolved from the map swatch's image code.
     *
     * - **Single key** (`size == 1`): a normal tile; the renderer draws the one texture.
     * - **Four keys** (`size == 4`) + [spriteKind] of [TileSwatch.Kind.COMPOSITE]: the `+`
     *   swatch joins its four sub-images (TL / TR / BL / BR) into one 2×2 tile to blend
     *   coastline / canyon transition edges. Use [spriteKind] to tell this apart from VARIANT.
     * - **Several keys** (`size >= 2`) + [TileSwatch.Kind.VARIANT]: the `?` swatch shows ONE
     *   of its interchangeable textures (forest/plains/hills/reef…). The tile picks one key
     *   and draws it at full tile size, NOT as a 2×2 grid.
     *
     * Multiple map swatches can share a terrain *name* (many distinct road / sea variants) while
     * having different images, so the renderer uses these keys rather than the name.
     */
    var spriteKeys: List<String> = emptyList()

    /** How the [spriteKeys] should be assembled onto the tile (see [TileSwatch.kind]). */
    var spriteKind: TileSwatch.Kind = TileSwatch.Kind.SINGLE

    /**
     * For [TileSwatch.Kind.VARIANT] tiles: which of the [spriteKeys] this tile displays.
     * The original picks one variant per tile at load time; we derive a deterministic
     * per-tile index (position-based) so it stays stable across frames and look-ups.
     */
    var variantIndex: Int = 0

    companion object {
        // A* flags
        const val FLAG_IGNORE_LAST_NODE = 1
        const val FLAG_FORCE_COST_1 = 2
        const val FLAG_IGNORE_UNITS = 4
        const val FLAG_UNUSED = 8
    }

    val x: Int get() = tileX
    val y: Int get() = tileY

    fun isSeaBased(): Boolean = terrain.hasModifier("Water")

    fun isStructure(): Boolean = hasStructure

    /** Movement cost for [aUnit] to enter this tile, derived from the terrain's modifiers. */
    fun getMovementCost(aUnit: Unit): Double =
        terrain.applyModifiers("movementCost", aUnit, terrain.cost)

    fun canBeMovedOnBy(aUnit: Unit): Boolean =
        getMovementCost(aUnit) != Double.POSITIVE_INFINITY

    /**
     * A* cost to enter this node. Enforces that occupied tiles are blocked (unless a flag
     * allows it), then delegates to the terrain's movement cost.
     *
     * @param movingUnit the unit trying to move here
     * @param flags      A* flags (see [FLAG_IGNORE_LAST_NODE] etc.)
     * @param isLastNode whether this is the destination of the search
     */
    override fun getCost(movingUnit: Unit?, flags: Int, isLastNode: Boolean): Double {
        if (flags and FLAG_FORCE_COST_1 != 0) return 1.0

        val ignoreUnitsOnLastNode = flags and FLAG_IGNORE_LAST_NODE != 0
        val occupyingUnit = unit
        if (occupyingUnit != null && occupyingUnit !== movingUnit && movingUnit != null) {
            val ignoreUnits = flags and FLAG_IGNORE_UNITS != 0
            if (!ignoreUnits) {
                if (occupyingUnit.controller?.isAllied(movingUnit.controller) != true) {
                    if (!occupyingUnit.isCloaked()) {
                        if (!(isLastNode && ignoreUnitsOnLastNode)) {
                            return Double.POSITIVE_INFINITY
                        }
                    }
                } else if (isLastNode && !ignoreUnitsOnLastNode) {
                    return Double.POSITIVE_INFINITY
                }
            }
        }
        return terrain.applyModifiers("movementCost", movingUnit, terrain.cost)
    }

    override fun getHeuristic(node: Node): Double {
        val other = node as? Tile ?: return 0.0
        return (kotlin.math.abs(x - other.x) + kotlin.math.abs(y - other.y)).toDouble()
    }

    fun isAdjacentTo(other: Tile): Boolean {
        if (other === this) return false
        if (other.x == x) {
            if (other.y == y + 1 || other.y == y - 1) return true
        }
        if (other.y == y) {
            if (other.x == x + 1 || other.x == x - 1) return true
        }
        return false
    }

    /**
     * Places a structure on this tile. The structure's terrain replaces the tile's terrain,
     * the tile becomes owned by [aController] (or neutral), and a "resources" terrain
     * modifier seeds the tile's resource pool.
     */
    fun upgrade(structureTerrain: Terrain, aController: Controller?) {
        require(!hasStructure) { "Tile: tile already has a structure" }
        hasStructure = true
        terrain = structureTerrain
        controller = aController
        for (modifier in structureTerrain.modifiers) {
            if (modifier.type == "resources") resources = 1000
        }
    }

    /** Removes any structure, returning the tile to its base terrain. */
    fun downgrade() {
        require(hasStructure) { "Tile: tile does not have a structure" }
        hasStructure = false
        controller = null
        capturer = null
        terrain = baseTerrain
        chunks = GameManager.TILE_CHUNKS
    }

    fun convertTerrainTo(newTerrainName: String) {
        val newTerrain = TileManager.get(newTerrainName)
        terrain = newTerrain
        // Refresh the tile's sprite so the renderer shows the converted terrain's texture
        // (e.g. Enriched Ore -> Ore -> Depleted Ore after extraction).
        val swatch = GameManager.instance.map.palette.firstOrNull { it.name == newTerrainName }
        if (swatch != null && swatch.imageFiles.isNotEmpty()) {
            spriteKind = swatch.kind
            spriteKeys = swatch.imageFiles.map { it.substringBeforeLast('.') }
            if (spriteKind == TileSwatch.Kind.VARIANT && spriteKeys.size > 1) {
                variantIndex = ((y * 73856093) xor (x * 19349663) and 0x7fffffff) % spriteKeys.size
            }
        }
    }

    /**
     * Extracts resources from this tile. Returns the amount actually taken (scaled by
     * [perc] and the terrain's "reducedResources" modifier).
     */
    fun takeResources(perc: Double = 1.0): Double {
        val res = terrain.applyModifiers("resources", null, 0.0) * perc
        val resMod = terrain.applyModifiers("reducedResources", null, res) * perc
        if (res > resources) {
            resources = 0
            return resMod
        }
        resources -= res.toInt()
        return res
    }

    /** Whether the given unit could move away from this tile at all. */
    fun couldUnitMoveAwayFromThisTile(unitName: String): Boolean {
        val fakeUnit = UnitManager.createFakeUnit(unitName) ?: return false
        return connections.any { (it as? Tile)?.canBeMovedOnBy(fakeUnit) == true }
    }

    override fun toString(): String = "[Tile: $x,$y]"
}
package cn.enaium.battalion.data

/**
 * Registry of all terrain templates. Terrains are defined once in `terrain/<id>.json` and
 * looked up by name. [get] returns a clone so callers never mutate the shared template.
 */
object TileManager {
    /** The logical size of one map tile, in world (SDL) pixels. */
    const val TILE_WIDTH = 56
    const val TILE_HEIGHT = 56

    private val terrain = mutableMapOf<String, Terrain>()

    fun add(terra: Terrain) {
        terrain[terra.name] = terra
    }

    fun get(name: String): Terrain {
        val found = terrain[name] ?: return Terrain(name, 1.0, "", "minimap_sea", "undefined")
        return found.clone()
    }

    /** Every registered terrain template (used by diagnostics / mod tooling). */
    fun all(): List<Terrain> = terrain.values.toList()

    fun remove(terra: Terrain) {
        terrain.remove(terra.name)
    }

    fun clear() {
        terrain.clear()
    }

    /**
     * Imports every `terrain/<id>.json` document. Modifier names resolve against the
     * [ModifierManager], so modifiers must be imported first.
     */
    fun importAll(assetsDir: String) {
        for (id in AssetLoader.list(assetsDir, "terrain").sorted()) {
            val cfg = AssetLoader.terrain(assetsDir, id) ?: continue
            val terra = Terrain(
                name = cfg.name,
                cost = cfg.cost,
                description = cfg.description,
                miniMapImage = cfg.minimapImage,
                sprite = cfg.sprite,
                sprites = cfg.sprites.toTerrainSprites(),
            )
            for (modName in cfg.modifiers) {
                ModifierManager.get(modName)?.let { terra.addModifier(it) }
            }
            terrain[terra.name] = terra
        }
    }
}

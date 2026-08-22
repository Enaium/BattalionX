package cn.enaium.battalion.data

import cn.enaium.battalion.util.XmlNode

/**
 * Registry of all terrain templates. Terrains are defined once in the terrain XML and looked
 * up by name. [get] returns a clone so callers never mutate the shared template.
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
     * Imports a `<terrains>` XML document.
     *
     * Expected structure per terrain:
     * ```
     * <terrain name="Forest">
     *   <cost value="2"/>
     *   <description value="..."/>
     *   <minimapimage value="minimap_forest"/>
     *   <sprite value="forest.png"/>
     *   <modifier type="Ground"/>
     * </terrain>
     * ```
     */
    fun importXML(root: XmlNode) {
        for (terrainNode in root.children("terrain")) {
            val terra = Terrain(
                name = terrainNode.attr("name"),
                cost = terrainNode.child("cost")?.value()?.toDoubleOrNull() ?: 1.0,
                description = terrainNode.child("description")?.value() ?: "",
                miniMapImage = terrainNode.child("minimapimage")?.value() ?: "",
                sprite = terrainNode.child("sprite")?.value() ?: "",
            )
            for (modifierNode in terrainNode.children("modifier")) {
                val type = modifierNode.attr("type")
                if (type.isNotEmpty()) {
                    ModifierManager.get(type)?.let { terra.addModifier(it) }
                }
            }
            terrain[terra.name] = terra
        }
    }
}
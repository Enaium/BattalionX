package cn.enaium.battalion.data

/**
 * How a map palette entry draws itself: interchangeable full-tile [variants], a 2×2
 * [composite] (TL/TR/BL/BR quadrant pieces for sea/shore/canyon transitions), or one plain
 * [single] texture. This replaces the old string image-code mini-language.
 *
 * A composite piece named `sea_edge` stands in for the directional sea strip matching that
 * corner; a piece named `sea_0_v1` is treated the same way (legacy maps).
 */
class TerrainSprites(
    val variants: List<String> = emptyList(),
    val composite: List<String> = emptyList(),
    val single: String = "",
) {
    /** The drawing mode this sprite set implies. */
    val kind: Kind
        get() = when {
            composite.size == 4 -> Kind.COMPOSITE
            variants.size >= 2 -> Kind.VARIANT
            else -> Kind.SINGLE
        }

    enum class Kind { COMPOSITE, VARIANT, SINGLE }

    companion object {
        /** Composite piece name meaning "the sea edge strip for this corner". */
        const val SEA_EDGE = "sea_edge"
        const val SEA_EDGE_LEGACY = "sea_0_v1"
    }
}

fun TerrainSpritesConfig.toTerrainSprites(): TerrainSprites =
    TerrainSprites(variants, composite, single)

/**
 * Static template for a kind of ground tile (e.g. "Plains", "Forest", "Factory Base").
 * Like [UnitData], a [Terrain] carries modifiers (referenced by name) that express its
 * rules — movement cost, defense, resource extraction, and so on.
 */
class Terrain(
    val name: String,
    val cost: Double,
    val description: String,
    val miniMapImage: String,
    val sprite: String = "",
    /** Default palette drawing when a map palette entry doesn't override it. */
    val sprites: TerrainSprites = TerrainSprites(),
) {
    val modifiers: MutableList<Modifier> = mutableListOf()
    var resources: Int = 0

    fun addModifier(modifier: Modifier) {
        modifiers.add(modifier)
    }

    fun removeModifier(modifier: Modifier) {
        modifiers.remove(modifier)
    }

    fun hasModifier(name: String): Boolean =
        modifiers.any { it.name.lowercase() == name.lowercase() }

    /** Applies every modifier of [type] to [value]. */
    fun applyModifiers(type: String, unit: Unit?, value: Double): Double {
        var result = value
        for (modifier in modifiers) result = modifier.apply(type, unit, result)
        return result
    }

    fun clone(): Terrain {
        val terra = Terrain(name, cost, description, miniMapImage, sprite, sprites)
        for (modifier in modifiers) terra.addModifier(modifier)
        return terra
    }

    override fun toString(): String = "<Terrain: $name>"
}

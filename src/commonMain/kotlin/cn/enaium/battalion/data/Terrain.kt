package cn.enaium.battalion.data

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
        val terra = Terrain(name, cost, description, miniMapImage, sprite)
        for (modifier in modifiers) terra.addModifier(modifier)
        return terra
    }

    override fun toString(): String = "<Terrain: $name>"
}
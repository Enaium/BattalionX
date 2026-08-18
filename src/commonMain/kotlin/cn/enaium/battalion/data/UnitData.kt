package cn.enaium.battalion.data

import cn.enaium.battalion.game.Controller

/**
 * Static template for a kind of unit (e.g. "Warmachine"). This holds the base stats read
 * from the units XML. A live [Unit] wraps a [UnitData] plus mutable per-unit state
 * (position, health, whether it has moved/fired...).
 *
 * Units also carry *modifiers* (referenced by name from the [ModifierManager]). These are
 * applied on demand via [applyModifiers], which is how special rules (Flak, Skysweeper,
 * Transport, Cloaking...) are expressed.
 *
 * The type arrays below mirror the original's string tables:
 *  - unitType:      none, ground, sea, air
 *  - movementType:  none, feet, wheeled, tracked, rudder, wings
 *  - weaponType:    none, light, medium, heavy
 *  - armorType:     none, light, medium, heavy
 */
class UnitData(
    val name: String,
    val sprite: String,
    val cost: Int,
    val range: Int,
    val health: Int,
    val damage: Int,
    val weaponRangeMin: Int,
    val weaponRangeMax: Int,
    val unitType: Int,
    val movementType: Int,
    val weaponType: Int,
    val armorType: Int,
    val audioProfile: String,
    val description: String,
) {
    val modifiers: MutableList<Modifier> = mutableListOf()
    val temporaryModifiers: MutableList<Modifier> = mutableListOf()

    companion object {
        val unitTypes = arrayOf("none", "ground", "sea", "air")
        val movementTypes = arrayOf("none", "feet", "wheeled", "tracked", "rudder", "wings")
        val armorTypes = arrayOf("none", "light", "medium", "heavy")
        val weaponTypes = arrayOf("none", "light", "medium", "heavy")

        const val UNIT_GROUND = 1
        const val UNIT_SEA = 2
        const val UNIT_AIR = 3

        fun unitTypeFromString(type: String): Int {
            val t = type.lowercase()
            for (i in unitTypes.indices) {
                if (t == unitTypes[i].lowercase()) return i
            }
            throw IllegalArgumentException("UnitData: invalid unit type '$type'")
        }

        fun armorTypeFromString(type: String): Int {
            val t = type.lowercase()
            for (i in armorTypes.indices) {
                if (t == armorTypes[i].lowercase()) return i
            }
            throw IllegalArgumentException("UnitData: invalid armor type '$type'")
        }

        fun movementTypeFromString(type: String): Int {
            val t = type.lowercase()
            for (i in movementTypes.indices) {
                if (t == movementTypes[i].lowercase()) return i
            }
            throw IllegalArgumentException("UnitData: invalid movement type '$type'")
        }

        fun weaponTypeFromString(type: String): Int {
            val t = type.lowercase()
            for (i in weaponTypes.indices) {
                if (t == weaponTypes[i].lowercase()) return i
            }
            throw IllegalArgumentException("UnitData: invalid weapon type '$type'")
        }
    }

    fun isAir(): Boolean = unitType == UNIT_AIR
    fun isSea(): Boolean = unitType == UNIT_SEA
    fun isGround(): Boolean = unitType == UNIT_GROUND

    /** The cost to build this unit, scaled by the controller's air/ground/sea cost modifier. */
    fun getCost(controller: Controller? = null): Int {
        var result = cost.toDouble()
        if (controller != null) {
            when {
                isGround() -> result *= controller.getGroundCost()
                isAir() -> result *= controller.getAirCost()
                isSea() -> result *= controller.getSeaCost()
            }
        }
        return kotlin.math.round(result).toInt()
    }

    fun addModifier(modifier: Modifier) {
        if (modifier.name.isEmpty()) return
        modifiers.add(modifier)
    }

    fun removeModifier(modifier: Modifier) {
        modifiers.remove(modifier)
    }

    fun addTemporaryModifier(modifier: Modifier) {
        temporaryModifiers.add(modifier)
    }

    fun removeTemporaryModifier(modifier: Modifier) {
        temporaryModifiers.remove(modifier)
    }

    fun clearTemporaryModifier(name: String) {
        temporaryModifiers.removeAll { it.name.lowercase() == name.lowercase() }
    }

    fun clearTemporaryModifiers() {
        temporaryModifiers.clear()
    }

    fun hasModifier(name: String): Boolean {
        val n = name.lowercase()
        return modifiers.any { it.name.lowercase() == n } ||
            temporaryModifiers.any { it.name.lowercase() == n }
    }

    fun getAllModifiers(): List<Modifier> = modifiers + temporaryModifiers

    /** Applies every modifier (permanent then temporary) of [type] to [value]. */
    fun applyModifiers(type: String, unit: Unit?, value: Double): Double {
        var result = value
        for (modifier in modifiers) result = modifier.apply(type, unit, result)
        for (modifier in temporaryModifiers) result = modifier.apply(type, unit, result)
        return result
    }

    fun clone(): UnitData {
        val stats = UnitData(
            name, sprite, cost, range, health, damage,
            weaponRangeMin, weaponRangeMax, unitType, movementType, weaponType, armorType,
            audioProfile, description,
        )
        for (modifier in modifiers) stats.addModifier(modifier)
        return stats
    }

    override fun toString(): String = "<$name>"
}
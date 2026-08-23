package cn.enaium.battalion.data

/**
 * A named rule modifier (e.g. "Flak", "Water"). It has a name and a *type* — what it
 * modifies ("damage", "defense", "movementCost", "attackRange", "resources"...). When the
 * game asks for an effective value of some type, it walks every modifier the unit / terrain
 * has and calls [apply] with that type. Only modifiers whose type matches actually change
 * the value; the rest pass it through untouched.
 */
/**
 * Effect recipe interpretation (copied exactly from the original ModifierEffect.as):
 *  - `"0"` -> 0, `"infinity"` -> +Infinity, `"+N%"` -> *(1+N/100), `"+N"` -> +N,
 *  - `"-N%"` -> *(1-N/100), `"-N"` -> -N, `"*N"` -> *N, plain `"N"` -> set to N.
 */
class Modifier(
    val name: String,
    description: String = "",
    image: String? = null,
    var visible: Boolean = false,
    val type: String = "",
) {
    var description: String = description
    var image: String? = image
    val effects: MutableList<ModifierEffect> = mutableListOf()

    /** Applies this modifier to [value] if its type matches [type]; otherwise passes through. */
    fun apply(type: String, unit: Unit?, value: Double): Double {
        var result = value
        if (type.lowercase() == this.type.lowercase()) {
            for (effect in effects) {
                result = effect.apply(unit, result)
            }
        }
        return result
    }

    fun addEffect(effect: ModifierEffect) {
        effects.add(effect)
    }

    fun removeEffect(effect: ModifierEffect) {
        effects.remove(effect)
    }

    override fun toString(): String = name + " " + effects.joinToString(" ") { it.toString() }
}
/**
 * A single effect recipe: the [value] string encodes how to transform the incoming value
 * (see the interpretation table in the file header).
 */
open class ModifierEffect(val value: String) {
    val effects: MutableList<ModifierEffect> = mutableListOf()

    init {
        require(value.isNotEmpty()) { "ModifierEffect: empty value" }
    }

    open fun apply(unit: Unit?, value: Double): Double {
        val v = this.value.lowercase()

        if (v == "0") return 0.0
        if (v == "infinity") return Double.POSITIVE_INFINITY

        val first = v[0]
        when (first) {
            '+' -> {
                val rest = v.substring(1)
                if (rest.endsWith("%")) {
                    val pct = rest.dropLast(1).toDoubleOrNull()
                    if (pct != null) return value + (pct / 100.0) * value
                } else {
                    val add = rest.toDoubleOrNull()
                    if (add != null) return value + add
                }
            }
            '-' -> {
                val rest = v.substring(1)
                if (rest.endsWith("%")) {
                    val pct = rest.dropLast(1).toDoubleOrNull()
                    if (pct != null) return value - (pct / 100.0) * value
                } else {
                    val minus = rest.toDoubleOrNull()
                    if (minus != null) return value + minus
                }
            }
            '*' -> {
                val mult = v.substring(1).toDoubleOrNull()
                if (mult != null) return value * mult
            }
            else -> {
                val set = v.toDoubleOrNull()
                if (set != null) return set
            }
        }
        throw IllegalStateException("ModifierEffect: value '$v' could not be applied")
    }

    fun addEffect(effect: ModifierEffect) {
        effects.add(effect)
    }

    fun removeEffect(effect: ModifierEffect) {
        effects.remove(effect)
    }

    override fun toString(): String = value + " " + effects.joinToString(" ") { it.toString() }
}

/**
 * A conditional effect. It only applies its nested effects when the unit satisfies the
 * condition described by [matchType] / [matchValue].
 *
 * The match types are:
 *  - `unitType`     -> the unit's unit type string equals the value ("ground"/"sea"/"air")
 *  - `movementType` -> the unit's movement type equals the value ("feet"/"wheeled"/...)
 *  - `weaponType`   -> the unit's weapon type equals the value
 *  - `armorType`    -> the unit's armor type equals the value
 *  - `modifier`     -> the unit's unit data has a modifier with the given name
 *
 * When the unit is null the match always passes through unchanged (no condition can hold).
 */
class ModifierEffectMatch(
    val matchType: String,
    matchValue: String,
) : ModifierEffect(matchValue) {

    override fun apply(unit: Unit?, value: Double): Double {
        if (unit == null) return value

        val matched = when (matchType.lowercase()) {
            "unittype" -> UnitData.unitTypes[unit.unitData.unitType].lowercase() == this.value.lowercase()
            "movementtype" -> UnitData.movementTypes[unit.unitData.movementType].lowercase() == this.value.lowercase()
            "weapontype" -> UnitData.weaponTypes[unit.unitData.weaponType].lowercase() == this.value.lowercase()
            "armortype" -> UnitData.armorTypes[unit.unitData.armorType].lowercase() == this.value.lowercase()
            "modifier" -> unit.unitData.hasModifier(this.value)
            else -> throw IllegalStateException("ModifierEffectMatch: invalid match type '$matchType'")
        }
        if (!matched) return value

        var result = value
        for (effect in effects) {
            result = effect.apply(unit, result)
        }
        return result
    }

    override fun toString(): String = matchType
}

/**
 * Registry of all named modifiers. Modifiers are defined once (`modifiers/unit/<id>.json`,
 * `modifiers/terrain/<id>.json`) and then *referenced by name* from units and terrains.
 */
object ModifierManager {
    private val modifiers = mutableMapOf<String, Modifier>()

    fun add(modifier: Modifier) {
        modifiers[modifier.name] = modifier
    }

    fun get(name: String): Modifier? = modifiers[name]

    fun remove(modifier: Modifier) {
        modifiers.remove(modifier.name)
    }

    fun clear() {
        modifiers.clear()
    }

    /**
     * Imports every modifier document. Unit modifiers load before terrain modifiers so a
     * name clash resolves like the original two-document order did.
     */
    fun importAll(assetsDir: String) {
        for (kind in listOf("Unit", "Terrain")) {
            for (id in AssetLoader.list(assetsDir, "modifiers/${kind.lowercase()}").sorted()) {
                val cfg = AssetLoader.modifier(assetsDir, kind, id) ?: continue
                import(cfg)
            }
        }
    }

    private fun import(cfg: ModifierConfig) {
        val modifier = Modifier(
            name = cfg.name,
            description = cfg.description,
            image = cfg.image.ifEmpty { null },
            visible = cfg.visible,
            type = cfg.modify,
        )
        for (effect in cfg.effects) {
            modifier.addEffect(ModifierEffect(effect))
        }
        for (match in cfg.matches) {
            modifier.addEffect(importMatch(match))
        }
        modifiers[cfg.name] = modifier
    }

    /** Recursively converts nested [ModifierMatchConfig]s into [ModifierEffectMatch]es. */
    private fun importMatch(match: ModifierMatchConfig): ModifierEffectMatch {
        val result = ModifierEffectMatch(match.match, match.value)
        for (effect in match.effects) {
            result.addEffect(ModifierEffect(effect))
        }
        for (nested in match.matches) {
            result.addEffect(importMatch(nested))
        }
        return result
    }
}

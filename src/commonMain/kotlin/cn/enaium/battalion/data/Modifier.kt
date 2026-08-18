package cn.enaium.battalion.data

import cn.enaium.battalion.util.XmlNode

/**
 * The modifier system is the heart of the game's rules.
 *
 * A [Modifier] has a name and a *type* (e.g. "damage", "defense", "movementCost",
 * "attackRange", "resources"...). When the game asks for an effective value of some
 * type (say the damage a unit deals), it walks every modifier the unit / terrain has
 * and calls [Modifier.apply] with that type. Only modifiers whose type matches actually
 * change the value; the rest pass it through untouched.
 *
 * Each modifier holds a list of [ModifierEffect]s. An effect is a small "recipe" written
 * as a string, e.g. `"+50%"`, `"-10%"`, `"Infinity"`, `"2"`, `"*1.5"`. The exact rules
 * for interpreting these strings are faithfully reproduced in [ModifierEffect.apply].
 *
 * A [ModifierEffectMatch] is a conditional effect: it only applies when the unit matches
 * some property (its unit type, movement type, weapon type, armor type, or whether it has
 * a particular modifier). Matches can be nested, so a match can contain further effects
 * and even further matches.
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
 * A single effect recipe. The [value] string encodes how to transform the incoming value.
 *
 * The interpretation (copied exactly from the original ModifierEffect.as):
 *  - `"0"`          -> returns 0
 *  - `"infinity"`   -> returns +Infinity
 *  - `"+N%"`        -> value * (1 + N/100)   (add a percentage)
 *  - `"+N"`         -> value + N             (add a fixed amount)
 *  - `"-N%"`        -> value * (1 - N/100)   (subtract a percentage)
 *  - `"-N"`         -> value + (-N)          (subtract a fixed amount)
 *  - `"*N"`         -> value * N             (multiply)
 *  - `"N"` (plain)  -> N                     (SET the value to N, discarding the input)
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
 * A conditional effect (a `<match>` element). It only applies its nested effects when the
 * unit satisfies the condition described by [matchType] / [matchValue].
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
 * Registry of all named modifiers. Modifiers are defined once (in the unitModifiers /
 * terrainModifiers XML) and then *referenced by name* from units and terrains.
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
     * Imports a `<unitmodifiers>` / `<terrainmodifiers>` XML document.
     *
     * Expected structure per modifier:
     * ```
     * <modifier name="Flak">
     *   <description value="..."/>
     *   <image value="..."/>
     *   <visible value="true"/>
     *   <modify type="damage">
     *     <effect value="+100%"/>
     *     <match type="armorType" value="light">
     *       <effect value="..."/>
     *     </match>
     *   </modify>
     * </modifier>
     * ```
     */
    fun importXML(root: XmlNode) {
        for (modifierNode in root.children("modifier")) {
            val name = modifierNode.attr("name")
            val visible = modifierNode.child("visible")?.value()?.lowercase() == "true"
            val modify = modifierNode.child("modify")
            val type = modify?.attr("type") ?: ""

            val modifier = Modifier(
                name = name,
                description = modifierNode.child("description")?.value() ?: "",
                image = modifierNode.child("image")?.value()?.ifEmpty { null },
                visible = visible,
                type = type,
            )
            if (modify != null) {
                for (child in modify.children) {
                    when (child.name.lowercase()) {
                        "effect" -> modifier.addEffect(ModifierEffect(child.attr("value")))
                        "match" -> {
                            val match = ModifierEffectMatch(child.attr("type"), child.attr("value"))
                            modifier.addEffect(match)
                            handleEffect(child, match)
                        }
                    }
                }
            }
            modifiers[name] = modifier
        }
    }

    /** Recursively parses `<effect>` and `<match>` children into [parent]. */
    private fun handleEffect(node: XmlNode, parent: ModifierEffect) {
        for (child in node.children) {
            when (child.name.lowercase()) {
                "effect" -> parent.addEffect(ModifierEffect(child.attr("value")))
                "match" -> {
                    val match = ModifierEffectMatch(child.attr("type"), child.attr("value"))
                    parent.addEffect(match)
                    handleEffect(child, match)
                }
            }
        }
    }
}
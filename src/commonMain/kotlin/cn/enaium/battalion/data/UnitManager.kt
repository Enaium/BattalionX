package cn.enaium.battalion.data

import cn.enaium.battalion.util.XmlNode

/**
 * Registry of all unit templates. Units are defined once in the units XML and looked up by
 * name. [get] returns a clone so callers never mutate the shared template.
 */
object UnitManager {
    private val units = mutableMapOf<String, UnitData>()

    /** The Vulture Drone is a reward unit; in the original it is gated on release status. */
    var allowVulture: Boolean = false

    fun add(unit: UnitData) {
        units[unit.name] = unit
    }

    fun get(name: String): UnitData? = units[name]?.clone()

    fun remove(unit: UnitData) {
        units.remove(unit.name)
    }

    fun clear() {
        units.clear()
    }

    fun getAll(): List<UnitData> = units.values.toList()

    /** A throwaway unit used for "could this unit move here" checks. */
    fun createFakeUnit(name: String): Unit? {
        val data = get(name) ?: return null
        return Unit(data)
    }

    /**
     * Imports a `<units>` XML document.
     *
     * Expected structure per unit:
     * ```
     * <unit name="Warmachine">
     *   <sprite value="Warmachine"/>
     *   <cost value="2500"/>
     *   <range value="3"/>
     *   <health value="75"/>
     *   <damage value="60"/>
     *   <weaponRangeMin value="2"/>
     *   <weaponRangeMax value="3"/>
     *   <unitType value="ground"/>
     *   <movementType value="tracked"/>
     *   <weaponType value="medium"/>
     *   <armorType value="medium"/>
     *   <modifier type="Constructor"/>
     * </unit>
     * ```
     * Each `<modifier type="...">` reference is resolved against the [ModifierManager].
     */
    fun importXML(root: XmlNode) {
        for (unitNode in root.children("unit")) {
            val name = unitNode.attr("name")
            if (!allowVulture && name == "Vulture Drone") continue

            val unit = UnitData(
                name = name,
                sprite = unitNode.child("sprite")?.value() ?: "",
                cost = unitNode.child("cost")?.value()?.toIntOrNull() ?: 0,
                range = unitNode.child("range")?.value()?.toIntOrNull() ?: 0,
                health = unitNode.child("health")?.value()?.toIntOrNull() ?: 0,
                damage = unitNode.child("damage")?.value()?.toIntOrNull() ?: 0,
                weaponRangeMin = unitNode.child("weaponRangeMin")?.value()?.toIntOrNull() ?: 0,
                weaponRangeMax = unitNode.child("weaponRangeMax")?.value()?.toIntOrNull() ?: 0,
                unitType = UnitData.unitTypeFromString(unitNode.child("unitType")?.value() ?: "none"),
                movementType = UnitData.movementTypeFromString(unitNode.child("movementType")?.value() ?: "none"),
                weaponType = UnitData.weaponTypeFromString(unitNode.child("weaponType")?.value() ?: "none"),
                armorType = UnitData.armorTypeFromString(unitNode.child("armorType")?.value() ?: "none"),
                audioProfile = unitNode.child("audio")?.value() ?: "",
                description = unitNode.child("description")?.value() ?: "",
            )

            for (modifierNode in unitNode.children("modifier")) {
                val type = modifierNode.attr("type")
                if (type.isNotEmpty()) {
                    ModifierManager.get(type)?.let { unit.addModifier(it) }
                }
            }
            units[name] = unit
        }
    }
}
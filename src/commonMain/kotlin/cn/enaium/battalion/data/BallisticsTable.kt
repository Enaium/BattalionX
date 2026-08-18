package cn.enaium.battalion.data

import cn.enaium.battalion.util.XmlNode

/**
 * The ballistics table maps an attack's weapon type against the defender's armor type to a
 * damage multiplier. The default multiplier is 1x (medium weapons are 1x against everything).
 * The XML overrides specific combinations.
 */
object BallisticsTable {
    private const val DEFAULT = 1.0

    private val table = Array(4) { DoubleArray(4) { DEFAULT } } // [weaponType][armorType]

    fun load(root: XmlNode) {
        for (attackNode in root.children("attack")) {
            val weapon = UnitData.weaponTypeFromString(attackNode.attr("type"))
            for (defenseNode in attackNode.children("defense")) {
                val armor = UnitData.armorTypeFromString(defenseNode.attr("type"))
                val value = defenseNode.attr("value").toDoubleOrNull() ?: DEFAULT
                table[weapon][armor] = value
            }
        }
    }

    /** Damage multiplier for a [weaponType] attacking an [armorType] defender. */
    fun getModifier(weaponType: Int, armorType: Int): Double = table[weaponType][armorType]
}
package cn.enaium.battalion.data

/**
 * The ballistics table maps an attack's weapon type against the defender's armor type to a
 * damage multiplier. The default multiplier is 1x (medium weapons are 1x against everything).
 * `ballistics.json` overrides specific combinations.
 */
object BallisticsTable {
    private const val DEFAULT = 1.0

    private val table = Array(4) { DoubleArray(4) { DEFAULT } } // [weaponType][armorType]

    fun load(cfg: BallisticsConfig) {
        for (weapon in cfg.weapons) {
            val w = UnitData.weaponTypeFromString(weapon.weapon)
            for (defense in weapon.defenses) {
                val armor = UnitData.armorTypeFromString(defense.armor)
                table[w][armor] = defense.multiplier
            }
        }
    }

    /** Damage multiplier for a [weaponType] attacking an [armorType] defender. */
    fun getModifier(weaponType: Int, armorType: Int): Double = table[weaponType][armorType]
}

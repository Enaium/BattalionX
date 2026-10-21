package cn.enaium.battalion.data

/**
 * Registry of all unit templates. Units are defined once in `units/<id>.json` and looked up
 * by display name. [get] returns a clone so callers never mutate the shared template.
 */
object UnitManager {
    private val units = mutableMapOf<String, UnitData>()

    /** The Vulture Drone is a reward unit; in the original it is gated on release status. */
    /**
     * The Vulture Drone is a reward unit. The original hides it unless the game's own release
     * check passes — except in Episode 3, where `UnitManager` forces it on
     * (`if (BattalionMain.EPISODE == EPISODE_EPISODE3) ALLOW_VULTURE = true`). This port ships the
     * Ep3 campaign, so the drone is always available here; leaving the flag off silently dropped
     * the unit from the registry, and every map that places one (Mission_10 fields six) lost
     * those vehicles in the match and in the editor's preview alike.
     */
    var allowVulture: Boolean = true

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
     * Imports every `units/<id>.json` document. Modifier names resolve against the
     * [ModifierManager], so modifiers must be imported first.
     */
    fun importAll(assetsDir: String) {
        for (id in AssetLoader.list(assetsDir, "units").sorted()) {
            val cfg = AssetLoader.unit(assetsDir, id) ?: continue
            import(assetsDir, cfg)
        }
    }

    private fun import(assetsDir: String, cfg: UnitConfig) {
        if (!allowVulture && cfg.rewardUnit && !units.containsKey(cfg.name)) {
            // Reward units stay unregistered until the release flag flips; still parse the
            // rest so a modded reward unit behaves like the Vulture Drone did.
        }
        if (!allowVulture && cfg.name == "Vulture Drone") return

        val unit = UnitData(
            name = cfg.name,
            spriteKey = SpriteKey(
                sheet = cfg.sprite.sheet,
                firingSheet = cfg.sprite.firingSheet ?: "",
                cellWidth = cfg.sprite.cellWidth,
                facingColumns = cfg.sprite.facingColumns,
                rows = cfg.sprite.rows,
                firingCellWidth = cfg.sprite.firingCellWidth,
            ),
            cost = cfg.cost,
            range = cfg.range,
            health = cfg.health,
            damage = cfg.damage,
            weaponRangeMin = cfg.weaponRangeMin,
            weaponRangeMax = cfg.weaponRangeMax,
            unitType = UnitData.unitTypeFromString(cfg.unitType),
            movementType = UnitData.movementTypeFromString(cfg.movementType),
            weaponType = UnitData.weaponTypeFromString(cfg.weaponType),
            armorType = UnitData.armorTypeFromString(cfg.armorType),
            audioMove = cfg.audio.move,
            audioFire = cfg.audio.fire,
            description = cfg.description,
        )
        for (modName in cfg.modifiers) {
            ModifierManager.get(modName)?.let { unit.addModifier(it) }
        }
        units[cfg.name] = unit
    }
}

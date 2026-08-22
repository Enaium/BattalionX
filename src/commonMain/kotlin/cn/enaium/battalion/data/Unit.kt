package cn.enaium.battalion.data

import cn.enaium.battalion.game.Controller
import cn.enaium.battalion.game.GameManager

/**
 * A live unit on the map: a [UnitData] template plus mutable per-unit state.
 *
 * This is a pure-logic port — the original also carried display sprites and tweens; here the
 * renderer reads the unit's logical state (position, health fraction, controller colour,
 * sprite name) and draws it. All game rules that a unit participates in live here.
 */
class Unit(
    unitData: UnitData,
    var x: Int = 0,
    var y: Int = 0,
    var controller: Controller? = null,
) {
    val baseUnitData: UnitData = unitData

    /** Stable identity used by the renderer to track this unit's walk/visual state. */
    val walkId: Int = nextWalkId++

    private var health: Double = unitData.health.toDouble()
    var resourcePool: Int = if (unitData.hasModifier("Constructor")) 2000 else -1

    var moved: Boolean = false
    var fired: Boolean = false
    var deployed: Boolean = false
    var repairing: Boolean = false
    var disabled: Boolean = false
    var cloaked: Boolean = false

    /**
     * Facing direction as a compass direction: 0 = north (up), 1 = east (right),
     * 2 = south (down), 3 = west (left). The renderer maps this to the correct
     * column of a unit's direction sprite sheet. Defaults to north (facing up).
     */
    var facing: Int = 0

    /**
     * The facing used for the *current animation* (movement/attack). Movement may face a
     * direction even before the idle facing is settled; the renderer uses this to pick the
     * animation column so the sprite visibly turns as it acts.
     */
    var animFacing: Int = facing

    /** The point in time (in renderer frame units) the current animation started. */
    var animStartMs: Long = -1

    /** The current animation being played, or [UnitAnim.NONE] when idle. */
    var anim: UnitAnim = UnitAnim.NONE

    // ---- walking animation state -------------------------------------------
    // When a unit moves, it walks along [movePath] tile by tile instead of teleporting.
    // [prevX]/[prevY] is the tile it is leaving, [x]/[y] the tile it is entering; the
    // renderer interpolates between them using [moveProgressMs]/[MOVE_STEP_MS].

    /** The remaining path to walk (first element is the tile after the current one). */
    var movePath: List<Tile> = emptyList()

    /** The tile the unit is currently walking away from. */
    var prevX: Int = x
    var prevY: Int = y

    /** Milliseconds spent walking the current segment (between [prevX]/[prevY] and [x]/[y]). */
    var moveProgressMs: Long = 0

    /** The game-clock ms at which the current walk started (driven by [MoveUnitAction.update]). */
    var moveStartMs: Long = -1

    /** True while the unit is walking (position is interpolated by the renderer). */
    var isMoving: Boolean = false

    /** Kind of unit animation (drives which sprite sheet row / frame is shown). */
    enum class UnitAnim {
        NONE, IDLE, MOVE, FIRE
    }

    /**
     * Begins a one-shot animation. The renderer advances it over [Unit.ANIM_DURATION_MS] ms
     * and returns the unit to the idle state when it finishes. The start time is taken from the
     * shared game clock ([GameManager.elapsedMs]) so renderer-driven expiry is consistent.
     */
    fun startAnim(kind: UnitAnim, atMs: Long? = null) {
        anim = kind
        animFacing = facing
        animStartMs = atMs ?: GameManager.instance.elapsedMs
    }

    /** The duration of a movement or firing animation, in ms. */
    companion object {
        const val ANIM_DURATION_MS = 650L

        /** How long one tile step of the walk takes (readable, not rushed). */
        const val MOVE_STEP_MS = 180L

        /** Monotonic counter assigning each unit a stable [Unit.walkId]. */
        private var nextWalkId: Int = 0
    }

    /**
     * Whether this unit is currently *upgraded* — i.e. riding inside a transport (an
     * Albatross Transport or Leviathan Barge). While upgraded the unit's effective [unitData]
     * is the transport's, but its [baseUnitData] stays the passenger's template so the
     * passenger is preserved when the unit is downgraded (unloaded).
     */
    var upgraded: Boolean = false
        private set

    /**
     * The unit's *current* effective template. Normally this is the constructor's [unitData].
     * While the unit rides in a transport it is swapped to the transport's [UnitData], and
     * restored to the passenger's template on [downgrade].
     */
    var unitData: UnitData = unitData
        private set

    init {
        if (unitData.hasModifier("Constructor")) resourcePool = 2000
    }

    /**
     * Puts this unit into a transport with the given transport [UnitData], preserving the
     * current health fraction and the passenger template.
     */
    fun upgrade(newUnitData: UnitData) {
        check(!upgraded) { "Unit: trying to upgrade a unit which is already upgraded" }
        val oldHealthPercentage = getHealthPercentage()
        upgraded = true
        unitData = newUnitData
        health = unitData.health * oldHealthPercentage
    }

    /**
     * Unloads this unit from its transport, restoring its passenger template and preserving the
     * current health fraction.
     */
    fun downgrade() {
        check(upgraded) { "Unit: trying to downgrade a unit which is not upgraded" }
        val oldHealthPercentage = getHealthPercentage()
        upgraded = false
        unitData = baseUnitData
        health = unitData.health * oldHealthPercentage
    }

    val name: String get() = unitData.name

    fun getRange(): Double = unitData.range.toDouble()

    fun getWeaponRangeMin(): Double = unitData.weaponRangeMin.toDouble()
    fun getWeaponRangeMax(): Double = unitData.weaponRangeMax.toDouble()

    fun isRanged(): Boolean =
        unitData.weaponRangeMin > 1 || unitData.weaponRangeMax > 1

    fun canAttack(): Boolean {
        if (unitData.weaponRangeMin <= 0 && unitData.weaponRangeMax <= 0) return false
        if (unitData.damage <= 0) return false
        return true
    }

    fun canMove(): Boolean = getRange() > 0.0

    fun isAlive(): Boolean = kotlin.math.floor(health) > 0

    fun getHealth(): Int = health.toInt()

    /** The exact fractional health (used by save serialization; [getHealth] truncates). */
    val healthValue: Double get() = health

    fun getMaxHealth(): Double = unitData.health.toDouble()

    fun getHealthPercentage(): Double = health / unitData.health

    /** Number of intact health chunks, used to scale damage and draw the HP bar. */
    fun getChunks(maxChunks: Int = 4): Int {
        if (health < 1) return 0
        if (health > unitData.health) health = unitData.health.toDouble()
        return kotlin.math.ceil(health / unitData.health * maxChunks).toInt()
    }

    fun takeDamage(damage: Double) {
        if (kotlin.math.abs(damage) < 0.01) return
        health -= damage
    }

    fun healDamage(amount: Double) {
        health += amount
        if (health > unitData.health) health = unitData.health.toDouble()
    }

    fun setHealth(amount: Double) {
        health = amount
    }

    /**
     * The raw damage this unit would deal to a defender of [defenderArmorType], after the
     * ballistics table and damage modifiers, and scaled by the attacker's remaining health.
     */
    fun getAttackDamage(defenderArmorType: Int): Double {
        val damage = unitData.applyModifiers("damage", this, unitData.damage.toDouble())
        val scale = getChunks(GameManager.UNIT_CHUNKS).toDouble() / GameManager.UNIT_CHUNKS
        val damageScale = GameManager.instance.getBallisticsModifier(unitData.weaponType, defenderArmorType)
        return damage * damageScale * scale
    }

    fun gainResources(amount: Int) {
        resourcePool += amount
    }

    fun spendResourcesFromPool(amount: Int) {
        resourcePool -= amount
    }

    val resourcePoolValue: Int get() = resourcePool

    fun isSelectable(): Boolean = !unitData.hasModifier("Not Selectable")

    fun isUpgraded(): Boolean = upgraded

    fun isAvailable(): Boolean =
        !hasMoved() && !hasFired() && !disabled && !isInActionQueue() && isSelectable()

    fun hasMoved(): Boolean = moved
    fun hasFired(): Boolean = fired

    /** Called at the start of a new turn: re-arms the unit unless it is disabled. */
    fun reset() {
        if (!disabled) {
            moved = false
            fired = false
        } else {
            moved = true
            fired = true
        }
    }

    fun disable() {
        disabled = true
    }

    fun enable() {
        disabled = false
    }

    fun isDisabled(): Boolean = disabled

    /**
     * Cloak/uncloak with the original's display flags. The port keeps only the logical state;
     * the extra arguments (whether movement/attacking breaks the cloak) are accepted for
     * API parity and ignored.
     */
    fun setCloaked(value: Boolean, uncloakOnMove: Boolean, uncloakOnAttack: Boolean) {
        cloaked = value
    }

    fun setCloaked(value: Boolean, uncloakOnMove: Boolean) {
        cloaked = value
    }

    /** Marks the unit as spent for the turn (darkened in the UI). No-op in the logic port. */
    fun darken() {}

    /** Sets the sprite facing direction. No-op in the logic port; kept for API parity. */
    fun fixFacingDirection(facingCurrentController: Boolean) {}

    fun isCloaked(): Boolean = cloaked

    fun isRepairing(): Boolean = repairing

    fun isDeployed(): Boolean = deployed

    fun getTile(): Tile? = GameManager.instance.unitTile(this)

    /** True if this unit already has an action queued for it this turn. */
    fun isInActionQueue(): Boolean = GameManager.instance.actionManager.hasActionWithUnit(this)

    /**
     * Creates a shallow copy of this unit for damage calculations and simulations.
     * The copy shares the same [UnitData] and controller, but has independent mutable state.
     */
    fun clone(): Unit {
        val copy = Unit(unitData, x, y, controller)
        copy.health = health
        copy.resourcePool = resourcePool
        copy.moved = moved
        copy.fired = fired
        copy.deployed = deployed
        copy.repairing = repairing
        copy.disabled = disabled
        copy.cloaked = cloaked
        copy.facing = facing
        return copy
    }

    override fun toString(): String =
        "[${baseUnitData.name}: $x,$y${if (isUpgraded()) " (transported)" else ""}]"
}
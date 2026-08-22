package cn.enaium.battalion.game

import cn.enaium.battalion.data.MoraleChangeAmounts
import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitData
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.actions.EndTurnAction
import cn.enaium.battalion.game.actions.MoraleBonusAction
import cn.enaium.battalion.game.controllers.DummyController
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.triggers.PlayerTrigger

/**
 * A controller drives one team's units. The base class holds shared state (resources,
 * structures, morale, difficulty) and the common rules; the concrete subclasses
 * ([HumanController], [HALController], [DummyController]) implement how a turn is actually
 * played.
 *
 * Controllers subscribe to the game's triggers and react to them (start of turn, unit moved,
 * unit attacked, unit killed, structure captured...). The base implementations are no-ops;
 * subclasses override the relevant ones.
 */
open class Controller(val team: Team) {
    companion object {
        const val EASY = 2
        const val MEDIUM = 1
        const val HARD = 0
    }

    val units = mutableListOf<Unit>()
    val structures = mutableListOf<Tile>()

    private var resources: Int = 0
    private var enabled: Boolean = true
    private var areAllUnitsEnabled: Boolean = true
    private val enabledUnits = mutableSetOf<String>()
    private val difficulty: Int = team.difficulty

    var morale: Double = 0.5
        private set
    private var maximumMorale: Double = 0.5

    var itIsMyTurn: Boolean = false
        private set
    var turnTime: Long = 0
        private set
    var idleTime: Long = 0
        private set

    var turnsSincePlayerAttacked: Int = 0
        private set
    var movedAUnitThisTurn: Boolean = false
        private set

    init {
        // Subscribe to the game events this controller cares about.
        TriggerManager.addListener(Trigger.START_GAME) { startGame() }
        TriggerManager.addListener(Trigger.START_ROUND) { startRound() }
        TriggerManager.addListener(PlayerTrigger.START_TURN) { trigger ->
            val playerTrigger = trigger as PlayerTrigger
            startTurn(playerTrigger.player)
            if (GameManager.instance.getController(playerTrigger.player) == this &&
                !GameManager.instance.isOneActiveAlliance()
            ) {
                startCurrentTurn()
            }
        }
        TriggerManager.addListener(PlayerTrigger.END_GAME) { endGame() }
    }

    // ---- Difficulty scaling -------------------------------------------------

    fun getDifficulty(): Int = difficulty

    fun getDifficultyResourcesScale(): Double = when (difficulty) {
        EASY -> 1.4
        HARD -> 0.7
        else -> 1.0
    }

    fun getDifficultyAttackScale(): Double = when (difficulty) {
        EASY -> 1.4
        HARD -> 0.7
        else -> 1.0
    }

    // ---- Resources ----------------------------------------------------------

    fun getResources(): Int = resources

    fun addResources(amount: Int) {
        resources += amount
    }

    fun spendResources(amount: Int) {
        resources -= amount
    }

    fun canAfford(unitType: String): Boolean {
        val data = UnitManager.get(unitType) ?: return false
        return data.getCost(this) <= resources
    }

    // ---- Air / ground / sea cost controls ----------------------------------

    /**
     * The cheapest "airCost" modifier across all owned structures. A structure that reduces
     * the air cost (an Air Control) makes air units cheaper to build.
     */
    fun getAirCost(): Double {
        var airCost = 1.0
        var hasAtLeastOne = false
        for (structure in structures) {
            val newValue = structure.terrain.applyModifiers("airCost", null, airCost)
            if (newValue < airCost && !hasAtLeastOne) hasAtLeastOne = true
            else airCost = newValue
        }
        return airCost
    }

    fun getGroundCost(): Double {
        var groundCost = 1.0
        var hasAtLeastOne = false
        for (structure in structures) {
            val newValue = structure.terrain.applyModifiers("groundCost", null, groundCost)
            if (newValue < groundCost && !hasAtLeastOne) hasAtLeastOne = true
            else groundCost = newValue
        }
        return groundCost
    }

    fun getSeaCost(): Double {
        var seaCost = 1.0
        var hasAtLeastOne = false
        for (structure in structures) {
            val newValue = structure.terrain.applyModifiers("seaCost", null, seaCost)
            if (newValue < seaCost && !hasAtLeastOne) hasAtLeastOne = true
            else seaCost = newValue
        }
        return seaCost
    }

    fun controlsAir(): Boolean {
        for (structure in structures) {
            if (structure.terrain.applyModifiers("airCost", null, 1.0) < 1.0) return true
        }
        return false
    }

    fun controlsGround(): Boolean {
        for (structure in structures) {
            if (structure.terrain.applyModifiers("groundCost", null, 1.0) < 1.0) return true
        }
        return false
    }

    fun controlsSea(): Boolean {
        for (structure in structures) {
            if (structure.terrain.applyModifiers("seaCost", null, 1.0) < 1.0) return true
        }
        return false
    }

    fun hasRequisiteControlStructuresToBuild(unitData: UnitData): Boolean = when {
        unitData.isAir() && controlsAir() -> true
        unitData.isGround() && controlsGround() -> true
        unitData.isSea() && controlsSea() -> true
        else -> false
    }

    // ---- Units & structures -------------------------------------------------

    fun addUnit(unit: Unit) {
        unit.controller = this
        units.add(unit)
    }

    fun removeUnit(unit: Unit) {
        units.remove(unit)
    }

    fun addStructure(tile: Tile) {
        tile.controller = this
        structures.add(tile)
    }

    fun removeStructure(tile: Tile) {
        structures.remove(tile)
    }

    fun controlsUnit(unit: Unit): Boolean = unit.controller === this

    fun controlsStructure(tile: Tile): Boolean = tile.controller === this

    fun getAvailableFactories(): List<Tile> {
        val possible = mutableListOf<Tile>()
        for (factory in structures) {
            if (factory.terrain.hasModifier("Construction") &&
                factory.unit == null &&
                factory.controller === this
            ) {
                possible.add(factory)
            }
        }
        return possible
    }

    /**
     * Every unit template this controller may order from a factory — enabled and buildable
     * (not "Unbuildable"). Mirrors the SWF `BuildMenu.createUnitLists`, which lists ALL such
     * units; support by the controller's control structures is surfaced per-card by the build
     * page (greyed out when unavailable) rather than filtering the list here.
     */
    fun buildableUnits(): List<cn.enaium.battalion.data.UnitData> =
        cn.enaium.battalion.data.UnitManager.getAll()
            .filter { !it.hasModifier("Unbuildable") }
            .filter { isUnitEnabled(it.name) }

    /**
     * Every unit template a Warmachine constructor of this controller may build onto an
     * adjacent tile — enabled and buildable (not "Unbuildable"). Mirrors the SWF
     * `WMBuildMenu.createUnitLists`: war-machine construction ignores control structures
     * (and the empty `WM_FORBIDDEN_UNITS` list), so all enabled units are shown and the
     * build page greys out ones the constructor's [pool] cannot afford.
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildableUnitsFromWarmachine(pool: Int): List<cn.enaium.battalion.data.UnitData> =
        cn.enaium.battalion.data.UnitManager.getAll()
            .filter { !it.hasModifier("Unbuildable") }
            .filter { isUnitEnabled(it.name) }

    // ---- Allies -------------------------------------------------------------

    fun isAllied(other: Controller?): Boolean {
        if (other === this) return true
        if (other == null) return false
        val seat = GameManager.instance.getPlayer(other)
        if (seat == -1) return false
        return team.isAllied(seat)
    }

    // ---- Unit enablement ----------------------------------------------------

    fun disableAllUnits() {
        areAllUnitsEnabled = false
    }

    fun enableAllUnits() {
        areAllUnitsEnabled = true
    }

    fun getAreAllUnitsDisabled(): Boolean = areAllUnitsEnabled

    /** The names of individually re-enabled units (used by save serialization). */
    fun getEnabledUnitNames(): Set<String> = enabledUnits.toSet()

    fun isUnitEnabled(unitName: String): Boolean =
        areAllUnitsEnabled || enabledUnits.contains(unitName)

    fun enableUnit(unitName: String) {
        enabledUnits.add(unitName)
    }

    fun disableUnit(unitName: String) {
        enabledUnits.remove(unitName)
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    // ---- Morale -------------------------------------------------------------

    /**
     * Sets the controller's morale. When morale crosses a new integer mark (its all-time
     * maximum), the team earns a morale boost: either extra resources on their Warmachines
     * (non-classic maps) or a direct resource award (classic maps with structures).
     */
    fun setMorale(value: Double) {
        if (!GameManager.instance.isMoraleEnabled) return
        morale = kotlin.math.round(value * 1000) / 1000.0
        val oldMaximumMorale = maximumMorale
        maximumMorale = maxOf(maximumMorale, value)
        val previous100Mark = kotlin.math.floor(oldMaximumMorale).toInt()
        val new100Mark = kotlin.math.floor(maximumMorale).toInt()
        if (new100Mark > previous100Mark) {
            if (!GameManager.instance.map.isClassic) {
                var hasWarMachine = false
                val warmachineResourceBoost = 2000
                for (unit in units) {
                    if (unit.baseUnitData.hasModifier("Constructor")) {
                        hasWarMachine = true
                        unit.gainResources(warmachineResourceBoost)
                    }
                }
                if (hasWarMachine) {
                    ActionManager.instance.add(MoraleBonusAction(GameManager.instance.getPlayer(this)), toFront = true)
                }
            } else if (GameManager.instance.map.hasStructures()) {
                val structureBonus = 2000
                resources += structureBonus
            }
        }
    }

    fun getCloakVisibility(): Double =
        if (GameManager.instance.isAlliedToThePlayer(this)) 0.5 else 0.0

    // ---- Turn handling ------------------------------------------------------

    fun endTurn(reason: Int) {
        ActionManager.instance.add(EndTurnAction(reason))
    }

    fun endTurnCleanup() {
        itIsMyTurn = false
    }

    /** Called when this controller surrenders. No-op in the original; game state is handled by [GameManager.surrender]. */
    fun surrender() {
    }

    fun resetTurnsSinceLastAttackCounter() {
        turnsSincePlayerAttacked = 0
    }

    /** Called when this controller's turn begins. */
    protected open fun startTurn(player: Int) {}

    protected open fun startCurrentTurn() {
        itIsMyTurn = true
        movedAUnitThisTurn = false
        ++turnsSincePlayerAttacked
        turnTime = 0
        idleTime = 0

        // Sudden-death: once the game runs past round 16, morale bleeds every turn, growing
        // with each round over the limit (matches original Controller.startCurrentTurn).
        if (GameManager.instance.isMoraleEnabled) {
            val deathMarchTurnThreshold = 16
            val round = GameManager.instance.round
            if (round > deathMarchTurnThreshold) {
                val turnsOverLimit = round - deathMarchTurnThreshold
                val penalty = MoraleChangeAmounts.PER_TURN_SUDDEN_DEATH_PENALTY * turnsOverLimit
                GameManager.instance.changeMorale(
                    this,
                    penalty,
                    MoraleChangeAmounts.PER_TURN_SUDDEN_DEATH_PENALTY_DESCRIPTION,
                )
            }
        }
    }

    protected open fun startRound() {}

    protected open fun startGame() {}

    protected open fun endGame() {}

    protected open fun attackUnit(unitX: Int, unitY: Int, targetX: Int, targetY: Int, unit: Unit, target: Unit) {}

    protected open fun moveUnit(unit: Unit, path: List<Tile>) {}

    protected open fun killUnit(unitX: Int, unitY: Int, unit: Unit) {}

    protected open fun buildUnit(unit: Unit) {}

    protected open fun captureTerrain(tile: Tile, oldPlayer: Int, player: Int, complete: Boolean) {}

    protected open fun repairUnit(unit: Unit) {}

    protected open fun changeUpgradeStatus(unit: Unit, name: String, upgraded: Boolean) {}

    protected open fun error() {}

    fun resetIdleTime() {
        idleTime = 0
    }

    fun setMovedAUnitThisTurn(value: Boolean) {
        movedAUnitThisTurn = value
    }

    fun setTurnTime(value: Long) {
        turnTime = value
    }

    fun initialize() {}

    open fun deactivate() {}

    open fun dispose() {}

    /** Advances the controller's turn timers. */
    open fun update(ms: Long) {
        updateTurnTime(ms)
    }

    /**
     * Tracks how long the current player has been idle, ending their turn after a grace
     * period. In solo (single-player) games idle penalties are disabled.
     */
    private fun updateTurnTime(ms: Long) {
        if (GameManager.instance.activeController !== this) return
        if (!itIsMyTurn) return
        turnTime += ms
        if (GameManager.instance.isSoloGame) return

        idleTime += ms
        if (turnTime < 15000) idleTime = 0
        if (this is DummyController) return

        if (!GameManager.instance.isMoraleEnabled) return
        var penaltyFreeIdleTime = 20000L
        when (GameManager.instance.round) {
            0 -> penaltyFreeIdleTime += 15000
            1 -> penaltyFreeIdleTime += 10000
            2 -> penaltyFreeIdleTime += 5000
        }
        var beingPenalizedForIdleTime = false
        if (idleTime >= penaltyFreeIdleTime) {
            beingPenalizedForIdleTime = true
            val firstThreshold = penaltyFreeIdleTime + 5500
            if (idleTime >= firstThreshold && idleTime - ms < firstThreshold) {
                endTurn(EndTurnAction.REASON_IDLE)
            }
        }
    }
}
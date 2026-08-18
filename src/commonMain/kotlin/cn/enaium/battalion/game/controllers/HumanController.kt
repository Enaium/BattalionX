package cn.enaium.battalion.game.controllers

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Controller
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager

/**
 * Human player controller — holds UI state and dispatches player actions to the ActionManager.
 *
 * The original HumanController is a massive class handling sprite selection, menus, path drawing,
 * camera movement, and all UI interaction. This port keeps only the logical state; the SDL
 * renderer will call its methods to dispatch actions when the player clicks.
 */
class HumanController(team: Team) : Controller(team) {

    companion object {
        /** Score threshold for marking a target as "bad" or "OK" in the target picker. */
        const val BAD_TARGET = -25.0
        const val OK_TARGET = 70.0
    }

    var control: Boolean = false
        private set

    /** The unit currently selected by the player, or null. */
    var selectedUnit: Unit? = null
        private set

    /** The path the player has drawn for the selected unit. */
    var currentPath: MutableList<Int> = mutableListOf()

    /** The undo-move action for the current movement. */
    var undoAction: cn.enaium.battalion.game.actions.UndoMoveAction? = null

    init {
        TriggerManager.addListener(cn.enaium.battalion.game.Trigger.CONTROL) {
            controlListener()
        }
    }

    private fun controlListener() {
        control = true
    }

    override fun startTurn(player: Int) {
        control = false
    }

    override fun startCurrentTurn() {
        super.startCurrentTurn()
        selectedUnit = null
        currentPath.clear()
        undoAction = null
    }

    override fun endGame() {
        // No-op for human controller.
    }

    override fun deactivate() {
        super.deactivate()
        selectedUnit = null
        currentPath.clear()
    }

    /** Called by the renderer when the player selects a unit tile. */
    fun selectUnit(unit: Unit?) {
        selectedUnit = unit
        currentPath.clear()
    }

    /** Called by the renderer to dispatch a move action. */
    fun dispatchMove(pathStrings: List<String>) {
        val unit = selectedUnit ?: return
        val move = cn.enaium.battalion.game.actions.MoveUnitAction(
            unit.x,
            unit.y,
            cn.enaium.battalion.game.actions.MoveUnitAction.stringizePath(pathStrings)
        )
        move.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(move)
    }

    /** Called by the renderer to dispatch an attack action. */
    fun dispatchAttack(targetX: Int, targetY: Int) {
        val unit = selectedUnit ?: return
        val attack = cn.enaium.battalion.game.actions.AttackUnitAction(unit.x, unit.y, targetX, targetY)
        attack.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(attack)
    }

    /**
     * Moves the selected unit along [pathStrings] to a tile, then attacks the tile at
     * [targetX]/[targetY], mirroring the original `HumanController.selectMove` (a MoveUnitAction
     * followed by an AttackUnitAction, so a melee unit can walk into range and fire this turn).
     */
    fun dispatchMoveThenAttack(pathStrings: List<String>, targetX: Int, targetY: Int) {
        val unit = selectedUnit ?: return
        val move = cn.enaium.battalion.game.actions.MoveUnitAction(
            unit.x,
            unit.y,
            cn.enaium.battalion.game.actions.MoveUnitAction.stringizePath(pathStrings),
        )
        move.isNetworked = false
        // The attack originates from the move's destination tile (the last path point), which is
        // where the unit stands when the AttackUnitAction fires.
        val destX = pathStrings[pathStrings.size - 2].toInt()
        val destY = pathStrings[pathStrings.size - 1].toInt()
        val attack = cn.enaium.battalion.game.actions.AttackUnitAction(
            destX,
            destY,
            targetX,
            targetY,
        )
        attack.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(move)
        cn.enaium.battalion.game.ActionManager.add(attack)
    }

    /** Called by the renderer to dispatch a "no attack" action (unit waits). */
    fun dispatchNoAttack() {
        val unit = selectedUnit ?: return
        val noAttack = cn.enaium.battalion.game.actions.NoAttackAction(unit.x, unit.y)
        noAttack.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(noAttack)
    }

    /** Called by the renderer to dispatch a build action. */
    fun dispatchBuild(factoryX: Int, factoryY: Int, unitName: String) {
        val build = cn.enaium.battalion.game.actions.BuildUnitAction(factoryX, factoryY, unitName)
        build.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(build)
    }

    /** Called by the renderer to dispatch an extract-resources action. */
    fun dispatchExtract(x: Int, y: Int) {
        val extract = cn.enaium.battalion.game.actions.ExtractResourcesAction(x, y)
        extract.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(extract)
    }

    /** Called by the renderer to end the player's turn. */
    fun dispatchEndTurn() {
        endTurn(cn.enaium.battalion.game.actions.EndTurnAction.REASON_VOLUNTARY)
    }

    /** Called by the renderer to undo the last move. */
    fun dispatchUndoMove() {
        undoAction?.let {
            cn.enaium.battalion.game.ActionManager.add(it)
        }
    }

    /** Called by the renderer to repair the selected unit. */
    fun dispatchRepair() {
        val unit = selectedUnit ?: return
        val repair = cn.enaium.battalion.game.actions.RepairUnitAction(unit.x, unit.y)
        repair.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(repair)
    }

    /**
     * Loads an adjacent friendly portable unit (the passenger at [passengerX]/[passengerY])
     * into the selected transport. The original applies the upgrade to the *passenger*'s tile —
     * its [Unit.unitData] swaps to the transport's template, and it is downgraded (its
     * [Unit.baseUnitData] template restored) when unloaded.
     */
    fun dispatchLoad(passengerX: Int, passengerY: Int) {
        val transport = selectedUnit ?: return
        // The selected unit must be the transport (has the "Transport" modifier).
        if (!transport.unitData.hasModifier("Transport")) return
        val passenger = GameManager.instance.unitAt(passengerX, passengerY) ?: return
        if (passenger.controller !== transport.controller) return
        if (passenger.isUpgraded()) return
        // The passenger must be adjacent to (or co-located with) the transport.
        if (kotlin.math.abs(passenger.x - transport.x) > 1 || kotlin.math.abs(passenger.y - transport.y) > 1) return
        val upgradeAction = cn.enaium.battalion.game.actions.UpgradeUnitAction(
            passenger.x, passenger.y,
            upgrade = true,
            unitType = transport.unitData.name,
        )
        upgradeAction.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(upgradeAction)
    }

    /**
     * Unloads the currently loaded unit (the passenger) selected by the player. The selected
     * unit must itself be the upgraded passenger (its [Unit.unitData] is the transport's but
     * [Unit.isUpgraded] is true).
     */
    fun dispatchUnload() {
        val unit = selectedUnit ?: return
        if (!unit.isUpgraded()) return
        val upgradeAction = cn.enaium.battalion.game.actions.UpgradeUnitAction(
            unit.x, unit.y,
            upgrade = false,
        )
        upgradeAction.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(upgradeAction)
    }
}
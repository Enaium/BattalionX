package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.Trigger
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.data.MoraleChangeAmounts

/**
 * Sends a trigger to the trigger manager. Used for game events that need to be dispatched.
 */
class SendTriggerAction(private val trigger: Trigger) : Action() {
    override fun fire() {
        super.fire()
        TriggerManager.add(trigger)
    }
}

/**
 * Converts terrain at a specific tile to a new terrain type.
 */
class ConvertTerrainAction(
    private val tileX: Int,
    private val tileY: Int,
    private val newTerrainName: String
) : Action() {
    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val tile = gameManager.tileAt(tileX, tileY) ?: return
        tile.convertTerrainTo(newTerrainName)
        gameManager.map.updateReference(tileX, tileY, newTerrainName)
    }
}

/**
 * Grants a morale bonus to a player when morale crosses a 100-mark threshold. The bonus
 * (2000 resources) is added to the controller's Warmachines or, on classic maps, directly to
 * the controller. The actual resource grant happens in [Controller.setMorale]; this action
 * only exists so the reward lands as a discrete action in the queue.
 */
class MoraleBonusAction(private val player: Int) : Action() {
    override fun fire() {
        super.fire()
        // The resource grant is handled when the morale mark is crossed; nothing further to do.
    }
}

/**
 * Indicates a unit is not attacking this turn. Marks the unit as moved so it cannot act.
 */
class NoAttackAction(private val unitX: Int, private val unitY: Int) : Action() {
    override fun usesUnit(aUnit: Unit): Boolean {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)
        return unit == aUnit
    }

    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)
        if (unit == null) {
            throw Error("NoAttackAction::Corrupt action")
        }
        if (unit.controller != gameManager.activeController) {
            throw Error("NoAttackAction::Only the units controller may do this action")
        }
        unit.darken()
        unit.moved = true
        GameManager.instance.activeController?.resetIdleTime()
    }
}

/**
 * Undoes a unit's movement, returning it to its previous position.
 */
class UndoMoveAction(
    private val previousX: Int,
    private val previousY: Int,
    private val newX: Int,
    private val newY: Int,
    private val unitWasCloaked: Boolean
) : Action() {
    private var disabled = false

    override fun update() {
        // No-op in the original
    }

    fun disable() {
        disabled = true
        // In the original this disabled the move-undo button in the UI.
    }

    override fun usesUnit(aUnit: Unit): Boolean {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(previousX, previousY)
        return unit == aUnit
    }

    override fun fire() {
        super.fire()
        fireImplied()
    }

    private fun fireImplied() {
        completeAction()
        if (disabled) return

        val gameManager = GameManager.instance
        val tile = gameManager.tileAt(newX, newY)
        val unit = gameManager.unitAt(newX, newY)
        val destination = gameManager.tileAt(previousX, previousY)

        if (destination?.unit != unit) {
            tile?.unit = null
            destination?.unit = unit
            unit?.moved = false
            unit?.reset()
            unit?.setCloaked(unitWasCloaked, false, false)
            unit?.setCloaked(false, true)
            unit?.x = destination?.x ?: previousX
            unit?.y = destination?.y ?: previousY
            gameManager.changeMorale(unit?.controller, MoraleChangeAmounts.UNDO_MOVE, MoraleChangeAmounts.UNDO_MOVE_DESCRIPTION)
        }
        gameManager.activeController?.resetIdleTime()
    }
}

/**
 * Starts the game: resets turn, round, and controller, then fires the start-game trigger.
 */
class StartGameAction : Action() {
    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        gameManager.turn = 0
        gameManager.round = 0
        gameManager.currentController = 0
        TriggerManager.add(Trigger(Trigger.START_GAME))
    }
}

/**
 * Starts a new round: increments the rounds-taken statistic for every controller and fires the
 * start-round trigger.
 */
class StartRoundAction : Action() {
    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        for (controller in gameManager.controllers) {
            controller?.team?.addStatistic(Team.ROUNDS_TAKEN, 1)
        }
        TriggerManager.add(Trigger(Trigger.START_ROUND))
    }
}
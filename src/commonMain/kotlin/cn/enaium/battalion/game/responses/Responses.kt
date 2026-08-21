package cn.enaium.battalion.game.responses

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.Response
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.conditions.BooleanVariableCondition
import cn.enaium.battalion.game.conditions.CounterVariableCondition
import cn.enaium.battalion.game.triggers.BuildUnitTrigger
import cn.enaium.battalion.game.triggers.KillUnitTrigger

/**
 * Scripted [Response]s produced by map `<script>` events. Each is a direct port of the matching
 * original `com.gamedozer.battalion.game.responses.*` class, but display-driven responses
 * (chat / highlight / scroll) delegate visuals to a listener (see [responseListener]) rather
 * than manipulating Flash sprites directly.
 */

/**
 * A hook the renderer can attach to show scripted visual responses (chat boxes, tile
 * highlights, camera scrolls). Non-display responses ignore it.
 */
object ResponseDisplay {
    /** Implemented by the renderer/UI layer to surface script visuals. */
    interface Hook {
        /** Shows or dismisses a mission voice-line chat box. */
        fun showChat(sender: String, message: String, avatar: String, color: Int)
        /** Highlights (show = true) or clears (show = false) the tile at (x, y). */
        fun highlightTile(x: Int, y: Int, show: Boolean, updateCamera: Boolean)
        /** Moves the viewport so tile (x, y) is centred. */
        fun scrollTo(x: Int, y: Int)
    }

    var hook: Hook? = null
}

/** Disables (or re-enables) morale for the whole game. */
class DisableMoraleResponse(private val disable: Boolean = true) : Response() {
    override fun fire() {
        GameManager.instance.isMoraleEnabled = !disable
        super.fire()
    }
}

/** Disables (or re-enables) AI/surrender control — i.e. freezes all controllers. */
class DisableControllersResponse(private val disable: Boolean = true) : Response() {
    override fun fire() {
        val controllers = GameManager.instance.controllers
        for (c in controllers) {
            c?.setEnabled(!disable)
        }
        TriggerManager.add(cn.enaium.battalion.game.Trigger(cn.enaium.battalion.game.Trigger.CONTROL))
        super.fire()
    }
}

/**
 * A mission voice-line. Emits to [ResponseDisplay.hook]; completes immediately so the sequence
 * continues (the renderer shows the chat for its own duration).
 */
class ChatResponse(
    private val sender: String,
    private val message: String,
    private val avatar: String,
    private val color: Int,
) : Response() {
    override fun fire() {
        ResponseDisplay.hook?.showChat(sender, message, avatar, color)
        // Synchronous: the renderer keeps the box visible; the script continues.
        completeResponse()
        super.fire()
    }
}

/** Highlights a tile for the current script sequence. */
class HighlightResponse(
    private val x: Int,
    private val y: Int,
    private val show: Boolean,
    private val updateCamera: Boolean = true,
) : Response() {
    override fun fire() {
        ResponseDisplay.hook?.highlightTile(x, y, show, updateCamera)
        completeResponse()
        super.fire()
    }
}

/** Moves the viewport to a tile. */
class ScrollResponse(private val x: Int, private val y: Int) : Response() {
    override fun fire() {
        ResponseDisplay.hook?.scrollTo(x, y)
        completeResponse()
        super.fire()
    }
}

/** Prints a trace message (kept for parity; no-op in production). */
class TraceResponse(private val message: String) : Response() {
    override fun fire() {
        super.fire()
    }
}

/**
 * Sends a [Trigger] — used to chain scripted triggers (e.g. fire a START_TURN for scripting).
 */
class SendTriggerResponse(private val trigger: cn.enaium.battalion.game.Trigger) : Response() {
    override fun fire() {
        TriggerManager.add(trigger)
        super.fire()
    }
}

/** Queues an [Action] (at the front) — used to run scripted game actions. */
class SendActionResponse(private val action: Action) : Response() {
    override fun fire() {
        ActionManager.add(action, toFront = true)
        super.fire()
    }
}

/** Awards resources to one player (player 0 = all). */
class ResourcesResponse(private val player: Int, private val amount: Int) : Response() {
    override fun fire() {
        val controllers = GameManager.instance.controllers
        if (player == 0) {
            for (c in controllers) c?.addResources(amount)
        } else {
            val controller = GameManager.instance.getController(player - 1)
            controller?.addResources(amount)
        }
        super.fire()
    }
}

/** Adds resources directly to a structure tile's pool. */
class StructureResourcesResponse(
    private val x: Int,
    private val y: Int,
    private val amount: Int,
) : Response() {
    override fun fire() {
        val tile = GameManager.instance.tileAt(x, y) ?: run { super.fire(); return }
        tile.resources += amount
        super.fire()
    }
}

/** Disables or enables all of a controller's units. */
class DisableAllUnitsResponse(private val disable: Boolean, private val player: Int) : Response() {
    override fun fire() {
        val controllers = GameManager.instance.controllers
        if (player == 0) {
            for (c in controllers) {
                if (disable) c?.disableAllUnits() else c?.enableAllUnits()
            }
        } else {
            val controller = GameManager.instance.getController(player - 1)
            if (disable) controller?.disableAllUnits() else controller?.enableAllUnits()
        }
        super.fire()
    }
}

/** Enables or disables a single unit type across a controller. */
class EnableUnitResponse(
    private val enable: Boolean,
    private val player: Int,
    private val unit: String,
) : Response() {
    override fun fire() {
        val controllers = GameManager.instance.controllers
        if (player == 0) {
            for (c in controllers) {
                if (enable) c?.enableUnit(unit) else c?.disableUnit(unit)
            }
        } else {
            val controller = GameManager.instance.getController(player - 1)
            if (enable) controller?.enableUnit(unit) else controller?.disableUnit(unit)
        }
        super.fire()
    }
}

/** Disables or enables a specific unit at a location. */
class UnitDisableResponse(
    private val x: Int,
    private val y: Int,
    private val disable: Boolean,
) : Response() {
    override fun fire() {
        val unit = GameManager.instance.unitAt(x, y)
        if (disable) unit?.disable() else unit?.enable()
        super.fire()
    }
}

/** Changes the alliance between two players. */
class ChangeAllianceResponse(
    private val playerA: Int,
    private val playerB: Int,
    private val allied: Boolean,
) : Response() {
    override fun fire() {
        val game = GameManager.instance
        val a = game.getController(playerA - 1)
        val b = game.getController(playerB - 1)
        if (a == null || b == null || !a.team.active || !b.team.active || a === b) {
            super.fire()
            return
        }
        if (allied) {
            a.team.addAlly(playerB - 1)
            b.team.addAlly(playerA - 1)
        } else {
            a.team.removeAlly(playerB - 1)
            b.team.removeAlly(playerA - 1)
        }
        super.fire()
    }
}

/** Names the unit at (x,y) so later conditions can match it by name. */
class NameUnitResponse(private val x: Int, private val y: Int, private val name: String) : Response() {
    override fun fire() {
        if (UnitManager.get(name) == null) {
            val unit = GameManager.instance.unitAt(x, y)
            if (unit != null) units[name] = unit
        }
        super.fire()
    }

    companion object {
        private val units = mutableMapOf<String, Unit>()
        fun getNamedUnit(name: String): Unit? = units[name]
    }
}

/** Awards a custom team statistic (used for scoring). */
class CustomScoreResponse(
    private val player: Int,
    private val variable: String,
    private val points: Int,
) : Response() {
    override fun fire() {
        val controller = if (player == -1) {
            GameManager.instance.getController(GameManager.instance.currentController)
        } else {
            GameManager.instance.getController(player - 1)
        } ?: run { super.fire(); return }
        controller.team.addCustomStatistic(variable, points)
        super.fire()
    }
}

/** Creates a unit at (x,y) for a player (optionally a Constructor with a resource pool). */
class CreateUnitResponse(
    private val x: Int,
    private val y: Int,
    private val type: String,
    private val hasResources: Boolean,
    private val resources: Int,
    private val player: Int,
    private val fadeIn: Boolean,
    private val parallel: Boolean,
) : Response() {
    override fun fire() {
        val game = GameManager.instance
        val unitData = UnitManager.get(type)
        if (unitData == null) { completeResponse(); return }
        if (game.unitAt(x, y) != null) { completeResponse(); return }
        val controller = game.getController(player - 1) ?: run { completeResponse(); return }
        val tile = game.tileAt(x, y) ?: run { completeResponse(); return }
        val newUnit = Unit(unitData, x, y, controller)
        newUnit.deployed = true
        if (newUnit.unitData.hasModifier("Constructor")) {
            newUnit.gainResources(resources - 2000)
        }
        game.addUnit(controller, newUnit)
        tile.unit = newUnit
        TriggerManager.add(BuildUnitTrigger(newUnit))
        completeResponse()
        super.fire()
    }
}

/** Creates a transport carrying a passenger (both present at (x,y)). */
class CreateTransportedUnitResponse(
    private val x: Int,
    private val y: Int,
    private val transportType: String,
    private val transporterType: String,
    private val player: Int,
) : Response() {
    override fun fire() {
        val game = GameManager.instance
        val transportData = UnitManager.get(transportType)
        val transporterData = UnitManager.get(transporterType)
        if (transportData == null || transporterData == null) { super.fire(); return }
        if (game.unitAt(x, y) != null) { super.fire(); return }
        val controller = game.getController(player - 1) ?: run { super.fire(); return }
        val tile = game.tileAt(x, y) ?: run { super.fire(); return }
        val newUnit = Unit(transportData, x, y, controller)
        newUnit.deployed = true
        newUnit.upgrade(transporterData)
        game.addUnit(controller, newUnit)
        tile.unit = newUnit
        TriggerManager.add(BuildUnitTrigger(newUnit))
        super.fire()
    }
}

/** Destroys (kills) the unit at (x,y). */
class DestroyUnitResponse(private val x: Int, private val y: Int) : Response() {
    override fun fire() {
        val game = GameManager.instance
        val unit = game.unitAt(x, y) ?: run { super.fire(); return }
        TriggerManager.add(KillUnitTrigger(x, y, unit))
        unit.controller?.team?.addStatistic(Team.UNITS_LOST, 1)
        game.removeUnit(unit)
        game.changeMorale(unit.controller, cn.enaium.battalion.data.MoraleChangeAmounts.UNITS_LOST,
            cn.enaium.battalion.data.MoraleChangeAmounts.UNITS_LOST_DESCRIPTION)
        super.fire()
    }
}

/** Removes the unit at (x,y) without dying ceremony. */
class RemoveUnitResponse(
    private val x: Int,
    private val y: Int,
    private val fadeOut: Boolean,
) : Response() {
    override fun fire() {
        val game = GameManager.instance
        val unit = game.unitAt(x, y) ?: run { completeResponse(); super.fire(); return }
        game.removeUnit(unit)
        completeResponse()
        super.fire()
    }
}

/** Damages the unit at (x,y); kills it if the damage reaches 0 health. */
class DamageUnitResponse(
    private val x: Int,
    private val y: Int,
    private val amount: Double,
) : Response() {
    override fun fire() {
        val game = GameManager.instance
        val unit = game.unitAt(x, y) ?: run { super.fire(); return }
        if (unit.getHealth().toDouble() <= amount) {
            TriggerManager.add(KillUnitTrigger(x, y, unit))
            unit.controller?.team?.addStatistic(Team.UNITS_LOST, 1)
            game.removeUnit(unit)
            game.changeMorale(unit.controller, cn.enaium.battalion.data.MoraleChangeAmounts.UNITS_LOST,
                cn.enaium.battalion.data.MoraleChangeAmounts.UNITS_LOST_DESCRIPTION)
        } else {
            unit.takeDamage(amount)
        }
        super.fire()
    }
}

/** Surrenders a player. */
class SurrenderPlayerResponse(private val player: Int) : Response() {
    override fun fire() {
        val surrender = cn.enaium.battalion.game.actions.SurrenderAction(player - 1)
        surrender.isNetworked = false
        ActionManager.add(surrender, toFront = true)
        super.fire()
    }
}

/** Increments a script counter variable. */
class IncrementCounterVariableResponse(private val counter: String) : Response() {
    override fun fire() {
        CounterVariableCondition.setCounter(counter, CounterVariableCondition.getCounter(counter) + 1)
        super.fire()
    }
}

/** Decrements a script counter variable. */
class DecrementCounterVariableResponse(private val counter: String) : Response() {
    override fun fire() {
        CounterVariableCondition.setCounter(counter, CounterVariableCondition.getCounter(counter) - 1)
        super.fire()
    }
}

/** Sets a script boolean variable. */
class SetBooleanVariableResponse(private val boolean: String, private val value: Boolean) : Response() {
    override fun fire() {
        BooleanVariableCondition.setBoolean(boolean, value)
        super.fire()
    }
}

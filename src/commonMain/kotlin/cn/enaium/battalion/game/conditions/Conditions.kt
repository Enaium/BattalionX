package cn.enaium.battalion.game.conditions

import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Condition
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.BuildUnitTrigger
import cn.enaium.battalion.game.triggers.CaptureTerrainTrigger
import cn.enaium.battalion.game.triggers.KillUnitTrigger
import cn.enaium.battalion.game.triggers.MoveUnitTrigger
import cn.enaium.battalion.game.triggers.PlayerTrigger
import cn.enaium.battalion.game.triggers.ResourcesAcquiredTrigger
import cn.enaium.battalion.game.triggers.TimePassedTrigger

/**
 * Concrete script conditions used by map `<script>` [cn.enaium.battalion.game.GameEvent]s.
 *
 * Each port matches the original `com.gamedozer.battalion.game.conditions.*` behaviour: it
 * registers a listener on the relevant game [Trigger] and flips [satisfied] when the observed
 * event matches its filters (unit name / player / coordinates / amount).
 */

/** Satisfied once the game starts. */
class StartGameCondition(var satisfied: Boolean = false) : Condition {
    init {
        TriggerManager.addListener(cn.enaium.battalion.game.Trigger.START_GAME) { satisfied = true }
    }
    override fun reset() { satisfied = false }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(cn.enaium.battalion.game.Trigger.START_GAME) { satisfied = true }
    }
}

/**
 * Satisfied when [player]'s turn starts (round [round] or any round when -1).
 */
class StartTurnCondition(
    private val player: Int = -1,
    private val round: Int = -1,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(PlayerTrigger.START_TURN) { trigger ->
            val pt = trigger as? PlayerTrigger ?: return@addListener
            if (player == -1 || player == pt.player) {
                if (round == -1 || round == GameManager.instance.round - 1) {
                    satisfied = true
                }
            }
        }
    }
    override fun reset() { satisfied = false }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(PlayerTrigger.START_TURN) {}
    }
}

/** Satisfied when a unit is built, filtered by name / player / location. */
class BuildUnitCondition(
    private val name: String = "",
    private val player: Int = -1,
    private val x: Int = -1,
    private val y: Int = -1,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(BuildUnitTrigger.NAME) { trigger ->
            val built = (trigger as? BuildUnitTrigger)?.unit ?: return@addListener
            if (name.isEmpty() || built.unitData.name.lowercase() == name.lowercase()) {
                if (x == -1 || x == built.x) {
                    if (y == -1 || y == built.y) {
                        val bc = built.controller
                        if (player == -1 || (bc != null && GameManager.instance.getPlayer(bc) == player)) {
                            satisfied = true
                        }
                    }
                }
            }
        }
    }
    override fun reset() { satisfied = false }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(BuildUnitTrigger.NAME) {}
    }
}

/** Satisfied when a unit is killed, filtered by name / player / location. */
class KillUnitCondition(
    private val name: String = "",
    private val player: Int = -1,
    private val x: Int = -1,
    private val y: Int = -1,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(KillUnitTrigger.NAME) { trigger ->
            val unit = (trigger as? KillUnitTrigger)?.unit ?: return@addListener
            val named = unit.baseUnitData.name.lowercase() == name.lowercase() || cn.enaium.battalion.game.responses.NameUnitResponse.getNamedUnit(name) == unit
            if (name.isEmpty() || named) {
                if (x == -1 || x == unit.x) {
                    if (y == -1 || y == unit.y) {
                        val uc = unit.controller
                        if (player == -1 || (uc != null && GameManager.instance.getPlayer(uc) == player)) {
                            satisfied = true
                        }
                    }
                }
            }
        }
    }
    override fun reset() { satisfied = false }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(KillUnitTrigger.NAME) {}
    }
}

/** Satisfied when a unit moves, filtered by name / player / location. */
class MoveUnitCondition(
    private val x: Int = -1,
    private val y: Int = -1,
    private val name: String = "",
    private val player: Int = -1,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(MoveUnitTrigger.NAME) { trigger ->
            val unit = (trigger as? MoveUnitTrigger)?.unit ?: return@addListener
            val unitData = unit.unitData
            val named = name.isNotEmpty() && unitData.name.lowercase() == name.lowercase() ||
                cn.enaium.battalion.game.responses.NameUnitResponse.getNamedUnit(name) == unit
            if (name.isEmpty() || named) {
                if (x == -1 || x == unit.x) {
                    if (y == -1 || y == unit.y) {
                        val uc = unit.controller
                        if (player == -1 || (uc != null && GameManager.instance.getPlayer(uc) == player)) {
                            satisfied = true
                        }
                    }
                }
            }
        }
    }
    override fun reset() { satisfied = false }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(MoveUnitTrigger.NAME) {}
    }
}

/** Satisfied when [player] has no units left. */
class NoUnitsCondition(
    private val player: Int,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(PlayerTrigger.NO_UNITS) { trigger ->
            val pt = trigger as? PlayerTrigger ?: return@addListener
            if (pt.player == player) satisfied = true
        }
    }
    override fun reset() { satisfied = false }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(PlayerTrigger.NO_UNITS) {}
    }
}

/** Satisfied when a structure capture completes, filtered by terrain / players. */
class CaptureTerrainCondition(
    private val name: String = "",
    private val oldPlayer: Int = -1,
    private val player: Int = -1,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(CaptureTerrainTrigger.NAME) { trigger ->
            val ct = trigger as? CaptureTerrainTrigger ?: return@addListener
            if (!ct.complete) return@addListener
            if (name.isEmpty() || ct.tile.terrain.name.lowercase() == name.lowercase()) {
                if (oldPlayer == -1 || oldPlayer == ct.oldPlayer) {
                    if (player == -1 || player == ct.player) {
                        satisfied = true
                    }
                }
            }
        }
    }
    override fun reset() { satisfied = false }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(CaptureTerrainTrigger.NAME) {}
    }
}

/** Satisfied once [player] accumulates at least [resources] resources. */
class ResourcesAcquiredCondition(
    private val resources: Int = -1,
    private val player: Int = -1,
    private var count: Int = 0,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(ResourcesAcquiredTrigger.NAME) { trigger ->
            val ra = trigger as? ResourcesAcquiredTrigger ?: return@addListener
            if (player == -1 || player == ra.player) {
                count += ra.resources
                if (count >= resources) satisfied = true
            }
        }
    }
    override fun reset() { satisfied = false; count = 0 }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(ResourcesAcquiredTrigger.NAME) {}
    }
}

/** Satisfied after [time] ms of cumulative game time. */
class TimePassedCondition(
    private val time: Int = 0,
    private var timer: Int = 0,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(TimePassedTrigger.NAME) { trigger ->
            val tp = trigger as? TimePassedTrigger ?: return@addListener
            timer += tp.ms.toInt()
            if (timer >= time) satisfied = true
        }
    }
    override fun reset() { satisfied = false; timer = 0 }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(TimePassedTrigger.NAME) {}
    }
}

/** Satisfied on a player's victory (or defeat, see [victory]). */
class EndGameCondition(
    private val player: Int = -1,
    private val victory: Boolean = true,
    var satisfied: Boolean = false,
) : Condition {
    init {
        TriggerManager.addListener(PlayerTrigger.END_GAME) { trigger ->
            val pt = trigger as? PlayerTrigger ?: return@addListener
            if (victory && (player == -1 || player == pt.player)) satisfied = true
        }
        TriggerManager.addListener(PlayerTrigger.SURRENDER) { trigger ->
            val pt = trigger as? PlayerTrigger ?: return@addListener
            if (!victory && (player == -1 || player == pt.player)) satisfied = true
        }
    }
    override fun reset() { satisfied = false }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {}
    override fun dispose() {
        TriggerManager.removeListener(PlayerTrigger.END_GAME) {}
        TriggerManager.removeListener(PlayerTrigger.SURRENDER) {}
    }
}

/** Satisfied when the named boolean script-variable reaches [value]. */
class BooleanVariableCondition(
    private val boolean: String,
    private val value: Boolean,
    var satisfied: Boolean = false,
) : Condition {
    init {
        if (booleans[boolean] == null) booleans[boolean] = !value
    }
    override fun reset() { satisfied = false; booleans[boolean] = !value }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {
        if (booleans[boolean] == value) satisfied = true
    }
    override fun dispose() {}

    companion object {
        private val booleans = mutableMapOf<String, Boolean>()
        fun getBoolean(name: String, default: Boolean = false): Boolean = booleans.getOrPut(name) { default }
        fun setBoolean(name: String, value: Boolean) { booleans[name] = value }
    }
}

/** Satisfied when the named counter script-variable reaches [value]. */
class CounterVariableCondition(
    private val counter: String,
    private val value: Int,
    private val defaultValue: Int = 0,
    var satisfied: Boolean = false,
) : Condition {
    init {
        if (counters[counter] == null) counters[counter] = defaultValue
    }
    override fun reset() { satisfied = false; counters[counter] = defaultValue }
    override fun isSatisfied(): Boolean = satisfied
    override fun update() {
        if (counters[counter] == value) satisfied = true
    }
    override fun dispose() {}

    companion object {
        private val counters = mutableMapOf<String, Int>()
        fun setCounter(name: String, value: Int) { counters[name] = value }
        fun getCounter(name: String, default: Int = 0): Int = counters.getOrPut(name) { default }
    }
}

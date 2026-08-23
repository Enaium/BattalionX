package cn.enaium.battalion.game

import cn.enaium.battalion.data.ConditionConfig
import cn.enaium.battalion.data.EventConfig
import cn.enaium.battalion.data.MapScriptConfig
import cn.enaium.battalion.data.ModifierManager
import cn.enaium.battalion.data.ScriptStateConfig
import cn.enaium.battalion.data.ScriptStructureConfig
import cn.enaium.battalion.data.ScriptUnitConfig
import cn.enaium.battalion.data.TileManager
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitManager

/**
 * Parses and executes a map's script ([MapScriptConfig]): authored state rehydration
 * (turn/round/controller, resources, units, structures) and, crucially, the [EventConfig]
 * definitions (a set of [Condition]s + [Response]s) that drive campaign and tutorial
 * scripting.
 *
 * The original `ScriptManager.as` is a singleton initialised once per the whole app; since the
 * script is per-game, the port exposes [parseScript] (called from [GameManager]'s init) and
 * keeps the parsed event set in the [GameEventManager].
 */
object ScriptManager {

    /** The script of the currently-loaded map (also consumed by [cn.enaium.battalion.game.ui.Dialogue]). */
    private var script: MapScriptConfig? = null

    /**
     * Parses [aScript]: rehydrates game state, then registers every event on the
     * [GameEventManager] so its conditions evaluate during [GameManager.update].
     */
    fun parseScript(aScript: MapScriptConfig?) {
        script = aScript ?: return
        val game = GameManager.instance

        applyState(aScript.state, game)
        for (u in aScript.units) applyUnit(u, game)
        for (s in aScript.structures) applyStructure(s, game)

        for (event in aScript.events) {
            eventFromConfig(event)?.let { game.gameEventManager.add(it) }
        }
    }

    /** Returns the currently-parsed script (used by the dialogue system). */
    fun getScript(): MapScriptConfig? = script

    private fun eventFromConfig(cfg: EventConfig): GameEvent? {
        val repeats = when {
            cfg.repeats == null -> GameEvent.REPEAT_ONCE
            else -> cfg.repeats
        }
        val event = GameEvent(repeats)
        event.source = cfg
        for (c in cfg.conditions) {
            event.addCondition(ConditionFactory.get(c))
        }
        for (r in cfg.responses) {
            event.addResponse(ResponseFactory.get(r))
        }
        return event
    }

    // ---- Rehydration -----------------------------------------------------------------

    private fun applyState(state: ScriptStateConfig, game: GameManager) {
        state.turn?.let { game.turn = it }
        state.round?.let { game.round = it }
        state.controller?.let { game.currentController = it }
        for (grant in state.resourcesPerController) {
            game.getController(grant.controller)?.addResources(grant.value)
        }
        for (i in state.disableAllUnits) {
            game.getController(i)?.disableAllUnits()
        }
        for (e in state.enabledUnits) {
            game.getController(e.controller)?.enableUnit(e.unit)
        }
    }

    private fun applyUnit(u: ScriptUnitConfig, game: GameManager) {
        val controller = game.getController(u.controller) ?: return
        val data = UnitManager.get(u.name) ?: return
        val unit = Unit(data, u.x, u.y, controller)
        unit.deployed = u.deployed
        unit.moved = u.moved
        unit.fired = u.fired
        unit.cloaked = u.cloaked
        unit.repairing = u.repairing
        u.health?.let { unit.setHealth(it) }
        // Constructor resource pool: restore the exact saved amount.
        u.resourcePool?.let { unit.resourcePool = it }
        // Upgraded units re-resolve to their upgrade template.
        if (u.upgrade.isNotEmpty()) {
            UnitManager.get(u.upgrade)?.let { unit.upgrade(it) }
        }
        // Re-apply temporary modifiers (e.g. "Cloak Damage") from the snapshot.
        for (modName in u.modifiers) {
            ModifierManager.get(modName)?.let { unit.unitData.addTemporaryModifier(it) }
        }
        game.addUnit(controller, unit)
        game.tileMap[u.y][u.x].unit = unit
    }

    private fun applyStructure(s: ScriptStructureConfig, game: GameManager) {
        val controller = s.controller?.let { game.getController(it) }
        val tile = game.tileAt(s.x, s.y) ?: return
        val terrain = TileManager.get(s.name)
        if (!tile.hasStructure) {
            tile.upgrade(terrain, controller)
        } else {
            tile.terrain = terrain
            tile.controller = controller
        }
        s.chunks?.let { tile.chunks = it }
        // Re-attach the in-progress capturer (-1 = none).
        if (s.capturerX != -1 && s.capturerY != -1) {
            tile.capturer = game.unitAt(s.capturerX, s.capturerY)
        }
        game.addStructure(controller, tile)
    }
}

package cn.enaium.battalion.game

import cn.enaium.battalion.data.GameMap
import cn.enaium.battalion.util.XmlNode

/**
 * Parses and executes the map's `<script>` XML: `<gamestate>`/`<resources>`/`<unit>`/
 * `<structure>` rehydration and, crucially, the `<event>` definitions (a set of
 * [Condition]s + [Response]s) that drive campaign and tutorial scripting.
 *
 * The original `ScriptManager.as` is a singleton initialised once per the whole app; since the
 * map script is per-game, the port exposes [parseScript] (called from [GameManager]'s init) and
 * keeps the parsed event set in the [GameEventManager].
 */
object ScriptManager {

    /** The script XML of the currently-loaded map, used by [ScriptManager.getScript]. */
    private var script: XmlNode? = null

    /**
     * Parses [aXml] (the map `<script>` node): rehydrates game state, then registers every
     * `<event>` on the [GameEventManager] so its conditions evaluate during [GameManager.update].
     */
    fun parseScript(aXml: XmlNode?) {
        script = aXml ?: return
        val game = GameManager.instance
        if (aXml == null) return

        // --- Optional rehydration nodes (resume saves / authored state) --------------------
        applyGamestate(aXml.children("gamestate"), game)
        applyResources(aXml.children("resources"), game)
        applyUnits(aXml.children("unit"), game)
        applyStructures(aXml.children("structure"), game)
        applyStructureResources(aXml.children("structureresources"), game)
        applyDisableAllUnits(aXml.children("disableAllUnits"), game)
        applyEnableUnits(aXml.children("enableUnit"), game)

        // --- Event definitions: <event><condition .../><response .../>...</event> ----------
        for (eventNode in aXml.children("event")) {
            val event = eventFromXml(eventNode)
            if (event != null) {
                game.gameEventManager.add(event)
            }
        }
    }

    /** Returns the currently-parsed script XML (the map's <script> node). */
    fun getScript(): XmlNode? = script

    private fun eventFromXml(node: XmlNode): GameEvent? {
        val repeatsString = node.attr("repeats")
        val repeats = when {
            repeatsString.isEmpty() -> GameEvent.REPEAT_ONCE
            repeatsString.lowercase().startsWith("i") -> GameEvent.REPEAT_FOREVER
            else -> repeatsString.toIntOrNull() ?: GameEvent.REPEAT_ONCE
        }
        val event = GameEvent(repeats)
        event.sourceXml = node
        for (conditionNode in node.children("condition")) {
            val c = ConditionFactory.get(conditionNode)
            event.addCondition(c)
        }
        for (responseNode in node.children("response")) {
            event.addResponse(ResponseFactory.get(responseNode))
        }
        return event
    }

    // ---- Rehydration helpers -------------------------------------------------------------

    private fun applyGamestate(rows: List<XmlNode>, game: GameManager) {
        for (gs in rows) {
            gs.attr("turn").toIntOrNull()?.let { game.turn = it }
            gs.attr("round").toIntOrNull()?.let { game.round = it }
            gs.attr("controller").toIntOrNull()?.let { game.currentController = it }
        }
    }

    private fun applyResources(rows: List<XmlNode>, game: GameManager) {
        for (r in rows) {
            val controller = r.attr("controller").toIntOrNull()?.let { game.getController(it) } ?: continue
            controller.addResources(r.attr("value").toIntOrNull() ?: 0)
        }
    }

    private fun applyUnits(rows: List<XmlNode>, game: GameManager) {
        for (unitNode in rows) {
            val controller = unitNode.attr("controller").toIntOrNull()?.let { game.getController(it) } ?: continue
            val name = unitNode.attr("name")
            val data = cn.enaium.battalion.data.UnitManager.get(name) ?: continue
            val x = unitNode.attr("x").toIntOrNull() ?: 0
            val y = unitNode.attr("y").toIntOrNull() ?: 0
            val unit = cn.enaium.battalion.data.Unit(data, x, y, controller)
            val status = unitNode.child("status")
            unit.deployed = status?.attr("deployed")?.startsWith("t") != false
            unit.moved = status?.attr("moved")?.startsWith("t") == true
            unit.fired = status?.attr("fired")?.startsWith("t") == true
            unit.cloaked = status?.attr("cloaked")?.startsWith("t") == true
            unit.repairing = status?.attr("repairing")?.startsWith("t") == true
            unitNode.child("health")?.attr("value")?.toDoubleOrNull()?.let { unit.setHealth(it) }
            // Constructor resource pool: restore the exact saved amount (the original draws the
            // default pool down to 0, then gains the saved value, so the net is the snapshot).
            unitNode.child("resourcePool")?.attr("value")?.toIntOrNull()?.let {
                unit.resourcePool = it
            }
            // Upgraded units re-resolve to their upgrade template.
            unitNode.child("upgrade")?.attr("name")?.let { upgradeName ->
                cn.enaium.battalion.data.UnitManager.get(upgradeName)?.let { unit.upgrade(it) }
            }
            // Re-apply temporary modifiers (e.g. "Cloak Damage") from the snapshot.
            for (modNode in unitNode.children("modifier")) {
                val modName = modNode.attr("value")
                if (modName.isNotEmpty()) {
                    cn.enaium.battalion.data.ModifierManager.get(modName)?.let { unit.unitData.addTemporaryModifier(it) }
                }
            }
            game.addUnit(controller, unit)
            game.tileMap[y][x].unit = unit
        }
    }

    private fun applyStructures(rows: List<XmlNode>, game: GameManager) {
        for (structNode in rows) {
            val controller = structNode.attr("controller").toIntOrNull()?.let { game.getController(it) }
            val x = structNode.attr("x").toIntOrNull() ?: 0
            val y = structNode.attr("y").toIntOrNull() ?: 0
            val tile = game.tileAt(x, y) ?: continue
            val terrain = cn.enaium.battalion.data.TileManager.get(structNode.attr("name")) ?: continue
            if (!tile.hasStructure) {
                tile.upgrade(terrain, controller)
            } else {
                tile.terrain = terrain
                tile.controller = controller
            }
            structNode.attr("chunks").toIntOrNull()?.let { tile.chunks = it }
            // Re-attach the in-progress capturer (capturerx/y = -1 when none).
            val capturerX = structNode.attr("capturerx").toIntOrNull() ?: -1
            val capturerY = structNode.attr("capturery").toIntOrNull() ?: -1
            if (capturerX != -1 && capturerY != -1) {
                tile.capturer = game.unitAt(capturerX, capturerY)
            }
            game.addStructure(controller, tile)
        }
    }

    private fun applyStructureResources(rows: List<XmlNode>, game: GameManager) {
        for (sr in rows) {
            val tile = game.tileAt(sr.attr("x").toIntOrNull() ?: -1, sr.attr("y").toIntOrNull() ?: -1) ?: continue
            tile.resources = sr.attr("value").toIntOrNull() ?: 0
        }
    }

    private fun applyDisableAllUnits(rows: List<XmlNode>, game: GameManager) {
        for (d in rows) {
            game.getController(d.attr("controller").toIntOrNull() ?: 0)?.disableAllUnits()
        }
    }

    private fun applyEnableUnits(rows: List<XmlNode>, game: GameManager) {
        for (e in rows) {
            game.getController(e.attr("controller").toIntOrNull() ?: 0)?.enableUnit(e.attr("unit"))
        }
    }
}

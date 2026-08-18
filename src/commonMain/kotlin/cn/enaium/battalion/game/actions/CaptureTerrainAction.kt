package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.CaptureTerrainTrigger

/**
 * Progresses a structure capture by the unit standing on it.
 *
 * Each capture action reduces the structure's capture chunks by the unit's capture strength
 * (a fraction of its health). When the chunks reach zero the structure's ownership flips to
 * the capturing unit's controller (unless it is Mission Critical, which cannot be captured).
 */
class CaptureTerrainAction(
    private val unitX: Int,
    private val unitY: Int
) : Action() {
    override fun usesUnit(aUnit: Unit): Boolean {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)
        return unit == aUnit
    }

    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)
        val tile = gameManager.tileAt(unitX, unitY)

        if (unit == null || tile == null) {
            completeAction()
            throw Error("CaptureTerrainAction::Corrupt capture action")
        }
        if (unit.controller != gameManager.activeController) {
            completeAction()
            throw Error("CaptureTerrainAction::Only the units controller may capture with the unit")
        }

        val oldPlayer = tile.controller?.let { gameManager.getPlayer(it) } ?: -1

        // Reset capture progress if there's no capturer or a different controller's unit is capturing
        if (tile.capturer == null) {
            tile.chunks = GameManager.TILE_CHUNKS
        } else if (unit.controller != tile.capturer?.controller) {
            tile.chunks = GameManager.TILE_CHUNKS
        }

        // If the unit already owns the structure, nothing to capture
        if (unit.controller == tile.controller) {
            tile.capturer = null
            completeAction()
            return
        }

        if (unit.unitData.hasModifier("Capture") || !unit.isDeployed()) {
            fireImplied()
            return
        }

        completeAction()
        throw Error("CaptureTerrainAction::Unit cannot complete action, can not capture!")
    }

    private fun fireImplied() {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)
        val tile = gameManager.tileAt(unitX, unitY)

        if (unit == null || tile == null) {
            completeAction()
            return
        }

        val oldPlayer = tile.controller?.let { gameManager.getPlayer(it) } ?: -1

        // Reduce capture chunks by the unit's capture strength
        tile.capturer = unit
        tile.chunks = tile.chunks - unit.getChunks(GameManager.UNIT_CHUNKS)

        if (tile.chunks <= 0) {
            // Capture complete
            tile.controller?.team?.addStatistic(Team.STRUCTURES_LOST, 1)
            unit.controller?.team?.addStatistic(Team.STRUCTURES_CAPTURED, 1)

            // Transfer ownership (unless Mission Critical)
            if (!tile.terrain.hasModifier("Mission Critical")) {
                tile.controller?.removeStructure(tile)
                unit.controller?.addStructure(tile)
            }

            tile.chunks = GameManager.TILE_CHUNKS
            TriggerManager.add(
                CaptureTerrainTrigger(tile, oldPlayer, unit.controller?.let { gameManager.getPlayer(it) } ?: -1, true)
            )
        } else {
            TriggerManager.add(
                CaptureTerrainTrigger(tile, oldPlayer, unit.controller?.let { gameManager.getPlayer(it) } ?: -1, false)
            )
        }

        gameManager.activeController?.resetIdleTime()
    }

    override fun update() {
        // No-op in the original
    }
}
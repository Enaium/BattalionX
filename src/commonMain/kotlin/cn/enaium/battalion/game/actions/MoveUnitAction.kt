package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.MoveUnitTrigger

/**
 * Moves a unit along a path from its current position to a destination.
 *
 * This action handles:
 * - Path validation and movement
 * - Jammer detection for cloaked units
 * - Cloak handling (re-cloaking after movement, uncloaking when detected)
 * - Unit movement along the path
 * - Camera following
 * - Trigger dispatch
 */
class MoveUnitAction(
    private val unitX: Int,
    private val unitY: Int,
    private val pathString: String
) : Action() {
    private val path: List<String> = pathString.split(",")
    private var undoAction: UndoMoveAction? = null
    private var jammedTile: Tile? = null
    private var uncloakUnit: Unit? = null
    private var destination: Tile? = null

    companion object {
        fun stringizePath(path: List<String>): String = path.joinToString(",")
        fun unstringizePath(pathString: String): List<String> = pathString.split(",")
    }

    override fun usesUnit(aUnit: Unit): Boolean {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY)
        return unit == aUnit
    }

    override fun fire() {
        super.fire()
        val gameManager = GameManager.instance
        val tile = gameManager.tileAt(unitX, unitY)
        val unit = gameManager.unitAt(unitX, unitY)
        val nodePath = gameManager.nodePath(path)

        if (tile == null || unit == null || nodePath == null) {
            completeAction()
            throw Error("MoveUnitAction::Corrupt movement action")
        }

        if (unit.controller != gameManager.activeController) {
            completeAction()
            throw Error("MoveUnitAction::Only the units controller may move the unit")
        }

        if (unit.hasMoved()) {
            completeAction()
            throw Error("MoveUnitAction::Unit cannot complete movement action. (Already moved | Invalid path)")
        }

        if (!gameManager.canMove(unit, nodePath)) {
            completeAction()
            throw Error("MoveUnitAction::${unit} cannot complete movement action to ${destination}. (Invalid path)")
        }

        gameManager.activeController?.resetIdleTime()
        gameManager.activeController?.setMovedAUnitThisTurn(true)

        // Check for jammers if unit has cloaking ability
        val jammers = mutableListOf<Unit>()
        if (unit.unitData.hasModifier("Cloaking")) {
            val allUnits = gameManager.units
            for (otherUnit in allUnits.reversed()) {
                if (otherUnit.controller?.isAllied(unit.controller) != true) {
                    if (otherUnit.unitData.hasModifier("Jamming")) {
                        jammers.add(otherUnit)
                    }
                }
            }
        }

        var canDoCloakBeforeMove = unit.unitData.hasModifier("Cloaking")

        // Process path to find destination and detect jammers/cloaked units
        val nodeList = nodePath.toMutableList()
        for (i in 1 until nodeList.size) {
            val tileT = nodeList[i]
            val unitT = tileT.unit

            if (unitT != null) {
                if (i == nodeList.size - 1 || unitT.controller?.isAllied(unit.controller) != true) {
                    // Trim path to stop before the occupied tile
                    while (nodeList.lastIndex >= i) {
                        nodeList.removeAt(nodeList.lastIndex)
                    }
                    // Ensure we stop at a valid tile
                    while (nodeList.isNotEmpty() && nodeList.last().unit != null && nodeList.last().unit != unit) {
                        nodeList.removeAt(nodeList.lastIndex)
                    }

                    if (unitT.controller?.isAllied(unit.controller) != true) {
                        uncloakUnit = unitT
                        disableUndo()
                    }
                    break
                }
            }

            // Check for jammer proximity
            for (jammerUnit in jammers) {
                val dx = kotlin.math.abs(jammerUnit.x - tileT.x)
                val dy = kotlin.math.abs(jammerUnit.y - tileT.y)
                val jammerRange = 2
                if (dx + dy <= jammerRange) {
                    if (i == 1) {
                        canDoCloakBeforeMove = false
                    }
                    jammedTile = tileT
                    disableUndo()
                }
            }
        }

        destination = nodeList.lastOrNull()

        if (nodeList.size >= 2) {
            // Store the full walk path (each tile to visit, starting from the unit's current
            // tile) and the starting tile. The walk is animated over [Unit.MOVE_STEP_MS] per
            // tile; the action only completes (teleporting the unit, setting `moved`, firing the
            // move trigger) once the unit has visually arrived, so queued follow-ups such as an
            // attack fire in sync with the arrival rather than before it.
            unit.movePath = nodeList.toList()
            unit.prevX = unitX
            unit.prevY = unitY
            unit.moveProgressMs = 0
            unit.isMoving = true
            unit.moveStartMs = GameManager.instance.elapsedMs

            val from = nodeList[nodeList.size - 2]
            val to = nodeList[nodeList.size - 1]
            unit.facing = facingFor(from.x, from.y, to.x, to.y)
            unit.animFacing = unit.facing
            unit.startAnim(Unit.UnitAnim.MOVE)
            lastUpdateMs = unit.moveStartMs
            return   // update() drains the walk, then fireImplied() + completion
        }

        // Zero-step move (should not normally happen): nothing to walk, finish immediately.
        fireImplied()
    }

    /** The game-clock ms of the most recent [update] tick (for per-frame walk advancement). */
    private var lastUpdateMs: Long = -1

    override fun update() {
        // Already completed via a zero-step fire() (or corrupted): nothing to do.
        if (unitWalkComplete) return
        val gameManager = GameManager.instance
        var unit = gameManager.unitAt(unitX, unitY) ?: run { completeAction(); unitWalkComplete = true; return }

        val now = gameManager.elapsedMs
        val dt = if (lastUpdateMs >= 0) now - lastUpdateMs else Unit.MOVE_STEP_MS
        lastUpdateMs = now
        unit.moveProgressMs += dt.coerceAtMost(Unit.MOVE_STEP_MS * 4)

        val totalMs = (unit.movePath.size - 1) * Unit.MOVE_STEP_MS
        if (unit.moveProgressMs >= totalMs) {
            unit.moved = true
            unit.isMoving = false
            unit.movePath = emptyList()
            fireImplied()
            unit = gameManager.unitAt(unitX, unitY) ?: return
            completeAction()
            unitWalkComplete = true
        }
    }

    private var unitWalkComplete: Boolean = false

    private fun fireImplied() {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(unitX, unitY) ?: return

        // Teleport the unit to its destination and, crucially, mark it as having moved.  This
        // is what enforces the one-move-per-turn rule — the unit cannot be moved (or re-selected
        // to act) again until its controller's next turn.
        val tile = gameManager.tileAt(unitX, unitY)
        tile?.unit = null
        destination?.unit = unit
        unit.x = destination?.x ?: unitX
        unit.y = destination?.y ?: unitY
        unit.moved = true

        // Handle cloaking after movement
        if (unit.unitData.hasModifier("Cloaking")) {
            var canCloakAtNewPosition = true
            val neighborX = listOf(-1, 1, 0, 0)
            val neighborY = listOf(0, 0, -1, 1)

            for (n in neighborX.indices) {
                val neighbor = gameManager.unitAt(
                    (destination?.x ?: 0) + neighborX[n],
                    (destination?.y ?: 0) + neighborY[n]
                )
                if (neighbor != null) {
                    if (neighbor.controller?.isAllied(unit.controller) != true) {
                        if (neighbor.unitData.name != "Blockade") {
                            canCloakAtNewPosition = false
                        }
                    }
                }
            }

            if (jammedTile == null && canCloakAtNewPosition) {
                unit.cloaked = true
            } else if (unit.isCloaked()) {
                disableUndo()
                unit.cloaked = false
            }
        }

        // Dispatch move unit trigger
        TriggerManager.add(MoveUnitTrigger(unit, destination?.let { listOf(it) } ?: emptyList()))

        // Handle uncloaking of discovered units
        if (uncloakUnit != null) {
            uncloakUnit?.cloaked = false
            if (unit.unitData.hasModifier("Tracking")) {
                // In the original, this would add an attack action
                val attackAction = AttackUnitAction(
                    unit.x, unit.y,
                    uncloakUnit?.x ?: 0, uncloakUnit?.y ?: 0
                )
                ActionManager.addSubsequentAction(this, attackAction)
            } else {
                unit.darken()
                unit.moved = true
                unit.fired = true
            }
            disableUndo()
        }

        // Radar reveal of cloaked units
        val radarRange = unit.unitData.applyModifiers("radar", unit, 1.0)
        val startTile = gameManager.tileAt(unit.x, unit.y) ?: return
        val radarTiles = Astar.range(startTile, 0.0, radarRange)
        for (radarTile in radarTiles) {
            val otherUnit = (radarTile as? Tile)?.unit
            if (otherUnit != null) {
                if (otherUnit.controller?.isAllied(unit.controller) != true) {
                    if (otherUnit.unitData.hasModifier("Cloaking") && otherUnit.isCloaked()) {
                        otherUnit.cloaked = false
                        disableUndo()
                    }
                }
            }
        }

        // Sort units and reset idle time
        // In the original, this would sort units and reset idle time
    }

    private fun disableUndo() {
        undoAction?.disable()
    }

    private fun direction(x1: Int, y1: Int, x2: Int, y2: Int): String {
        return when {
            y1 == y2 -> if (x1 < x2) "right" else "left"
            y1 < y2 -> "down"
            else -> "up"
        }
    }

    /** Compass facing for a move from (x1,y1) to (x2,y2): 0=N, 1=E, 2=S, 3=W. */
    private fun facingFor(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val dx = x2 - x1
        val dy = y2 - y1
        return when {
            dy < 0 -> 0  // north
            dy > 0 -> 2  // south
            dx > 0 -> 1  // east
            dx < 0 -> 3  // west
            else -> 0
        }
    }
}
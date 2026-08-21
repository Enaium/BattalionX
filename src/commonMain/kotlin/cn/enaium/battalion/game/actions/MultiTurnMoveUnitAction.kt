package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.ActionManager

/**
 * Moves a unit toward a destination that is several turns away, possibly across an island
 * boundary (a ground unit crossing water via a Leviathan Barge, or a multi-turn route).
 *
 * On the first fire the unit either:
 *  - reaches its destination *this turn* → a plain [MoveUnitAction] to the target, or
 *  - walks toward an internal interim destination returned by
 *    [GameManager.getMovementCombiner]. If that interim destination is where the unit already
 *    stands (it needs to board / is already aboard a barge), the unit is *upgraded* into a
 *    "Leviathan Barge" transport (or downgraded when it has arrived) so it can cross water.
 *
 * Unlike [MoveUnitAction] this action persists across turns: it re-queues itself via
 * [ActionManager.addSubsequentAction] until the unit reaches [destinationX]/[destinationY].
 *
 * Mirrors `com.gamedozer.battalion.game.actions.MultiTurnMoveUnitAction`.
 */
class MultiTurnMoveUnitAction(
    private val unitX: Int,
    private val unitY: Int,
    private val destinationX: Int,
    private val destinationY: Int,
) : Action() {

    override fun usesUnit(aUnit: Unit): Boolean {
        val unit = GameManager.instance.unitAt(unitX, unitY)
        return unit == aUnit
    }

    override fun fire() {
        super.fire()
        val game = GameManager.instance
        val unit = game.unitAt(unitX, unitY) ?: return
        if (!unit.canMove()) {
            // Already used this turn.
            completeAction()
            return
        }
        val startTile = game.tileAt(unit.x, unit.y) ?: return
        val endTile = game.tileAt(destinationX, destinationY) ?: return
        val movementCombiner = game.movementCombiner
        val interimDestination = movementCombiner.getInterimDestination(
            startTile, endTile, unit, Tile.FLAG_IGNORE_LAST_NODE,
        ) ?: run {
            // Something is blocking the route.
            completeAction()
            return
        }

        val turnCount = movementCombiner.getTurnCount()
        if (turnCount == 0) {
            // Reachable this turn.
            if (unit.isUpgraded()) {
                // Disembark first, then walk toward the (now reachable) target.
                ActionManager.addSubsequentAction(this, CallbackAction({
                    moveUnitCloserToTarget(unit, endTile)
                }, "MTMUA:delayedMove"))
                ActionManager.addSubsequentAction(this, UpgradeUnitAction(unit.x, unit.y, upgrade = false))
                completeAction()
                return
            }
            val path = Astar.pathTrim(startTile, endTile, unit, Tile.FLAG_IGNORE_LAST_NODE)?.toMutableList()
                ?: run { completeAction(); return }
            val last = path.last() as? Tile
            if (last != null && last.unit != null && !last.unit!!.isCloaked()) {
                path.removeAt(path.lastIndex)
            }
            ActionManager.addSubsequentAction(
                this,
                MoveUnitAction(unitX, unitY, MoveUnitAction.stringizePath(tilesToStrings(path.filterIsInstance<Tile>()))),
            )
            completeAction()
            return
        }

        if (interimDestination === startTile) {
            // Board / already aboard the barge; then move closer to the target.
            ActionManager.addSubsequentAction(this, CallbackAction({
                moveUnitCloserToTarget(unit, endTile)
            }, "MTMUA:delayedMove"))
            ActionManager.addSubsequentAction(
                this,
                UpgradeUnitAction(unit.x, unit.y, if (unit.isUpgraded()) false else true, "Leviathan Barge"),
            )
            completeAction()
            return
        }

        ActionManager.addSubsequentAction(this, CallbackAction({
            moveUnitCloserToTarget(unit, interimDestination)
        }, "MTMUA:moveCloser"))
        completeAction()
    }

    internal fun moveUnitCloserToTarget(aUnit: Unit, aEndTile: Tile) {
        if (!aUnit.canMove()) return
        val game = GameManager.instance
        val startTile = game.tileAt(aUnit.x, aUnit.y) ?: return
        val path = Astar.pathTrim(startTile, aEndTile, aUnit, 0)?.toMutableList() ?: return
        val tiles = path.filterIsInstance<Tile>().toMutableList()
        while (tiles.isNotEmpty() && tiles.last().unit != null) {
            tiles.removeAt(tiles.lastIndex)
        }
        if (tiles.isEmpty()) return
        if (aUnit.canMove()) {
            ActionManager.add(
                MoveUnitAction(aUnit.x, aUnit.y, MoveUnitAction.stringizePath(tilesToStrings(tiles))),
            )
        }
    }

    private fun tilesToStrings(path: List<Tile>): List<String> {
        val strings = mutableListOf<String>()
        for (tile in path) {
            strings.add(tile.x.toString())
            strings.add(tile.y.toString())
        }
        return strings
    }

    override fun update() {
        // Multi-turn movement progresses one step per fire; nothing asynchronous here.
    }
}

package cn.enaium.battalion.game.triggers

import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.game.Trigger

/** A trigger tied to a specific player index. */
open class PlayerTrigger(name: String, val player: Int) : Trigger(name) {
    companion object {
        const val START_TURN = "startTurn"
        const val END_GAME = "endGame"
        const val NO_UNITS = "noUnits"
        const val SURRENDER = "surrender"
    }
}

/** Fired once per frame with how many milliseconds have elapsed. */
class TimePassedTrigger(val ms: Long) : Trigger("timePassed")

/** A unit finished moving along [path]. */
class MoveUnitTrigger(val unit: Unit, val path: List<Tile>) : Trigger("moveUnit")

/** A unit attacked another unit. */
class AttackUnitTrigger(
    val unitX: Int,
    val unitY: Int,
    val targetX: Int,
    val targetY: Int,
    val unit: Unit,
    val target: Unit,
) : Trigger("attackUnit")

/** A unit was destroyed. */
class KillUnitTrigger(val unitX: Int, val unitY: Int, val unit: Unit) : Trigger("killUnit")

/** A unit was built by a constructor. */
class BuildUnitTrigger(val unit: Unit) : Trigger("buildUnit")

/**
 * A structure capture progressed or completed.
 * [oldPlayer] is the previous owner; [player] the capturing player; [complete] is true when
 * the capture finished and ownership flipped.
 */
class CaptureTerrainTrigger(
    val tile: Tile,
    val oldPlayer: Int,
    val player: Int,
    val complete: Boolean,
) : Trigger(NAME) {
    companion object {
        const val NAME = "captureTerrain"
    }
}

/** A unit was upgraded or downgraded. */
class UpgradeUnitTrigger(val unit: Unit, val upgradeName: String, val upgrade: Boolean) : Trigger("upgradeUnit")

/** A unit was repaired. */
class RepairUnitTrigger(val unit: Unit) : Trigger("repairUnit")

/** A controller acquired [resources] from its structures. */
class ResourcesAcquiredTrigger(val player: Int, val resources: Int) : Trigger("resourcesAcquired")

/** A unit extracted resources from an ore deposit. */
class ExtractResourcesTrigger(val unit: Unit) : Trigger("extractUnit")
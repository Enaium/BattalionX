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

/** Fired regularly with how many milliseconds have elapsed. */
class TimePassedTrigger(val ms: Long) : Trigger(NAME) {
    companion object {
        const val NAME = "timePassed"
    }
}

/** A unit finished moving along [path]. */
class MoveUnitTrigger(val unit: Unit, val path: List<Tile>) : Trigger(NAME) {
    companion object {
        const val NAME = "moveUnit"
    }
}

/**
 * A unit began its walking animation (the first tile step).
 *
 * The movement sound fires on this trigger, not on the later [MoveUnitTrigger]: the original
 * queues the move sound as the action starts (simultaneously with the first step), while the
 * completion trigger only fires after the walk has fully drained.
 */
class MoveStartTrigger(val unit: Unit) : Trigger(NAME) {
    companion object {
        const val NAME = "moveStart"
    }
}

/** A unit attacked another unit. */
class AttackUnitTrigger(
    val unitX: Int,
    val unitY: Int,
    val targetX: Int,
    val targetY: Int,
    val unit: Unit,
    val target: Unit,
) : Trigger(NAME) {
    companion object {
        const val NAME = "attackUnit"
    }
}

/**
 * A unit began its firing animation (the muzzle-flash moment).
 *
 * The weapon sound fires on this trigger, not on the later [AttackUnitTrigger]: the original
 * queues `fireAttackerSound` as the FIRST sequential tween, i.e. simultaneously with the
 * muzzle flash, while impact damage/explosion lands after the animation completes.
 */
class AttackStartTrigger(val unit: Unit) : Trigger(NAME) {
    companion object {
        const val NAME = "attackStart"
    }
}

/** A unit was destroyed. */
class KillUnitTrigger(val unitX: Int, val unitY: Int, val unit: Unit) : Trigger(NAME) {
    companion object {
        const val NAME = "killUnit"
    }
}

/** A unit was built by a constructor. */
class BuildUnitTrigger(val unit: Unit) : Trigger(NAME) {
    companion object {
        const val NAME = "buildUnit"
    }
}

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
class ResourcesAcquiredTrigger(val player: Int, val resources: Int) : Trigger(NAME) {
    companion object {
        const val NAME = "resourcesAcquired"
    }
}

/** A unit extracted resources from an ore deposit. */
class ExtractResourcesTrigger(val unit: Unit) : Trigger("extractUnit")
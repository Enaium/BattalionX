package cn.enaium.battalion.game

import cn.enaium.battalion.data.ConditionConfig
import cn.enaium.battalion.game.conditions.BooleanVariableCondition
import cn.enaium.battalion.game.conditions.BuildUnitCondition
import cn.enaium.battalion.game.conditions.CaptureTerrainCondition
import cn.enaium.battalion.game.conditions.CounterVariableCondition
import cn.enaium.battalion.game.conditions.EndGameCondition
import cn.enaium.battalion.game.conditions.KillUnitCondition
import cn.enaium.battalion.game.conditions.MoveUnitCondition
import cn.enaium.battalion.game.conditions.NoUnitsCondition
import cn.enaium.battalion.game.conditions.ResourcesAcquiredCondition
import cn.enaium.battalion.game.conditions.StartGameCondition
import cn.enaium.battalion.game.conditions.StartTurnCondition
import cn.enaium.battalion.game.conditions.TimePassedCondition

/**
 * Builds a [Condition] from a typed [ConditionConfig] inside a map script.
 *
 * Mirrors the original `com.gamedozer.battalion.game.ConditionFactory.get`. Player/coordinate
 * values in the configs are 1-indexed in the original ("player 1" = controller index 0), so
 * they are decremented when parsed. An unknown/empty condition parses to an always-true
 * [LogicalAndCondition].
 */
object ConditionFactory {
    fun get(cfg: ConditionConfig): Condition {
        return when (cfg.type) {
            "not" -> NotCondition(ConditionFactory.get(cfg.conditions.firstOrNull() ?: ConditionConfig("startGame")))
            "and" -> {
                val and = LogicalAndCondition()
                for (c in cfg.conditions) and.add(get(c))
                and
            }
            "or" -> {
                val or = LogicalOrCondition()
                for (c in cfg.conditions) or.add(get(c))
                or
            }
            "xor" -> {
                val xor = LogicalXorCondition()
                for (c in cfg.conditions) xor.add(get(c))
                xor
            }
            "startGame" -> StartGameCondition(cfg.satisfied)
            "startTurn" -> StartTurnCondition(
                (cfg.player ?: 1) - 1,
                cfg.round ?: -1,
                cfg.satisfied,
            )
            "counterVariable" -> CounterVariableCondition(
                cfg.name.ifEmpty { cfg.boolean },
                cfg.value ?: 0,
                cfg.defaultValue ?: 0,
                cfg.satisfied,
            )
            "booleanVariable" -> BooleanVariableCondition(
                cfg.boolean,
                cfg.value?.let { it != 0 } ?: false,
                cfg.satisfied,
            )
            "timePassed" -> TimePassedCondition(
                cfg.time ?: 0,
                0,
                cfg.satisfied,
            )
            "endGame" -> EndGameCondition(
                (cfg.player ?: 1) - 1,
                cfg.victory,
                cfg.satisfied,
            )
            "moveUnit" -> MoveUnitCondition(
                cfg.x ?: -1,
                cfg.y ?: -1,
                cfg.unitName,
                cfg.player ?: -1,
                cfg.satisfied,
            )
            "killUnit" -> KillUnitCondition(
                cfg.unitName,
                (cfg.player ?: 1) - 1,
                cfg.x ?: -1,
                cfg.y ?: -1,
                cfg.satisfied,
            )
            "resourcesAcquired" -> ResourcesAcquiredCondition(
                cfg.resources ?: -1,
                (cfg.player ?: 1) - 1,
                cfg.count ?: 0,
                cfg.satisfied,
            )
            "buildUnit" -> BuildUnitCondition(
                cfg.unitName,
                (cfg.player ?: 1) - 1,
                cfg.x ?: -1,
                cfg.y ?: -1,
                cfg.satisfied,
            )
            "captureTerrain" -> CaptureTerrainCondition(
                cfg.terrainName,
                (cfg.oldPlayer ?: 1) - 1,
                (cfg.player ?: 1) - 1,
                cfg.satisfied,
            )
            "noUnits" -> NoUnitsCondition(
                (cfg.player ?: 1) - 1,
                cfg.satisfied,
            )
            else -> LogicalAndCondition()
        }
    }
}

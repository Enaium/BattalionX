package cn.enaium.battalion.game

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
import cn.enaium.battalion.util.XmlNode

/**
 * Builds a [Condition] from a `<condition>` XML node inside a map `<script>`.
 *
 * Mirrors the original `com.gamedozer.battalion.game.ConditionFactory.get`. Player/coordinate
 * values in the XML are 1-indexed in the original ("player 1" = controller index 0), so they are
 * decremented when parsed. An unknown/empty condition parses to an always-true [LogicalAndCondition].
 */
object ConditionFactory {
    fun get(node: XmlNode): Condition {
        val type = node.attr("type")
        return when (type) {
            "not" -> NotCondition(ConditionFactory.get(node.child("condition") ?: XmlNode("condition", emptyMap(), emptyList(), "")))
            "and" -> {
                val and = LogicalAndCondition()
                for (c in node.children("condition")) and.add(get(c))
                and
            }
            "or" -> {
                val or = LogicalOrCondition()
                for (c in node.children("condition")) or.add(get(c))
                or
            }
            "xor" -> {
                val xor = LogicalXorCondition()
                for (c in node.children("condition")) xor.add(get(c))
                xor
            }
            "startGame" -> StartGameCondition(node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true)
            "startTurn" -> StartTurnCondition(
                node.child("player")?.value()?.toIntOrNull()?.minus(1) ?: 0,
                node.child("round")?.value()?.toIntOrNull() ?: -1,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "counterVariable" -> CounterVariableCondition(
                node.child("name")?.value() ?: node.child("counter")?.value() ?: "",
                node.child("value")?.value()?.toIntOrNull() ?: 0,
                node.child("defaultValue")?.value()?.toIntOrNull() ?: 0,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "booleanVariable" -> BooleanVariableCondition(
                node.child("boolean")?.value() ?: "",
                node.child("value")?.value()?.lowercase()?.startsWith("t") == true,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "timePassed" -> TimePassedCondition(
                node.child("time")?.value()?.toIntOrNull() ?: 0,
                0,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "endGame" -> EndGameCondition(
                node.child("player")?.value()?.toIntOrNull()?.minus(1) ?: 0,
                node.child("victory")?.value()?.lowercase()?.startsWith("f") != true,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "moveUnit" -> MoveUnitCondition(
                node.child("x")?.value()?.toIntOrNull() ?: -1,
                node.child("y")?.value()?.toIntOrNull() ?: -1,
                node.child("unitName")?.value() ?: "",
                node.child("player")?.value()?.toIntOrNull() ?: -1,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "killUnit" -> KillUnitCondition(
                node.child("unitName")?.value() ?: "",
                node.child("player")?.value()?.toIntOrNull()?.minus(1) ?: 0,
                node.child("x")?.value()?.toIntOrNull() ?: -1,
                node.child("y")?.value()?.toIntOrNull() ?: -1,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "resourcesAcquired" -> ResourcesAcquiredCondition(
                node.child("resources")?.value()?.toIntOrNull() ?: -1,
                node.child("player")?.value()?.toIntOrNull()?.minus(1) ?: 0,
                node.child("count")?.value()?.toIntOrNull() ?: 0,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "buildUnit" -> BuildUnitCondition(
                node.child("unitName")?.value() ?: "",
                node.child("player")?.value()?.toIntOrNull()?.minus(1) ?: 0,
                node.child("x")?.value()?.toIntOrNull() ?: -1,
                node.child("y")?.value()?.toIntOrNull() ?: -1,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "captureTerrain" -> CaptureTerrainCondition(
                node.child("terrainName")?.value() ?: "",
                node.child("oldPlayer")?.value()?.toIntOrNull()?.minus(1) ?: 0,
                node.child("player")?.value()?.toIntOrNull()?.minus(1) ?: 0,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            "noUnits" -> NoUnitsCondition(
                node.child("player")?.value()?.toIntOrNull()?.minus(1) ?: 0,
                node.child("satisfied")?.attr("value")?.lowercase()?.startsWith("t") == true,
            )
            else -> LogicalAndCondition()
        }
    }
}

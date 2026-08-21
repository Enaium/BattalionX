package cn.enaium.battalion.game

import cn.enaium.battalion.game.triggers.PlayerTrigger
import cn.enaium.battalion.util.XmlNode

/**
 * Builds a [Trigger] from a `<trigger>` XML node (used by [ResponseFactory] for
 * sendTrigger responses). The original only parsed a handful of triggers; we cover the common
 * player-driven ones and fall back to a bare [Trigger].
 */
object TriggerFactory {
    fun get(node: XmlNode): Trigger {
        val type = node.attr("type")
        val player = node.child("player")?.value()?.toIntOrNull()?.minus(1) ?: -1
        return when (type) {
            "startTurn" -> PlayerTrigger(PlayerTrigger.START_TURN, player)
            "endGame" -> PlayerTrigger(PlayerTrigger.END_GAME, player)
            "surrender" -> PlayerTrigger(PlayerTrigger.SURRENDER, player)
            "noUnits" -> PlayerTrigger(PlayerTrigger.NO_UNITS, player)
            else -> Trigger(type.ifEmpty { "control" })
        }
    }
}

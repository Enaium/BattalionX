package cn.enaium.battalion.game

import cn.enaium.battalion.data.TriggerConfig
import cn.enaium.battalion.game.triggers.PlayerTrigger

/**
 * Builds a [Trigger] from a typed [TriggerConfig] (used by [ResponseFactory] for
 * sendTrigger responses). The original only parsed a handful of triggers; we cover the
 * common player-driven ones and fall back to a bare [Trigger].
 */
object TriggerFactory {
    fun get(cfg: TriggerConfig): Trigger {
        val player = (cfg.player ?: 0)
        return when (cfg.type) {
            "startTurn" -> PlayerTrigger(PlayerTrigger.START_TURN, player)
            "endGame" -> PlayerTrigger(PlayerTrigger.END_GAME, player)
            "surrender" -> PlayerTrigger(PlayerTrigger.SURRENDER, player)
            "noUnits" -> PlayerTrigger(PlayerTrigger.NO_UNITS, player)
            else -> Trigger(cfg.type.ifEmpty { "control" })
        }
    }
}

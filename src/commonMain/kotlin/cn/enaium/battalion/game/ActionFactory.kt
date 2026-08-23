package cn.enaium.battalion.game

import cn.enaium.battalion.data.ResponseConfig
import cn.enaium.battalion.game.actions.SurrenderAction

/**
 * Builds an [Action] from a scripted `sendAction` response (the original only reconstructed
 * a SurrenderAction, which carries its player index).
 */
object ActionFactory {
    fun get(cfg: ResponseConfig): Action {
        val player = (cfg.player ?: 1) - 1
        return SurrenderAction(player).apply { isNetworked = false }
    }
}

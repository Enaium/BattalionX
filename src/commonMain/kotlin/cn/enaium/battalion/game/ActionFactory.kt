package cn.enaium.battalion.game

import cn.enaium.battalion.game.actions.SurrenderAction
import cn.enaium.battalion.util.XmlNode

/**
 * Builds a [Action] from an `<action>`/`<SurrenderAction>` XML node (used by the scripted
 * sendAction response). The original only reconstructed a SurrenderAction.
 */
object ActionFactory {
    fun get(node: XmlNode): Action {
        // SurrenderAction carries a @player attribute.
        val player = node.attr("player").toIntOrNull()?.minus(1) ?: 0
        return SurrenderAction(player).apply { isNetworked = false }
    }
}

package cn.enaium.battalion.game.hal

import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.controllers.HALController

/**
 * Heuristic AI Logic — the brain of the AI controller.
 *
 * A simplified port of the original HAL.as. The AI creates a [HALTurn] at the start of each
 * turn, which plans and executes actions (move, attack, build, capture, extract) in chunks.
 */
class HAL(private val controller: HALController) {
    private var turn: HALTurn? = null
    private val game: GameManager get() = GameManager.instance

    fun getController(): HALController = controller

    fun startTurn() {
        turn?.dispose()
        turn = null
    }

    /**
     * Called every frame during the AI's turn. On the first call it creates a [HALTurn];
     * on subsequent calls it delegates to the turn's tick.
     */
    fun tick() {
        if (turn == null) {
            turn = HALTurn(this)
            return
        }
        turn?.tick()
    }
}
package cn.enaium.battalion.game.controllers

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.Controller
import cn.enaium.battalion.game.Trigger
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.actions.CallbackAction
import cn.enaium.battalion.game.hal.HAL

/**
 * AI controller — a simplified port of the original HAL (Heuristic AI Logic) system.
 *
 * The original HAL uses a complex goal/worker/builder architecture with priority queues.
 * This port implements a simpler but functional AI that:
 *  1. Attacks enemies in range
 *  2. Moves toward enemies
 *  3. Builds units from factories
 *  4. Captures structures
 *  5. Extracts resources
 *
 * The AI runs its logic in chunks via [HAL], which is called each frame while it's the
 * controller's turn.
 */
class HALController(team: Team) : Controller(team) {
    private var hal: HAL? = null
    private var control: Boolean = false

    init {
        hal = HAL(this)
        TriggerManager.addListener(Trigger.CONTROL) { trigger ->
            controlListener()
        }
    }

    override fun startTurn(player: Int) {
        control = false
    }

    override fun endGame() {
        // No-op for AI.
    }

    override fun startCurrentTurn() {
        super.startCurrentTurn()
        control = true
        hal?.startTurn()
    }

    private fun controlListener() {
        if (control && isEnabled()) {
            // Run the AI's tick loop until the action queue is empty or we hit a time limit.
            val startTime = kotlin.time.TimeSource.Monotonic.markNow()
            var ticks = 0
            while (startTime.elapsedNow().inWholeMilliseconds < 40 && ActionManager.isEmpty()) {
                hal?.tick()
                ticks++
            }
            if (ActionManager.isEmpty()) {
                // If the AI has nothing to do, queue an empty action to keep the loop moving.
                ActionManager.add(CallbackAction({}, "HALController:nothing"))
            }
        }
    }

    override fun dispose() {
        super.dispose()
        hal = null
    }
}
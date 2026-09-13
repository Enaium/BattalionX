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

    /** True when the AI queued an action that has since finished (its animation played out). */
    private var aiActed = false

    /** Game-clock ms when the AI's last action finished; -1 before the first one. */
    private var lastActionEndMs = -1L

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
        lastActionEndMs = -1L
        aiActed = false
        hal?.startTurn()
    }

    private fun controlListener() {
        if (control && isEnabled()) {
            val now = cn.enaium.battalion.game.GameManager.instance.elapsedMs
            if (aiActed) {
                // The AI's action just finished: note when, so the pause below starts counting
                // from the end of the action (with its animation) rather than from its start.
                aiActed = false
                lastActionEndMs = now
            }
            if (lastActionEndMs >= 0 && now - lastActionEndMs < ACTION_GAP_MS) {
                // Hold the turn open without acting: the AI's units move and fight in a rhythm
                // the player can follow instead of several acting within the same moment.
                ActionManager.add(CallbackAction({}, "HALController:gap"))
                return
            }
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
            } else {
                aiActed = true
            }
        }
    }

    override fun dispose() {
        super.dispose()
        hal = null
    }

    companion object {
        /**
         * Pause (ms) between two AI actions. The original gets its pacing from longer action
         * animations; the port's walk and fire animations are shorter, so without this the AI's
         * units act back to back and the player cannot follow what the opponent is doing.
         */
        private const val ACTION_GAP_MS = 500L
    }
}
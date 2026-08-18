package cn.enaium.battalion.game

import cn.enaium.battalion.data.Unit

/**
 * Executes [Action]s one at a time, in order.
 *
 * The queue holds actions produced by the human controller, the AI, and the rules themselves
 * (e.g. an attack that triggers a counterattack schedules further actions). [update] is called
 * every frame: it fires the current action if it hasn't fired yet, otherwise updates it until
 * it completes, then moves on. When the queue runs empty a [Trigger.CONTROL] trigger is
 * dispatched — controllers and the AI use that as the signal that control has been returned.
 */
object ActionManager {
    val instance: ActionManager get() = this // For compatibility with GameManager
    private val actionQueue = mutableListOf<Action>()
    private var currentAction: Action? = null

    fun add(action: Action, toFront: Boolean = false) {
        if (toFront) actionQueue.add(0, action) else actionQueue.add(action)
    }

    fun isEmpty(): Boolean = currentAction == null && actionQueue.isEmpty()

    fun hasActionWithUnit(unit: Unit): Boolean =
        actionQueue.any { it.usesUnit(unit) }

    fun clear() {
        currentAction = null
        actionQueue.clear()
    }

    fun update() {
        if (currentAction == null) {
            if (actionQueue.isNotEmpty()) {
                currentAction = actionQueue.removeAt(0)
            }
        } else {
            val action = currentAction!!
            try {
                if (!action.isFired()) {
                    action.fire()
                } else {
                    action.update()
                }
            } catch (t: Throwable) {
                // An action that throws must not wedge the game loop: report and move on.
                TriggerManager.add(Trigger(Trigger.ERROR))
            }
            if (action.isCompleted()) {
                action.dispose()
                currentAction = null
                if (actionQueue.isEmpty()) {
                    TriggerManager.add(Trigger(Trigger.CONTROL))
                }
            }
        }
    }

    /** Queues [subsequent] as a consequence of [cause] firing (e.g. an attack on reveal). */
    fun addSubsequentAction(cause: Action, subsequent: Action) {
        subsequent.isNetworked = false
        add(subsequent, toFront = true)
    }
}
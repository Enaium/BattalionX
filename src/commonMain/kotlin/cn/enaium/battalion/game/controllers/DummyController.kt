package cn.enaium.battalion.game.controllers

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.game.Controller

/**
 * A controller that does nothing — used for empty player slots or network placeholders.
 * The original also had subtypes for "HumanDummy" and "AIDummy".
 */
class DummyController(
    team: Team,
    private val dummyType: String = DUMMY_TYPE_HUMAN,
) : Controller(team) {

    companion object {
        const val DUMMY_TYPE_HUMAN = "HumanDummy"
        const val DUMMY_TYPE_AI = "AIDummy"
    }

    val type: String get() = dummyType

    override fun startCurrentTurn() {
        super.startCurrentTurn()
        // Dummy does nothing and ends its turn immediately.
    }
}
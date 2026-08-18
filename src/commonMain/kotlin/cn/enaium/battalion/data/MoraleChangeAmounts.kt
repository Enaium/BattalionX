package cn.enaium.battalion.data

/**
 * Morale adjustment amounts applied to a controller for various game events, along with the
 * human-readable descriptions used in UI messages.
 */
object MoraleChangeAmounts {
    const val UNITS_LOST: Double = -0.01
    const val UNITS_LOST_DESCRIPTION: String = "lost a unit"

    const val UNITS_KILLED: Double = 0.01
    const val UNITS_KILLED_DESCRIPTION: String = "killed a unit"

    const val LINCHPIN_LOST: Double = -0.15
    const val LINCHPIN_LOST_DESCRIPTION: String = "lost a Warmachine"

    const val UNITS_BUILT: Double = 0.0075
    const val UNITS_BUILT_DESCRIPTION: String = "built a unit"

    const val EXTRACTED_RESOURCES: Double = 0.025
    const val EXTRACTED_RESOURCES_DESCRIPTION: String = "extracted resources"

    const val UNDO_MOVE: Double = -0.1
    const val UNDO_MOVE_DESCRIPTION: String = "undid a move"

    const val PER_TURN_SUDDEN_DEATH_PENALTY: Double = -0.01
    const val PER_TURN_SUDDEN_DEATH_PENALTY_DESCRIPTION: String = "sudden death penalty"

    const val PER_TURN_LACK_OF_ATTACK_PENALTY: Double = -0.05
    const val PER_TURN_LACK_OF_ATTACK_PENALTY_DESCRIPTION: String = "failed to attack recently"

    const val CAUSED_DAMAGED: Double = 0.00004
    const val CAUSED_DAMAGED_DESCRIPTION: String = "damaged an enemy unit"

    const val REPAIR_UNIT: Double = -0.00025
    const val REPAIR_UNIT_DESCRIPTION: String = "repaired a unit"

    const val DELAY_OF_GAME: Double = -0.02
    const val DELAY_OF_GAME_DESCRIPTION: String = "took too long to act"
}
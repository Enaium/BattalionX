package cn.enaium.battalion.data

import cn.enaium.battalion.game.Controller
import cn.enaium.battalion.game.controllers.DummyController
import cn.enaium.battalion.game.controllers.HALController
import cn.enaium.battalion.game.controllers.HumanController

/**
 * A team of one or more players. Teams hold the game *statistics* (units built/killed/lost,
 * structures captured/lost, resources...) used to compute the final score, plus the set of
 * allied player indices used for allied-unit checks.
 *
 * Each team also describes the difficulty and controller type of its player — used to create
 * the [Controller] that drives its units ("Human", "AI", or a dummy type).
 */
class Team(
    val name: String,
    val teamName: String,
    val avatarString: String,
    val rank: Int,
    val color: UInt,
    val controllerType: String,
    var difficulty: Int,
    var active: Boolean = true,
) {
    val allies: MutableList<Int> = mutableListOf()
    private val stats = IntArray(STATISTICS)
    private val customStats = mutableMapOf<String, Int>()

    companion object {
        // Statistic slots (mirrors Team.as).
        const val UNITS_BUILT = 0
        const val UNITS_KILLED = 1
        const val UNITS_LOST = 2
        const val STRUCTURES_CAPTURED = 3
        const val STRUCTURES_LOST = 4
        const val RESOURCES_COLLECTED = 5
        const val FINAL_SCORE = 6
        const val TIME_TAKEN = 7
        const val ROUNDS_TAKEN = 8
        const val SEA_UNITS_BUILT = 9
        const val AIR_UNITS_BUILT = 10
        const val GROUND_UNITS_BUILT = 11
        const val ORE_EXTRACTED = 12
        const val RESOURCES_SPENT = 13
        const val KILLED_PLAYER1_UNIT = 14
        const val KILLED_PLAYER2_UNIT = 15
        const val KILLED_PLAYER3_UNIT = 16
        const val KILLED_PLAYER4_UNIT = 17
        const val RESOURCE_DAMAGE_CAUSED = 18
        const val STATISTICS = 19

        /** Score multiplier per unit of each statistic. */
        val MULTIPLIERS: Map<Int, Int> = mapOf(
            UNITS_BUILT to 500,
            UNITS_KILLED to 1000,
            UNITS_LOST to -100,
            STRUCTURES_CAPTURED to 2500,
            STRUCTURES_LOST to -1000,
            RESOURCES_COLLECTED to 1,
        )

        const val VICTORY_BONUS = 5000

        fun getScoreModifier(scoreType: Int): Int = MULTIPLIERS[scoreType] ?: 0

        /**
         * Builds a team from its typed [TeamConfig] (see [MapSettings] / campaign docs).
         * AI teams inherit the mission difficulty; human teams are always difficulty 1.
         */
        fun fromConfig(cfg: TeamConfig, missionDifficulty: Int = 1): Team {
            val isHuman = cfg.controllerType == "Human"
            val d = if (isHuman) 1.0
                    else cfg.difficulty.takeIf { it > 0 }?.toDouble() ?: missionDifficulty.toDouble()
            return Team(
                name = cfg.name,
                teamName = cfg.teamName,
                avatarString = cfg.avatar,
                rank = cfg.rank,
                color = cfg.color.toUIntOrNull(16) ?: 0u,
                controllerType = cfg.controllerType,
                difficulty = d.toInt(),
                active = cfg.active,
            ).apply {
                for ((i, v) in cfg.stats.withIndex()) addStatistic(i, v)
                for (ally in cfg.allies) addAlly(ally)
            }
        }

        /** Restores a team from a saved-game snapshot. */
        fun fromSave(cfg: TeamConfig): Team = fromConfig(cfg)
    }

    /** True if this team is allied with player index [player]. */
    fun isAllied(player: Int): Boolean = allies.contains(player)

    fun addAlly(player: Int) {
        if (allies.contains(player)) return
        allies.add(player)
    }

    fun removeAlly(player: Int) {
        allies.remove(player)
    }

    fun getStatistic(statistic: Int): Int = stats[statistic]

    fun addStatistic(statistic: Int, add: Int) {
        if (statistic in stats.indices) stats[statistic] += add
    }

    fun addCustomStatistic(statistic: String, add: Int) {
        customStats[statistic] = (customStats[statistic] ?: 0) + add
    }

    /**
     * Computes the final score:
     *  (sum of statistic*multiplier + victory bonus) / rounds taken, scaled by difficulty.
     */
    fun calculateFinalScore() {
        var score = 0
        for ((stat, mult) in MULTIPLIERS) score += stats[stat] * mult
        if (active) score += VICTORY_BONUS
        score /= maxOf(1, stats[ROUNDS_TAKEN])
        var scoreModifier = 1.0
        if (difficulty == Controller.EASY) scoreModifier = 0.5
        else if (difficulty == Controller.HARD) scoreModifier = 2.0
        score = (score * scoreModifier).toInt()
        score = maxOf(0, score)
        stats[FINAL_SCORE] = score
    }


    /** Creates the controller that drives this team's units. */
    fun createController(): Controller = when (controllerType) {
        "Human" -> HumanController(this)
        DummyController.DUMMY_TYPE_HUMAN -> DummyController(this, DummyController.DUMMY_TYPE_HUMAN)
        "AI" -> HALController(this)
        DummyController.DUMMY_TYPE_AI -> DummyController(this, DummyController.DUMMY_TYPE_AI)
        else -> Controller(this)
    }
}
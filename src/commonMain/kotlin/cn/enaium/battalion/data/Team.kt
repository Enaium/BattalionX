package cn.enaium.battalion.data

import cn.enaium.battalion.game.Controller
import cn.enaium.battalion.game.controllers.DummyController
import cn.enaium.battalion.game.controllers.HALController
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.util.XmlNode

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
         * Builds a team from a `<team>` element (see [MapSettings.getFromXML]).
         *
         * ```
         * <team active="true">
         *   <name value="Player 1"/>
         *   <teamname value="Blue"/>
         *   <color value="ff0000"/>
         *   <avatar value="..."/>
         *   <rank value="1"/>
         *   <controllertype value="Human"/>
         *   <difficulty value="1"/>
         *   <stats value="0,0,..."/>
         *   <allies value="0"/>
         * </team>
         * ```
         */
        fun getFromXML(xml: XmlNode): Team {
            val activeString = xml.attr("active")
            val active = activeString.lowercase().firstOrNull() == 't'

            var d = xml.child("difficulty")?.value()?.toDoubleOrNull()
            val controllerType = xml.child("controllertype")?.value() ?: ""
            if (d == null || d.isNaN() || controllerType == "Human") d = 1.0

            val t = Team(
                name = xml.child("name")?.value() ?: "",
                teamName = xml.child("teamname")?.value() ?: "",
                avatarString = xml.child("avatar")?.value() ?: "",
                rank = xml.child("rank")?.value()?.toIntOrNull() ?: 0,
                color = (xml.child("color")?.value()?.toUIntOrNull(16) ?: 0u),
                controllerType = controllerType,
                difficulty = d.toInt(),
                active = active,
            )

            xml.child("stats")?.value()?.let { statsString ->
                if (statsString.isNotEmpty()) {
                    val parts = statsString.split(",")
                    for (i in parts.indices) t.addStatistic(i, parts[i].toIntOrNull() ?: 0)
                }
            }
            xml.child("allies")?.value()?.let { alliesString ->
                if (alliesString.isNotEmpty()) {
                    for (ally in alliesString.split(",")) t.addAlly(ally.toIntOrNull() ?: 0)
                }
            }
            return t
        }
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

    /**
     * Serializes this team for a saved game (mirrors `Team.toXML` in the original).
     *
     * ```
     * <team active="true">
     *   <name value="Player 1"/>
     *   <teamname value="Blue"/>
     *   <color value="ff0000"/>
     *   <avatar value="..."/>
     *   <rank value="1"/>
     *   <controllertype value="Human"/>
     *   <difficulty value="1"/>
     *   <stats value="0,0,..."/>
     *   <allies value="0"/>
     * </team>
     * ```
     */
    fun toXML(includeStats: Boolean = true): String {
        val sb = StringBuilder("<team active=\"$active\">")
        sb.append("<name value=\"${escape(name)}\"/>")
        sb.append("<teamname value=\"${escape(teamName)}\"/>")
        sb.append("<color value=\"${color.toString(16)}\"/>")
        sb.append("<avatar value=\"${escape(avatarString)}\"/>")
        sb.append("<rank value=\"$rank\"/>")
        sb.append("<controllertype value=\"$controllerType\"/>")
        sb.append("<difficulty value=\"$difficulty\"/>")
        if (includeStats) {
            sb.append("<stats value=\"${stats.joinToString(",")}\"/>")
        }
        sb.append("<allies value=\"${allies.joinToString(",")}\"/>")
        sb.append("</team>")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** Creates the controller that drives this team's units. */
    fun createController(): Controller = when (controllerType) {
        "Human" -> HumanController(this)
        DummyController.DUMMY_TYPE_HUMAN -> DummyController(this, DummyController.DUMMY_TYPE_HUMAN)
        "AI" -> HALController(this)
        DummyController.DUMMY_TYPE_AI -> DummyController(this, DummyController.DUMMY_TYPE_AI)
        else -> Controller(this)
    }
}
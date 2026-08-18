package cn.enaium.battalion.data

import cn.enaium.battalion.util.XmlNode

/**
 * The settings for a single mission / multiplayer match: the round limit, starting resources,
 * difficulty, the campaign it belongs to, medal thresholds, and the list of [Team]s.
 */
class MapSettings(
    var rounds: Int,
    var resources: Int,
    var difficulty: Int,
    var campaignName: String = "",
    var campaign: Int = -1,
    var silverMedalThreshold: Int = -1,
    var goldMedalThreshold: Int = -1,
) {
    var map: GameMap? = null
    var useUnlockedUnits: Boolean = false
    val teams: MutableList<Team> = mutableListOf()

    fun getTeam(i: Int): Team = teams[i]

    fun addTeam(team: Team) {
        teams.add(team)
    }

    /**
     * Parses a `<mapsettings>` element. AI teams inherit the mission difficulty; human teams
     * are always difficulty 1.
     *
     * ```
     * <mapsettings rounds="12" resources="3000" difficulty="1" campaignname="..." campaign="0"
     *              silverMedal="..." goldMedal="...">
     *   <teams><team .../></teams>
     * </mapsettings>
     * ```
     */
    companion object {
        fun getFromXML(xml: XmlNode): MapSettings {
            val silver = xml.attr("silverMedal").toIntOrNull() ?: -1
            val gold = xml.attr("goldMedal").toIntOrNull() ?: -1
            val ms = MapSettings(
                rounds = xml.attr("rounds").toIntOrNull() ?: 0,
                resources = xml.attr("resources").toIntOrNull() ?: 0,
                difficulty = xml.attr("difficulty").toIntOrNull() ?: 1,
                campaignName = xml.attr("campaignname"),
                campaign = xml.attr("campaign").toIntOrNull() ?: -1,
                silverMedalThreshold = silver,
                goldMedalThreshold = gold,
            )
            val teams = xml.child("teams")
            if (teams != null) {
                for (teamNode in teams.children("team")) {
                    val team = Team.getFromXML(teamNode)
                    ms.addTeam(team)
                }
            }
            return ms
        }
    }
}
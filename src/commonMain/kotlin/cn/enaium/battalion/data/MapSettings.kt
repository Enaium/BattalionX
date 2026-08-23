package cn.enaium.battalion.data

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
     * Snapshot for a saved game: settings plus every team's full state (stats, allies).
     * [GameLoader.loadSavedGame] feeds this back through [fromConfig].
     */
    fun toConfig(): MissionSettingsConfig = MissionSettingsConfig(
        rounds = rounds,
        resources = resources,
        difficulty = difficulty,
        silverMedal = silverMedalThreshold,
        goldMedal = goldMedalThreshold,
        teams = teams.map { t ->
            TeamConfig(
                name = t.name,
                teamName = t.teamName,
                color = t.color.toString(16),
                avatar = t.avatarString,
                rank = t.rank,
                controllerType = t.controllerType,
                difficulty = t.difficulty,
                active = t.active,
                stats = (0 until Team.STATISTICS).map { t.getStatistic(it) },
                allies = t.allies.toList(),
            )
        },
    )

    companion object {
        /**
         * Builds settings from a typed [MissionSettingsConfig]. AI teams inherit the mission
         * difficulty; human teams are always difficulty 1. [difficultyOverride] replaces the
         * mission difficulty (the menu's Easy/Normal/Hard selection).
         */
        fun fromConfig(
            cfg: MissionSettingsConfig,
            campaignIndex: Int = -1,
            campaignName: String = "",
            difficultyOverride: Int? = null,
        ): MapSettings {
            val ms = MapSettings(
                rounds = cfg.rounds,
                resources = cfg.resources,
                difficulty = difficultyOverride ?: cfg.difficulty,
                campaignName = campaignName,
                campaign = campaignIndex,
                silverMedalThreshold = cfg.silverMedal,
                goldMedalThreshold = cfg.goldMedal,
            )
            for (team in cfg.teams) {
                if (!team.active) continue
                ms.addTeam(Team.fromConfig(team, ms.difficulty))
            }
            // The mission's difficulty applies to every AI team; the human is always 1.
            if (difficultyOverride != null) {
                for (team in ms.teams) {
                    team.difficulty = if (team.controllerType == "AI") difficultyOverride else 1
                }
            }
            return ms
        }
    }
}

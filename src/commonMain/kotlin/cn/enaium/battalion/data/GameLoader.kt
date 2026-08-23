package cn.enaium.battalion.data

import cn.enaium.battalion.game.GameManager

/**
 * Loads the game's static data and builds a ready-to-run [GameManager] for a chosen mission
 * or skirmish map.
 *
 * Every document is typed JSON (see [AssetConfigs] for the on-disk contract):
 *  - `modifiers/{unit,terrain}/<id>.json` — rule modifiers, imported FIRST because terrain /
 *    unit configs only reference them by name.
 *  - `units/<id>.json` / `terrain/<id>.json` — one file per template.
 *  - `ballistics.json`, `sprites.json`.
 *  - `campaigns/campaign.json` + `campaigns/bootcamp.json`.
 *  - `maps/<Map>.json`.
 *
 * Mods add content by dropping files into these folders; nothing else changes.
 */
object GameLoader {

    class MissionInfo(
        val number: Int,
        val name: String,
        /** Map base name resolved under `maps/` (e.g. "Mission_1", "bootcamp/batut_basics"). */
        val mapId: String,
        val settings: MissionSettingsConfig,
        val brief: String = "",
    ) {
        /** Kept for callers that key saves/medals off the old URL form. */
        val mapUrl: String get() = "maps/$mapId"
    }

    /**
     * Imports the registries: unit+terrain modifiers, then terrain, then units, then
     * ballistics. Order matters — modifier definitions must exist before the registries that
     * resolve them by name.
     */
    fun loadAssets(assetsDir: String) {
        AssetLoader.clearCache()
        ModifierManager.clear()
        TileManager.clear()
        UnitManager.clear()
        ModifierManager.importAll(assetsDir)
        TileManager.importAll(assetsDir)
        UnitManager.importAll(assetsDir)
        AssetLoader.ballistics(assetsDir)?.let { BallisticsTable.load(it) }
    }

    /** Parses a campaign document into its ordered list of missions. */
    fun parseCampaign(cfg: CampaignConfig): List<MissionInfo> =
        cfg.levels
            .map { level ->
                MissionInfo(
                    number = level.number,
                    name = level.name,
                    mapId = level.map,
                    settings = level.settings,
                    brief = level.brief,
                )
            }
            .sortedBy { it.number }

    /** Finds the Ep3 single-player campaign (not bootcamp). */
    fun findCampaign(assetsDir: String): List<MissionInfo>? =
        findCampaignNamed(assetsDir, isBootcamp = false)

    /** Finds the Ep3 bootcamp campaign. */
    fun findBootcamp(assetsDir: String): List<MissionInfo>? =
        findCampaignNamed(assetsDir, isBootcamp = true)

    /** Finds a campaign document (`bootcamp` for the tutorial, otherwise the Ep3 campaign). */
    fun findCampaignNamed(assetsDir: String, isBootcamp: Boolean): List<MissionInfo>? {
        val cfg = AssetLoader.campaign(assetsDir, if (isBootcamp) "bootcamp" else "campaign")
            ?: return null
        return parseCampaign(cfg)
    }

    /** Loads a single-player campaign mission ([missionIndex], 0-based). */
    fun loadCampaignMission(
        assetsDir: String,
        missionIndex: Int,
        difficultyOverride: Int? = null,
    ): GameManager {
        loadAssets(assetsDir)
        val missions = findCampaign(assetsDir)
            ?: error("GameLoader: no campaign document found in $assetsDir")
        if (missionIndex !in missions.indices) {
            error("GameLoader: mission $missionIndex out of range (0..${missions.lastIndex})")
        }
        val mission = missions[missionIndex]
        val mapCfg = findMap(assetsDir, mission.mapId)
            ?: error("GameLoader: could not find map for '${mission.mapId}'")
        return buildGame(mapCfg, mission.settings,
            difficultyOverride = difficultyOverride,
            mapUrl = "maps/${mission.mapId}", mapName = mission.mapId.substringAfterLast("/"))
            .also { it.setCampaignContext(missionIndex, bootcamp = false, "maps/${mission.mapId}") }
    }

    /** Loads a single-player bootcamp mission ([missionIndex], 0-based). */
    fun loadBootcampMission(
        assetsDir: String,
        missionIndex: Int,
        difficultyOverride: Int? = null,
    ): GameManager {
        loadAssets(assetsDir)
        val missions = findBootcamp(assetsDir)
            ?: error("GameLoader: no bootcamp document found in $assetsDir")
        if (missionIndex !in missions.indices) {
            error("GameLoader: bootcamp mission $missionIndex out of range (0..${missions.lastIndex})")
        }
        val mission = missions[missionIndex]
        val mapCfg = findMap(assetsDir, mission.mapId)
            ?: error("GameLoader: could not find map for '${mission.mapId}'")
        return buildGame(mapCfg, mission.settings,
            difficultyOverride = difficultyOverride,
            mapUrl = "maps/${mission.mapId}", mapName = mission.mapId.substringAfterLast("/"))
            .also { it.setCampaignContext(missionIndex, bootcamp = true, "maps/${mission.mapId}") }
    }

    /** True when a campaign [MissionInfo]'s map file is actually present and loadable. */
    fun isMissionLoadable(assetsDir: String, mission: MissionInfo): Boolean =
        findMap(assetsDir, mission.mapId) != null

    /**
     * Reconstructs a [GameManager] from a serialized saved game ([GameManager.toSave]).
     *
     * The map is re-loaded from the save's [SavedGameConfig.mapId], the settings come from
     * the snapshot, and its script section rehydrates the exact turn/round/controller/
     * resources/units/structures before the new [GameManager] is returned — it is NOT
     * started; the caller resumes with [GameManager.resumeFromSave].
     */
    fun loadSavedGame(assetsDir: String, save: SavedGameConfig): GameManager {
        loadAssets(assetsDir)
        val mapCfg = findMap(assetsDir, save.mapId)
            ?: error("GameLoader: save references unknown map '${save.mapId}'")
        return buildGame(mapCfg, save.settings, save.script,
            mapUrl = "maps/${save.mapId}",
            mapName = save.mapName.ifEmpty { save.mapId.substringAfterLast('/') })
    }

    /** Public access to the parsed [MapConfig] for a mission (used by the menu minimap). */
    fun findMapPublic(assetsDir: String, mapId: String): MapConfig? = findMap(assetsDir, mapId)

    /**
     * Loads a skirmish / multiplayer map. [mapFile] is a map base name (e.g. `Mission_1`),
     * resolved to `maps/<base>.json`. Player 1 is Human, every other team is AI.
     */
    fun loadSkirmish(assetsDir: String, mapFile: String): GameManager {
        loadAssets(assetsDir)
        val base = mapFile.substringBeforeLast('.').removePrefix("maps/")
        val mapCfg = findMap(assetsDir, base)
            ?: error("GameLoader: cannot read map file $mapFile")

        // Build default settings with one Human + AI per team found in the map palette.
        val map = GameMap(mapCfg, "maps/$base", base)
        val teamCount = map.palette.map { it.ownerTeam() }.filter { it > 0 }.maxOrNull() ?: 2
        val teams = (1..teamCount).map { i ->
            TeamConfig(
                name = "Player $i",
                teamName = "Team $i",
                color = if (i == 1) "ff00ff00" else "ff0000ff",
                controllerType = if (i == 1) "Human" else "AI",
                active = true,
            )
        }
        val settings = MissionSettingsConfig(rounds = Int.MAX_VALUE, resources = 3000, teams = teams)
        return buildGame(mapCfg, settings, mapUrl = base, mapName = base)
    }

    /** Builds a [GameManager] from a map config, its settings, and an optional save script. */
    private fun buildGame(
        mapCfg: MapConfig,
        settings: MissionSettingsConfig,
        script: MapScriptConfig? = null,
        difficultyOverride: Int? = null,
        mapUrl: String? = null,
        mapName: String? = null,
    ): GameManager {
        val mapSettings = MapSettings.fromConfig(settings, difficultyOverride = difficultyOverride)
        val url = mapUrl ?: mapCfg.name
        val name = mapName ?: mapCfg.name
        val map = GameMap(mapCfg, url, name)
        mapSettings.map = map
        return GameManager(mapSettings, script)
    }

    /** Resolves a map id like `Mission_1` or `bootcamp/batut_basics` to its document. */
    private fun findMap(assetsDir: String, mapId: String): MapConfig? =
        AssetLoader.map(assetsDir, "$mapId.json")
}

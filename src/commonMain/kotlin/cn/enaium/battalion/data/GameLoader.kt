package cn.enaium.battalion.data

import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.mod.ModRegistry

/**
 * Loads the game's static data and builds a ready-to-run [GameManager] for a chosen mission
 * or skirmish map.
 *
 * Every document is typed JSON (see [AssetConfigs] for the on-disk contract):
 *  - `modifiers/{unit,terrain}/<id>.json` — rule modifiers, imported FIRST because terrain /
 *    unit configs only reference them by name.
 *  - `units/<id>.json` / `terrain/<id>.json` — one file per template.
 *  - `ballistics.json`, `sprites.json`.
 *  - `campaigns/<generation>.json` — one document per game generation (see [CAMPAIGN_IDS])
 *    plus `campaigns/bootcamp.json` for the tutorial.
 *  - `maps/<Map>.json`.
 *
 * Mods never replace those documents. A mod ships JSON Patch files under
 * `mods/<id>/data/patches/<same relative path>` (see [cn.enaium.battalion.mod.ModData]) and its
 * own media under `mods/<id>/images` / `mods/<id>/sounds`, addressed by resource id.
 */
object GameLoader {

    /** The port's original content (Battalion Vengeance): the default campaign. */
    const val DEFAULT_CAMPAIGN = "vengeance"

    /** The tutorial campaign document, listed after the generations by the mission select. */
    const val BOOTCAMP_CAMPAIGN = "bootcamp"

    /**
     * The single-player campaigns the mission select offers, in release order: Battalion
     * Nemesis, Battalion Skirmish, Battalion Ghosts, Battalion Vengeance. Each is one document
     * under `campaigns/` and one directory under `maps/`; missions use Vengeance's unit art
     * while generation-specific art (portraits) comes from the generation's own sheet.
     */
    val CAMPAIGN_IDS: List<String> = listOf("nemesis", "skirmish", "ghosts", "vengeance")

    class MissionInfo(
        val number: Int,
        val name: String,
        /** Map id under `maps/` (e.g. "ep3/Mission_1", "bootcamp/batut_basics"). */
        val mapId: String,
        val settings: MissionSettingsConfig,
        val brief: String = "",
    ) {
        /** Kept for callers that key saves/medals off the old URL form. */
        val mapUrl: String get() = "maps/$mapId"
    }

    /**
     * One playable generation: its campaign document plus the ordered missions. [id] is the
     * document name under `campaigns/` (e.g. `"nemesis"`), [displayName] the label shown by
     * the mission-select switch.
     */
    class Campaign(
        val id: String,
        val displayName: String,
        val missions: List<MissionInfo>,
        /** Header art key for the mission select (`""` = the generic `default_header`). */
        val headerBackground: String,
        /** Portrait sheet in `sprites.json` (`"avatars"` = the Vengeance/Ghosts sheet). */
        val avatarSheet: String,
        /** Final-victory artwork key shown after the last level is won (`""` = none). */
        val victoryImage: String = "",
    )

    /**
     * Imports the registries: unit+terrain modifiers, then terrain, then units, then
     * ballistics. Order matters — modifier definitions must exist before the registries that
     * resolve them by name.
     */
    fun loadAssets(assetsDir: String) {
        // Mods must be resolved before anything reads data, media or the campaign list: both
        // layers (JSON Patch + namespaced resources) are built here, once per assets directory.
        ModRegistry.ensureLoaded(assetsDir)
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

    /**
     * The generation campaigns present in [assetsDir], in release order ([CAMPAIGN_IDS]).
     * A campaign whose document is missing (a trimmed asset set) is skipped.
     *
     * Mods may still ship a campaign document — that is what [findCampaign] reads — but they are
     * deliberately not listed here: the mission select's switch row stays the game's own
     * generations, and the levels a mod adds are reached from its "Mod Maps" list.
     */
    fun findCampaigns(assetsDir: String): List<Campaign> {
        ModRegistry.ensureLoaded(assetsDir)
        return CAMPAIGN_IDS.mapNotNull { findCampaign(assetsDir, it) }
    }

    /** One generation campaign by its document name (e.g. `"ghosts"`), or null when absent. */
    fun findCampaign(assetsDir: String, id: String): Campaign? {
        val cfg = AssetLoader.campaign(assetsDir, id) ?: return null
        return Campaign(
            id = id,
            displayName = cfg.displayName.ifEmpty { id.replaceFirstChar { it.uppercase() } },
            missions = parseCampaign(cfg),
            headerBackground = cfg.headerBackground,
            avatarSheet = cfg.avatarSheet,
            victoryImage = cfg.victoryImage,
        )
    }

    /**
     * Loads a single-player campaign mission of [campaignId], 0-based.
     *
     * [playerTeam] is the index of the team the player commands (the mission select's faction
     * picker); when given, that team becomes the human one and the others are AI — the side the
     * mission author assigned to the player is used otherwise.
     */
    fun loadCampaignMission(
        assetsDir: String,
        missionIndex: Int,
        campaignId: String = DEFAULT_CAMPAIGN,
        difficultyOverride: Int? = null,
        playerTeam: Int? = null,
    ): GameManager {
        loadAssets(assetsDir)
        val campaign = findCampaign(assetsDir, campaignId)
            ?: error("GameLoader: no campaign '$campaignId' document found in $assetsDir")
        if (missionIndex !in campaign.missions.indices) {
            error("GameLoader: mission $missionIndex out of range (0..${campaign.missions.lastIndex})")
        }
        val mission = campaign.missions[missionIndex]
        val mapCfg = findMap(assetsDir, mission.mapId)
            ?: error("GameLoader: could not find map for '${mission.mapId}'")
        val settings = withPlayerTeam(mission.settings, playerTeam)
        return buildGame(mapCfg, settings,
            difficultyOverride = difficultyOverride,
            mapUrl = "maps/${mission.mapId}", mapName = mission.mapId.substringAfterLast("/"))
            .also {
                val last = missionIndex == campaign.missions.lastIndex
                it.setCampaignContext(
                    "maps/${mission.mapId}", campaign.avatarSheet,
                    levelName = mission.name,
                    victoryImage = if (last) campaign.victoryImage else "",
                    isFinalMission = last,
                )
            }
    }

    /**
     * The mission settings with [playerTeam] (an index into `settings.teams`) as the only human
     * team. Used by the mission select's faction picker so either side of a mission can be
     * played; the mission's own Human/AI assignment is kept when [playerTeam] is null or out of
     * range.
     */
    fun withPlayerTeam(settings: MissionSettingsConfig, playerTeam: Int?): MissionSettingsConfig {
        if (playerTeam == null || playerTeam !in settings.teams.indices) return settings
        if (settings.teams[playerTeam].controllerType == "Human") return settings
        return settings.copy(
            teams = settings.teams.mapIndexed { index, team ->
                if (index == playerTeam) team.copy(controllerType = "Human")
                else if (team.controllerType == "Human") team.copy(controllerType = "AI")
                else team
            },
        )
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
            .also {
                it.setCampaignContext(
                    "maps/${save.mapId}", save.avatarSheet,
                    levelName = save.campaignLevelName,
                    victoryImage = save.campaignVictoryImage,
                    isFinalMission = save.campaignFinalMission,
                )
            }
    }

    /** Public access to the parsed [MapConfig] for a mission (used by the menu minimap). */
    fun findMapPublic(assetsDir: String, mapId: String): MapConfig? = findMap(assetsDir, mapId)

    /**
     * Loads a skirmish / multiplayer map. [mapFile] is a map id (e.g. `ep3/Mission_1`),
     * resolved to `maps/<base>.json`. Player 1 is Human, every other team is AI.
     */
    fun loadSkirmish(assetsDir: String, mapFile: String): GameManager {
        loadAssets(assetsDir)
        val base = mapFile.substringBeforeLast('.').removePrefix("maps/")
        val mapCfg = findMap(assetsDir, base)
            ?: error("GameLoader: cannot read map file $mapFile")
        return loadSkirmishFromConfig(assetsDir, mapCfg, "maps/$base", base)
    }

    /**
     * Plays an in-memory map document as a free-for-all skirmish — the map editor's Test
     * button. The document is not saved first: the editor hands its working copy over, so a
     * test always shows exactly what the author is looking at.
     */
    fun loadSkirmishFromConfig(
        assetsDir: String,
        mapCfg: MapConfig,
        mapUrl: String,
        mapName: String,
    ): GameManager {
        loadAssets(assetsDir)
        // Build default settings with one Human + AI per team that actually owns something.
        // A map saved by the editor may keep palette entries for a colour it no longer uses;
        // [compactTeams] renumbers the real players to 1..n so no ghost team joins the match.
        val cfg = compactTeams(mapCfg)
        val map = GameMap(cfg, mapUrl, mapName)
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
        return buildGame(cfg, settings, mapUrl = mapUrl, mapName = mapName)
    }

    /**
     * The map editor's Test button: the original `EditorInterface.onTestClick` settings.
     *
     * The original builds `MapSettings(Int.MAX_VALUE, 0, Controller.MEDIUM)`, enables unlocked
     * units (premium in the online build), and gives the first team the author's name (here
     * "Player 1", the offline equivalent) and the rest "Testo-tron (AI)", with the editor's four
     * team colours (red, blue, green, yellow). No resources are granted: an authored map has to
     * earn them from its ore.
     */
    fun loadEditorTest(assetsDir: String, mapCfg: MapConfig): GameManager {
        loadAssets(assetsDir)

        // The editor's palette can keep entries for a colour that has nothing left on the map
        // (paint, then delete). The original Save renumbers players to P1..Pn; do the same for
        // the in-memory test, or those leftovers become ghost players that take empty turns
        // forever (they never fire NO_UNITS because they never lost a unit).
        val testMap = compactTeams(mapCfg)
        val teamCount = placedTeams(mapCfg).size.coerceIn(1, 4)

        val colors = listOf("ffff0000", "ff0000ff", "ff00ff00", "ffffd800")
        val teams = (0 until teamCount).map { index ->
            TeamConfig(
                name = if (index == 0) "Player 1" else "Testo-tron (AI)",
                teamName = "Team${index + 1}",
                color = colors[index],
                controllerType = if (index == 0) "Human" else "AI",
                difficulty = 1,
                active = true,
            )
        }
        val settings = MissionSettingsConfig(
            rounds = Int.MAX_VALUE, resources = 0, difficulty = 1, teams = teams,
        )
        return buildGame(
            testMap, settings,
            mapUrl = "editor/${mapCfg.name}", mapName = mapCfg.name,
            useUnlockedUnits = true,
        )
    }

    /**
     * Renumbers the teams that own a unit or structure to 1..n (the original editor does the
     * same when it saves a map). Palette entries belong to the map document, not the tiles, so a
     * colour can survive after its last unit is deleted; without this renumbering the loader
     * would create a controller for it and the turn order would cycle through an empty team.
     */
    private fun compactTeams(cfg: MapConfig): MapConfig {
        val placed = placedTeams(cfg)
        if (placed.isEmpty() || placed == (1..placed.size).toList()) return cfg
        val remap = placed.withIndex().associate { (index, team) -> team to index + 1 }
        return cfg.copy(
            palette = cfg.palette.map { entry ->
                if (entry.team <= 0) entry else entry.copy(team = remap[entry.team] ?: 0)
            },
        )
    }

    /** Teams that actually own a unit or structure on [cfg] (palette leftovers excluded). */
    private fun placedTeams(cfg: MapConfig): List<Int> {
        val teams = mutableSetOf<Int>()
        for (tile in cfg.tiles) {
            for (layer in tile.layers) {
                if (layer.depth == 0) continue
                val team = cfg.palette.getOrNull(layer.reference)?.team ?: 0
                if (team > 0) teams += team
            }
        }
        return teams.sorted()
    }

    /** Builds a [GameManager] from a map config, its settings, and an optional save script. */
    /**
     * The mission settings a campaign gives the map [mapId] (its teams, colours, avatars and AI
     * difficulty), or null when no campaign level uses that map. The editor's preview uses these
     * so it shows the mission's own factions — with the editor's synthetic teams a mission's
     * vehicles came out in the wrong colours, which reads as "the preview is not the game".
     */
    fun campaignSettingsFor(assetsDir: String, mapId: String): MissionSettingsConfig? {
        loadAssets(assetsDir)
        for (campaign in findCampaigns(assetsDir)) {
            val level = campaign.missions.firstOrNull { it.mapId == mapId } ?: continue
            return level.settings
        }
        return null
    }

    /**
     * Builds a match from an in-memory map document (the editor's edited document, not the file on
     * disk) with [settings] and no script, which is what its preview needs.
     */
    fun buildPreviewGame(mapCfg: MapConfig, settings: MissionSettingsConfig, url: String): GameManager =
        buildGame(mapCfg, settings, script = null, mapUrl = url, mapName = mapCfg.name)

    private fun buildGame(
        mapCfg: MapConfig,
        settings: MissionSettingsConfig,
        script: MapScriptConfig? = null,
        difficultyOverride: Int? = null,
        mapUrl: String? = null,
        mapName: String? = null,
        useUnlockedUnits: Boolean = false,
    ): GameManager {
        val mapSettings = MapSettings.fromConfig(settings, difficultyOverride = difficultyOverride)
        mapSettings.useUnlockedUnits = useUnlockedUnits
        val url = mapUrl ?: mapCfg.name
        val name = mapName ?: mapCfg.name
        val map = GameMap(mapCfg, url, name)
        mapSettings.map = map
        return GameManager(mapSettings, script)
    }

    /** Resolves a map id like `ep3/Mission_1` or `bootcamp/batut_basics` to its document. */
    private fun findMap(assetsDir: String, mapId: String): MapConfig? =
        AssetLoader.map(assetsDir, "$mapId.json")
}

package cn.enaium.battalion.data

import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.util.Fio
import cn.enaium.battalion.util.JsonXml
import cn.enaium.battalion.util.XmlNode
import cn.enaium.battalion.util.XmlParser

/**
 * Loads the game's static data (terrain, units, modifiers, ballistics) and builds a
 * ready-to-run [GameManager] for a chosen mission or skirmish map.
 *
 * The original Flash game embedded every XML document as a `.bin` byte array asset. Here the
 * same documents live as JSON under the project's `assets/data/` directory (one file per doc,
 * each `{"name", "attrs", "children", "text"}` — the [JsonXml] layout), so mods ship by editing
 * or adding those JSON files. [loadAssets] imports the fixed set of registry documents;
 * campaigns and maps are resolved by name from `data/` (`campaign.json`, `bootcamp.json`,
 * `maps/<Mission>.json`, `maps/bootcamp/<...>.json`).
 *
 * The two high-level entry points are:
 *  - [loadCampaignMission] — a single-player mission from the Ep3 campaign (Human vs AI).
 *  - [loadSkirmish] — any map played as a free-for-all skirmish (2 players: Human vs AI).
 */
object GameLoader {

    /** Returns the map settings XML for a given mission, or null. */
    class MissionInfo(
        val number: Int,
        val name: String,
        val mapUrl: String,
        val mapSettingsXml: XmlNode,
        /** The mission brief (from the campaign `<level><brief/>`), for the mission-select panel. */
        val brief: String = "",
    )

    /**
     * Imports the fixed set of registry documents (JSON under `assets/data/`) into the global
     * registries ([TileManager], [UnitManager], [ModifierManager], [BallisticsTable]).
     * The doc names are the modding contract: `unitmodifiers.json` + `terrainmodifiers.json`
     * are imported FIRST because terrain/unit entries only reference modifiers by name
     * (`<modifier type="Water"/>`), so the definitions must exist before the registries that
     * resolve them — importing them afterwards would silently register every terrain and unit
     * without its modifier effects (e.g. no `movementCost` block -> fully walkable maps).
     * The arena `units_arena.json` (full arena unit set) replaces base entries by name and
     * therefore comes last; `ballistics.json` is independent.
     */
    fun loadAssets(assetsDir: String) {
        for (file in registryDocs) {
            val root = readDoc(assetsDir, file) ?: continue
            when (root.name) {
                "unitmodifiers", "terrainmodifiers" -> ModifierManager.importXML(root)
                "terrain" -> TileManager.importXML(root)
                "units" -> UnitManager.importXML(root)
                "ballisticsTable" -> BallisticsTable.load(root)
                else -> Unit // campaign / map handled separately
            }
        }
    }

    /** The registry documents imported by [loadAssets], in import order. */
    private val registryDocs = listOf(
        "unitmodifiers.json",
        "terrainmodifiers.json",
        "terrain.json",
        "units.json",
        "units_arena.json",
        "ballistics.json",
    )

    /** Parses a `<campaign>` XML document into its ordered list of missions. */
    fun parseCampaign(root: XmlNode): List<MissionInfo> {
        val missions = mutableListOf<MissionInfo>()
        for (level in root.children("level")) {
            val number = level.attr("number").toIntOrNull() ?: continue
            val name = level.attr("name")
            val map = level.child("map") ?: continue
            val mapSettings = level.children("mapsettings").firstOrNull() ?: continue
            val brief = level.child("brief")?.attr("value") ?: ""
            missions.add(
                MissionInfo(
                    number = number,
                    name = name,
                    mapUrl = map.attr("url"),
                    mapSettingsXml = mapSettings,
                    brief = brief,
                )
            )
        }
        return missions.sortedBy { it.number }
    }

    /** Finds the Ep3 single-player campaign (not bootcamp). */
    fun findCampaign(assetsDir: String): List<MissionInfo>? =
        findCampaignNamed(assetsDir, isBootcamp = false)

    /** Finds the Ep3 bootcamp campaign. */
    fun findBootcamp(assetsDir: String): List<MissionInfo>? =
        findCampaignNamed(assetsDir, isBootcamp = true)

    /**
     * Finds the campaign document (JSON): `bootcamp.json` for the tutorial campaign, otherwise
     * `campaign.json` (the Ep3 single-player campaign). Returns null when the doc is absent.
     */
    fun findCampaignNamed(assetsDir: String, isBootcamp: Boolean): List<MissionInfo>? {
        val root = readDoc(assetsDir, if (isBootcamp) "bootcamp.json" else "campaign.json") ?: return null
        return parseCampaign(root)
    }

    /**
     * Loads a single-player mission. [assetsDir] is the project's asset directory (JSON data
     * under `assets/data/`), [missionIndex] selects which campaign mission to play (0-based).
     */
    fun loadCampaignMission(assetsDir: String, missionIndex: Int, difficultyOverride: Int? = null): GameManager {
        loadAssets(assetsDir)
        val missions = findCampaign(assetsDir)
            ?: error("GameLoader: no campaign document found in $assetsDir")
        if (missionIndex !in missions.indices) {
            error("GameLoader: mission $missionIndex out of range (0..${missions.lastIndex})")
        }
        val mission = missions[missionIndex]
        val mapNode = findMapXml(assetsDir, mission.mapUrl)
            ?: error("GameLoader: could not find map for '${mission.mapUrl}'")
        return buildGame(mapNode, mission.mapSettingsXml, difficultyOverride = difficultyOverride, mapUrl = mission.mapUrl, mapName = mission.mapUrl.substringAfterLast("/"))
            .also { it.setCampaignContext(missionIndex, bootcamp = false, mission.mapUrl) }
    }

    /**
     * Loads a single-player bootcamp mission (the "Boot Camp" tutorial campaign).
     * [missionIndex] selects which bootcamp mission to play (0-based).
     */
    fun loadBootcampMission(assetsDir: String, missionIndex: Int, difficultyOverride: Int? = null): GameManager {
        loadAssets(assetsDir)
        val missions = findBootcamp(assetsDir)
            ?: error("GameLoader: no bootcamp document found in $assetsDir")
        if (missionIndex !in missions.indices) {
            error("GameLoader: bootcamp mission $missionIndex out of range (0..${missions.lastIndex})")
        }
        val mission = missions[missionIndex]
        val mapNode = findMapXml(assetsDir, mission.mapUrl)
            ?: error("GameLoader: could not find map for '${mission.mapUrl}'")
        return buildGame(mapNode, mission.mapSettingsXml, difficultyOverride = difficultyOverride, mapUrl = mission.mapUrl, mapName = mission.mapUrl.substringAfterLast("/"))
            .also { it.setCampaignContext(missionIndex, bootcamp = true, mission.mapUrl) }
    }

    /** True when a campaign [MissionInfo]'s map file is actually present and loadable. */
    fun isMissionLoadable(assetsDir: String, mission: MissionInfo): Boolean =
        findMapXml(assetsDir, mission.mapUrl) != null

    /**
     * Reconstructs a [GameManager] from a serialized saved game (the XML produced by
     * [cn.enaium.battalion.game.GameManager.toXML]).
     *
     * Mirrors the original's restore: the map is re-loaded from the save's `mapurl`, the
     * `mapsettings` (rounds/teams/difficulty) come from the save, and the `<script>` snapshot
     * rehydrates the exact turn/round/controller/resources/units/structures before the new
     * [GameManager] is returned — it is NOT started; the caller resumes with
     * [cn.enaium.battalion.game.GameManager.resumeFromSave].
     */
    fun loadSavedGame(assetsDir: String, saveXml: String): GameManager {
        loadAssets(assetsDir)
        val root = XmlParser.parseOrNull(saveXml) ?: error("GameLoader: invalid save XML")
        val mapUrl = root.attr("mapurl")
        val mapNode = findMapXml(assetsDir, mapUrl)
            ?: error("GameLoader: save references unknown map '$mapUrl'")
        val settingsNode = root.child("mapsettings") ?: error("GameLoader: save missing <mapsettings>")
        val scriptNode = root.child("script")
        return buildGame(mapNode, settingsNode, scriptNode,
            mapUrl = mapUrl, mapName = root.attr("mapname").ifEmpty { mapUrl.substringAfterLast('/') })
    }

    /** Public access to the parsed `<map>` XML for a mission, or null (used by the menu minimap). */
    fun findMapXmlPublic(assetsDir: String, url: String): XmlNode? = findMapXml(assetsDir, url)

    /**
     * Loads a skirmish / multiplayer map. [mapFile] is a map base name (e.g. `Mission_1`),
     * resolved to `data/maps/<mapFile>.json`. Player 1 is Human, every other team is AI.
     */
    fun loadSkirmish(assetsDir: String, mapFile: String): GameManager {
        loadAssets(assetsDir)
        val base = mapFile.substringBeforeLast('.')
        val rel = if (mapFile.contains("/")) mapFile else "maps/$base.json"
        val mapNode = readDoc(assetsDir, rel)
            ?: error("GameLoader: cannot read map file $mapFile")

        // Build a default mapsettings with one Human + AI per team found in the map palette.
        val map = GameMap(mapNode, mapFile, mapFile)
        val teamCount = map.palette.mapNotNull { it.getTeam().takeIf { t -> t > 0 } }.maxOrNull() ?: 2
        val sb = StringBuilder()
        sb.append("<mapsettings rounds=\"2147483647\" resources=\"3000\" difficulty=\"1\"><teams>")
        for (i in 1..teamCount) {
            val type = if (i == 1) "Human" else "AI"
            sb.append("<team active=\"true\"><name value=\"Player $i\"/><teamname value=\"Team $i\"/>")
            sb.append("<color value=\"${if (i == 1) "ff00ff00" else "ff0000ff"}\"/>")
            sb.append("<rank value=\"1\"/><controllertype value=\"$type\"/></team>")
        }
        sb.append("</teams></mapsettings>")
        val settingsNode = XmlParser.parseOrNull(sb.toString())!!
        return buildGame(mapNode, settingsNode, mapUrl = mapFile, mapName = mapFile.substringBeforeLast('.'))
    }

    /** Builds a [GameManager] from a map XML node, its `<mapsettings>` node, and an optional save `<script>` snapshot. */
    private fun buildGame(
        mapNode: XmlNode,
        settingsNode: XmlNode,
        scriptNode: XmlNode? = null,
        difficultyOverride: Int? = null,
        mapUrl: String? = null,
        mapName: String? = null,
    ): GameManager {
        val mapSettings = MapSettings.getFromXML(settingsNode, difficultyOverride)
        val url = mapUrl ?: mapNode.child("name")?.text ?: "Map"
        val name = mapName ?: mapNode.child("name")?.text ?: "Map"
        val map = GameMap(mapNode, url, name)
        mapSettings.map = map
        return GameManager(mapSettings, scriptNode)
    }

    /**
     * Resolves a campaign URL such as `maps/ep3/Mission_1.yimf` or
     * `maps/ep3/bootcamp/batut_stealth.yimf` to its JSON document:
     * `data/maps/<base>.json` (or `data/maps/bootcamp/<base>.json` when the URL is under a
     * `bootcamp/` path segment). Returns the parsed map node, or null when unknown.
     */
    private fun findMapXml(assetsDir: String, url: String): XmlNode? {
        val base = url.substringAfterLast('/').substringBeforeLast('.')
        val boot = "/bootcamp/".let { url.contains(it) }
        val rel = if (boot) "maps/bootcamp/$base.json" else "maps/$base.json"
        val root = readDoc(assetsDir, rel) ?: return null
        return if (root.name == "map") root else null
    }

    /** The directory that holds the JSON documents. */
    private fun dataDir(assetsDir: String): String = "$assetsDir/data"

    /** Reads and parses one JSON document (relative to `dataDir`), or null when absent/broken. */
    private fun readDoc(assetsDir: String, rel: String): XmlNode? {
        val text = Fio.readText("${dataDir(assetsDir)}/$rel") ?: return null
        return JsonXml.parseNodeOrNull(text)
    }
}
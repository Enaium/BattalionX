package cn.enaium.battalion.data

import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.util.Fio
import cn.enaium.battalion.util.XmlParser
import cn.enaium.battalion.util.XmlNode

/**
 * Loads the game's static XML data (terrain, units, modifiers, ballistics) and builds a
 * ready-to-run [GameManager] for a chosen mission or skirmish map.
 *
 * The original Flash game embedded every XML document as a `.bin` byte array asset. The
 * unpacked assets keep that layout, so this loader scans the asset directory for `.bin` files,
 * sniffs each file's root element and routes it to the right registry.
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
    )

    /**
     * Scans the asset directory for `.bin` files and imports every XML document into the global
     * registries ([TileManager], [UnitManager], [ModifierManager], [BallisticsTable]).
     *
     * The unpacked SWF keeps the embedded XML documents under a `binaryData/` subdirectory, so
     * that is where we look.
     */
    fun loadAssets(assetsDir: String) {
        val files = Fio.listDir(binDir(assetsDir)).orEmpty()
        for (file in files) {
            if (!file.endsWith(".bin")) continue
            val text = Fio.readText("${binDir(assetsDir)}/$file") ?: continue
            val root = XmlParser.parseOrNull(text) ?: continue
            when (root.name) {
                "terrain" -> TileManager.importXML(root)
                "units" -> UnitManager.importXML(root)
                "unitmodifiers", "terrainmodifiers" -> ModifierManager.importXML(root)
                "ballisticsTable" -> BallisticsTable.load(root)
                else -> Unit // campaign / map handled separately
            }
        }
    }

    /** Parses a `<campaign>` XML document into its ordered list of missions. */
    fun parseCampaign(root: XmlNode): List<MissionInfo> {
        val missions = mutableListOf<MissionInfo>()
        for (level in root.children("level")) {
            val number = level.attr("number").toIntOrNull() ?: continue
            val name = level.attr("name")
            val map = level.child("map") ?: continue
            val mapSettings = level.children("mapsettings").firstOrNull() ?: continue
            missions.add(
                MissionInfo(
                    number = number,
                    name = name,
                    mapUrl = map.attr("url"),
                    mapSettingsXml = mapSettings,
                )
            )
        }
        return missions.sortedBy { it.number }
    }

    /** Finds a `<campaign>` XML document in the assets, preferring the Ep3 single-player campaign. */
    fun findCampaign(assetsDir: String): List<MissionInfo>? {
        val files = Fio.listDir(binDir(assetsDir)).orEmpty()
        var fallback: List<MissionInfo>? = null
        var ep3Fallback: List<MissionInfo>? = null
        for (file in files) {
            if (!file.endsWith(".bin")) continue
            val text = Fio.readText("${binDir(assetsDir)}/$file") ?: continue
            val root = XmlParser.parseOrNull(text) ?: continue
            if (root.name != "campaign") continue
            val missions = parseCampaign(root)
            val lower = file.lowercase()
            // Prefer the main Ep3 campaign (not bootcamp/side-campaigns).
            // The real campaign file contains "ep3" and "campaign" but NOT "bootcamp".
            if (lower.contains("ep3") && lower.contains("campaign") && !lower.contains("bootcamp")) {
                return missions
            }
            // Also collect a secondary Ep3 fallback (bootcamp, etc.) in case the main one is missing.
            if (lower.contains("ep3") && ep3Fallback == null) {
                ep3Fallback = missions
            }
            if (fallback == null) fallback = missions
        }
        return ep3Fallback ?: fallback
    }

    /**
     * Loads a single-player mission. [assetsDir] is the unpacked SWF asset directory,
     * [missionIndex] selects which campaign mission to play (0-based).
     */
    fun loadCampaignMission(assetsDir: String, missionIndex: Int): GameManager {
        loadAssets(assetsDir)
        val missions = findCampaign(assetsDir)
            ?: error("GameLoader: no campaign XML found in $assetsDir")
        if (missionIndex !in missions.indices) {
            error("GameLoader: mission $missionIndex out of range (0..${missions.lastIndex})")
        }
        val mission = missions[missionIndex]
        val mapNode = findMapXml(assetsDir, mission.mapUrl)
            ?: error("GameLoader: could not find map for '${mission.mapUrl}'")
        return buildGame(mapNode, mission.mapSettingsXml)
    }

    /**
     * Loads a skirmish / multiplayer map. [mapFile] is the name of the map `.bin` file in the
     * assets directory. Player 1 is Human, every other team is AI.
     */
    fun loadSkirmish(assetsDir: String, mapFile: String): GameManager {
        loadAssets(assetsDir)
        val text = Fio.readText("${binDir(assetsDir)}/$mapFile")
            ?: error("GameLoader: cannot read map file $mapFile")
        val mapNode = XmlParser.parseOrNull(text) ?: error("GameLoader: invalid XML in $mapFile")

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
        return buildGame(mapNode, settingsNode)
    }

    /** Builds a [GameManager] from a map XML node and its `<mapsettings>` node. */
    private fun buildGame(mapNode: XmlNode, settingsNode: XmlNode): GameManager {
        val mapSettings = MapSettings.getFromXML(settingsNode)
        val map = GameMap(mapNode, mapSettings.getTeam(0).name, mapNode.child("name")?.text ?: "Map")
        mapSettings.map = map
        return GameManager(mapSettings)
    }

    /**
     * Locates the `.bin` file for a map referenced by a campaign URL such as
     * `maps/ep3/Mission_1.yimf`. The unpacked files are named after the embeds, e.g.
     * `Ep3XMLs_Mission_1_yimf.bin`, so the URL's base name (before the extension) is matched as
     * a substring of the file name.
     */
    private fun findMapXml(assetsDir: String, url: String): XmlNode? {
        val base = url.substringAfterLast('/').substringBeforeLast('.')
        val files = Fio.listDir(binDir(assetsDir)).orEmpty()
        for (file in files) {
            if (!file.endsWith(".bin")) continue
            if (!file.contains(base, ignoreCase = true)) continue
            val text = Fio.readText("${binDir(assetsDir)}/$file") ?: continue
            val root = XmlParser.parseOrNull(text) ?: continue
            if (root.name == "map") return root
        }
        return null
    }

    /** The directory that holds the unpacked `.bin` XML documents. */
    private fun binDir(assetsDir: String): String = "$assetsDir/binaryData"
}
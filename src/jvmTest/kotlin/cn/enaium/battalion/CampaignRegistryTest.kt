package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the campaign registry behind the mission select: every game generation (Nemesis,
 * Skirmish, Ghosts, Vengeance) plus the Boot Camp tutorial must load from `assets/data/`, list
 * its levels in order, and resolve each level's map document.
 *
 * A level whose map id has no `maps/<id>.json` is unplayable and the menu silently skips it
 * (`GameLoader.isMissionLoadable`), so a typo in a campaign document would otherwise only show
 * up as a missing mission — this pins the data contract instead.
 */
class CampaignRegistryTest {

    private val assetsDir = "assets"

    private fun allCampaigns(): List<GameLoader.Campaign> {
        if (!File("$assetsDir/data").isDirectory) return emptyList()
        return GameLoader.findCampaigns(assetsDir) +
            listOfNotNull(GameLoader.findCampaign(assetsDir, GameLoader.BOOTCAMP_CAMPAIGN))
    }

    @Test
    fun `every generation campaign loads with playable levels`() {
        val campaigns = allCampaigns()
        if (campaigns.isEmpty()) return
        // The registry lists the generations in release order, then the tutorial.
        assertEquals(
            GameLoader.CAMPAIGN_IDS + GameLoader.BOOTCAMP_CAMPAIGN,
            campaigns.map { it.id },
        )
        for (campaign in campaigns) {
            assertTrue(campaign.missions.isNotEmpty(), "${campaign.id} has no levels")
            assertTrue(campaign.displayName.isNotEmpty(), "${campaign.id} has no display name")
            assertTrue(campaign.avatarSheet.isNotEmpty(), "${campaign.id} has no portrait sheet")
            assertEquals(
                campaign.missions.indices.toList(),
                campaign.missions.map { it.number },
                "${campaign.id} level numbers must be 0-based and gapless",
            )
            for (mission in campaign.missions) {
                assertTrue(
                    GameLoader.isMissionLoadable(assetsDir, mission),
                    "${campaign.id} level '${mission.name}' has no map document (${mission.mapId})",
                )
            }
        }
    }

    /**
     * The mission select's faction picker must swap which side the player commands: the picked
     * team becomes the human one and the other side turns AI (otherwise a mission could start
     * with two humans or none).
     */
    @Test
    fun `choosing the other faction swaps the human and AI sides`() {
        if (!File("$assetsDir/data").isDirectory) return
        val mission = GameLoader.findCampaign(assetsDir, GameLoader.DEFAULT_CAMPAIGN)?.missions?.first()
            ?: return
        assertEquals(
            listOf("Human", "AI"),
            mission.settings.teams.map { it.controllerType },
            "the first mission's authored side assignment",
        )
        val swapped = GameLoader.withPlayerTeam(mission.settings, 1)
        assertEquals(listOf("AI", "Human"), swapped.teams.map { it.controllerType })

        val game = GameLoader.loadCampaignMission(assetsDir, 0, playerTeam = 1)
        assertEquals("AI", game.getController(0)?.team?.controllerType)
        assertEquals("Human", game.getController(1)?.team?.controllerType)
        assertEquals(
            1,
            game.controllers.indexOfFirst { it?.team?.controllerType == "Human" },
            "exactly the picked team must be human",
        )
        // The match opens with the player's turn whichever side they picked.
        game.startGame()
        for (i in 1..20) game.update(16)
        assertEquals(1, game.currentController, "the picked team must take the first turn")
    }

    /**
     * Nemesis, Ghosts and Skirmish name their maps `Mission_N` exactly like Vengeance does, so
     * their documents must be namespaced per generation — a shared id would make the medals of
     * one generation follow the levels of another and the wrong map load.
     */
    @Test
    fun `generations never share a map id`() {
        val campaigns = allCampaigns()
        if (campaigns.isEmpty()) return
        val ids = campaigns.flatMap { campaign -> campaign.missions.map { campaign.id to it.mapId } }
        val duplicates = ids.groupBy { it.second }.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "map ids used by several campaigns: $duplicates")
    }
}

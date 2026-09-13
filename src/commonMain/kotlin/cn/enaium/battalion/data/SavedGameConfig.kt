package cn.enaium.battalion.data

import kotlinx.serialization.Serializable

/**
 * The typed saved-game document ([cn.enaium.battalion.game.GameManager.toSave] writes it,
 * [GameLoader.loadSavedGame] reads it back).
 */
@Serializable
data class SavedGameConfig(
    /** Map base name resolved under `maps/`. */
    val mapId: String,
    val mapName: String = "",
    val settings: MissionSettingsConfig,
    /** Rehydration state: turn/round/controller, resources, units, structures, events. */
    val script: MapScriptConfig,
    /** `sprites.json` portrait sheet of the campaign the save was made in. */
    val avatarSheet: String = "avatars",
    /** Campaign level name shown by the results screen's chapter panel ("" outside campaigns). */
    val campaignLevelName: String = "",
    /** Campaign final-victory artwork shown when the last level is won ("" = none). */
    val campaignVictoryImage: String = "",
    /** Whether the saved game is the campaign's last level. */
    val campaignFinalMission: Boolean = false,
)

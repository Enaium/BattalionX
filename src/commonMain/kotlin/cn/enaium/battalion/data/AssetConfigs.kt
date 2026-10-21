package cn.enaium.battalion.data

import kotlinx.serialization.Serializable

/**
 * The typed (kotlinx.serialization) configuration documents that replace the old XML-shaped
 * `{"name","attrs","children","text"}` bridge. Every document is a plain JSON file with
 * explicit fields — this IS the modding contract: a mod drops a folder of these files under
 * `src/commonMain/resources/data/` and the registries pick them up.
 *
 * Layout on disk (all relative to `src/commonMain/resources/data/`):
 * ```
 * units/<id>.json            one UnitConfig per unit
 * terrain/<id>.json          one TerrainConfig per terrain
 * modifiers/unit/<id>.json   one ModifierConfig per unit modifier
 * modifiers/terrain/<id>.json one ModifierConfig per terrain modifier
 * ballistics.json            BallisticsConfig (weapon x armor multipliers)
 * campaigns/campaign.json    CampaignConfig (Ep3 campaign)
 * campaigns/bootcamp.json    CampaignConfig (tutorial)
 * maps/<Map>.json            MapConfig per map
 * sprites.json               SpriteAtlasConfig (UI/effects sheets: regions + frames)
 * ```
 */

// ---------------------------------------------------------------------------
// Units — one file per unit (`units/<id>.json`)
// ---------------------------------------------------------------------------

/**
 * Sprite-sheet layout of one unit. The renderer slices every sheet by these numbers instead
 * of its old hardcoded heuristics (`texW == 336`, `_firing` name probing, facing-column math).
 */
@Serializable
data class UnitSpriteConfig(
    val sheet: String,
    /**
     * Directional sheets: columns are facings in the exported tilesheet's order
     * `[right, down, left, up]` (= east/south/west/north). This list maps compass facing
     * (0=N,1=E,2=S,3=W) to its column — default [3, 0, 1, 2].
     */
    val facingColumns: List<Int> = listOf(3, 0, 1, 2),
    /** Width of one cell in pixels (56 for standard sheets). */
    val cellWidth: Int = 56,
    /**
     * Rows are animation states; walk rows cycle while moving, idle rows loop,
     * fire rows play once on FIRE.
     */
    val rows: Int = 4,
    /** Dedicated firing sheet key (e.g. `"corvette_firing"`), or null when none exists. */
    val firingSheet: String? = null,
    /** Cell size of the firing sheet (the exported firing sheets use 144×144 cells). */
    val firingCellWidth: Int = 144,
)

/** Sound event names for one unit (keys into `sounds.json`). */
@Serializable
data class UnitAudioConfig(
    /** Movement sound event, e.g. `"TankMove1"`. Empty = silent. */
    val move: String = "",
    /** Firing sound event, e.g. `"FireScorpionTank"`. Empty = silent. */
    val fire: String = "",
)


/**
 * One unit template. Lives in its own file `units/<id>.json` so mods add a unit by dropping
 * one file (and never touch a shared units list).
 */
@Serializable
data class UnitConfig(
    /** Display name, e.g. `"Warmachine"`. Also the registry key. */
    val name: String,
    /** Where the unit shows up in build menus / movement rules. */
    val unitType: String,        // ground | sea | air
    val movementType: String,    // feet | wheeled | tracked | rudder | wings
    val weaponType: String,      // light | medium | heavy
    val armorType: String,       // light | medium | heavy
    val cost: Int = 0,
    val range: Int = 0,
    val health: Int = 0,
    val damage: Int = 0,
    val weaponRangeMin: Int = 0,
    val weaponRangeMax: Int = 0,
    val description: String = "",
    /** Modifier names resolved against [ModifierManager] at import time. */
    val modifiers: List<String> = emptyList(),
    /** Which sprite sheet to use, in which state, from which region. */
    val sprite: UnitSpriteConfig = UnitSpriteConfig(sheet = ""),
    /** Movement/firing sound events. */
    val audio: UnitAudioConfig = UnitAudioConfig(),
    /** Reward-unit gate: only registered when the release flag is set (Vulture Drone). */
    val rewardUnit: Boolean = false,
)

// ---------------------------------------------------------------------------
// Terrain — one file per terrain (`terrain/<id>.json`)
// ---------------------------------------------------------------------------

/**
 * How a map palette entry referencing this terrain draws itself. Replaces the old string
 * image-code mini-language (`"?a.png;b.png"`, `"@(...)"`, `"+"`) with typed fields.
 */
@Serializable
data class TerrainSpritesConfig(
    /** Interchangeable full-tile textures; a map tile picks one (deterministically). */
    val variants: List<String> = emptyList(),
    /**
     * Composite 2x2 tiles (sea/shore/canyon): four quadrant pieces TL/TR/BL/BR. A piece named
     * `"sea_edge"` stands in for the directional sea strip matching that corner.
     */
    val composite: List<String> = emptyList(),
    /** The plain texture when the tile has neither variants nor composite pieces. */
    val single: String = "",
)

/** One terrain template (`terrain/<id>.json`). Structures set [sprite]. */
@Serializable
data class TerrainConfig(
    val name: String,
    val cost: Double = 1.0,
    val description: String = "",
    val minimapImage: String = "",
    /** Structure art key (factory, capital, ...); empty for plain terrain. */
    val sprite: String = "",
    val modifiers: List<String> = emptyList(),
    /** Default palette drawing for this terrain (maps may override per entry). */
    val sprites: TerrainSpritesConfig = TerrainSpritesConfig(),
)

// ---------------------------------------------------------------------------
// Modifiers — one file per modifier (`modifiers/{unit,terrain}/<id>.json`)
// ---------------------------------------------------------------------------

/** A conditional block: applies [effects] when the subject matches [match]/[value]. */
@Serializable
data class ModifierMatchConfig(
    /** unitType | movementType | weaponType | armorType | modifier */
    val match: String,
    val value: String,
    /** Effect recipes ("+100%", "-10", "*1.5", "2", "Infinity", "0"). */
    val effects: List<String> = emptyList(),
    val matches: List<ModifierMatchConfig> = emptyList(),
)

/** One named rule modifier (`modifiers/<kind>/<id>.json`). */
@Serializable
data class ModifierConfig(
    val name: String,
    /** "Unit" or "Terrain" — which registry owns it. */
    val kind: String,
    /** What this modifier modifies: damage, defense, movementCost, attackRange, resources... */
    val modify: String = "",
    /** Top-level effect recipes applied unconditionally. */
    val effects: List<String> = emptyList(),
    val matches: List<ModifierMatchConfig> = emptyList(),
    val description: String = "",
    /** Icon key shown in the UI. */
    val image: String = "",
    val visible: Boolean = false,
)

// ---------------------------------------------------------------------------
// Ballistics (`ballistics.json`)
// ---------------------------------------------------------------------------

/** Damage multiplier of one weapon type against one armor type. */
@Serializable
data class BallisticEntryConfig(val armor: String, val multiplier: Double = 1.0)

@Serializable
data class BallisticsConfig(
    /** One row per weapon type. */
    val weapons: List<BallisticEntryContainer> = emptyList(),
) {
    @Serializable
    data class BallisticEntryContainer(val weapon: String, val defenses: List<BallisticEntryConfig> = emptyList())
}

// ---------------------------------------------------------------------------
// Campaigns (`campaigns/campaign.json`, `campaigns/bootcamp.json`)
// ---------------------------------------------------------------------------

/** Per-mission match settings embedded in a campaign level. */
@Serializable
data class MissionSettingsConfig(
    val rounds: Int = 2147483647,
    val resources: Int = 0,
    val difficulty: Int = 1,
    val silverMedal: Int = -1,
    val goldMedal: Int = -1,
    val teams: List<TeamConfig> = emptyList(),
)

@Serializable
data class TeamConfig(
    val name: String = "",
    val teamName: String = "",
    /** ARGB hex string as in the original mission files, e.g. "ff00ff00". */
    val color: String = "",
    val avatar: String = "",
    val rank: Int = 0,
    /** Human | AI */
    val controllerType: String = "AI",
    val difficulty: Int = 1,
    val active: Boolean = true,
    /** Comma-free stat restore: index-ordered values for a saved game. */
    val stats: List<Int> = emptyList(),
    val allies: List<Int> = emptyList(),
)

@Serializable
data class CampaignLevelConfig(
    val number: Int,
    val name: String,
    /** Map id under `maps/` (e.g. `"ep3/Mission_1"`, `"bootcamp/batut_basics"`). */
    val map: String,
    val brief: String = "",
    val objective: String = "",
    val settings: MissionSettingsConfig = MissionSettingsConfig(),
)

@Serializable
data class CampaignConfig(
    val levels: List<CampaignLevelConfig> = emptyList(),
    /** Sprites shown by the menu (victory art / header). */
    val victoryImage: String = "",
    val headerBackground: String = "",
    /** Generation label for the mission-select switch (e.g. `"Vengeance"`). */
    val displayName: String = "",
    /** `sprites.json` sheet holding this generation's portraits (e.g. `"avatars_nemesis"`). */
    val avatarSheet: String = "avatars",
)

// ---------------------------------------------------------------------------
// Maps (`maps/<Map>.json`)
// ---------------------------------------------------------------------------

/** One palette entry: what lives on the tile and which images draw it. */
@Serializable
data class PaletteEntryConfig(
    /** Terrain or unit name (registry key), player prefix stripped. */
    val name: String,
    /** Owning team 1..4, 0 = neutral. */
    val team: Int = 0,
    val sprites: TerrainSpritesConfig = TerrainSpritesConfig(),
    /**
     * The direction a placed UNIT faces (0..5, the sheet's facing columns), or -1 to let the map
     * decide: the original faces a unit toward the middle of the map, which is what every shipped
     * map relies on. The editor's Q/E sets it, so an author can point a vehicle where they want.
     */
    val facing: Int = -1,
)

/** One placed tile layer. */
@Serializable
data class TileLayerConfig(
    /** 0 = terrain, 1 = structure, 2 = unit. */
    val depth: Int,
    /** Index into [MapConfig.palette]. */
    val reference: Int,
)

@Serializable
data class MapTileConfig(val c: Int, val r: Int, val layers: List<TileLayerConfig> = emptyList())

// --- scripted events ---------------------------------------------------------

@Serializable
data class ConditionConfig(
    val type: String,
    /** Generic field bag mirroring the original condition parameters (player/x/y/value/...). */
    val player: Int? = null,
    val round: Int? = null,
    val time: Int? = null,
    val count: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val value: Int? = null,
    val defaultValue: Int? = null,
    val resources: Int? = null,
    val name: String = "",
    val boolean: String = "",
    val unitName: String = "",
    val terrainName: String = "",
    val oldPlayer: Int? = null,
    val victory: Boolean = true,
    val satisfied: Boolean = false,
    val conditions: List<ConditionConfig> = emptyList(),
)

@Serializable
data class ResponseConfig(
    val type: String,
    /** Generic field bag mirroring the original response parameters. */
    val counter: String = "",
    val boolean: String = "",
    val value: String = "",
    val sender: String = "",
    val message: String = "",
    val avatar: String = "",
    /** ARGB hex string, e.g. "FF00FF00". */
    val color: String = "",
    val x: Int? = null,
    val y: Int? = null,
    val show: Boolean = false,
    val updateCamera: Boolean = true,
    val disable: Boolean = true,
    val controller: Int? = null,
    val player: Int? = null,
    val amount: Double? = null,
    val resources: Int? = null,
    val type_: String = "",           // createUnit "type"
    val transportType: String = "",
    val transporterType: String = "",
    val fadeIn: Boolean = false,
    val parallel: Boolean = false,
    val fadeOut: Boolean = false,
    val playerA: Int? = null,
    val playerB: Int? = null,
    val allied: Boolean = false,
    val enable: Boolean = true,
    val unit: String = "",
    val variable: String = "",
    val points: Int? = null,
    val trigger: TriggerConfig? = null,
    val surrenderPlayer: Int? = null,
)

@Serializable
data class TriggerConfig(
    val type: String,
    val player: Int? = null,
)

@Serializable
data class EventConfig(
    /** Repeat count; -1 = forever, omitted/null = once. */
    val repeats: Int? = null,
    val conditions: List<ConditionConfig> = emptyList(),
    val responses: List<ResponseConfig> = emptyList(),
)

// --- script (authored map state + events) ------------------------------------

/** A starting unit authored on the map script. */
@Serializable
data class ScriptUnitConfig(
    val name: String,
    val x: Int,
    val y: Int,
    val controller: Int,
    val deployed: Boolean = true,
    val moved: Boolean = false,
    val fired: Boolean = false,
    val cloaked: Boolean = false,
    val repairing: Boolean = false,
    val health: Double? = null,
    val resourcePool: Int? = null,
    /** Upgrade target name (transport riding etc.). */
    val upgrade: String = "",
    /** Temporary modifier names re-applied on load. */
    val modifiers: List<String> = emptyList(),
)

@Serializable
data class ScriptStructureConfig(
    val name: String,
    val x: Int,
    val y: Int,
    val controller: Int? = null,
    val chunks: Int? = null,
    /** Capturer position (-1 = none). */
    val capturerX: Int = -1,
    val capturerY: Int = -1,
    val resources: Int? = null,
)

@Serializable
data class ScriptStateConfig(
    val turn: Int? = null,
    val round: Int? = null,
    val controller: Int? = null,
    /** Per-controller resource grants. */
    val resourcesPerController: List<ScriptResourceGrant> = emptyList(),
    /** Controllers whose unit lists are restricted. */
    val disableAllUnits: List<Int> = emptyList(),
    val enabledUnits: List<ScriptEnableUnit> = emptyList(),
)

@Serializable
data class ScriptResourceGrant(val controller: Int, val value: Int)

@Serializable
data class ScriptEnableUnit(val controller: Int, val unit: String)

/** The `<script>` block: authored state plus the event definitions. */
@Serializable
data class MapScriptConfig(
    val state: ScriptStateConfig = ScriptStateConfig(),
    val units: List<ScriptUnitConfig> = emptyList(),
    val structures: List<ScriptStructureConfig> = emptyList(),
    val events: List<EventConfig> = emptyList(),
)

/** One whole map (`maps/<Map>.json`). */
@Serializable
data class MapConfig(
    val name: String,
    val description: String = "",
    val author: String = "",
    /** classic_mode games charge resources for transports etc. */
    val classicMode: Boolean = false,
    /** Special maps unlock reward content. */
    val special: Boolean = false,
    val rows: Int,
    val cols: Int,
    val palette: List<PaletteEntryConfig> = emptyList(),
    val tiles: List<MapTileConfig> = emptyList(),
    val script: MapScriptConfig = MapScriptConfig(),
)

// ---------------------------------------------------------------------------
// Global sprite atlas (`sprites.json`) — non-registry art: UI sheets, effects,
// avatars. Regions tell the renderer where each logical frame lives inside the
// image, replacing hardcoded pixel offsets scattered through BattalionRenderer.
// ---------------------------------------------------------------------------

/** One region inside a sheet's image, in source pixels. */
@Serializable
data class SpriteRegionConfig(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int,
    val height: Int,
)

/** One sheet: an image plus how it is sliced. */
@Serializable
data class SheetConfig(
    /** Image key under `src/commonMain/resources/images/` (file base name). */
    val image: String,
    /**
     * Grid slicing: when set, region i = (i % columns) * cellW, (i / columns) * cellH.
     * Named regions below take precedence over grid cells.
     */
    val columns: Int = 1,
    val cellWidth: Int = 0,
    val cellHeight: Int = 0,
    /** Explicit named regions (e.g. cursor move/attack cells). */
    val regions: Map<String, SpriteRegionConfig> = emptyMap(),
)

@Serializable
data class SpriteAtlasConfig(
    val sheets: Map<String, SheetConfig> = emptyMap(),
)


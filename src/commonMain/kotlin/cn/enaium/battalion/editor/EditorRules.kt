package cn.enaium.battalion.editor

import cn.enaium.battalion.data.AssetLoader
import kotlinx.serialization.Serializable

/**
 * The original editor's terrain rules, converted from BattalionVengeanceEdit's embedded
 * `xml/editor/terrain` documents.
 *
 * Each rule set is a *match* table: the editor builds a 9-character string of the identities of
 * the 3×3 cells around a tile (row-major, the tile itself in the middle) and takes the first rule
 * that matches. A rule's art then replaces the tile's images:
 *
 *  - `regular` rules carry one or more interchangeable whole-tile images (`plains_v1`, ...);
 *  - `mini` rules carry four 28×28 quadrant pieces (`canyon_5_v1`, `sea_0_nw`, ...).
 *
 * A rule's match string may use `*` (any), `-` (any identity in [dashWild]) and `~` (any
 * identity in [tildeWild]).
 */
@Serializable
data class EditorRuleConfig(
    val match: String = "",
    /** Whole-tile images of a `regular` rule. */
    val images: List<String> = emptyList(),
    /** TL, TR, BL, BR quadrant pieces of a `mini` rule. */
    val composite: List<String> = emptyList(),
)

/** One terrain's rule set (`RuleMapper` in the original editor). */
@Serializable
data class EditorTerrainRulesConfig(
    /** The port's registry name ("Plains", "High Bridge", ...). */
    val name: String,
    /** The identity character the match strings use (`P`, `S`, `b`, ...). */
    val identity: String = "",
    /** Identities a `~` matches (the original `subscriptions.xml` entry). */
    val tildeWild: String = "",
    /** Identities a `-` matches. */
    val dashWild: String = "",
    /** Identity assumed outside the map (`road` assumes `r`, everything else `S`). */
    val oob: String = "",
    /** Rule set used when no rule matches (`shore` → `sea`, bridges → `sea`, ...). */
    val dependency: String = "",
    /** Game mode the terrain is offered in: "" (both) or `wm_mode` (the ore deposits). */
    val mode: String = "",
    /** Art key shown as the palette row's icon. */
    val icon: String = "",
    val rules: List<EditorRuleConfig> = emptyList(),
)

/** One unit row of the original editor's `xml/editor/units.xml` list. */
@Serializable
data class EditorUnitRulesConfig(
    /** The port's registry name (the original display name, normalized). */
    val name: String,
    /** Game mode the row is offered in: "" (both) or `wm_mode` (the War Machine). */
    val mode: String = "",
    /**
     * The game's editor refuses to PAINT this unit (it can still be present in a loaded map).
     * The standalone BattalionVengeanceEdit build dropped this flag; the game build keeps it.
     */
    val restricted: Boolean = false,
)

/** One building row of the original editor's `xml/editor/buildings.xml` list. */
@Serializable
data class EditorBuildingRulesConfig(
    val name: String,
    /** Game mode the row is offered in ("" = both). */
    val mode: String = "",
)

@Serializable
data class EditorRulesConfig(
    val terrains: List<EditorTerrainRulesConfig> = emptyList(),
    /** The editor's unit list, in the original panel order. */
    val units: List<EditorUnitRulesConfig> = emptyList(),
    /** The editor's building list, in the original panel order. */
    val buildings: List<EditorBuildingRulesConfig> = emptyList(),
)

/**
 * The rule engine of the original editor (`RuleMapper.getMatch` + `isMatch`).
 */
object EditorRules {

    fun load(assetsDir: String): EditorRulesConfig =
        AssetLoader.read<EditorRulesConfig>(assetsDir, "editor/rules.json") ?: EditorRulesConfig()

    fun byName(rules: EditorRulesConfig): Map<String, EditorTerrainRulesConfig> =
        rules.terrains.associateBy { it.name }

    /**
     * The first rule of [terrain] matching [test], or null.
     *
     * [test] is nine identity characters (three rows of three, the tile itself in the middle).
     */
    fun firstMatch(terrain: EditorTerrainRulesConfig, test: String): EditorRuleConfig? =
        terrain.rules.firstOrNull { isMatch(test, it.match, terrain.tildeWild, terrain.dashWild) }

    /**
     * The original `RuleMapper.isMatch`: `*` matches anything, `-` matches an identity listed in
     * the rule set's dash wildcards, `~` one in its tilde wildcards, everything else exactly.
     */
    fun isMatch(test: String, rule: String, tildeWild: String, dashWild: String): Boolean {
        if (test.length != rule.length) return false
        for (i in test.indices) {
            val c = rule[i]
            if (c == test[i]) continue
            if (c == '-' && dashWild.indexOf(test[i]) >= 0) continue
            if (c == '~' && tildeWild.indexOf(test[i]) >= 0) continue
            if (c == '*') continue
            return false
        }
        return true
    }
}

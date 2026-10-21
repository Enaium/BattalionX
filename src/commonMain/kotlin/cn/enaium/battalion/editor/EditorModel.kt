package cn.enaium.battalion.editor

import cn.enaium.battalion.data.AssetLoader
import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.MapConfig
import cn.enaium.battalion.data.MapScriptConfig
import cn.enaium.battalion.data.MapTileConfig
import cn.enaium.battalion.mod.ModRegistry
import cn.enaium.battalion.data.PaletteEntryConfig
import cn.enaium.battalion.data.TerrainSpritesConfig
import cn.enaium.battalion.data.TileLayerConfig
import cn.enaium.battalion.data.TileManager
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.util.Fio
import kotlinx.serialization.json.Json

/** One row of an editor panel: a placeable material, with the art key its row icon uses. */
class EditorItem(
    /** Registry name of the material ("Plains", "Scorpion Tank", "Factory"). */
    val name: String,
    /** Layer the material is placed on (0 terrain, 1 building, 2 unit). */
    val depth: Int,
    /** Owning team (0 = neutral terrain / neutral building, 1..4 = a player). */
    val team: Int,
    /** Art key the row icon is drawn from (terrain icon, unit sheet, structure sprite). */
    val icon: String,
)

/**
 * The map document the editor edits, with the original editor's (`CustomMap`) rules:
 *
 *  - the map is three layers deep: terrain (0), buildings (1), units (2);
 *  - every cell holds, per layer, an entry of the document's palette (a material + team + art);
 *  - **a terrain tile's art is chosen by its neighbours**: the 3×3 identities around a cell form
 *    the match string of the original `subscriptions.xml` tables, and the first matching rule of
 *    the terrain's rule set supplies the art ([EditorRules]);
 *  - **every edit re-runs the rules over the 5×5 cells around it** (`recalculateMapArea(col-2,
 *    row-2, 5, 5)`), so painting one river tile produces a coastline instead of a square of water;
 *  - a terrain whose rule set matches nothing falls back to its dependency (shore/bridge/reef →
 *    sea) and the cell becomes that dependency, as in the original.
 */
class EditorModel(val assetsDir: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    /** The open document, or null before one is loaded. */
    var doc: MapConfig? = null
        private set

    /** Map id of the open document (a game map id, a file name, or the new map's name). */
    var mapId: String? = null
        private set

    /** Where Save writes; null for a document that has no file yet. */
    var targetPath: String? = null
        private set

    /** True when the document is saved as a mod's patch rather than a game document. */
    var targetIsModPatch = false
        private set

    var dirty = false
        private set

    /** Human-readable result of the last operation (shown in the editor's status line). */
    var status = ""
        private set

    /** Cells whose art changed in the most recent rule pass. */
    var lastRecalculated = 0
        private set

    /** The converted rules of the original editor (loaded once). */
    val rules: EditorRulesConfig by lazy { EditorRules.load(assetsDir) }

    /** Rules by material name. */
    val rulesByName: Map<String, EditorTerrainRulesConfig> by lazy { EditorRules.byName(rules) }

    /** The document's mode: `classic_mode` or `wm_mode` (the original's two editor styles). */
    val style: String get() = if (doc?.classicMode == true) CLASSIC_MODE else WM_MODE

    // ---------------------------------------------------------------------
    // Cells
    // ---------------------------------------------------------------------

    private var indexDoc: MapConfig? = null
    private var cellIndex: Map<Int, MapTileConfig> = emptyMap()

    private fun cellKey(c: Int, r: Int): Int = r * (doc?.cols ?: 1) + c

    private fun cells(): Map<Int, MapTileConfig> {
        val current = doc ?: return emptyMap()
        if (current !== indexDoc) {
            indexDoc = current
            cellIndex = current.tiles.associateBy { it.r * current.cols + it.c }
        }
        return cellIndex
    }

    fun cols(): Int = doc?.cols ?: 0
    fun rows(): Int = doc?.rows ?: 0
    fun inBounds(c: Int, r: Int): Boolean = c in 0 until cols() && r in 0 until rows()

    fun tileAt(c: Int, r: Int): MapTileConfig? = cells()[cellKey(c, r)]

    /** The palette entry a cell's [depth] layer draws with, or null. */
    fun entryAt(c: Int, r: Int, depth: Int): PaletteEntryConfig? {
        val layer = tileAt(c, r)?.layers?.firstOrNull { it.depth == depth } ?: return null
        return doc?.palette?.getOrNull(layer.reference)
    }

    /** The material name on a cell's [depth] layer (null when empty). */
    fun nameAt(c: Int, r: Int, depth: Int = 0): String? = entryAt(c, r, depth)?.name

    /** The terrain under a cell (the Shift-click "pick" gesture of the original). */
    fun terrainNameAt(c: Int, r: Int): String? = nameAt(c, r, 0)

    private fun replaceTile(tile: MapTileConfig) {
        val current = doc ?: return
        val tiles = current.tiles.toMutableList()
        val at = tiles.indexOfFirst { it.c == tile.c && it.r == tile.r }
        if (at >= 0) tiles[at] = tile else tiles += tile
        doc = current.copy(tiles = tiles)
    }

    private fun setLayer(c: Int, r: Int, depth: Int, reference: Int): Boolean {
        val tile = tileAt(c, r) ?: return false
        if (tile.layers.any { it.depth == depth && it.reference == reference }) return false
        val layers = (tile.layers.filterNot { it.depth == depth } + TileLayerConfig(depth, reference))
            .sortedBy { it.depth }
        replaceTile(tile.copy(layers = layers))
        return true
    }

    private fun clearLayer(c: Int, r: Int, depth: Int): Boolean {
        val tile = tileAt(c, r) ?: return false
        if (tile.layers.none { it.depth == depth }) return false
        replaceTile(tile.copy(layers = tile.layers.filterNot { it.depth == depth }))
        return true
    }

    /** Index of the palette entry drawing [name]/[team] with [sprites], adding it when missing. */
    private fun paletteRef(name: String, team: Int, sprites: TerrainSpritesConfig): Int {
        val current = doc ?: return -1
        val found = current.palette.indexOfFirst {
            it.name == name && it.team == team && it.sprites == sprites
        }
        if (found >= 0) return found
        doc = current.copy(palette = current.palette + PaletteEntryConfig(name, team, sprites))
        // The palette changed: the tile index (keyed by doc identity) is stale, but it only maps
        // cells; the palette is looked up through doc.palette, so nothing else to refresh.
        return doc!!.palette.lastIndex
    }

    // ---------------------------------------------------------------------
    // Panels
    // ---------------------------------------------------------------------

    /** The terrain panel: the original's terrain list, in its own order and mode-filtered. */
    fun terrainItems(): List<EditorItem> {
        ensureGameData()
        return rules.terrains
            .filter { it.mode.isEmpty() || it.mode == style }
            .map { terrain ->
                val icon = terrain.icon.ifEmpty {
                    terrain.rules.firstOrNull()?.let { rule ->
                        rule.images.firstOrNull() ?: rule.composite.firstOrNull { it.isNotEmpty() }.orEmpty()
                    }.orEmpty()
                }
                EditorItem(terrain.name, 0, 0, icon)
            }
            .filter { terrain -> terrain.icon.isNotEmpty() || rulesByName.containsKey(terrain.name) }
    }

    /**
     * Unit rows for one of the four unit colours (the panel's colour radio buttons).
     *
     * The order, mode filter and restriction flags come from the game's editor list
     * (`xml/editor/units.xml`); registry units the list does not name (mod units) are appended
     * alphabetically so they stay placeable.
     */
    fun unitItems(team: Int): List<EditorItem> {
        ensureGameData()
        val listed = rules.units
            .filter { it.mode.isEmpty() || it.mode == style }
            .mapNotNull { entry ->
                val unit = UnitManager.get(entry.name) ?: return@mapNotNull null
                EditorItem(entry.name, 2, team, unit.spriteKey.sheet)
            }
        val named = rules.units.mapTo(mutableSetOf()) { it.name }
        val extra = UnitManager.getAll()
            .filter { it.name !in named }
            .sortedBy { it.name }
            .map { EditorItem(it.name, 2, team, it.spriteKey.sheet) }
        return listed + extra
    }

    /**
     * Building rows for one of the five building colours (the last one is neutral), in the
     * game editor's list order (`xml/editor/buildings.xml`); unknown structures (mods) follow.
     */
    fun buildingItems(team: Int): List<EditorItem> {
        ensureGameData()
        val listed = rules.buildings
            .filter { it.mode.isEmpty() || it.mode == style }
            .mapNotNull { entry ->
                val sprite = TileManager.get(entry.name).sprite
                if (sprite.isEmpty()) null else EditorItem(entry.name, 1, team, sprite)
            }
        val named = rules.buildings.mapTo(mutableSetOf()) { it.name }
        val extra = TileManager.all()
            .filter { it.sprite.isNotEmpty() && it.name !in named }
            .sortedBy { it.name }
            .map { EditorItem(it.name, 1, team, it.sprite) }
        return listed + extra
    }

    /** The rule set of a terrain, or null (port-only terrain such as Desert). */
    fun rulesFor(name: String): EditorTerrainRulesConfig? = rulesByName[name]

    // ---------------------------------------------------------------------
    // Loading / saving
    // ---------------------------------------------------------------------

    private fun ensureGameData() {
        ModRegistry.ensureLoaded(assetsDir)
        if (TileManager.all().isEmpty() || UnitManager.getAll().isEmpty()) {
            runCatching { GameLoader.loadAssets(assetsDir) }
        }
    }

    /** A new empty map, every cell on the default terrain (the original's `NEW_ENTRY` path). */
    fun newMap(name: String, cols: Int, rows: Int, classicMode: Boolean = false): Boolean {
        ensureGameData()
        val w = cols.coerceIn(MIN_COLS, MAX_COLS)
        val h = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        val plains = rulesByName[DEFAULT_TERRAIN]?.rules?.firstOrNull()
        val sprites = plains?.let { spritesOf(it) }
            ?: TerrainSpritesConfig(single = TileManager.get(DEFAULT_TERRAIN).sprites.single)
        val palette = listOf(PaletteEntryConfig(DEFAULT_TERRAIN, 0, sprites))
        val tiles = (0 until h).flatMap { r ->
            (0 until w).map { c ->
                MapTileConfig(c = c, r = r, layers = listOf(TileLayerConfig(0, 0)))
            }
        }
        doc = MapConfig(
            name = name.ifEmpty { "Untitled Map" },
            rows = h, cols = w, palette = palette, tiles = tiles,
            classicMode = classicMode,
        )
        mapId = name.ifEmpty { "Untitled Map" }
        targetPath = null
        targetIsModPatch = false
        dirty = false
        recalculateAll()
        status = "New ${w}×$h map (${if (classicMode) "classic" else "blitz"})"
        return true
    }

    fun open(id: String): Boolean {
        ensureGameData()
        val cfg = AssetLoader.map(assetsDir, "$id.json")
        if (cfg == null) {
            status = "Could not read maps/$id.json"
            return false
        }
        val mod = ModRegistry.mods.firstOrNull { it.patches.containsKey("maps/$id.json") }
        adopt(id, cfg)
        targetPath = if (mod != null) "${mod.dir}/data/patches/maps/$id.json"
        else "${AssetLoader.dataDir(assetsDir)}/maps/$id.json"
        targetIsModPatch = mod != null
        status = "Loaded maps/$id.json (${cfg.cols}×${cfg.rows})"
        return true
    }

    fun openFile(path: String): Boolean {
        ensureGameData()
        val text = Fio.readText(path) ?: run { status = "Could not read $path"; return false }
        val cfg = try {
            json.decodeFromString(MapConfig.serializer(), text)
        } catch (t: Throwable) {
            status = "Not a map document: ${t.message}"
            return false
        }
        adopt(path.substringAfterLast('/').removeSuffix(".json"), cfg)
        targetPath = path
        targetIsModPatch = false
        status = "Loaded $path"
        return true
    }

    private fun adopt(id: String, cfg: MapConfig) {
        mapId = id
        doc = cfg
        dirty = false
    }

    /** Writes the document to the file it was opened from. */
    fun save(): Boolean {
        val path = targetPath ?: run {
            status = "This map has no file yet"
            return false
        }
        return write(path, patch = targetIsModPatch)
    }

    fun saveAs(path: String): Boolean {
        targetPath = path
        targetIsModPatch = false
        return write(path, patch = false)
    }

    fun exportTo(path: String): Boolean = write(path, patch = false).also {
        status = if (it) "Exported $path" else "Could not export to $path"
    }

    private fun write(path: String, patch: Boolean): Boolean {
        val cfg = doc ?: return false
        val body = json.encodeToString(MapConfig.serializer(), cfg)
        val text = if (patch) {
            "[\n  {\n    \"op\": \"add\",\n    \"path\": \"/\",\n    \"value\": $body\n  }\n]\n"
        } else {
            "$body\n"
        }
        val ok = Fio.writeText(path, text)
        if (ok) dirty = false
        status = if (ok) "Saved $path" else "Could not write $path"
        return ok
    }

    /** The JSON the Save panel shows (the original prints the map XML for copying). */
    fun documentJson(): String {
        val cfg = doc ?: return ""
        return json.encodeToString(MapConfig.serializer(), cfg)
    }

    /**
     * The script document as JSON for the Script panel.
     *
     * An all-default script serialises as `{}`, which read as "the script is missing". Show the
     * document's typed skeleton instead (`state` / `units` / `structures` / `events`) so an
     * empty map is visibly a script document with no events, not a broken viewer.
     */
    fun scriptJson(): String {
        val cfg = doc ?: return ""
        val encoded = json.encodeToString(MapScriptConfig.serializer(), cfg.script)
        return if (encoded == "{}") EMPTY_SCRIPT_DOCUMENT else encoded
    }

    /** True when the document's `script` block holds no state, units, structures or events. */
    fun scriptIsEmpty(): Boolean {
        val cfg = doc ?: return true
        return json.encodeToString(MapScriptConfig.serializer(), cfg.script) == "{}"
    }

    /** One-line description of the script's contents for the Script panel. */
    fun scriptSummary(): String {
        val script = doc?.script ?: return "No map open"
        if (scriptIsEmpty()) return "This map's script block is empty."
        val state = listOfNotNull(
            script.state.turn?.let { "turn $it" },
            script.state.round?.let { "round $it" },
            script.state.controller?.let { "controller $it" },
        )
        return buildString {
            append("${script.events.size} event(s)")
            append(" · ${script.units.size} script unit(s)")
            append(" · ${script.structures.size} script structure(s)")
            if (state.isNotEmpty()) append(" · state: ${state.joinToString(", ")}")
        }
    }

    fun resetScript(): Boolean {
        val cfg = doc ?: return false
        doc = cfg.copy(script = MapScriptConfig())
        dirty = true
        status = "Script reset"
        return true
    }

    /** Every map document the game ships and mods add. */
    fun availableMaps(): List<String> {
        ModRegistry.ensureLoaded(assetsDir)
        val base = AssetLoader.list(assetsDir, "maps").flatMap { entry ->
            val nested = AssetLoader.list(assetsDir, "maps/$entry")
            if (nested.isEmpty()) listOf(entry) else nested.map { "$entry/$it" }
        }
        val modded: List<String> = ModRegistry.data?.patchedPaths
            ?.filter { path -> path.startsWith("maps/") && path.endsWith(".json") }
            ?.map { path -> path.removePrefix("maps/").removeSuffix(".json") }
            .orEmpty()
        return (base + modded).distinct().sorted()
    }

    // ---------------------------------------------------------------------
    // Placement
    // ---------------------------------------------------------------------

    /**
     * Places a terrain, a building or a unit on (col,row).
     *
     * Terrain placement mirrors `CustomMap.setLayerItemAt`: setting the same material again is a
     * no-op, otherwise the cell takes the material and the 5×5 area around it is re-joined.
     */
    fun place(col: Int, row: Int, item: EditorItem): Boolean {
        if (doc == null || !inBounds(col, row)) return false
        val placed = when (item.depth) {
            0 -> placeTerrain(col, row, item.name)
            1 -> placeBuilding(col, row, item.name, item.team)
            else -> placeUnit(col, row, item.name, item.team)
        }
        if (placed) {
            dirty = true
            status = "Placed ${item.name} at $col,$row"
        }
        return placed
    }

    private fun placeTerrain(col: Int, row: Int, name: String): Boolean {
        if (nameAt(col, row, 0) == name) return false
        val sprites = spritesForMaterial(name, 0) ?: return false
        val ref = paletteRef(name, 0, sprites)
        if (ref < 0) return false
        setLayer(col, row, 0, ref)
        // The art is final only after the rules have seen the new neighbourhood.
        recalculateArea(col - 2, row - 2, 5, 5)
        return true
    }

    fun placeUnit(col: Int, row: Int, name: String, team: Int): Boolean {
        val sprites = UnitManager.get(name)?.let { TerrainSpritesConfig(single = it.spriteKey.sheet) }
            ?: return false
        if (entryAt(col, row, 2)?.name == name && entryAt(col, row, 2)?.team == team) return false
        val ref = paletteRef(name, team, sprites)
        if (ref < 0) return false
        setLayer(col, row, 2, ref)
        return true
    }

    fun placeBuilding(col: Int, row: Int, name: String, team: Int): Boolean {
        val sprites = TerrainSpritesConfig(single = TileManager.get(name).sprite)
        if (sprites.single.isEmpty()) return false
        if (entryAt(col, row, 1)?.name == name && entryAt(col, row, 1)?.team == team) return false
        val ref = paletteRef(name, team, sprites)
        if (ref < 0) return false
        setLayer(col, row, 1, ref)
        return true
    }

    /** Deletes a unit (the units panel's "Delete Unit" button). */
    fun deleteUnit(col: Int, row: Int): Boolean =
        clearLayer(col, row, 2).also { if (it) { dirty = true; status = "Deleted unit at $col,$row" } }

    /** Deletes a building (the buildings panel's "Delete Building" button). */
    fun deleteBuilding(col: Int, row: Int): Boolean =
        clearLayer(col, row, 1).also { if (it) { dirty = true; status = "Deleted building at $col,$row" } }

    /** The art a material is placed with when the rules have not chosen one yet. */
    private fun spritesForMaterial(name: String, team: Int): TerrainSpritesConfig? {
        val rule = rulesFor(name)?.rules?.firstOrNull()
        if (rule != null) return spritesOf(rule)
        if (team > 0) {
            val sheet = UnitManager.get(name)?.spriteKey?.sheet.orEmpty()
            if (sheet.isNotEmpty()) return TerrainSpritesConfig(single = sheet)
            val sprite = TileManager.get(name).sprite
            if (sprite.isNotEmpty()) return TerrainSpritesConfig(single = sprite)
        }
        val base = TileManager.get(name).sprites
        if (base.single.isEmpty() && base.variants.isEmpty() && base.composite.isEmpty()) return null
        return TerrainSpritesConfig(base.variants, base.composite, base.single)
    }

    private fun spritesOf(rule: EditorRuleConfig): TerrainSpritesConfig = when {
        rule.composite.size == 4 -> TerrainSpritesConfig(composite = rule.composite)
        rule.images.size == 1 -> TerrainSpritesConfig(single = rule.images[0])
        rule.images.isNotEmpty() -> TerrainSpritesConfig(variants = rule.images)
        else -> TerrainSpritesConfig()
    }

    // ---------------------------------------------------------------------
    // The rule pass
    // ---------------------------------------------------------------------

    /**
     * Re-runs the drawing rules for one cell (`RuleMapper.getMatch` + the dependency fallback).
     */
    fun resolveTerrain(col: Int, row: Int): Boolean {
        val current = doc ?: return false
        if (!inBounds(col, row)) return false
        val name = nameAt(col, row, 0) ?: return false
        val mapper = rulesByName[name] ?: return false
        val test = matchStringAt(col, row, mapper.oob.ifEmpty { OOB_ASSUMPTION })
        var chosen = mapper
        var match = EditorRules.firstMatch(mapper, test)
        if (match == null && mapper.dependency.isNotEmpty()) {
            // The original swaps the cell to the dependency material when the rule set has no
            // matching tile (a bridge with no road around it becomes sea, and so on).
            chosen = rulesByName[mapper.dependency] ?: return false
            match = EditorRules.firstMatch(chosen, test) ?: return false
            val sprites = spritesOf(match)
            if (sprites.single.isEmpty() && sprites.variants.isEmpty() && sprites.composite.isEmpty()) {
                return false
            }
            val ref = paletteRef(chosen.name, 0, sprites)
            if (ref < 0) return false
            val changed = setLayer(col, row, 0, ref)
            clearLayer(col, row, 1)
            return changed
        }
        if (match == null) return false
        val sprites = spritesOf(match)
        if (sprites.single.isEmpty() && sprites.variants.isEmpty() && sprites.composite.isEmpty()) {
            return false
        }
        val ref = paletteRef(chosen.name, 0, sprites)
        if (ref < 0) return false
        return setLayer(col, row, 0, ref)
    }

    /**
     * The nine identity characters around (col,row): row-major, the cell itself in the middle.
     * Outside the map the mapper's own out-of-bounds assumption is used (`S` by default,
     * `r` for roads — the original's imaginary out-of-bounds road).
     */
    fun matchStringAt(col: Int, row: Int, oob: String): String {
        val assumption = oob.ifEmpty { OOB_ASSUMPTION }
        val out = StringBuilder(9)
        for (r in row - 1..row + 1) {
            for (c in col - 1..col + 1) {
                if (!inBounds(c, r)) {
                    out.append(assumption.firstOrNull() ?: 'S')
                } else {
                    out.append(identityAt(c, r))
                }
            }
        }
        return out.toString()
    }

    /** The identity character of a cell's terrain (`P`, `S`, `H`, ...). */
    private fun identityAt(c: Int, r: Int): Char {
        val name = nameAt(c, r, 0) ?: return UNKNOWN_IDENTITY
        val identity = rulesByName[name]?.identity
        if (!identity.isNullOrEmpty()) return identity[0]
        // Terrain the original editor has no rule document for (Desert, Factory Base) is "other":
        // `?` matches `*` but none of the tilde/dash wildcard groups, so it never reads as land,
        // sea or road by accident.
        return UNKNOWN_IDENTITY
    }

    /** Re-runs the rules over a rectangle (the original's `recalculateMapArea`). */
    fun recalculateArea(c0: Int, r0: Int, w: Int, h: Int): Int {
        var changed = 0
        for (r in r0 until r0 + h) {
            for (c in c0 until c0 + w) {
                if (!inBounds(c, r)) continue
                if (resolveTerrain(c, r)) changed++
            }
        }
        lastRecalculated = changed
        return changed
    }

    fun recalculateAll(): Int = recalculateArea(0, 0, cols(), rows())

    // ---------------------------------------------------------------------
    // Whole-map operations
    // ---------------------------------------------------------------------

    /** Fills the map with a terrain (the original's Fill popup, `fillMapWith`). */
    fun fill(terrainName: String): Boolean {
        val sprites = spritesForMaterial(terrainName, 0) ?: run {
            status = "No art for $terrainName"
            return false
        }
        val ref = paletteRef(terrainName, 0, sprites)
        if (ref < 0) return false
        for (r in 0 until rows()) {
            for (c in 0 until cols()) {
                setLayer(c, r, 0, ref)
            }
        }
        dirty = true
        recalculateAll()
        status = "Filled with $terrainName"
        return true
    }

    /** Grows/shrinks the map (the original's Resize popup, `CustomMap.resize`). */
    fun resize(top: Int, left: Int, bottom: Int, right: Int): Boolean {
        val current = doc ?: return false
        val oldCols = current.cols
        val oldRows = current.rows
        val newCols = (oldCols + left + right).coerceIn(MIN_COLS, MAX_COLS)
        val newRows = (oldRows + top + bottom).coerceIn(MIN_ROWS, MAX_ROWS)
        if (newCols == oldCols && newRows == oldRows) return false
        val old = current.tiles.associateBy { it.c to it.r }
        val plainsSprites = rulesByName[DEFAULT_TERRAIN]?.rules?.firstOrNull()?.let { spritesOf(it) }
            ?: current.palette.firstOrNull { it.name == DEFAULT_TERRAIN && it.team == 0 }?.sprites
            ?: TerrainSpritesConfig(single = TileManager.get(DEFAULT_TERRAIN).sprites.single)
        val plains = current.palette.indexOfFirst {
            it.name == DEFAULT_TERRAIN && it.team == 0 && it.sprites == plainsSprites
        }.takeIf { it >= 0 } ?: run {
            doc = current.copy(palette = current.palette + PaletteEntryConfig(DEFAULT_TERRAIN, 0, plainsSprites))
            doc!!.palette.lastIndex
        }
        val tiles = (0 until newRows).flatMap { r ->
            (0 until newCols).map { c ->
                val source = old[(c - left) to (r - top)]
                if (source != null) {
                    MapTileConfig(c = c, r = r, layers = source.layers)
                } else {
                    MapTileConfig(c = c, r = r, layers = listOf(TileLayerConfig(0, plains)))
                }
            }
        }
        doc = doc!!.copy(cols = newCols, rows = newRows, tiles = tiles)
        dirty = true
        recalculateAll()
        status = "Resized to ${newCols}×$newRows"
        return true
    }

    /** Which teams have a unit or building on the map (the original's `countPlayers`). */
    fun countPlayers(): List<Int> {
        val cfg = doc ?: return emptyList()
        val teams = mutableSetOf<Int>()
        for (tile in cfg.tiles) {
            for (layer in tile.layers) {
                if (layer.depth == 0) continue
                val team = cfg.palette.getOrNull(layer.reference)?.team ?: continue
                if (team > 0) teams += team
            }
        }
        return teams.sorted()
    }

    companion object {
        /** The original's map limits (`EditorInterface.MIN_COLS` ... `MAX_COLS`). */
        const val MIN_COLS = 10
        const val MIN_ROWS = 10
        const val MAX_COLS = 20
        const val MAX_ROWS = 20

        /** The original's out-of-bounds assumption (`OUT_OF_BOUNDS_ASSUMPTION`). */
        const val OOB_ASSUMPTION = "S"

        /** Identity used for terrain without a rule document. */
        const val UNKNOWN_IDENTITY = '?'

        const val DEFAULT_TERRAIN = "Plains"

        /** The fill popup's terrains (`VALID_FILL_TERRAIN`). */
        val FILL_TERRAINS = listOf("Plains", "Sea", "Forest", "Mountain", "Road")

        /** Unit colours (`VALID_UNIT_COLORS`): 4 teams. */
        const val UNIT_TEAMS = 4

        /** Building colours (`VALID_BUILDING_COLORS`): 4 teams plus neutral. */
        const val BUILDING_TEAMS = 5

        /** Shown by the Script panel when the document has no state, units, structures or events. */
        private const val EMPTY_SCRIPT_DOCUMENT = """{
    "state": {},
    "units": [],
    "structures": [],
    "events": []
}"""

        const val WM_MODE = "wm_mode"
        const val CLASSIC_MODE = "classic_mode"
    }
}

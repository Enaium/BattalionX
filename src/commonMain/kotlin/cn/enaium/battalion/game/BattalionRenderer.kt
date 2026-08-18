package cn.enaium.battalion.game

import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.TileManager
import cn.enaium.battalion.data.TileSwatch
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.controllers.HALController
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.ui.Dialogue
import cn.enaium.battalion.game.ui.TextRenderer
import cn.enaium.battalion.game.ui.TextureManager
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLTexture

/**
 * Draws the whole game state to the SDL renderer: the terrain grid, structures, units, the
 * selection and its move/attack ranges, a top status bar and a right-hand info panel.
 *
 * The original rendered sprite sheets; this port draws vector shapes and text so it works with
 * no image assets. [TileManager.TILE_WIDTH] is the logical size of one tile in world pixels.
 */
class BattalionRenderer(
    val game: GameManager,
    private val text: TextRenderer,
    private val renderer: SDLRenderer,
    private val textures: TextureManager,
) {
    /** Transient message (e.g. build feedback) shown in the side panel. */
    var buildMessage: String = ""

    /** The active mission dialogue, shown as an overlay when non-null and active. */
    var dialogue: Dialogue? = null

    // Avatar sprite sheet: 780×600, 6 columns × 4 rows, each cell 130×150 pixels.
    private val AVATAR_COLS = 6
    private val AVATAR_ROWS = 4
    private val AVATAR_CELL_W = 130
    private val AVATAR_CELL_H = 150

    // Avatar name -> (column, row) in the sprite sheet.
    // These positions are based on visual inspection of the sprite sheet.
    private val avatarPositions = mapOf(
        "tucker" to (0 to 0),           // Top-left
        "yurik" to (1 to 0),            // Second from left, top row
        "hayne" to (2 to 0),            // Third from left, top row
        "mullen" to (3 to 0),           // Fourth from left, top row
        "pearl" to (4 to 0),            // Fifth from left, top row
        "argent" to (5 to 0),           // Rightmost, top row
        "akadian_3" to (0 to 1),        // Leftmost, second row
        "evil_durand" to (1 to 1),      // Second from left, second row
        "evil_durand_dark" to (2 to 1), // Third from left, second row
    )

    // Avatar texture, loaded once.
    private var avatarTexture: SDLTexture? = null

    /**
     * Returns the source rectangle for the avatar with the given [name], or null if unknown.
     */
    private fun avatarFrame(name: String): SDLFRect? {
        val pos = avatarPositions[name.lowercase()] ?: return null
        val (col, row) = pos
        return SDLFRect(
            (col * AVATAR_CELL_W).toFloat(),
            (row * AVATAR_CELL_H).toFloat(),
            AVATAR_CELL_W.toFloat(),
            AVATAR_CELL_H.toFloat()
        )
    }

    /**
     * Loads the avatar sprite sheet texture if not already loaded.
     */
    private fun loadAvatarTexture() {
        if (avatarTexture != null) return
        avatarTexture = textures.texture("avatars")
    }

    /**
     * Per-unit walking position (fractional tile coords). [MoveUnitAction] advances
     * [Unit.moveProgressMs] each frame and completes when the walk finishes; the renderer only
     * interpolates the *displayed* position from that progress, so the animation and the queued
     * attack / sound effects stay in sync.
     */
    private data class UnitWalk(val visX: Double, val visY: Double)

    /**
     * Returns the rendered tile position (fractional) for a unit, interpolating along
     * [Unit.movePath] by [Unit.moveProgressMs]. Turning at corners is picked up from the
     * segment direction; when idle the unit simply sits on its logical tile.
     */
    private fun unitRenderTileH(unit: Unit): UnitWalk {
        val path = unit.movePath
        if (!unit.isMoving || path.size < 2) {
            return UnitWalk(unit.x.toDouble(), unit.y.toDouble())
        }
        val stepMs = Unit.MOVE_STEP_MS
        val progress = unit.moveProgressMs.coerceIn(0, ((path.size - 1) * stepMs).coerceAtLeast(stepMs))
        val segF = progress.toDouble() / stepMs
        var idx = segF.toInt()
        if (idx >= path.size - 1) idx = path.size - 2
        val frac = segF - idx
        val from = path[idx]
        val to = path[idx + 1]
        // Face the unit along the current segment so it visually turns at corners.
        unit.animFacing = facingFor(to.x - from.x, to.y - from.y)
        return UnitWalk(
            from.x + (to.x - from.x) * frac,
            from.y + (to.y - from.y) * frac,
        )
    }

    /** Compass facing (0=N,1=E,2=S,3=W) for an orthogonal step vector. */
    private fun facingFor(dx: Int, dy: Int): Int = when {
        dy < 0 -> 0  // north
        dy > 0 -> 2  // south
        dx > 0 -> 1  // east
        dx < 0 -> 3  // west
        else -> unitDefaultFacing()
    }

    private fun unitDefaultFacing(): Int = 1

    // terrain sprite key -> texture, resolved once from the map palette's image codes.
    private val terrainTextures = mutableMapOf<String, SDLTexture?>()

    /** Resolves and caches the terrain textures from the map palette. */
    fun setupTextures() {
        for (swatch in game.map.palette) {
            if (swatch.getTeam() != 0) continue // units are not terrain
            for (file in swatch.imageFiles) {
                val key = file.substringBeforeLast('.')
                if (terrainTextures.containsKey(key)) continue
                terrainTextures[key] = textures.textureOrFallback(key)
            }
        }
    }

    companion object {
        const val WINDOW_W = 800
        const val WINDOW_H = 590
        const val TOP_BAR_H = 30
        const val SIDE_W = 240

        val MAP_X = 0
        val MAP_Y = TOP_BAR_H
        val MAP_W = TileManager.TILE_WIDTH * 10
        val MAP_H = TileManager.TILE_HEIGHT * 10
        val SIDE_X = MAP_W
        val SIDE_Y = TOP_BAR_H

        /** Number of map tiles visible in the viewport (the map area is 10×10 tiles). */
        val VIEW_COLS = MAP_W / TileManager.TILE_WIDTH
        val VIEW_ROWS = MAP_H / TileManager.TILE_HEIGHT
    }

    // Camera: the top-left map tile currently visible. Larger maps scroll within these bounds.
    var cameraX = 0
        private set
    var cameraY = 0
        private set

    /**
     * A monotonically increasing frame index used to drive terrain animations (animated sea /
     * shore / reef strips cycle one 56px frame every 500 ms). Ticked from [render].
     * We count tiles per frame rather than wall-clock ms so the animation is stable.
     */
    private var terrainAnimMs: Long = 0
    private val terrainAnimFrame: Int get() = (terrainAnimMs / 500).toInt()

    /** Clamps the camera so the viewport stays inside the map. */
    fun clampCamera() {
        val map = game.map
        cameraX = cameraX.coerceIn(0, maxOf(0, map.width - VIEW_COLS))
        cameraY = cameraY.coerceIn(0, maxOf(0, map.height - VIEW_ROWS))
    }

    /** Scrolls the camera by [dx]/[dy] tiles and clamps it to the map. */
    fun scrollCamera(dx: Int, dy: Int) {
        cameraX += dx
        cameraY += dy
        clampCamera()
    }

    /** Screen x for a map column, accounting for the camera offset. */
    private fun screenX(col: Int): Int = MAP_X + (col - cameraX) * TileManager.TILE_WIDTH

    /** Screen y for a map row, accounting for the camera offset. */
    private fun screenY(row: Int): Int = MAP_Y + (row - cameraY) * TileManager.TILE_HEIGHT

    // Terrain -> base colour. Unknown terrain falls back to a neutral grey.
    private fun terrainColor(name: String): SDLColor = when (name) {
        "Sea" -> SDLColor(40, 90, 160)
        "Shore" -> SDLColor(120, 160, 190)
        "Reef" -> SDLColor(60, 140, 170)
        "Plains" -> SDLColor(150, 190, 110)
        "Forest" -> SDLColor(70, 130, 60)
        "Mountain" -> SDLColor(140, 140, 145)
        "Hill" -> SDLColor(170, 160, 90)
        "Road" -> SDLColor(180, 170, 130)
        "Factory Base" -> SDLColor(120, 120, 120)
        "Ore Deposit", "Enriched Ore Deposit", "Depleted Ore Deposit" -> SDLColor(150, 110, 60)
        "Wasteland" -> SDLColor(190, 170, 120)
        "Volcano" -> SDLColor(110, 60, 50)
        "Bridge", "High Bridge" -> SDLColor(140, 130, 120)
        "Canyon" -> SDLColor(160, 120, 90)
        "Rock Formation" -> SDLColor(130, 130, 135)
        else -> SDLColor(110, 110, 110)
    }

    /**
     * The accent colour for a controller/team, read from the team's configured colour
     * (matching the original, where the mission sets e.g. P1 = light green, P2 = light blue).
     * Falls back to a per-index palette if the team colour is unset.
     *
     * The colour is **lightened** before use as a multiplicative tint: applied directly, a pure
     * team colour (e.g. pure green) would multiply away every other channel and make the sprite
     * a dark silhouette. Mixing partway to white keeps the hue but lets the sprite's shading show.
     */
    private fun teamColor(team: cn.enaium.battalion.data.Team?): SDLColor {
        if (team != null && team.color != 0u) {
            // team.color is ARGB (e.g. ff00ff00). Drop the alpha byte.
            val rgb = team.color and 0x00ffffffu
            val r = ((rgb shr 16) and 0xffu).toInt()
            val g = ((rgb shr 8) and 0xffu).toInt()
            val b = (rgb and 0xffu).toInt()
            // blend 55% toward white so the tint stays bright and readable
            val blend = 0.55
            return SDLColor(
                (r + (255 - r) * blend).toInt(),
                (g + (255 - g) * blend).toInt(),
                (b + (255 - b) * blend).toInt(),
            )
        }
        return SDLColor(200, 200, 200)
    }

    /** Accent colour for the team at controller index [index] (for panel / fallback use). */
    private fun teamColor(index: Int): SDLColor {
        val c = game.getController(index)
        return teamColor(c?.team)
    }

    /**
     * The team's *un-lightened* RGB colour (ARGB byte order on the value), used as the tint
     * target for CPU palettization. This is the actual configured team colour — not the
     * display-lightened one from [teamColor].
     */
    private fun rawTeamColor(team: cn.enaium.battalion.data.Team?): SDLColor {
        if (team != null && team.color != 0u) {
            val rgb = team.color and 0x00ffffffu
            return SDLColor(
                ((rgb shr 16) and 0xffu).toInt(),
                ((rgb shr 8) and 0xffu).toInt(),
                (rgb and 0xffu).toInt(),
            )
        }
        return SDLColor(200, 200, 200)
    }

    private fun controllerIndex(unit: Unit): Int =
        game.getControllerIndex(unit.controller ?: return -1)

    /** Accent colour for a unit's own team. */
    private fun unitColor(unit: Unit): SDLColor = teamColor(unit.controller?.team)

    /** Maps a unit type name to its sprite-index key (the exported sprite's embedded name). */
    private fun spriteKeyForUnit(unit: Unit): String {
        // Prefer the unit's own registered sprite name from its XML (`<sprite value="Warmachine"/>`).
        // The TextureManager indexes images by the repeated lowercase name in their filename
        // (e.g. "Warmachine" -> "warmachine"), so we lowercase + strip spaces for the lookup.
        val key = unit.unitData.sprite
            .lowercase()
            .replace(" ", "")
            .replace("-", "")
        if (textures.pathFor(key) != null || textures.textureSize(key) != null) return key
        // Fall back to the explicit complete table (source: _assets/3316.bin tilesheet definitions).
        return when (unit.unitData.name) {
            "Warmachine" -> "warmachine"
            "Jammer Truck" -> "jammer"
            "Lancer Tank" -> "lancer"
            "Spider Tank" -> "spider"
            "Heavy Commando" -> "hcommando"
            "Strike Commando" -> "commando"
            "Blockade" -> "blockade"
            "Flak Tank" -> "flak"
            "Scorpion Tank" -> "scorpion"
            "Mortar Truck" -> "mortar"
            "Rocket Truck" -> "rocket"
            "Annihilator Tank" -> "annihilator"
            "Turret" -> "turret"
            "Stealth Tank" -> "stealth"
            "Albatross Transport" -> "albatross"
            "Raptor Fighter" -> "raptor"
            "Vulture Drone" -> "vulture"
            "Condor Bomber" -> "condor"
            "Leviathan Barge" -> "leviathan"
            "Hunter Support" -> "hunter"
            "Corvette Fighter" -> "corvette"
            "U-Boat" -> "uboat"
            "Battlecruiser" -> "battlecruiser"
            "Intrepid" -> "intrepid"
            else -> key
        }
    }

    /**
     * Maps a compass facing (0=N, 1=E, 2=S, 3=W) to a column in a unit's direction
     * sprite sheet.
     *
     * The original unit tilesheet (`_assets/3316.bin` and `TileSheetSliceManager`)
     * lays out its 6 columns as, left → right:
     *   col 0 = "right"    (facing EAST)
     *   col 1 = "down"     (facing SOUTH)
     *   col 2 = "left"     (facing WEST)
     *   col 3 = "up"       (facing NORTH)
     *   col 4 = "right_idle"  (east, animation frame 2)
     *   col 5 = "left_idle"   (west,  animation frame 2)
     * So east→0, south→1, west→2, north→3.
     */
    private fun facingColumn(facing: Int): Int = when (facing) {
        1 -> 0  // east  → column 0 ("right")
        2 -> 1  // south → column 1 ("down")
        3 -> 2  // west  → column 2 ("left")
        else -> 3  // north / unknown → column 3 ("up")
    }

    /** Renders one frame. */
    fun render() {
        terrainAnimMs += 16
        renderer.drawColor = SDLColor(30, 30, 35)
        renderer.clear()

        drawTopBar()
        drawMap()
        drawSidePanel()

        val dia = dialogue
        if (dia != null && dia.isActive()) drawDialogue(dia)

        if (game.gameOver) drawGameOverOverlay()

        renderer.present()
    }

    /** Draws the end-of-game victory / defeat banner over the whole screen. */
    private fun drawGameOverOverlay() {
        // Dim the board.
        renderer.drawColor = SDLColor(0, 0, 0, 170)
        renderer.fillRect(SDLRect(0, 0, WINDOW_W, WINDOW_H))

        val title = if (game.playerWon) "VICTORY" else "DEFEAT"
        val color = if (game.playerWon) SDLColor(120, 220, 120) else SDLColor(220, 90, 80)
        val titleX = WINDOW_W / 2
        val titleY = WINDOW_H / 2 - 40
        text.drawCentered(title, titleX, titleY, 42, color)
        text.drawCentered("Press ESC to close", titleX, titleY + 44, 18, SDLColor(220, 220, 225))
    }

    private fun drawTopBar() {
        renderer.drawColor = SDLColor(45, 45, 55)
        renderer.fillRect(SDLRect(0, 0, WINDOW_W, TOP_BAR_H))

        val active = game.activeController
        val playerName = active?.team?.name ?: "—"
        val text1 = "Turn ${game.turn}   Round ${game.round}   Player: $playerName"
        text.draw(text1, 8, 7, 16, SDLColor(230, 230, 230))

        val status = if (game.gameOver) "GAME OVER" else if (active is HALController) "AI thinking..." else "Your move"
        text.draw(status, WINDOW_W - 8 - text.measure(status, 16), 7, 16,
            if (game.gameOver) SDLColor(255, 120, 80) else SDLColor(120, 220, 120))
    }

    private fun drawMap() {
        val map = game.map

        // Terrain grid — only the tiles visible in the viewport, offset by the camera.
        val fromCol = cameraX
        val fromRow = cameraY
        val toCol = minOf(cameraX + VIEW_COLS, map.width)
        val toRow = minOf(cameraY + VIEW_ROWS, map.height)
        for (row in fromRow until toRow) {
            for (col in fromCol until toCol) {
                val tile = game.tileAt(col, row) ?: continue
                val px = screenX(col)
                val py = screenY(row)
                drawTerrainTile(tile, px, py)
            }
        }

        // Selection ranges (under units). Only a unit that can still act this turn shows its
        // move/attack range — a unit that has already moved or fired (or is otherwise spent) is
        // darkened and shows no target highlights, matching the one-action-per-turn rule.
        //
        // The range markers use the game's own sprite assets (select/selectRed/attackTargets)
        // exactly like the original human controller draws them, instead of vector rectangles.
        val selected = (game.activeController as? HumanController)?.selectedUnit
        if (selected != null && selected.controller === game.activeController && selected.isAvailable()) {
            // Green "select" markers on every tile the unit can walk to this turn.
            for (tile in game.tilesInMoveRange(selected)) {
                if (tile.x < cameraX || tile.x >= cameraX + VIEW_COLS) continue
                if (tile.y < cameraY || tile.y >= cameraY + VIEW_ROWS) continue
                val t = textureMarker("select")
                if (t != null) {
                    renderer.renderTexture(t, dst = SDLFRect(screenX(tile.x).toFloat(), screenY(tile.y).toFloat(),
                        TileManager.TILE_WIDTH.toFloat(), TileManager.TILE_HEIGHT.toFloat()))
                }
            }
            // Red "selectRed" + target overlay: enemy units the unit can attack this turn —
            // reachable by walking into weapon range, not merely what is in range from the
            // unit's current spot.
            for (target in game.unitsAttackableThisTurn(selected)) {
                if (target.x < cameraX || target.x >= cameraX + VIEW_COLS) continue
                if (target.y < cameraY || target.y >= cameraY + VIEW_ROWS) continue
                val px = screenX(target.x)
                val py = screenY(target.y)
                textureMarker("selectRed")?.let { t ->
                    renderer.renderTexture(t, dst = SDLFRect(px.toFloat(), py.toFloat(),
                        TileManager.TILE_WIDTH.toFloat(), TileManager.TILE_HEIGHT.toFloat()))
                }
                // attackTargets is a 3-frame strip (bad/ok/good) — show the "target" frame.
                textureMarker("attackTargets")?.let { t ->
                    renderer.renderTexture(
                        t,
                        src = SDLFRect(112f, 0f, 56f, 56f),
                        dst = SDLFRect(px.toFloat(), py.toFloat(),
                            TileManager.TILE_WIDTH.toFloat(), TileManager.TILE_HEIGHT.toFloat()),
                    )
                }
            }
        }

        // Structures.
        for (row in fromRow until toRow) {
            for (col in fromCol until toCol) {
                val tile = game.tileAt(col, row) ?: continue
                if (tile.hasStructure) drawStructure(tile)
            }
        }

        // Units.
        for (unit in game.units) {
            if (!unit.isDeployed()) continue
            if (unit.x in fromCol until toCol && unit.y in fromRow until toRow) drawUnit(unit)
        }

        // Selection highlight: the white "marker2" frame under the selected unit.
        if (selected != null) {
            textureMarker("marker2")?.let { t ->
                renderer.renderTexture(t, dst = SDLFRect(screenX(selected.x).toFloat(),
                    screenY(selected.y).toFloat(), TileManager.TILE_WIDTH.toFloat(),
                    TileManager.TILE_HEIGHT.toFloat()))
            }
        }
    }

    /** Loads (and caches) a 56×56 range-marker texture by sprite name. */
    private fun textureMarker(name: String): SDLTexture? =
        markers.getOrPut(name) { textures.texture(name) }

    /** Cache of the small range-marker textures (select / selectRed / attackTargets / marker2). */
    private val markers = mutableMapOf<String, SDLTexture?>()

    /**
     * Draws a structure on its tile. Uses the structure's terrain sprite (factory, capital,
     * oil refinery, control center, ...) drawn at its natural size and anchored to the tile's
     * bottom, tinted by the owning team. Falls back to a placeholder box for unknown sprites.
     */
    private fun drawStructure(tile: Tile) {
        val px = screenX(tile.x)
        val py = screenY(tile.y)
        val owner = tile.controller?.let { game.getControllerIndex(it) } ?: -1

        // The structure's terrain carries its sprite name (e.g. "capital", "factory").
        val sprite = tile.terrain.sprite
        val tex = if (sprite.isNotEmpty()) textures.textureOrFallback(sprite) else null
        if (tex != null) {
            val size = textures.textureSize(sprite)
            val texW = size?.first ?: TileManager.TILE_WIDTH
            val texH = size?.second ?: TileManager.TILE_HEIGHT
            val w = minOf(texW, TileManager.TILE_WIDTH)
            val h = texH
            // Team tint over the structure.
            val color = if (owner >= 0) teamColor(owner) else SDLColor(255, 255, 255)
            tex.colorMod = color
            // Anchored to the bottom of the tile so taller buildings overflow upward.
            renderer.renderTexture(
                tex,
                src = SDLFRect(0f, 0f, w.toFloat(), h.toFloat()),
                dst = SDLFRect(px.toFloat(), (py + TileManager.TILE_HEIGHT - h).toFloat(), w.toFloat(), h.toFloat()),
            )
            tex.colorMod = SDLColor(255, 255, 255)
            return
        }

        // Fallback placeholder.
        val color = if (owner >= 0) teamColor(owner) else SDLColor(150, 150, 150)
        renderer.drawColor = color
        renderer.fillRect(SDLRect(px + 8, py + 18, TileManager.TILE_WIDTH - 16, TileManager.TILE_HEIGHT - 26))
        renderer.drawColor = SDLColor(90 + color.r / 3, 90 + color.g / 3, 90 + color.b / 3)
        renderer.drawLine(px + 6, py + 20, px + (TileManager.TILE_WIDTH / 2), py + 8)
        renderer.drawLine(px + (TileManager.TILE_WIDTH / 2), py + 8, px + TileManager.TILE_WIDTH - 6, py + 20)
    }

    /** Draws a unit as its sprite, tinted by team colour, with a health bar. */
    private fun drawUnit(unit: Unit) {
        // Draw at the walked position along the unit's movement path (corners turn, no teleport).
        val walk = unitRenderTileH(unit)
        val px = screenX(kotlin.math.round(walk.visX).toInt())
        val py = screenY(kotlin.math.round(walk.visY).toInt())
        val color = unitColor(unit)

        val cx = px + TileManager.TILE_WIDTH / 2
        val cy = py + TileManager.TILE_HEIGHT / 2

        // Draw the unit sprite (scaled to fit the tile). The unit's material is repainted into
        // its team colour via CPU palettization ([TextureManager.textureForTeam]) rather than a
        // multiplicative tint, so the red source art actually becomes the team colour.
        val sprite = spriteKeyForUnit(unit)
        val rawTeam = rawTeamColor(unit.controller?.team)
        val tex = if (sprite.isNotEmpty()) textures.textureForTeam(sprite, rawTeam) else null
        if (tex != null) {
            val size = tex.size
            val texW = size.x.toInt()
            val texH = size.y.toInt()

            val cellW = 56
            // Every unit sheet is 6 cells wide. The cell height is either 56 (regular
            // "Unit" tilesheets) or 112 (tall "UnitTall" tilesheets), and the full
            // height is 4 frames tall (one frame = one row). For a 336x448 sheet that is
            // 4 bands of 56x112; for a 336x224 sheet it is 4 bands of 56x56.
            val cellH = if (texW == 336) texH / 4 else texH

            // Choose the source row: the unit's row 0 is a movement/firing frame; the idle
            // animation cycles rows 0..3. The original animates the idle sprite on a 500ms
            // loop. We advance the row while the unit is acting (or idling) so the sprite
            // actually animates.
            // Expire a finished one-shot action animation back to the idle state. The game clock
            // ([GameManager.elapsedMs]) is the time base the action used to stamp [Unit.animStartMs].
            if (unit.anim != Unit.UnitAnim.NONE &&
                game.elapsedMs - unit.animStartMs > Unit.ANIM_DURATION_MS
            ) {
                unit.anim = Unit.UnitAnim.NONE
            }
            val acting = unit.anim != Unit.UnitAnim.NONE
            val row = if (acting) 0 else ((terrainAnimMs / 300) % 4).toInt()
            // The facing column used for the animation (motion may precede the settled idle).
            val col = facingColumn(if (acting) unit.animFacing else unit.facing)

            // The original draws each unit's 56-wide cell at its natural size, with the
            // cell's bottom edge aligned to the bottom of the tile the unit stands on
            // (see Unit.setX/setY and GameUnit: sprite.y = -(sprite.height - TILE_HEIGHT)).
            // Tall units therefore overflow above the tile, which matches the source game.
            val src = when {
                texW == 336 -> SDLFRect(
                    (col * cellW).toFloat(),
                    (row * cellH).toFloat(), cellW.toFloat(), cellH.toFloat())
                // Single 56x56 sprite (or an unknown sheet): draw it unchanged.
                else -> SDLFRect(0f, 0f, minOf(texW, cellW).toFloat(),
                    minOf(texH, cellH).toFloat())
            }
            val frameW = src.width.toInt()
            val frameH = src.height.toInt()
            // Draw at natural pixel size, centred on the tile and anchored to its bottom.
            val w = minOf(frameW, cellW)
            val h = minOf(frameH, cellH)
            val dstX = (cx - w / 2).toFloat()
            val dstY = (py + TileManager.TILE_HEIGHT - h).toFloat()
            val dim = unit.hasMoved() || unit.hasFired()
            // The texture is already team-coloured; only dim spent units (no recolour).
            tex.colorMod = if (dim) SDLColor(150, 150, 150) else SDLColor(255, 255, 255)
            renderer.renderTexture(tex, src = src, dst = SDLFRect(dstX, dstY, w.toFloat(), h.toFloat()))
            tex.colorMod = SDLColor(255, 255, 255)
        } else {
            // Fallback: a coloured square with the unit's initial.
            val s = 22
            renderer.drawColor = color
            renderer.fillRect(SDLRect(cx - s / 2, cy - s / 2, s, s))
            renderer.drawColor = SDLColor(20, 20, 20)
            renderer.drawRect(SDLRect(cx - s / 2, cy - s / 2, s, s))
            text.drawCentered(unit.unitData.name.firstOrNull()?.toString() ?: "?", cx, cy - 8, 18, SDLColor(255, 255, 255))
        }

        // Health bar.
        val health = unit.getHealth()
        val maxHealth = unit.baseUnitData.health
        val ratio = (health.toDouble() / maxHealth).coerceIn(0.0, 1.0)
        val barW = TileManager.TILE_WIDTH - 8
        val barY = py + TileManager.TILE_HEIGHT - 6
        renderer.drawColor = SDLColor(20, 20, 20)
        renderer.fillRect(SDLRect(px + 4, barY, barW, 4))
        renderer.drawColor = if (ratio > 0.5) SDLColor(80, 200, 80) else if (ratio > 0.25) SDLColor(220, 200, 60) else SDLColor(220, 60, 60)
        renderer.fillRect(SDLRect(px + 4, barY, (barW * ratio).toInt(), 4))
    }

    /** For a `+` composite whose piece is plain "sea_0_v1", the directional sea strip it stands in for. */
    private val SEA_EDGE_STRIPS = listOf("sea_0_nw", "sea_0_ne", "sea_0_se", "sea_0_sw")

    /**
     * Draws one frame of a terrain texture into the destination rect (px, py, dstW, dstH).
     *
     * The original `TileSwatch.getSprite` treats terrain textures three ways:
     *  1. **Strip textures** (height < tile height, e.g. the 112×28 / 224×28 sea & shore
     *     pieces): tiled both horizontally and vertically to fill the cell, STATIC — never
     *     animated frames. (The original `generateJoinedTile` copies 28px segments to build
     *     a full 56×56 quadrant; animating them slices into artefacts / black empty frames.)
     *  2. **Frame-grid tilesheets** (a whole multiple of the tile, wider/taller than one tile,
     *     e.g. reef 224×56, volcano 224×112): sliced into [dstW]×[dstH] frames and played as a
     *     looping animation. [animShift] de-phases adjacent tiles so they are not in lockstep.
     *  3. **Tall single sprites / structures / mountains** (> dstH but not a frame grid):
     *     drawn at natural size anchored to the bottom so they overflow upward.
     *
     * A plain 56×56 texture is a single frame, drawn unchanged.
     */
    private fun drawTerrainFrame(
        tile: Tile,
        key: String,
        px: Int,
        py: Int,
        dstW: Int,
        dstH: Int,
        animShift: Int = 0,
    ) {
        val tex = textures.textureOrFallback(key)
        if (tex == null) {
            renderer.drawColor = terrainColor(tile.terrain.name)
            renderer.fillRect(SDLRect(px, py, dstW, dstH))
            return
        }
        val size = textures.textureSize(key)
        val texW = size?.first ?: dstW
        val texH = size?.second ?: dstH

        // ---- 1. Strip texture (height below the tile height): tile it statically. ----
        if (texH < dstH) {
            // Sea/shore strips are 28px tall and 112..224 wide; repeat them to fill the cell.
            // The original copies 28px (height) segments horizontally within a 56px band.
            val segH = texH
            val segW = texW
            // Tile horizontally across the destination.
            var ox = 0
            while (ox < dstW) {
                renderer.renderTexture(
                    tex,
                    src = SDLFRect(0f, 0f, segW.toFloat(), segH.toFloat()),
                    dst = SDLFRect((px + ox).toFloat(), py.toFloat(), segW.toFloat(), segH.toFloat()),
                )
                ox += segW
            }
            return
        }

        // ---- 2. Tall texture: bottom-anchored static sprite (mountains 56×65, hills 56×112,
        //        structures, etc). This matches the original `TileSwatch.getSprite`, which only
        //        slices a bitmap into frames when its *width* exceeds one tile; a 56-wide tall
        //        bitmap is drawn unchanged and overflows upward. ----
        if (texH > dstH && texW <= dstW) {
            val srcW = minOf(texW, dstW)
            val srcH = texH
            renderer.renderTexture(
                tex,
                src = SDLFRect(0f, 0f, srcW.toFloat(), srcH.toFloat()),
                dst = SDLFRect(px.toFloat(), (py + dstH - srcH).toFloat(), srcW.toFloat(), srcH.toFloat()),
            )
            return
        }

        // ---- 3. Horizontal frame-grid animated tilesheet. The original `TileSwatch.getSprite`
        //        slices a bitmap wider than one tile into 56px-wide vertical frames and plays
        //        them left→right. The frame keeps the texture's FULL height: reef/wasteland are
        //        224×56 (4 frames), volcano is 224×112 (4 tall frames). Never split into rows. ----
        if (texW > dstW && texW % dstW == 0 && dstW > 0) {
            val frames = texW / dstW
            // Stable per-tile offset keeps neighbouring tiles out of phase (deterministic).
            val frame = ((((terrainAnimFrame + ((tile.x * 3 + tile.y) + animShift)) % frames) + frames) % frames)
            val src = SDLFRect(
                (frame * dstW).toFloat(),
                0f,
                dstW.toFloat(), texH.toFloat(),
            )
            // Tall frame (e.g. volcano 112px): draw at natural height anchored to the bottom so
            // it overflows upward; tile-height frames fill the cell exactly.
            if (texH > dstH) {
                renderer.renderTexture(
                    tex,
                    src = src,
                    dst = SDLFRect(px.toFloat(), (py + dstH - texH).toFloat(), dstW.toFloat(), texH.toFloat()),
                )
            } else {
                renderer.renderTexture(
                    tex,
                    src = src,
                    dst = SDLFRect(px.toFloat(), py.toFloat(), dstW.toFloat(), dstH.toFloat()),
                )
            }
            return
        }

        // ---- 4. Plain single-frame texture: draw full cell. ----
        renderer.renderTexture(
            tex,
            dst = SDLFRect(px.toFloat(), py.toFloat(), dstW.toFloat(), dstH.toFloat()),
        )
    }

    /**
     * Draws the terrain tile at pixel position (px, py).
     *
     * The tile's swatch [TileSwatch.kind] decides how [Tile.spriteKeys] are assembled:
     *  - [TileSwatch.Kind.SINGLE] / [TileSwatch.Kind.VARIANT]: one texture covers the whole
     *    tile (variants pick one of [Tile.spriteKeys] via [Tile.variantIndex]).
     *  - [TileSwatch.Kind.COMPOSITE]: a 2×2 quadrant mosaic (sea/shores/canyons) where each of
     *    the four keys draws one quadrant; a `sea_0_v1` slot is replaced by the directional
     *    sea strip for that corner, matching the original `TileSwatch.generateJoinedTile`.
     */
    private fun drawTerrainTile(tile: Tile, px: Int, py: Int) {
        val keys = tile.spriteKeys
        when (tile.spriteKind) {
            TileSwatch.Kind.SINGLE, TileSwatch.Kind.VARIANT -> {
                if (keys.isEmpty()) {
                    renderer.drawColor = terrainColor(tile.terrain.name)
                    renderer.fillRect(SDLRect(px, py, TileManager.TILE_WIDTH, TileManager.TILE_HEIGHT))
                    return
                }
                // A variant tile shows exactly one of its interchangeable textures.
                val key = keys[tile.variantIndex.coerceIn(0, keys.lastIndex)]
                drawTerrainFrame(tile, key, px, py, TileManager.TILE_WIDTH, TileManager.TILE_HEIGHT)
            }
            TileSwatch.Kind.COMPOSITE -> {
                if (keys.size != 4) {
                    // Malformed composite — fall back to a flat fill.
                    renderer.drawColor = terrainColor(tile.terrain.name)
                    renderer.fillRect(SDLRect(px, py, TileManager.TILE_WIDTH, TileManager.TILE_HEIGHT))
                    return
                }
                val halfW = TileManager.TILE_WIDTH / 2
                val halfH = TileManager.TILE_HEIGHT / 2
                val positions = listOf(
                    0 to 0,               // top-left
                    halfW to 0,           // top-right
                    0 to halfH,           // bottom-left
                    halfW to halfH,       // bottom-right
                )
                for (i in 0 until 4) {
                    val (ox, oy) = positions[i]
                    // "sea_0_v1" in a composite slot means "the sea edge meeting this corner";
                    // the original swaps in the matching directional sea strip.
                    val key = if (keys[i] == "sea_0_v1") SEA_EDGE_STRIPS[i] else keys[i]
                    drawTerrainFrame(tile, key, px + ox, py + oy, halfW, halfH, animShift = i)
                }
            }
        }
    }

    private fun drawSidePanel() {
        renderer.drawColor = SDLColor(40, 40, 48)
        renderer.fillRect(SDLRect(SIDE_X, SIDE_Y, SIDE_W, WINDOW_H - SIDE_Y))

        var y = SIDE_Y + 12

        val selected = (game.activeController as? HumanController)?.selectedUnit
        if (selected != null) {
            text.draw("Unit", SIDE_X + 10, y, 15, SDLColor(150, 150, 170)); y += 20
            text.draw(selected.unitData.name, SIDE_X + 10, y, 20, SDLColor(255, 255, 255)); y += 30
            y += 6

            text.draw("Health: ${selected.getHealth()}/${selected.baseUnitData.health}", SIDE_X + 10, y, 15, SDLColor(230, 230, 230)); y += 22
            text.draw("Damage: ${selected.unitData.damage}", SIDE_X + 10, y, 15, SDLColor(230, 230, 230)); y += 22
            text.draw("Range: ${selected.unitData.weaponRangeMin}-${selected.unitData.weaponRangeMax}", SIDE_X + 10, y, 15, SDLColor(230, 230, 230)); y += 22
            text.draw("Move: ${selected.unitData.range}", SIDE_X + 10, y, 15, SDLColor(230, 230, 230)); y += 22

            val moved = if (selected.hasMoved()) "moved" else "ready"
            val fired = if (selected.hasFired()) "fired" else "ready"
            text.draw("Move: $moved | Fire: $fired", SIDE_X + 10, y, 15, SDLColor(200, 200, 200)); y += 26
        } else {
            text.draw("No unit selected", SIDE_X + 10, y, 15, SDLColor(150, 150, 170)); y += 26
        }

        if (buildMessage.isNotEmpty()) {
            y += 4
            text.draw(buildMessage, SIDE_X + 10, y, 14, SDLColor(255, 220, 120)); y += 22
        }

        y += 4
        renderer.drawColor = SDLColor(70, 70, 80)
        renderer.drawLine(SIDE_X + 8, y, SIDE_X + SIDE_W - 8, y)
        y += 12

        text.draw("Controls", SIDE_X + 10, y, 15, SDLColor(150, 150, 170)); y += 22
        for (line in listOf(
            "Left-click: select / move",
            "Left-click enemy: attack",
            "Right-click: clear selection",
            "N: no attack (wait)",
            "E: end turn",
            "B: build (on factory)",
            "X: extract ore (WM)",
            "R: repair selected unit",
        )) {
            text.draw(line, SIDE_X + 10, y, 13, SDLColor(210, 210, 210)); y += 18
        }
    }

    /** Draws the mission briefing dialogue box over the map. */
    private fun drawDialogue(dia: Dialogue) {
        val line = dia.current() ?: return
        val boxX = 20
        val boxY = WINDOW_H - 130
        val boxW = WINDOW_W - 40
        val boxH = 110

        // Dim the map behind the box.
        renderer.drawColor = SDLColor(0, 0, 0, 140)
        renderer.fillRect(SDLRect(0, 0, WINDOW_W, WINDOW_H))

        // Box background + border.
        renderer.drawColor = SDLColor(35, 35, 45, 235)
        renderer.fillRect(SDLRect(boxX, boxY, boxW, boxH))
        renderer.drawColor = SDLColor(120, 120, 140)
        renderer.drawRect(SDLRect(boxX, boxY, boxW, boxH))

        // Portrait slot on the left. The avatar sheet cell (130×150) is scaled down to fit.
        val portraitW = 88
        val portraitH = 100
        val portraitX = boxX + 10
        val portraitY = boxY + 5
        val textX = portraitX + portraitW + 12

        val avatarTex = avatarTexture ?: run { loadAvatarTexture(); avatarTexture }
        val frame = avatarFrame(line.avatar)
        if (avatarTex != null && frame != null) {
            renderer.renderTexture(avatarTex, src = frame, dst = SDLFRect(portraitX.toFloat(), portraitY.toFloat(), portraitW.toFloat(), portraitH.toFloat()))
            renderer.drawColor = SDLColor(120, 120, 140)
            renderer.drawRect(SDLRect(portraitX, portraitY, portraitW, portraitH))
        } else {
            // Fallback: a neutral placeholder box with the sender's initial.
            renderer.drawColor = SDLColor(60, 60, 70)
            renderer.fillRect(SDLRect(portraitX, portraitY, portraitW, portraitH))
            renderer.drawColor = SDLColor(120, 120, 140)
            renderer.drawRect(SDLRect(portraitX, portraitY, portraitW, portraitH))
            text.drawCentered(line.sender.firstOrNull()?.toString() ?: "?", portraitX + portraitW / 2, portraitY + portraitH / 2 - 8, 28, SDLColor(230, 230, 230))
        }

        // Sender name in the line colour.
        val nameColor = SDLColor((line.color shr 16) and 0xFF, (line.color shr 8) and 0xFF, line.color and 0xFF)
        text.draw(line.sender, textX, boxY + 10, 18, nameColor)
        renderer.drawColor = SDLColor(80, 80, 95)
        renderer.drawLine(textX, boxY + 34, boxX + boxW - 14, boxY + 34)

        // Message, wrapped to the box width.
        val msgY = boxY + 44
        var y = msgY
        for (wrapped in wrapText(line.message, boxW - 28 - (portraitW + 12))) {
            if (y > boxY + boxH - 26) break
            text.draw(wrapped, textX, y, 16, SDLColor(230, 230, 235)); y += 20
        }

        // Continue hint.
        val hint = if (dia.hasMore()) "Click / Space to continue" else "Click / Space to start"
        text.draw(hint, textX, boxY + boxH - 24, 14, SDLColor(150, 150, 170))
    }

    /** Wraps [input] to fit [maxWidth] pixels, splitting on word boundaries. */
    private fun wrapText(input: String, maxWidth: Int): List<String> {
        val words = input.split(" ")
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (text.measure(candidate, 16) <= maxWidth || current.isEmpty()) {
                current.setLength(0); current.append(candidate)
            } else {
                lines.add(current.toString()); current.setLength(0); current.append(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
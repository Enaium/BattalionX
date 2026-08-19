package cn.enaium.battalion.game

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.TileManager
import cn.enaium.battalion.data.TileSwatch
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.controllers.HALController
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.triggers.AttackUnitTrigger
import cn.enaium.battalion.game.triggers.KillUnitTrigger
import cn.enaium.battalion.game.ui.Dialogue
import cn.enaium.battalion.game.ui.TextRenderer
import cn.enaium.battalion.game.ui.TextureManager
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFlipMode
import cn.enaium.sdl.SDLFloatPoint
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

    // Avatar name -> frame index in the 6×4 sheet. The indices are the original tilesheet's
    // `<animation name="..."><frame frame="N"/></animation>` entries (_assets/3316.bin),
    // frame = row * 6 + column in the 780×600 sheet, NOT a visual-inspection ordering.
    private val avatarFrames = mapOf(
        "durand" to 0,
        "mullen" to 1,
        "pearl" to 2,
        "tucker" to 3,
        "argent" to 4,
        "general" to 5,
        "evil_durand" to 6,
        "mayjor" to 7,
        "akadian_3" to 8,
        "evil_durand_dark" to 9,
        "hayne" to 10,
        "blank" to 11,
        "usul" to 12,
        "drag_2" to 13,
        "drag_3" to 14,
        "blank_asdasddd" to 15,
        "blank_asdzxc" to 16,
        "yurik" to 17,
    )

    // Avatar texture, loaded once.
    private var avatarTexture: SDLTexture? = null

    /**
     * Returns the source rectangle for the avatar with the given [name], or null if unknown.
     */
    private fun avatarFrame(name: String): SDLFRect? {
        val index = avatarFrames[name.lowercase()] ?: return null
        val col = index % AVATAR_COLS
        val row = index / AVATAR_COLS
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

    // ---------------------------------------------------------------------
    // Explosion effects
    //
    // The original plays a small explosion at the target when a unit attacks and a big
    // explosion when a unit is destroyed (tilesheets "Small Explosion" / "Explosion" in
    // _assets/3316.bin). Both are one-shot bitmap animations; we subscribe to the game's
    // attack/kill triggers and draw the sheet frame for the current age of the effect.
    // ---------------------------------------------------------------------

    private class ExplosionFx(val x: Int, val y: Int, val startMs: Long, val big: Boolean)

    private val explosions = mutableListOf<ExplosionFx>()

    /** The active attack/death explosion sprites, keyed as in _assets/3316.bin. */
    private val explosionSheet = "explosion"          // 16 frames, 75 ms, 56×72 each, 4×4 grid
    private val smallExplosionSheet = "small_explosion" // 8 frames, 100 ms, 56×56 each, 4×2 grid

    private fun addExplosion(x: Int, y: Int, big: Boolean) {
        explosions.add(ExplosionFx(x, y, game.elapsedMs, big))
    }

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

        // Attack impact (small puff on the target) and destruction (big blast) effects.
        TriggerManager.addListener(AttackUnitTrigger.NAME) { trigger ->
            val t = trigger as? AttackUnitTrigger ?: return@addListener
            if (!game.gameOver) addExplosion(t.targetX, t.targetY, big = false)
        }
        TriggerManager.addListener(KillUnitTrigger.NAME) { trigger ->
            val t = trigger as? KillUnitTrigger ?: return@addListener
            if (!game.gameOver) addExplosion(t.unitX, t.unitY, big = true)
        }
    }

    // ---------------------------------------------------------------------
    // Mouse hover move-path preview
    //
    // With a selected unit, the map shows the same path the unit will actually walk if the
    // player clicks the hovered tile: movement_start on the unit's tile, movement_straight
    // along straight segments, movement_turn at corners, movement_end at the destination
    // (each rotated to its travel direction), plus the small selectedArrow riding the cursor.
    // ---------------------------------------------------------------------

    /** The path from the selected unit to the hovered tile (empty when there is none). */
    private var hoverPath: List<Tile> = emptyList()

    /**
     * Recomputes [hoverPath] from the cursor position. The path is computed exactly like the
     * click handler (Astar.path + canMove), so the preview *is* the final movement path.
     */
    private fun updateHoverPath() {
        hoverPath = emptyList()
        val human = game.activeController as? HumanController ?: return
        val unit = human.selectedUnit ?: return
        if (!unit.isAvailable() || !unit.canMove() || unit.isMoving) return
        if (!unit.isDeployed()) return

        // Hovered tile from the cursor (a float offset the mouse is inside the map area).
        val mouse = SDL.mouseState
        if (mouse.x < MAP_X || mouse.y < MAP_Y) return
        val tx = ((mouse.x - MAP_X) / TileManager.TILE_WIDTH).toInt() + cameraX
        val ty = ((mouse.y - MAP_Y) / TileManager.TILE_HEIGHT).toInt() + cameraY
        val g = game
        if (tx < 0 || ty < 0 || tx >= g.map.width || ty >= g.map.height) return
        if (tx == unit.x && ty == unit.y) return

        // Enemy units the player can attack this turn get the red target markers instead of a
        // path (a click there attacks, it does not move there).
        val hoveredUnit = g.unitAt(tx, ty)
        if (hoveredUnit != null && g.unitsAttackableThisTurn(unit).contains(hoveredUnit)) return

        val start = g.tileAt(unit.x, unit.y) ?: return
        val end = g.tileAt(tx, ty) ?: return
        val path = Astar.path(start, end, unit) ?: return
        if (!g.canMove(unit, path)) return
        hoverPath = path.filterIsInstance<Tile>().toList()
    }

    /** Compass direction (0=N,1=E,2=S,3=W) of the step [from]→[to]. */
    private fun pathDirFrom(from: Tile, to: Tile): Int = when {
        to.y < from.y -> 0
        to.y > from.y -> 2
        to.x > from.x -> 1
        else -> 3
    }

    /** Rotation angle (degrees, base sprites point east) for a compass direction. */
    private fun pathAngle(dir: Int): Double = when (dir) {
        1 -> 0.0    // east
        2 -> 90.0   // south
        3 -> 180.0  // west
        else -> 270.0 // north
    }

    /** Draws a single 56×56 path marker sprite on tile (x, y), rotated to face [dir]. */
    private fun drawPathMarker(sprite: String, x: Int, y: Int, dir: Int) {
        val tex = textures.texture(sprite) ?: return
        renderer.renderTextureRotated(
            tex,
            dst = SDLFRect(screenX(x).toFloat(), screenY(y).toFloat(),
                TileManager.TILE_WIDTH.toFloat(), TileManager.TILE_HEIGHT.toFloat()),
            angle = pathAngle(dir),
            center = SDLFloatPoint(TileManager.TILE_WIDTH / 2f, TileManager.TILE_HEIGHT / 2f),
        )
    }

    /**
     * Draws the corner marker for a 90° change of direction.
     *
     * The base sprite is an elbow whose incoming arm approaches from the east and whose
     * outgoing arm leaves south (the arrowhead cut sits on the incoming arm's tip). A
     * clockwise corner (out = in + 1) is that shape rotated onto the incoming direction; a
     * counter-clockwise corner (out = in - 1) needs the horizontal mirror first, otherwise
     * the elbow's chirality comes out flipped (e.g. an up→left corner looks like down→left).
     */
    private fun drawPathCorner(x: Int, y: Int, inDir: Int, outDir: Int) {
        val tex = textures.texture("movement_turn") ?: return
        val clockwise = (outDir - inDir + 4) % 4 == 1
        val angle = pathAngle(inDir) + if (clockwise) 0.0 else 180.0
        renderer.renderTextureRotated(
            tex,
            dst = SDLFRect(screenX(x).toFloat(), screenY(y).toFloat(),
                TileManager.TILE_WIDTH.toFloat(), TileManager.TILE_HEIGHT.toFloat()),
            angle = angle,
            center = SDLFloatPoint(TileManager.TILE_WIDTH / 2f, TileManager.TILE_HEIGHT / 2f),
            flip = if (clockwise) SDLFlipMode.NONE else SDLFlipMode.HORIZONTAL,
        )
    }

    /** Draws the whole hover path chain plus the cursor arrow. */
    private fun drawHoverPath() {
        val g = game
        val unit = (g.activeController as? HumanController)?.selectedUnit ?: return
        if (hoverPath.size < 2) return

        // Start marker on the unit's tile, pointing along the first step.
        drawPathMarker("movement_start", unit.x, unit.y, pathDirFrom(hoverPath[0], hoverPath[1]))

        // Intermediate tiles: straight markers, or corner markers where the path turns.
        for (i in 1 until hoverPath.size) {
            val cur = hoverPath[i]
            if (i == hoverPath.size - 1) {
                // Destination marker points along the last step.
                drawPathMarker("movement_end", cur.x, cur.y, pathDirFrom(hoverPath[i - 1], cur))
            } else {
                val inDir = pathDirFrom(hoverPath[i - 1], cur)
                val outDir = pathDirFrom(cur, hoverPath[i + 1])
                if (inDir != outDir) {
                    drawPathCorner(cur.x, cur.y, inDir, outDir)
                } else {
                    drawPathMarker("movement_straight", cur.x, cur.y, inDir)
                }
            }
        }

        // The small arrow follows the cursor.
        val mouse = SDL.mouseState
        val arrow = textures.texture("selectedArrow") ?: return
        val size = textures.textureSize("selectedArrow") ?: (9 to 10)
        renderer.renderTexture(
            arrow,
            dst = SDLFRect(mouse.x - size.first / 2f, mouse.y - size.second / 2f,
                size.first.toFloat(), size.second.toFloat()),
        )
    }

            /** Advances and draws every active explosion effect (above units, below the selection frame). */
    private fun drawExplosions() {
        if (explosions.isEmpty()) return
        val cellW = 56
        val iter = explosions.iterator()
        while (iter.hasNext()) {
            val fx = iter.next()
            val frameCount = if (fx.big) 16 else 8
            val waitMs = if (fx.big) 75L else 100L
            val frame = ((game.elapsedMs - fx.startMs) / waitMs).toInt()
            if (frame >= frameCount) {
                iter.remove()
                continue
            }
            val sheet = if (fx.big) explosionSheet else smallExplosionSheet
            val tex = textures.texture(sheet) ?: continue
            // Deep frames (tall explosion) span 72 source px; the tilesheet declares the
            // logical cell as 96 tall, so the blast is drawn scaled to 56×96.
            val srcH = if (fx.big) 72 else 56
            val dstH = if (fx.big) 96 else 56
            val srcX = (frame % 4) * cellW
            val srcY = (frame / 4) * srcH
            val px = screenX(fx.x)
            val py = screenY(fx.y)
            renderer.renderTexture(
                tex,
                src = SDLFRect(srcX.toFloat(), srcY.toFloat(), cellW.toFloat(), srcH.toFloat()),
                dst = SDLFRect(px.toFloat(), (py + TileManager.TILE_HEIGHT - dstH).toFloat(),
                    cellW.toFloat(), dstH.toFloat()),
            )
        }
    }

    companion object {
        // The window is the mission map (560) plus the info panel. The panel is the `mainframe`
        // sprite scaled to the panel HEIGHT (560/600 → 0.933) with its 10px transparent left
        // edge cropped off, so the frame's metal starts flush at the map's right edge: no gap,
        // no overlap covering the last map column. 144 source px × 0.933 ≈ 134.
        const val WINDOW_W = 694
        const val WINDOW_H = 590
        const val TOP_BAR_H = 30
        const val SIDE_W = 134

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

    // ---------------------------------------------------------------------
    // Player-name intro animation
    //
    // After the mission brief (when the player gets control), the current player's name is
    // revealed with a slide-in / hold / slide-out animation on a dark panel, mirroring the
    // original game's turn-intro.
    // ---------------------------------------------------------------------

    private class PlayerIntro(val name: String, val startMs: Long)

    private var intro: PlayerIntro? = null

    /** Shows the player-name intro animation (replaces any running one). */
    fun showPlayerIntro(name: String) {
        intro = PlayerIntro(name, game.elapsedMs)
    }

    /**
     * Draws the running intro animation: the current player's avatar next to the original
     * game's `nameplate` sprite (380_nameplate_nameplate.png, 151×27) with the player's name
     * overlaid, sliding in from below, holding, then sliding out above.
     */
    private fun drawPlayerIntro() {
        val it = intro ?: return
        val t = game.elapsedMs - it.startMs
        val slideIn = 500L
        val hold = 1400L
        val slideOut = 600L
        if (t >= slideIn + hold + slideOut) {
            intro = null
            return
        }
        val plate = textures.texture("nameplate") ?: return
        // Whole intro group slides vertically: in from +70px, hold, out to -70px.
        val dy: Int = when {
            t < slideIn -> 70 - (70f * t / slideIn).toInt()
            t < slideIn + hold -> 0
            else -> -(70f * (t - slideIn - hold) / slideOut).toInt()
        }
        // Plate drawn ~2.15x the source (151×27 → 325×58); avatar 130×150 → 64×74.
        val plateW = 325
        val plateH = 58
        val avatarW = 64
        val avatarH = 74
        val gap = 14
        val totalW = avatarW + gap + plateW
        val left = WINDOW_W / 2 - totalW / 2
        val cy = WINDOW_H / 2 - plateH / 2 + dy

        // Avatar (current player's portrait frame).
        val team = game.activeController?.team
        val tex = avatarTexture ?: run { loadAvatarTexture(); avatarTexture }
        val frame = team?.let { avatarFrame(it.avatarString.lowercase()) } ?: avatarFrame("tucker")
        if (tex != null && frame != null) {
            renderer.renderTexture(
                tex,
                src = frame,
                dst = SDLFRect(left.toFloat(), (cy + (plateH - avatarH) / 2).toFloat(),
                    avatarW.toFloat(), avatarH.toFloat()),
            )
        }

        // The nameplate sprite with the player's name overlaid.
        renderer.renderTexture(
            plate,
            dst = SDLFRect((left + avatarW + gap).toFloat(), cy.toFloat(), plateW.toFloat(), plateH.toFloat()),
        )
        val name = (team?.teamName?.ifEmpty { team?.name } ?: it.name).uppercase()
        text.drawCentered(name, left + avatarW + gap + plateW / 2, cy + plateH / 2 - 14, 24,
            SDLColor(252, 234, 150))
    }

    /** Renders one frame. */
    fun render() {
        terrainAnimMs += 16
        renderer.drawColor = SDLColor(30, 30, 35)
        renderer.clear()

        updateHoverPath()
        drawTopBar()
        drawMap()
        drawSidePanel()

        // Selected-unit info bar across the whole bottom of the window (hidden when nothing
        // is selected). Drawn under the dialogue/game-over overlays.
        drawUnitInfo()

        val dia = dialogue
        if (dia != null && dia.isActive()) drawDialogue(dia)

        drawPlayerIntro()

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
        }

        // Move-path preview for the hovered tile (the path a click would walk).
        if (hoverPath.isNotEmpty()) drawHoverPath()

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

        // Attackable-target markers are drawn ABOVE the unit sprites so the target frame is
        // not hidden behind the vehicle's art.
        if (selected != null && selected.controller === game.activeController && selected.isAvailable()) {
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

        // Explosion effects float above the units.
        drawExplosions()

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
            // While walking, cycle the 4 sprite rows by the walk progress so the unit
            // visibly strides instead of gliding on a frozen frame; idle units loop.
            val row = when {
                acting -> 0
                unit.isMoving -> ((unit.moveProgressMs / (Unit.MOVE_STEP_MS / 2)) % 4).toInt()
                else -> ((terrainAnimMs / 300) % 4).toInt()
            }
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

        // Health bar — self-drawn: four tall (vertical) slot rectangles, each a mini progress bar
        // (not four discrete pips). All four full = green, three remaining = yellow, two or
        // fewer = orange. Damage depletes from the top of each slot downward, and slots
        // empty leftmost-first. A fixed black backdrop covers the full 4-slot area and never
        // changes with the health. Scaled ~0.8 of the original attempt.
        val health = unit.getHealth()
        val maxHealth = unit.baseUnitData.health
        val ratio = (health.toDouble() / maxHealth).coerceIn(0.0, 1.0)
        val slots = 4
        val slotW = (8 * 0.8).toInt()          // 6
        val slotH = (16 * 0.8).toInt()         // 12
        val gap = (2 * 0.8).toInt()            // 1
        val totalW = slots * slotW + (slots - 1) * gap
        val barX = px + (TileManager.TILE_WIDTH - totalW) / 2
        val barY = py + TileManager.TILE_HEIGHT - slotH - 2

        // An undamaged unit shows no health bar at all (neither the backdrop nor the fill).
        if (ratio >= 0.999) return

        // Fixed black backdrop sized for all four slots.
        renderer.drawColor = SDLColor(8, 8, 10)
        renderer.fillRect(SDLRect(barX, barY, totalW, slotH))

        // Remaining "full slots" decide the colour band.
        val remaining = ratio * slots
        val fullSlots = kotlin.math.floor(remaining).toInt()
        val fill = when {
            fullSlots >= 4 -> SDLColor(80, 200, 80)     // all four full: green
            fullSlots == 3 -> SDLColor(220, 200, 60)    // three left: yellow
            else -> SDLColor(240, 150, 40)              // two or fewer: orange
        }
        // Each slot is a vertical mini progress bar anchored to the BOTTOM of the slot, so the
        // filled part grows up from the slot's base and damage empties the slot from the TOP
        // downward. Slots empty leftmost-first: slot i (0 = left) holds
        // clamp(remaining - (slots - 1 - i), 0, 1) of its height.
        for (i in 0 until slots) {
            val fillFrac = (remaining - (slots - 1 - i)).coerceIn(0.0, 1.0)
            if (fillFrac > 0.01) {
                renderer.drawColor = fill
                renderer.fillRect(SDLRect(
                    barX + i * (slotW + gap),
                    barY + slotH - (slotH * fillFrac).toInt(),
                    slotW,
                    (slotH * fillFrac).toInt(),
                ))
            }
        }
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

    // ---------------------------------------------------------------------
    // Right-hand info panel
    //
    // Top to bottom (mirroring the original game's HUD): a mini-map, a row of three
    // texture buttons (undo move / next unit / options), the current player's portrait,
    // the selected unit's details, the player list (team colour background = vehicle
    // colour), and the end-turn button. All buttons carry their sprite material
    // (normal/pressed frame halves) and report their rects through [panelButtonAt].
    // ---------------------------------------------------------------------

    /** Which panel button is under the cursor / was pressed. */
    enum class PanelButton { UNDO_MOVE, NEXT_UNIT, OPTIONS, END_TURN }

    /** The panel button under screen point (sx, sy), or null. */
    fun panelButtonAt(sx: Int, sy: Int): PanelButton? =
        btnRects.firstOrNull { sx >= it.x && sx < it.x + it.w && sy >= it.y && sy < it.y + it.h }?.btn

    /** The button currently held down by the mouse (used to show the pressed frame). */
    var pressedButton: PanelButton? = null

    private data class BtnHit(val btn: PanelButton, val x: Int, val y: Int, val w: Int, val h: Int)

    /** Rectangles of the clickable buttons, rebuilt every frame in [drawSidePanel]. */
    private val btnRects = mutableListOf<BtnHit>()

    /** Panel layout constants (SIDE_W = 240). */
    private object Panel {
        const val PAD = 14
        const val MINIMAP_W = 200
        const val MINIMAP_H = 96
        const val MINIMAP_Y = 42
        const val BUTTONS_Y = 184
        const val BUTTON_W = 66
        const val BUTTON_H = 40
        const val MINIMAP_OFFSET_X = 10      // extra right shift of the minimap
        const val MINIMAP_SCALE = 1.12f      // minimap enlargement
        const val PORTRAIT_OFFSET_X = 6      // right shift of the portrait window
        const val PORTRAIT_OFFSET_Y = -12    // up shift of the portrait window
        const val END_TURN_Y = 504
    }

    /**
     * The mapped rectangle (screen coords) of the `mainframe` sprite drawn in the panel.
     * It is scaled to the panel HEIGHT (600 → 560) so the frame is never height-compressed.
     * Source x 0..9 is transparent padding; it is cropped (src x=10..154 → 144 px × 0.933
     * ≈ 134) so the frame's metal starts flush against the map's right edge.
     */
    private fun mainframeRect(): SDLRect {
        val mfScale = (WINDOW_H - SIDE_Y).toFloat() / 600f
        val mfW = ((154 - 10) * mfScale).toInt()
        return SDLRect(
            SIDE_X,
            SIDE_Y,
            mfW,
            WINDOW_H - SIDE_Y,
        )
    }

    /**
     * Draws the whole right-hand panel. The `mainframe` sprite (500_mainframe_mainframe.png,
     * 154×600) is the hollow frame; minimap and portrait are drawn *under* it so the baked-in
     * border/portrait frame overlays their edges (they read as embedded, not floating on top).
     * Buttons, player rows and END TURN sit on the frame's opaque areas, so they draw above it.
     */
    private fun drawSidePanel() {
        renderer.drawColor = SDLColor(26, 27, 30)
        renderer.fillRect(SDLRect(SIDE_X, SIDE_Y, SIDE_W, WINDOW_H - SIDE_Y))
        btnRects.clear()

        // Content inside the hollow windows, drawn below the frame.
        drawMiniMap()
        drawPortrait()

        // The frame itself overlays the content's edges. Source x 0..9 is transparent
        // padding; crop it so the metal starts at the map edge.
        textures.texture("mainframe")?.let { mf ->
            val mr = mainframeRect()
            renderer.renderTexture(
                mf,
                src = SDLFRect(10f, 0f, 144f, 600f),
                dst = SDLFRect(mr.x.toFloat(), mr.y.toFloat(), mr.width.toFloat(), mr.height.toFloat()),
            )
        }

        drawPanelButtons()
        drawPlayerList()
        drawEndTurnButton()
    }

    /**
     * The mapped rectangle (screen coords) of the mainframe's transparent portrait window
     * (source x 20..134, y 240..392) — the full inner opening of the baked-in portrait
     * frame. The avatar is drawn into this rect.
     */
    private fun portraitWindow(): SDLRect {
        val mr = mainframeRect()
        val mfScale = (WINDOW_H - SIDE_Y).toFloat() / 600f
        return SDLRect(
            (mr.x + (20 - 10) * mfScale).toInt() + Panel.PORTRAIT_OFFSET_X,
            (mr.y + 240 * mfScale).toInt() + Panel.PORTRAIT_OFFSET_Y,
            ((134 - 20) * mfScale).toInt(),
            ((392 - 240) * mfScale).toInt(),
        )
    }

    /** Self-drawn mini-map: terrain colour per tile, units as dots, viewport box. */
    private fun drawMiniMap() {
        val map = game.map
        // Enlarged by MINIMAP_SCALE and shifted right by MINIMAP_OFFSET_X.
        val cell = minOf(Panel.MINIMAP_W.toDouble() / map.width, Panel.MINIMAP_H.toDouble() / map.height) * Panel.MINIMAP_SCALE
        val mmW = (cell * map.width).toInt()
        val mmH = (cell * map.height).toInt()
        val x0 = SIDE_X + (SIDE_W - mmW) / 2 + Panel.MINIMAP_OFFSET_X
        val y0 = Panel.MINIMAP_Y + (Panel.MINIMAP_H - mmH) / 2

        // No solid backdrop: the minimap sits directly on the (hollow) mainframe background,
        // matching the portrait. Only a thin outline separates it.

        for (row in 0 until map.height) {
            for (col in 0 until map.width) {
                val tile = game.tileAt(col, row) ?: continue
                val dst = SDLFRect(
                    (x0 + (col * cell).toInt()).toFloat(),
                    (y0 + (row * cell).toInt()).toFloat(),
                    kotlin.math.ceil(cell).toFloat(), kotlin.math.ceil(cell).toFloat())
                // Draw the tile's actual terrain texture (not a flat colour) so the minimap
                // looks like the real map. Use the first sprite key of the tile's swatch.
                val keys = tile.spriteKeys
                val tex = if (keys.isNotEmpty()) textures.textureOrFallback(keys[0]) else null
                if (tex != null) {
                    renderer.renderTexture(tex, dst = dst)
                } else {
                    renderer.drawColor = terrainColor(tile.terrain.name)
                    renderer.fillRect(SDLRect(dst.x.toInt(), dst.y.toInt(), dst.width.toInt(), dst.height.toInt()))
                }
            }
        }
        // Units as team-coloured dots.
        for (unit in game.units) {
            if (!unit.isDeployed()) continue
            renderer.drawColor = unitColor(unit)
            renderer.fillRect(SDLRect(
                x0 + (unit.x * cell).toInt(), y0 + (unit.y * cell).toInt(),
                kotlin.math.ceil(cell).toInt(), kotlin.math.ceil(cell).toInt()))
        }
        // Viewport box + frame.
        renderer.drawColor = SDLColor(255, 255, 255)
        renderer.drawRect(SDLRect(
            x0 + (cameraX * cell).toInt(), y0 + (cameraY * cell).toInt(),
            (VIEW_COLS * cell).toInt(), (VIEW_ROWS * cell).toInt()))
        renderer.drawColor = SDLColor(120, 120, 140)
        renderer.drawRect(SDLRect(x0, y0, mmW, mmH))
    }

    /** The three small action buttons in a row (undo move / next unit / options). */
    private fun drawPanelButtons() {
        // Buttons render at their native sprite frame size (28/33×40) so the metal texture
        // is never distorted; the row is centered in the panel.
        val h = Panel.BUTTON_H
        val widths = listOf(28, 33, 28)
        val gap = 10
        val totalW = widths.sum() + gap * 2
        val y = Panel.BUTTONS_Y
        // Centred, then shifted right a little so the row reads against the frame's right edge.
        var x = SIDE_X + (SIDE_W - totalW) / 2 + 6
        val human = game.activeController as? HumanController

        drawPanelButton(PanelButton.UNDO_MOVE, "undo_move_button", widths[0], h,
            SDLRect(x, y, widths[0], h), disabled = human?.undoAction == null)
        x += widths[0] + gap
        drawPanelButton(PanelButton.NEXT_UNIT, "nextUnitButton", widths[1], h,
            SDLRect(x, y, widths[1], h), disabled = !hasSelectableUnit())
        x += widths[1] + gap
        drawPanelButton(PanelButton.OPTIONS, "optionsButton", widths[2], h,
            SDLRect(x, y, widths[2], h), disabled = false)
    }

    /** True when the active human player still has a selectable (available) unit. */
    private fun hasSelectableUnit(): Boolean {
        val human = game.activeController as? HumanController ?: return false
        return game.units.any { it.controller === human && it.isDeployed() && it.isAvailable() }
    }

/**
     * Draws one texture button with its 4-frame sprite strip (frame 0 = normal, 1 = hover,
     * 2 = pressed, 3 = disabled) and records its hit rect.
     */
    private fun drawPanelButton(
        btn: PanelButton,
        sprite: String,
        frameW: Int,
        frameH: Int,
        dst: SDLRect,
        disabled: Boolean,
    ) {
        btnRects.add(BtnHit(btn, dst.x, dst.y, dst.width, dst.height))
        val tex = textures.texture(sprite) ?: return
        val held = pressedButton == btn && (SDL.mouseState.buttons and 1) != 0
        val hover = (SDL.mouseState.buttons and 1) == 0 &&
            panelButtonAt(SDL.mouseState.x.toInt(), SDL.mouseState.y.toInt()) == btn
        val frame = when {
            disabled -> 3
            held -> 2
            hover -> 1
            else -> 0
        }
        renderer.renderTexture(
            tex,
            src = SDLFRect((frame * frameW).toFloat(), 0f, frameW.toFloat(), frameH.toFloat()),
            dst = SDLFRect(dst.x.toFloat(), dst.y.toFloat(), dst.width.toFloat(), dst.height.toFloat()),
        )
    }

    /** The current player's portrait inside the mainframe's baked-in portrait window. */
    private fun drawPortrait() {
        val team = game.activeController?.team ?: return
        val tex = avatarTexture ?: run { loadAvatarTexture(); avatarTexture }
        val frame = avatarFrame(team.avatarString.lowercase()) ?: avatarFrame("tucker")

        // The portrait window baked into mainframe (transparent); the 130×150 avatar is
        // scaled up to fill the window (slightly larger than a strict fit so it visibly
        // fills the frame) and centred on the window's centre.
        val win = portraitWindow()
        val scale = maxOf(win.width.toFloat() / 130f, win.height.toFloat() / 150f)
        val fitW = (130 * scale).toInt()
        val fitH = (150 * scale).toInt()
        val fitX = win.x + (win.width - fitW) / 2
        val fitY = win.y + (win.height - fitH) / 2
        if (tex != null && frame != null) {
            renderer.renderTexture(
                tex,
                src = frame,
                dst = SDLFRect(fitX.toFloat(), fitY.toFloat(), fitW.toFloat(), fitH.toFloat()),
            )
        } else {
            renderer.drawColor = SDLColor(60, 60, 70)
            renderer.fillRect(SDLRect(fitX, fitY, fitW, fitH))
        }

        // Turn status under the portrait (over the panel below the window).
        val name = team.teamName.ifEmpty { team.name }
        text.drawCentered(name, win.x + win.width / 2, win.y + win.height + 4, 14,
            teamColor(game.activeController?.team))
        val status = if (game.activeController is HALController) "AI thinking..." else "Your turn"
        text.drawCentered(status, win.x + win.width / 2, win.y + win.height + 24, 12,
            SDLColor(180, 180, 195))
    }

    /**
 * Selected unit details as a full-width bar across the bottom of the window, with a solid
 * background. Hidden entirely when nothing is selected. A transient build message shows in
 * the same bar.
 */
    private fun drawUnitInfo() {
        val selected = (game.activeController as? HumanController)?.selectedUnit
        val line = if (selected != null) {
            val moved = if (selected.hasMoved()) "moved" else "ready"
            val fired = if (selected.hasFired()) "fired" else "ready"
            "${selected.unitData.name}   HP ${selected.getHealth()}/${selected.baseUnitData.health}   " +
                "DMG ${selected.unitData.damage}   RNG ${selected.unitData.weaponRangeMin}-${selected.unitData.weaponRangeMax}   " +
                "MOV ${selected.unitData.range}   [$moved/$fired]"
        } else if (buildMessage.isNotEmpty()) {
            buildMessage
        } else {
            return
        }

        val barH = 24
        val barY = WINDOW_H - barH
        renderer.drawColor = SDLColor(22, 22, 26)
        renderer.fillRect(SDLRect(0, barY, WINDOW_W, barH))
        renderer.drawColor = SDLColor(90, 90, 100)
        renderer.drawLine(0, barY, WINDOW_W, barY)
        text.draw(line, 8, barY + 5, 13, SDLColor(235, 235, 235))
    }

    /**
     * Player list: the four slots baked into the bottom of the `mainframe` background
     * (source y ≈ 390..590, four equal bands), each HALF the band height so the END TURN
     * button (native 71px) fits below. Slots are right-aligned with the END TURN button
     * (their right edges line up) and separated by a ~4px vertical gap. Each active
     * player's slot is filled with their faction colour (semi-transparent so the metal
     * shows through), with the name on top; the current player gets a bright outline.
     * No nameplate strip, no flag, no rank.
     */
    private fun drawPlayerList() {
        val players = game.controllers.filterNotNull().filter { it.team.active }
        val mr = mainframeRect()
        val mfScale = (WINDOW_H - SIDE_Y).toFloat() / 600f
        val slotsTop = (mr.y + 390 * mfScale).toInt()
        // Per-item height 24px with a 4px inter-slot gap → 28px stride.
        val gap = 4
        val fillH = 24
        val slotH = fillH + gap
        // The slot runs to the screen's right edge (WINDOW_W), inset 16px on the left.
        val slotX = mr.x + mfScale.toInt() + 8
        val slotRight = WINDOW_W
        val slotW = slotRight - slotX
        players.forEachIndexed { i, c ->
            val y = slotsTop + i * slotH
            val faction = rawTeamColor(c.team)
            val isCurrent = c === game.activeController
            // Faction-coloured slot background (metal shows through).
            renderer.drawColor = SDLColor(faction.r, faction.g, faction.b, if (isCurrent) 150 else 110)
            renderer.fillRect(SDLRect(slotX, y + gap / 2, slotW, fillH))
            if (isCurrent) {
                renderer.drawColor = SDLColor(255, 255, 255)
                renderer.drawRect(SDLRect(slotX, y + gap / 2, slotW, fillH))
            }
            val luma = 0.299 * faction.r + 0.587 * faction.g + 0.114 * faction.b
            val fg = if (luma > 150) SDLColor(25, 25, 25) else SDLColor(245, 245, 245)
            text.draw(c.team.name, slotX + 4, y + (slotH - 13) / 2, 13, fg)
        }
    }

    /** The END TURN button at the bottom of the panel (4-frame sprite strip, 126×71 frames). */
    private fun drawEndTurnButton() {
        val w = 126
        val h = 71
        val x = SIDE_X + (SIDE_W - w) / 2 + 4
        val y = Panel.END_TURN_Y
        btnRects.add(BtnHit(PanelButton.END_TURN, x, y, w, h))
        val tex = textures.texture("endturnbutton") ?: return
        val held = pressedButton == PanelButton.END_TURN && (SDL.mouseState.buttons and 1) != 0
        val hover = (SDL.mouseState.buttons and 1) == 0 &&
            panelButtonAt(SDL.mouseState.x.toInt(), SDL.mouseState.y.toInt()) == PanelButton.END_TURN
        val frame = when {
            game.gameOver -> 3
            held -> 2
            hover -> 1
            else -> 0
        }
        renderer.renderTexture(
            tex,
            src = SDLFRect((frame * 126).toFloat(), 0f, 126f, 71f),
            dst = SDLFRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()),
        )
    }

    /** Draws the mission briefing dialogue box over the map. */
    private fun drawDialogue(dia: Dialogue) {
        val line = dia.current() ?: return
        val boxX = 20
        val boxY = WINDOW_H - 130
        val boxW = WINDOW_W - 40
        val boxH = 110

        // No full-screen black veil: the map behind the dialogue stays fully visible.

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
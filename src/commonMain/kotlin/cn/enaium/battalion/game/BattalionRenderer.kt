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
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

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

    /** True while the current dialogue line is still being typed out. */
    fun isDialogueTyping(): Boolean = dialogue?.isActive() == true && !diaTypeDone

    /**
     * The tile whose info the bottom recon strip shows. Set when the player clicks a tile (the
     * original ReconPanel updates on mouse-down, not on hover); null shows the blank strip.
     */
    private var focusTile: Tile? = null

    /** Updates the recon strip's tile from a click. Pass null to blank the strip. */
    fun setFocusTile(tile: Tile?) {
        focusTile = tile
    }

    /** Instantly finishes the typewriter reveal of the current line. */
    fun skipDialogueTyping() {
        diaTypeDone = true
    }

    // ---------------------------------------------------------------------
    // Mission dialogue (mirrors the original ChatWindow)
    //
    // The original is a wood-textured BattalionPanel (alpha 0.65) with a "frame1" bevel border,
    // PANEL_HEIGHT=154, added at the bottom of the game area and tweened up over 500ms on show
    // (Sine.easeOut) and back down on dismiss (Sine.easeIn). Consecutive chat lines reuse the
    // same window (no re-slide); it only slides out when the run ends. The layout is: avatar
    // at (2,2) at natural 130×150, a colorBarLong bar tinted to the speaker's colour, then the
    // message which is *typed out* character-by-character (ChatWindow.enterFrameHandler) in
    // white BERNHC size 17, with a graphical continueButton at bottom-right. We key the slide
    // on the active/inactive edges of [dialogue].
    private var diaWasActive = false
    private var diaAnimStartMs = 0L
    private var diaSlidingIn = false
    private var diaSlidingOut = false

    /** How long the original ChatWindow tween takes to slide the panel in/out (ms). */
    private val DIALOGUE_SLIDE_MS = 500L

    // Typewriter: the current line is revealed character-by-character. A click while it is
    // still typing fast-forwards it (full reveal) instead of advancing to the next line.
    private val DIALOGUE_TYPE_CHARS_PER_SEC = 45
    private var diaTypeLine: Dialogue.Line? = null
    private var diaTypeStartMs = 0L
    private var diaTypeDone = true

    /** The dialogue panel geometry (matches ChatWindow constants). */
    private val DIALOGUE_PANEL_H = 154
    private val DIALOGUE_PANEL_BOTTOM = MAP_Y + GameManager.GAME_HEIGHT // 30 + 560 = 590

    /** BattalionPanel alpha (0.65): the wood backing is drawn translucent so the map shows through. */
    private val DIALOGUE_PANEL_ALPHA = 0.65f

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
        val segCount = path.size - 1
        val stepMs = Unit.MOVE_STEP_MS
        val totalMs = segCount * stepMs
        val progress = unit.moveProgressMs.coerceIn(0, totalMs.coerceAtLeast(stepMs))

        // Ease the WHOLE trip through one continuous curve, then locate the unit along the
        // path by the eased distance travelled. Easing per-tile would restart (or sharply
        // change) velocity at every tile boundary, which reads as a series of distinct one-tile
        // hops; a single eased progress makes the motion one seamless glide that only
        // accelerates at the start and coasts to a stop at the end.
        val easedF = easeInOutQuad(progress.toDouble() / totalMs)
        val easedSteps = easedF * segCount

        var idx = easedSteps.toInt()
        if (idx >= segCount) idx = segCount - 1
        val frac = easedSteps - idx
        val from = path[idx]
        val to = path[idx + 1]
        // Face the unit along the current segment so it visually turns at corners.
        unit.animFacing = facingFor(to.x - from.x, to.y - from.y)
        return UnitWalk(
            from.x + (to.x - from.x) * frac,
            from.y + (to.y - from.y) * frac,
        )
    }

    /** Quad.easeInOut — accelerate from rest, constant middle, decelerate to rest (C1-smooth, so
     * velocity varies continuously with no jump at the path's tile boundaries). */
    private fun easeInOutQuad(x: Double): Double =
        if (x < 0.5) 2.0 * x * x else 1.0 - 2.0 * (1.0 - x) * (1.0 - x)

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
     * Firing-sheet overlays queued while drawing units. They are rendered AFTER every unit and
     * the attackable-target frames, so a firing sprite is never covered by the target vehicle's
     * art (matching the original UnitAttackAnimation, which is added to the scroller's TOP).
     */
    private class FiringOverlay(val unit: Unit, val col: Int, val row: Int)
    private val firingOverlays = mutableListOf<FiringOverlay>()

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

    /**
     * Draws every queued firing-sheet overlay at its natural 144×144 size, centred on the firing
     * unit's tile (mirroring the original UnitAttackAnimation overlay). Called after all units and
     * attack-target frames are drawn so the firing sprite is never covered by the target's art.
     */
    private fun drawFiringOverlays() {
        for (ov in firingOverlays) {
            val unit = ov.unit
            val firingKey = firingSpriteKeyFor(unit) ?: continue
            val rawTeam = rawTeamColor(unit.controller?.team)
            val tex = textures.textureForTeam(firingKey, rawTeam) ?: continue
            val px = screenX(unit.x) + TileManager.TILE_WIDTH / 2
            val py = screenY(unit.y) + TileManager.TILE_HEIGHT / 2
            renderer.renderTexture(
                tex,
                src = SDLFRect((ov.col * 144).toFloat(), (ov.row * 144).toFloat(), 144f, 144f),
                dst = SDLFRect((px - 72).toFloat(), (py - 72).toFloat(), 144f, 144f),
            )
        }
    }

    /**
     * Draws the mode marker beside the cursor while a unit is selected and can act: the
     * `cursor` sprite (467_cursor_cursor.png, 144×48 = two 48×48 cells) shows the *move* cell
     * when the hovered tile is a valid move destination, or the *attack* cell when the hovered
     * tile holds an enemy the unit can attack this turn.
     */
    private fun drawCursorMarker() {
        val g = game
        val human = g.activeController as? HumanController ?: return
        val unit = human.selectedUnit ?: return
        if (!unit.isAvailable() || unit.isMoving || !unit.isDeployed()) return

        // Hovered tile under the cursor.
        val mouse = SDL.mouseState
        if (mouse.x < MAP_X || mouse.y < MAP_Y) return
        val tx = ((mouse.x - MAP_X) / TileManager.TILE_WIDTH).toInt() + cameraX
        val ty = ((mouse.y - MAP_Y) / TileManager.TILE_HEIGHT).toInt() + cameraY
        if (tx < 0 || ty < 0 || tx >= g.map.width || ty >= g.map.height) return

        // Which cell to show: attack takes priority over move.
        val hoveredUnit = g.unitAt(tx, ty)
        val cellX = if (hoveredUnit != null && g.unitsAttackableThisTurn(unit).contains(hoveredUnit)) {
            1 // attack (right half)
        } else {
            val start = g.tileAt(unit.x, unit.y)
            val end = g.tileAt(tx, ty)
            if (start == null || end == null || start === end) return
            val path = Astar.path(start, end, unit)
            if (path == null || !g.canMove(unit, path)) return
            0 // move (left half)
        }

        val tex = textures.texture("cursor") ?: return
        // The cursor sprite (467_cursor_cursor.png, 144×48) holds two 48×48 cells side by side:
        // cell 0 = move, cell 1 = attack (the rightmost 48px of the sheet is empty padding).
        val cellW = 48f
        val cellH = 48f
        // Marker sits at the cursor's bottom-right.
        renderer.renderTexture(
            tex,
            src = SDLFRect(cellX * cellW, 0f, cellW, cellH),
            dst = SDLFRect(mouse.x.toFloat(), mouse.y.toFloat(), cellW, cellH),
        )
    }

    /**
     * Blinking "AI thinking" badge (175_ai_thinking_ai_thinking.png, 60×43) at the top-left
     * of the map while the current turn belongs to an AI controller.
     */
    private fun drawAIThinking() {
        if (game.activeController !is HALController) return
        // Blink slowly (~0.6Hz: on for 800ms, off for 800ms).
        val phase = (game.elapsedMs / 800L) % 2L
        if (phase == 0L) return
        val tex = textures.texture("ai_thinking") ?: return
        val w = 60
        val h = 43
        renderer.renderTexture(
            tex,
            dst = SDLFRect((MAP_X + 4).toFloat(), (MAP_Y + 4).toFloat(), w.toFloat(), h.toFloat()),
        )
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
        // Map (0..560) + the recon info strip (42px) below it; the top bar was removed so the
        // map starts at the window's top edge (no leftover dark band).
        const val WINDOW_H = 602
        const val TOP_BAR_H = 0
        const val SIDE_W = 134

        /** Max muzzle-recoil displacement (px) of a unit's sprite while it is firing. */
        const val FIRE_RECOIL_PX = 6f

        /** Window (ms) over which the firing recoil spring-back completes (~1/3 of the fire anim). */
        const val FIRE_RECOIL_MS = 220f

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
     * The `_firing` sprite key for [unit], e.g. "corvette_firing" for "corvette". Firing
     * tilesheets exist for most armed units (Annihilator, Corvette, Flak, Scorpion, Turret,
     * Warmachine, ...). Returns null for units with no firing sheet.
     */
    private fun firingSpriteKeyFor(unit: Unit): String? {
        val base = spriteKeyForUnit(unit)
        val firing = "${base}_firing"
        // Only use the firing sheet if the texture actually exists in the asset index.
        return if (textures.pathFor(firing) != null || textures.textureSize(firing) != null) firing else null
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

    /** How long the intro's two lines take to sweep apart to full separation (ms). */
    private val INTRO_GROW_MS = 200L

    private var intro: PlayerIntro? = null

    /** Shows the player-name intro animation (replaces any running one). */
    fun showPlayerIntro(name: String) {
        intro = PlayerIntro(name, game.elapsedMs)
    }

    /**
     * Draws the running turn-intro animation, recreating the original `StartTurnAnimation`:
     *
     * - A horizontal bar centred on the game area: the two horizontal lines (each 6px thick,
     *   player-coloured) sweep apart over the first [INTRO_GROW_MS], hold, then collapse back
     *   together over another [INTRO_GROW_MS] (symmetric speed). The interior is fully
     *   transparent so the map stays visible. The right end stops at the info-panel edge; the
     *   panel is drawn on top so it occludes the bar's right edge.
     * - The player's name (size 44, white) below, sliding in from the right edge to the left,
     *   holding, then sliding out to the left over the 6s lifetime, exactly like the original's
     *   `startingX=3700 → 30 → -700` tweens.
     * - "Round N" (size 20) beneath the name, tracking the name's slide with a slight delay.
     *
     * The original also pins the turn avatar to the top-right, but that icon is drawn by
     * [StartTurnAction] separately (and duplicated by the panel portrait), so it is omitted here.
     * Total animation time is 6000ms (matches the original Timer).
     */
    private fun drawPlayerIntro() {
        val it = intro ?: return
        val t = game.elapsedMs - it.startMs
        val total = 6000L
        if (t >= total) {
            intro = null
            return
        }
        val team = game.activeController?.team

        // ---- Bar: player-coloured thick lines (transparent interior), growing/collapsing. ----
        // Original: drawRect(-20, -85/2, 700, 85), centred at y=285 of the 560-tall game area;
        // scaleY interpolates 0.1 → 1 (0-GROW_MS), holds, 1 → 0 (3-3.5s).
        val barCenterY = MAP_Y + GameManager.GAME_HEIGHT / 2
        val barHalfH = 43 // 85/2
        val lineThickness = 6 // px — the two horizontal "lines" are this thick
        val scaleY = when {
            // The two lines grow apart and (at t=3000) collapse back together, both over
            // INTRO_GROW_MS so the separation and merge happen at the same speed.
            // (.toFloat() is required: Long/Long would integer-divide and snap the collapse
            // straight from full height to 0, skipping the exit animation.)
            t < INTRO_GROW_MS -> 0.1f + 0.9f * t / INTRO_GROW_MS
            t < 3000L -> 1f
            else -> 1f - (t - 3000L).toFloat() / INTRO_GROW_MS
        }.coerceIn(0f, 1f)
        if (scaleY > 0f) {
            val outline = rawTeamColor(team)
            renderer.drawColor = SDLColor(outline.r, outline.g, outline.b)
            val y0 = (barCenterY - barHalfH * scaleY).toInt()
            val h = (barHalfH * 2 * scaleY).toInt()
            // The interior is fully transparent: the two horizontal lines (top/bottom edges)
            // sweep apart from the screen middle over an unobscured map. The right end is
            // clamped to the info-panel edge (SIDE_X); the panel is drawn after this intro so
            // it occludes the bar's right edge and nothing pokes over the panel.
            val xL = MAP_X - 20
            val xR = SIDE_X
            // top line
            renderer.fillRect(SDLRect(xL, y0, xR - xL, lineThickness))
            // bottom line
            renderer.fillRect(SDLRect(xL, y0 + h - lineThickness, xR - xL, lineThickness))
            // thin vertical connector at the left (mostly off-window)
            renderer.fillRect(SDLRect(xL, y0, lineThickness, h))
        }

        // ---- Name ("Round N" beneath) sliding across the bar. ----
        // Original tweens x from 3700 → 30 (1s), holds, then 30 → -700 (2s), all relative to a
        // 560-wide game area. We reproduce start/rest within the window.
        val name = (team?.teamName?.ifEmpty { team?.name } ?: it.name).uppercase()
        val nameSize = 44
        val roundSize = 20
        val nameX = introTextX(t, slideInStart = 0L, slideOutStart = 2000L)
        val roundX = introTextX(t, slideInStart = 500L, slideOutStart = 2500L)
        val nameY = barCenterY - barHalfH + 4
        val roundY = nameY + text.measure("Mg", nameSize) // below the name

        // DropShadowFilter(2) on both text fields — draw a soft offset shadow underneath.
        text.draw(name, nameX + 2, nameY + 2, nameSize, SDLColor(0, 0, 0, 200))
        text.draw(name, nameX, nameY, nameSize, SDLColor(255, 255, 255))
        text.draw("Round ${game.round + 1}", roundX + 2, roundY + 2, roundSize, SDLColor(0, 0, 0, 200))
        text.draw("Round ${game.round + 1}", roundX, roundY, roundSize, SDLColor(255, 255, 255))
    }

    /**
     * The x-position of the intro text field at time [t] (ms since the intro started),
     * reproducing the original's three tweens: far off-screen right → parked (30px) → far
     * off-screen left. [slideInStart] is when the in-tween begins and [slideOutStart] when the
     * out-tween begins; the in-tween takes 1000ms, the out-tween 2000ms.
     */
    private fun introTextX(t: Long, slideInStart: Long, slideOutStart: Long): Int {
        val parked = MAP_X + 30
        // Before the in-tween begins it sits parked far off the right edge.
        if (t < slideInStart) return MAP_X + 3700
        val inT = t - slideInStart
        if (inT < 1000L) {
            // 3700 → 30 over 1s (linear approximation of the original 1s tween).
            val f = inT / 1000f
            return (MAP_X + 3700 - (3700 - 30) * f).toInt()
        }
        // Between the in-tween and the out-tween it holds at the parked x.
        if (t < slideOutStart) return parked
        val outT = t - slideOutStart
        if (outT < 2000L) {
            val f = outT / 2000f
            return (parked - (700 + 30) * f).toInt()
        }
        // After the out-tween it sits parked far off the left edge.
        return parked - (700 + 30)
    }

    /** Renders one frame. */
    fun render() {
        terrainAnimMs += 16
        firingOverlays.clear()
        renderer.drawColor = SDLColor(30, 30, 35)
        renderer.clear()

        updateHoverPath()
        updateDialogueAnimation(dialogue)
        drawMap()

        // AI-turn indicator: blinking "AI thinking" badge at the top-left of the map.
        drawAIThinking()

        // Turn-intro draws before the side panel: the intro bar's right edge is clamped to the
        // panel edge (SIDE_X) and the opaque panel is drawn on top, occluding the bar so no
        // part of it (vertical connector or line ends) pokes over the info panel.
        drawPlayerIntro()

        drawSidePanel()

        // Selected-unit info bar across the whole bottom of the window (hidden when nothing
        // is selected). Drawn under the dialogue/game-over overlays.
        drawUnitInfo()

        val dia = dialogue
        // Dialog draws while active AND during its slide-in/out transition.
        if (dia != null && (dia.isActive() || diaSlidingIn || diaSlidingOut)) drawDialogue(dia)

        if (game.gameOver) drawGameOverOverlay()

        renderer.present()
    }

    /**
     * Advances the dialogue slide-in/out transitions (mirroring the original ChatWindow) and
     * the typewriter reveal of the current line. Called once per frame before drawing.
     */
    private fun updateDialogueAnimation(dia: Dialogue?) {
        val now = game.elapsedMs
        val active = dia?.isActive() == true

        // Typewriter: track the current line; when the active line changes (or the window
        // (re)appears) restart the reveal.
        val line = dia?.current()
        if (line !== diaTypeLine) {
            if (line != null) {
                diaTypeLine = line
                diaTypeStartMs = now
                diaTypeDone = false
            } else {
                diaTypeLine = null
                diaTypeDone = true
            }
        } else if (!diaTypeDone && line != null) {
            val revealMs = (line.message.length * 1000L) / DIALOGUE_TYPE_CHARS_PER_SEC
            if (now - diaTypeStartMs >= revealMs) diaTypeDone = true
        }

        // Slide in/out on the active/inactive edges, exactly like a ChatWindow tween.
        if (active && !diaWasActive) {
            diaWasActive = true
            diaSlidingIn = true
            diaSlidingOut = false
            diaAnimStartMs = now
        } else if (!active && diaWasActive) {
            diaWasActive = false
            diaSlidingIn = false
            // If the panel is already fully drawn (stable) we slide it out; the draw guard
            // (isActive || diaSliding*) keeps it visible during this window.
            diaSlidingOut = true
            diaAnimStartMs = now
        }

        if (diaSlidingIn) {
            if (now - diaAnimStartMs >= DIALOGUE_SLIDE_MS) diaSlidingIn = false
        } else if (diaSlidingOut) {
            if (now - diaAnimStartMs >= DIALOGUE_SLIDE_MS) diaSlidingOut = false
        }
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

        // Firing-sheet overlays: drawn after every unit and the attackable-target frames so the
        // firing sprite sits above the target vehicle's art (like the original TOP layer).
        drawFiringOverlays()

        // Cursor mode marker (move / attack) beside the cursor — drawn above units and the
        // attackable-target frames so it is never hidden behind the sprites.
        drawCursorMarker()

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
        // The walk yields FRACTIONAL tile coords; map them to pixel positions directly (not through
        // the integer-tile screenX/screenY) so the unit glides between tiles instead of snapping
        // tile-to-tile.
        val walk = unitRenderTileH(unit)
        val px = (MAP_X + (walk.visX - cameraX) * TileManager.TILE_WIDTH).toInt()
        val py = (MAP_Y + (walk.visY - cameraY) * TileManager.TILE_HEIGHT).toInt()
        val color = unitColor(unit)

        val cx = px + TileManager.TILE_WIDTH / 2
        val cy = py + TileManager.TILE_HEIGHT / 2

        // Draw the unit sprite (scaled to fit the tile). The unit's material is repainted into
        // its team colour via CPU palettization ([TextureManager.textureForTeam]) rather than a
        // multiplicative tint, so the red source art actually becomes the team colour.
        val rawTeam = rawTeamColor(unit.controller?.team)
        val isFiring = unit.anim == Unit.UnitAnim.FIRE
        // While firing, prefer the unit's dedicated `_firing` tilesheet (e.g. corvette_firing)
        // which has its own muzzle-flash animation; fall back to the base sheet when absent.
        val sprite = if (isFiring) firingSpriteKeyFor(unit) ?: spriteKeyForUnit(unit)
                     else spriteKeyForUnit(unit)
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

            // Expire a finished one-shot action animation back to the idle state. The game clock
            // ([GameManager.elapsedMs]) is the time base the action used to stamp [Unit.animStartMs].
            if (unit.anim != Unit.UnitAnim.NONE &&
                game.elapsedMs - unit.animStartMs > Unit.ANIM_DURATION_MS
            ) {
                unit.anim = Unit.UnitAnim.NONE
            }
            // Choose the source row. The walk takes priority while the unit is moving: it cycles
            // the 4 stride rows by the walk progress so the unit visibly strides tile to tile
            // (previously a trailing MOVE animation froze it on frame 0, gliding instead of
            // walking). A firing unit advances through its `_firing` sheet's animation frames
            // (~80ms each) so the muzzle flash plays out over the shot. Idle units loop through
            // the rows on a ~1.2s cycle.
            val fireFrame = run {
                val elapsed = (game.elapsedMs - unit.animStartMs).coerceIn(0L, Unit.ANIM_DURATION_MS)
                (elapsed / 80L).toInt()
            }
            val row = when {
                unit.isMoving -> ((unit.moveProgressMs / (Unit.MOVE_STEP_MS / 2)) % 4).toInt()
                unit.anim == Unit.UnitAnim.FIRE -> fireFrame
                else -> ((terrainAnimMs / 300) % 4).toInt()
            }
            // The facing column used for the animation (motion may precede the settled idle).
            val col = facingColumn(
                if (unit.anim != Unit.UnitAnim.NONE || unit.isMoving) unit.animFacing else unit.facing
            )

            // The original draws each unit's 56-wide cell at its natural size, with the
            // cell's bottom edge aligned to the bottom of the tile the unit stands on
            // (see Unit.setX/setY and GameUnit: sprite.y = -(sprite.height - TILE_HEIGHT)).
            // Tall units therefore overflow above the tile, which matches the source game.
            //
            // The `_firing` tilesheets (e.g. corvette_firing) are 576-wide = 4 columns × 144px
            // cells, where column = facing direction (right/down/left/up = 0/1/2/3, i.e. the same
            // as [facingColumn]) and row = animation frame. Each unit displays the cell via
            // [firingDesc] so the body matches the idle sprite's size and position.
            val isFiringSheet = isFiring && texW == 576
            val fireCell = if (isFiringSheet) 144 else cellW

            val src = when {
                texW == 336 -> SDLFRect(
                    (col * cellW).toFloat(),
                    (row * cellH).toFloat(), cellW.toFloat(), cellH.toFloat())
                isFiringSheet -> SDLFRect(
                    (col * fireCell).toFloat(),
                    (row * fireCell).toFloat(), fireCell.toFloat(), fireCell.toFloat())
                // Single 56x56 sprite (or an unknown sheet): draw it unchanged.
                else -> SDLFRect(0f, 0f, minOf(texW, cellW).toFloat(),
                    minOf(texH, cellH).toFloat())
            }
            val frameW = src.width.toInt()
            val frameH = src.height.toInt()
            // A firing sheet's 144px cell is drawn at its NATURAL 144×144 size, centred on the
            // unit's tile — exactly like the original UnitAttackAnimation overlay
            // (anim.x = unitX + (56-144)/2, anim.y = unitY + 28 - 72 + (144-h)); the base unit
            // sprite is hidden during the shot and the firing sprite plays over the map. Other
            // sprites draw at natural pixel size, centred on the tile and anchored to its bottom.
            val w = if (isFiringSheet) 144 else minOf(frameW, cellW)
            val h = if (isFiringSheet) 144 else minOf(frameH, cellH)
            // Recoil: while a *mobile* unit is firing, kick the sprite backward (opposite its
            // aim) and let it spring back quickly — a sharp punch peaking at muzzle ignition and
            // settling within the first ~200ms of the (longer) fire animation.
            //
            // Immobile units (Turret, range 0) cannot walk and fire in place; the original gives
            // them no muzzle-recoil kick (their <Unit> Firing tilesheet animates in place), so we
            // skip the recoil offset entirely for them. The firing-sheet overlay also gets no
            // recoil (the original UnitAttackAnimation is drawn un-shifted).
            val recoil = if (unit.anim == Unit.UnitAnim.FIRE && unit.canMove() && !isFiringSheet) {
                val elapsed = (game.elapsedMs - unit.animStartMs).coerceIn(0, Unit.ANIM_DURATION_MS)
                val t = (elapsed.toFloat() / FIRE_RECOIL_MS).coerceIn(0f, 1f)
                (1f - t) * FIRE_RECOIL_PX
            } else 0f
            // Backward per facing (facing is the aim direction): N=up, E=right, S=down, W=left,
            // so the recoil shoves the sprite the opposite way on the axis it aims along.
            val recX = when (if (unit.anim == Unit.UnitAnim.FIRE) unit.animFacing else unit.facing) {
                0 -> 0f                     // aiming north → kick south (down)
                1 -> -recoil                // aiming east → kick west (left)
                2 -> 0f                     // aiming south → kick north (up)
                else -> recoil              // aiming west → kick east (right)
            }
            val recY = when (if (unit.anim == Unit.UnitAnim.FIRE) unit.animFacing else unit.facing) {
                0 -> recoil                 // aiming north → kick south (down)
                1 -> 0f
                2 -> -recoil                // aiming south → kick north (up)
                else -> 0f
            }
            // The firing overlay is centred on the tile (144×144); other sprites are centred on
            // the tile horizontally and anchored to its bottom.
            val dstX = if (isFiringSheet) (cx - 72 + recX).toFloat()
                       else (cx - w / 2 + recX).toFloat()
            val dstY = if (isFiringSheet) (cy - 72 + recY).toFloat()
                       else (py + TileManager.TILE_HEIGHT - h + recY).toFloat()
            if (isFiringSheet) {
                // Don't draw here: queue the firing sprite so it renders on TOP of every unit
                // (and the target's art) in the post-unit pass.
                firingOverlays.add(FiringOverlay(unit, col, row))
            } else {
                val dim = unit.hasMoved() || unit.hasFired()
                // The texture is already team-coloured; only dim spent units (no recolour).
                tex.colorMod = if (dim) SDLColor(150, 150, 150) else SDLColor(255, 255, 255)
                renderer.renderTexture(tex, src = src, dst = SDLFRect(dstX, dstY, w.toFloat(), h.toFloat()))
                tex.colorMod = SDLColor(255, 255, 255)
            }
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
        const val BUTTONS_Y = 184
        const val BUTTON_W = 66
        const val BUTTON_H = 40
        const val PORTRAIT_OFFSET_X = 6      // right shift of the portrait window
        const val PORTRAIT_OFFSET_Y = -12    // up shift of the portrait window
        const val END_TURN_Y = 504

        // The mainframe sprite's hollow minimap window, in source coords (154×600 sheet):
        // the transparent interior spans x≈27..141, y≈25..115.
        const val MM_SRC_X0 = 27
        const val MM_SRC_X1 = 141
        const val MM_SRC_Y0 = 25
        const val MM_SRC_Y1 = 115
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
        // The minimap window is the mainframe's hollow interior, mapped to screen coords.
        val mr = mainframeRect()
        val mfScale = (WINDOW_H - SIDE_Y).toFloat() / 600f
        val winX = mr.x + (Panel.MM_SRC_X0 - 10) * mfScale
        val winW = (Panel.MM_SRC_X1 - Panel.MM_SRC_X0) * mfScale
        val winY = mr.y + Panel.MM_SRC_Y0 * mfScale
        val winH = (Panel.MM_SRC_Y1 - Panel.MM_SRC_Y0) * mfScale

        // Fit the map inside the window (no enlargement/offset), centred in the hollow.
        val cell = minOf(winW.toDouble() / map.width, winH.toDouble() / map.height)
        val mmW = (cell * map.width).toInt()
        val mmH = (cell * map.height).toInt()
        val x0 = (winX + (winW - mmW) / 2f).toInt()
        val y0 = (winY + (winH - mmH) / 2f).toInt()

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
     * Reproduces the original SWF `ReconPanel`: a full-width strip along the bottom of the map
     * that shows info for whichever tile is under the cursor. The strip is the baked
     * `recon_unit` / `recon_terrain` / `recon_none` sprite (565×42).
     *
     *  - A tile with a visible unit uses `recon_unit`: the unit's name, its sprite, a colour
     *    health bar (reconHealth_*), and its HP / damage / move values.
     *  - An empty (or cloaked) tile uses `recon_terrain`: the terrain name, description and
     *    visible modifiers.
     *  - No tile under the cursor uses `recon_none` and shows a transient build message if set.
     */
    private fun drawUnitInfo() {
        // Show the tile the player clicked (set on mouse-down, like the original ReconPanel), or
        // the selected unit's tile when nothing has been clicked this turn. Never hover-driven.
        val infoTile = focusTile ?: (game.activeController as? HumanController)?.selectedUnit?.let {
            game.tileAt(it.x, it.y)
        }

        val reconBg = if (infoTile != null) {
            // A unit on the clicked tile shows its info even when cloaked (the click reveals it).
            if (infoTile.unit != null) "recon_unit" else "recon_terrain"
        } else "recon_none"
        val bg = textures.texture(reconBg) ?: return

        val barH = bg.size.y.toInt()   // 42
        val barY = WINDOW_H - barH
        renderer.renderTexture(bg, dst = SDLFRect(0f, barY.toFloat(), WINDOW_W.toFloat(), barH.toFloat()))

        if (infoTile == null) {
            if (buildMessage.isNotEmpty()) {
                text.draw(buildMessage, 8, barY + (barH - 13) / 2 + 2, 13, SDLColor(245, 245, 245))
            }
            return
        }

        // Left icon cell shows the tile's material (terrain / structure / unit sprite).
        drawReconIcon(infoTile)

        val unit = infoTile.unit
        if (unit != null) {
            drawReconUnit(unit, barY, barH)
        } else {
            drawReconTile(infoTile, barY, barH)
        }
    }

    /**
     * Draws the tile's unit / terrain material in the strip's left icon cell. The cell is a
     * SQUARE with a black backdrop; the material is scaled PROPORTIONALLY (contain) inside it,
     * never stretched — a non-square sprite gets letterboxed onto the black square.
     */
    private fun drawReconIcon(tile: Tile) {
        val size = 40
        val x = 6
        val y = barYOf(size) + 1

        // Black square backdrop (always drawn so non-square materials letterbox cleanly).
        renderer.drawColor = SDLColor(0, 0, 0)
        renderer.fillRect(SDLRect(x, y, size, size))

        // A unit on the tile takes priority: show the VEHICLE's sprite, not the terrain
        // (cloaked included — clicking the tile reveals it).
        val unit = tile.unit
        if (unit != null) {
            val key = spriteKeyForUnit(unit)
            val rawTeam = rawTeamColor(unit.controller?.team)
            val tex = if (key.isNotEmpty()) textures.textureForTeam(key, rawTeam) else null
            if (tex != null) {
                drawReconIconTexture(tex, x, y, size)
                return
            }
        }

        // No unit: show the terrain / structure material.
        val spriteKey = if (tile.hasStructure) tile.terrain.sprite.ifEmpty { null }
                        else tile.spriteKeys.firstOrNull() ?: tile.terrain.sprite.ifEmpty { null }
        val tex = spriteKey?.let { textures.textureOrFallback(it) }
        if (tex != null) {
            drawReconIconTexture(tex, x, y, size)
        } else {
            renderer.drawColor = terrainColor(tile.terrain.name)
            renderer.fillRect(SDLRect(x, y, size, size))
        }
    }

    /**
     * The source crop for [tex]'s first 56-wide cell. All base unit sheets are 336 wide
     * (6 columns of 56). The cell height is height/4: a 224-tall sheet has a 56×56 cell (the
     * unit fills it — crop the whole cell); a 448-tall sheet has a 56×112 cell whose body sits
     * in the bottom half, so crop the bottom 56px. Cropping to the body keeps every unit roughly
     * the same icon size instead of letterboxing tall 56×112 cells into tiny stamps.
     */
    private fun reconBody(tex: cn.enaium.sdl.SDLTexture): SDLRect {
        val tw = tex.size.x.toInt()
        val th = tex.size.y.toInt()
        val cellH = if (tw == 336) th / 4 else th
        if (tw == 336 && cellH > 56) {
            return SDLRect(0, 56, 56, 56)  // 112-tall cell: bottom half
        }
        return SDLRect(0, 0, minOf(tw, 56), minOf(cellH, 56))
    }

    /** Draws [tex] inside the square icon cell at (x,y), scaled proportionally (contain). */
    private fun drawReconIconTexture(tex: cn.enaium.sdl.SDLTexture, x: Int, y: Int, size: Int) {
        val body = reconBody(tex)
        val scale = minOf(size.toFloat() / body.width, size.toFloat() / body.height)
        val dw = (body.width * scale).toInt()
        val dh = (body.height * scale).toInt()
        val dx = x + (size - dw) / 2
        val dy = y + (size - dh) / 2
        renderer.renderTexture(
            tex,
            src = SDLFRect(body.x.toFloat(), body.y.toFloat(), body.width.toFloat(), body.height.toFloat()),
            dst = SDLFRect(dx.toFloat(), dy.toFloat(), dw.toFloat(), dh.toFloat()),
        )
    }

    /** The strip's top y (the strip is 42px tall at the window bottom). */
    private fun barYOf(h: Int): Int = WINDOW_H - h

    /** Draws a 20×20 cell of the `info_icons` sheet (400×80 = 20 cols × 4 rows). */
    private fun drawInfoIcon(frame: Int, x: Int, y: Int) {
        val tex = textures.texture("info_icons") ?: return
        val cell = 20
        renderer.renderTexture(
            tex,
            src = SDLFRect(((frame % 20) * cell).toFloat(), ((frame / 20) * cell).toFloat(), cell.toFloat(), cell.toFloat()),
            dst = SDLFRect(x.toFloat(), y.toFloat(), cell.toFloat(), cell.toFloat()),
        )
    }

    /**
     * The info_icons sheet frame for a modifier (attribute) name, e.g. Plains → Uneven + Cover.
     * Frames come from the `info_icons` tilesheet animations (400×80, 20×20 cells).
     */
    private fun modifierIconFrame(name: String): Int? = when (name.lowercase()) {
        "ground" -> 0
        "water" -> 1
        "impassable" -> 2
        "uneven" -> 3
        "pavement" -> 4
        "rugged" -> 5
        "precipitous" -> 6
        "barbed" -> 7
        "shallow" -> 8
        "cover" -> 9
        "camouflage" -> 10
        "bunker" -> 11
        "high ground" -> 12
        "highground" -> 12
        "farsight" -> 13
        "garrison" -> 14
        "strait" -> 15
        "reduced mobility" -> 16
        "reducedmobility" -> 16
        "hazard" -> 17
        "canopy" -> 18
        "shielded" -> 19
        "construction" -> 20
        "capturable" -> 21
        "air proficiency" -> 22
        "airproficiency" -> 22
        "ground proficiency" -> 23
        "groundproficiency" -> 23
        "sea proficiency" -> 24
        "seaproficiency" -> 24
        "level 1 oil refining" -> 25
        "level 2 oil refining" -> 26
        "level 3 oil refining" -> 27
        "mission critical" -> 28
        "missioncritical" -> 28
        "shore" -> 29
        "skysweeper" -> 30
        "counterbattery" -> 31
        "counter battery" -> 31
        "capture" -> 32
        "flak" -> 33
        "transport" -> 34
        "unbuildable" -> 35
        "cloaking" -> 36
        "tricky waters" -> 37
        "trickywaters" -> 37
        "airbound" -> 38
        "air bound" -> 38
        "seabound" -> 39
        "sea bound" -> 39
        "groundbound" -> 40
        "ground bound" -> 40
        "blitz" -> 41
        "mammoth" -> 42
        "maintenance" -> 44
        "firebomb" -> 45
        "fire bomb" -> 45
        "overdrive" -> 46
        "radar" -> 47
        "antisub" -> 48
        "anti-sub" -> 48
        "submerged" -> 49
        "massivehull" -> 50
        "massive hull" -> 50
        "doubledamage" -> 63
        "double damage" -> 63
        "tracking" -> 64
        "constructor" -> 65
        "extractor" -> 66
        "extractable" -> 67
        "irreparable" -> 68
        "jamming" -> 69
        "piercing" -> 70
        "fixed" -> 71
        "linchpin" -> 72
        "notselectable" -> 73
        "not selectable" -> 73
        "blindspot" -> 74
        "blind spot" -> 74
        "stun" -> 75
        "scavenge" -> 76
        else -> null
    }

    /**
     * Draws the `recon_unit` panel for [unit], mirroring the SWF ReconPanel.setUnit:
     * the vehicle sprite in the left icon cell, name + description, then a stats block whose
     * LABELS (Health: / Damage: / Move: / Modifiers:) sit on one top line and whose VALUES
     * (icon + number) sit in the line below. The block is shifted left by half a cell and down
     * a little from the earlier layout.
     */
    private fun drawReconUnit(unit: Unit, barY: Int, barH: Int) {
        val textX = 72  // right of the left icon cell

        // Unit name (bold, like the SWF title) + description below.
        text.draw(unit.unitData.name, textX, barY + 1, 15, SDLColor(20, 20, 20))
        val desc = unit.unitData.description
        var dy = barY + 16
        for (wrapped in wrapRecon(desc, 210, 11)) {
            if (dy > barY + barH - 8) break
            text.draw(wrapped, textX, dy, 11, SDLColor(230, 230, 230))
            dy += 13
        }

        // Stats block: labels on one top line, values on the line below with a clear gap.
        // Damage and Move titles are shifted left of the previous layout, and the Modifiers
        // title sits at the position the Move column used before this change.
        val labelY = barY + 2
        val valueY = barY + 18

        // Column x-offsets for Health / Damage / Move / Modifiers. The whole block sits right
        // of the previous (over-shifted) position by ~two health-icon widths.
        val col0 = textX + 260
        val col1 = col0 + 95
        val col2 = col1 + 95
        val col3 = col2 + 95

        // Labels (one top line).
        text.draw("Health:", col0, labelY, 11, SDLColor(15, 15, 15))
        text.draw("Damage:", col1, labelY, 11, SDLColor(15, 15, 15))
        text.draw("Move:", col2, labelY, 11, SDLColor(15, 15, 15))
        text.draw("Modifiers:", col3, labelY, 11, SDLColor(15, 15, 15))

        // 1. Health value: armor icon + HP bar + HP number.
        val armorFrame = when (unit.unitData.armorType) {
            1 -> 51  // light
            2 -> 52  // medium
            else -> 53 // heavy
        }
        drawInfoIcon(armorFrame, col0, valueY)
        val healthPct = unit.getHealthPercentage().coerceIn(0.0, 1.0)
        val healthColor = when {
            healthPct >= 0.75 -> "reconHealth_green"
            healthPct >= 0.5 -> "reconHealth_yellow"
            healthPct >= 0.25 -> "reconHealth_orange"
            else -> "reconHealth_red"
        }
        val hx = col0 + 22
        val hy = valueY + 5
        textures.texture("reconHealth_black")?.let { renderer.renderTexture(it, dst = SDLFRect(hx.toFloat(), hy.toFloat(), 39f, 8f)) }
        textures.texture(healthColor)?.let { renderer.renderTexture(it, dst = SDLFRect(hx.toFloat(), hy.toFloat(), (39 * healthPct).toFloat(), 8f)) }
        textures.texture("reconHealth_overlay")?.let { renderer.renderTexture(it, dst = SDLFRect(hx.toFloat(), hy.toFloat(), 39f, 8f)) }
        text.draw("${unit.getHealth()}/${unit.baseUnitData.health}", hx + 42, valueY, 11, SDLColor(15, 15, 15))

        // 2. Damage value: weapon icon + damage number.
        val weaponFrame = when (unit.unitData.weaponType) {
            1 -> 60  // light
            2 -> 61  // medium
            else -> 62 // heavy
        }
        drawInfoIcon(weaponFrame, col1, valueY)
        val dmg = if (unit.unitData.damage > 0) {
            val rng = if (unit.unitData.weaponRangeMin == 1 && unit.unitData.weaponRangeMax == 1) ""
            else " ${unit.unitData.weaponRangeMin}-${unit.unitData.weaponRangeMax}"
            "${unit.unitData.damage}$rng"
        } else "N/A"
        text.draw(dmg, col1 + 22, valueY, 11, SDLColor(15, 15, 15))

        // 3. Move value: movement icon + range number.
        val moveFrame = when (unit.unitData.movementType) {
            1 -> 59  // feet
            2 -> 54  // wheeled
            3 -> 55  // tracked
            4 -> 58  // rudder
            else -> 56 // wings
        }
        drawInfoIcon(moveFrame, col2, valueY)
        text.draw("${unit.unitData.range}", col2 + 22, valueY, 11, SDLColor(15, 15, 15))

        // 4. Modifiers: visible unit modifiers as info icons.
        val mods = unit.unitData.getAllModifiers().filter { it.visible }
        var modx = col3
        for (mod in mods) {
            val frame = modifierIconFrame(mod.name)
            if (frame != null) drawInfoIcon(frame, modx, valueY)
            modx += 21
        }
    }

    /** Draws the `recon_terrain` panel for [tile]: terrain name, description, modifiers. */
    private fun drawReconTile(tile: Tile, barY: Int, barH: Int) {
        val textX = 72  // right of the left icon cell
        var name = tile.terrain.name
        if (name == "Factory") name = "Warfactory"
        text.draw(name, textX, barY + 2, 15, SDLColor(20, 20, 20))

        // Description wraps in the bar's main area.
        val desc = tile.terrain.description
        var dy = barY + 16
        if (desc.isNotEmpty()) {
            for (wrapped in wrapRecon(desc, 300, 12)) {
                if (dy > barY + barH - 10) break
                text.draw(wrapped, textX, dy, 12, SDLColor(230, 230, 230))
                dy += 15
            }
        }

        // Visible modifiers (e.g. Def +1, Canopy, Shielded...) as small tags.
        val visible = tile.terrain.modifiers.filter { it.visible }
        if (visible.isNotEmpty()) {
            text.draw("Modifiers:", textX + 380, barY + 2, 12, SDLColor(15, 15, 15))
            var mx = textX + 380
            var my = barY + 17
            for (mod in visible) {
                val tag = mod.image?.ifEmpty { mod.name } ?: mod.name
                text.draw(tag, mx, my, 11, SDLColor(15, 15, 15))
                mx += text.measure(tag, 11) + 8
            }
        }
    }

    /** Word-wraps tile/unit descriptions to [maxWidth] px. */
    private fun wrapRecon(input: String, maxWidth: Int, sizePx: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (word in input.split(" ")) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (text.measure(cand, sizePx) <= maxWidth || cur.isEmpty()) {
                cur.setLength(0); cur.append(cand)
            } else {
                out.add(cur.toString()); cur.setLength(0); cur.append(word)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
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

    /**
     * Draws the mission briefing dialogue, recreating the original SWF ChatWindow:
     *
     * - A wood-textured BattalionPanel (height 154) with the "frame1" bevel border, drawn at
     *   alpha 0.65 so the map behind stays fully visible. Its bottom edge sits on the game area
     *   baseline; the whole panel slides up/down by [diaSlideOffset] for show/dismiss tween.
     * - The speaker's avatar at natural size (130×150) at the top-left.
     * - A thin colour-bar (colorBarLong) down the avatar's right, tinted to the speaker colour.
     * - The message typed out character-by-character ("sender:\n message") in white size 17.
     * - A graphical continue button at the bottom-right.
     */
    private fun drawDialogue(dia: Dialogue) {
        val line = dia.current() ?: return
        val boxW = GameManager.GAME_WIDTH
        val boxH = DIALOGUE_PANEL_H
        val boxX = MAP_X
        val boxBottom = DIALOGUE_PANEL_BOTTOM + diaSlideOffset()
        val boxY = boxBottom - boxH

        // Wood-textured panel background at BattalionPanel's 0.65 alpha. The renderer ignores
        // drawColor alpha (fills render opaque), so the old black underlay + full-opacity wood
        // showed as a solid, non-transparent box; the wood texture is drawn with alphaMod
        // instead (texture alpha blending works) so the map behind stays visible.
        val wood = textures.texture("wood_texture") ?: textures.texture("panel")
        if (wood != null) {
            val prevAlpha = wood.alphaMod
            try {
                // Tile the uncropped wood texture across the whole box (FrameMaker.createBlock).
                wood.colorMod = SDLColor(255, 255, 255)
                wood.alphaMod = (255 * DIALOGUE_PANEL_ALPHA).toInt()
                drawTiled(wood, SDLRect(boxX, boxY, boxW, boxH))
            } finally {
                wood.alphaMod = prevAlpha  // shared cached texture — restore before anyone else draws it
            }
        } else {
            // No wood texture available: plain opaque panel so the text stays readable.
            renderer.drawColor = SDLColor(20, 22, 28)
            renderer.fillRect(SDLRect(boxX, boxY, boxW, boxH))
        }

        // "frame1" bevel border around the panel.
        drawFrameBorder(boxX, boxY, boxW, boxH)

        // Avatar (speaker) natural size at top-left, like ChatWindow avatar at (2,2).
        val avatarW = 130
        val avatarH = 150
        val avatarX = boxX + 2
        val avatarY = boxY + 2
        val avatarTex = avatarTexture ?: run { loadAvatarTexture(); avatarTexture }
        val frame = avatarFrame(line.avatar)
        if (avatarTex != null && frame != null) {
            renderer.renderTexture(avatarTex, src = frame, dst = SDLFRect(avatarX.toFloat(), avatarY.toFloat(), avatarW.toFloat(), avatarH.toFloat()))
        }

        // Colour bar (colorBarLong, 5×150) tinted to the speaker's colour, right of the avatar.
        val barX = avatarX + avatarW + 2
        val barW = 7
        val barH = avatarH
        val barColor = SDLColor((line.color shr 16) and 0xFF, (line.color shr 8) and 0xFF, line.color and 0xFF)
        renderer.drawColor = barColor
        renderer.fillRect(SDLRect(barX, avatarY, barW, barH))

        // Message text field, right of the colour bar, width to the panel edge.
        val textX = barX + barW + 2
        val textW = boxW - textX - 8
        // "sender:" + newline + message, like ChatWindow messageText.
        val senderBold = "${line.sender}:"
        val msgSize = 17

        // Typewriter reveal: show only the first N characters of the combined text.
        val fullText = "$senderBold\n${line.message}"
        val shown = typewriterShown(fullText)

        val nameColor = SDLColor((line.color shr 16) and 0xFF, (line.color shr 8) and 0xFF, line.color and 0xFF)
        if (shown.isNotEmpty()) {
            // "sender:" line in the speaker colour, then the wrapped message in white.
            val senderLine = senderBold.substring(0, minOf(senderBold.length, shown.length))
            text.draw(senderLine, textX, boxY + 6, msgSize, nameColor)
            val msgStart = senderBold.length + 1
            if (shown.length > msgStart) {
                val msgShown = shown.substring(msgStart)
                var y = boxY + 6 + text.measure("Mg", msgSize)
                for (wrapped in wrapText(msgShown, textW, msgSize)) {
                    if (y > boxY + boxH - 30) break
                    text.draw(wrapped, textX, y, msgSize, SDLColor(245, 245, 245))
                    y += (msgSize + 4)
                }
            }
        }

        // Continue button (continued across consecutive lines — a graphical button, not text).
        val cont = textures.texture("continueButton")
        if (cont != null) {
            // 4-frame strip, 152×41 frames. Show the "up/normal" frame.
            renderer.renderTexture(
                cont,
                src = SDLFRect(0f, 0f, 152f, 41f),
                dst = SDLFRect((boxX + boxW - 160).toFloat(), (boxY + boxH - 45).toFloat(), 152f, 41f),
            )
        }
    }

    /** The vertical slide offset of the dialogue panel for the current show/dismiss tween. */
    private fun diaSlideOffset(): Int {
        val t = game.elapsedMs - diaAnimStartMs
        return when {
            diaSlidingIn -> {
                // Sine.easeOut from +PANEL_HEIGHT down to 0 over 500ms.
                val f = t.toFloat() / DIALOGUE_SLIDE_MS
                (DIALOGUE_PANEL_H * (1f - easeOutSin(f))).toInt()
            }
            diaSlidingOut -> {
                // Sine.easeIn from 0 to +PANEL_HEIGHT over 500ms.
                val f = t.toFloat() / DIALOGUE_SLIDE_MS
                (DIALOGUE_PANEL_H * easeInSin(f)).toInt()
            }
            else -> 0
        }.coerceAtLeast(0)
    }

    /** Sine ease-in/out helpers matching the original ChatWindow Tweens. */
    private fun easeOutSin(f: Float): Float = sin(f * (PI.toFloat() / 2f))
    private fun easeInSin(f: Float): Float = 1f - cos(f * (PI.toFloat() / 2f))

    /** Tiles [tex] across the rectangle [dst], repeating the texture at natural size. */
    private fun drawTiled(tex: SDLTexture, dst: SDLRect) {
        val tw = maxOf(1, tex.size.x.toInt())
        val th = maxOf(1, tex.size.y.toInt())
        var y = dst.y
        while (y < dst.y + dst.height) {
            var x = dst.x
            while (x < dst.x + dst.width) {
                val w = minOf(tw, dst.x + dst.width - x)
                val h = minOf(th, dst.y + dst.height - y)
                renderer.renderTexture(tex, dst = SDLFRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()))
                x += tw
            }
            y += th
        }
    }

    /**
     * Draws a nine-slice "frame1"-style bevel border around the rectangle at (x,y,w,h). The
     * frame1 sprite is 150×150; a 16px corner is copied intact and the edges/sides tile the
     * interior strip, reproducing FrameMaker.createFrame. Falls back to a simple double-line
     * border if the frame texture is unavailable.
     */
    private fun drawFrameBorder(x: Int, y: Int, w: Int, h: Int) {
        val frameTex = textures.texture("frame1")
        val corner = 16
        if (frameTex == null || w <= corner * 2 || h <= corner * 2) {
            renderer.drawColor = SDLColor(120, 120, 140)
            renderer.drawRect(SDLRect(x, y, w, h))
            renderer.drawColor = SDLColor(60, 60, 75)
            renderer.drawRect(SDLRect(x + 2, y + 2, w - 4, h - 4))
            return
        }
        renderer.drawColor = SDLColor(255, 255, 255)
        renderer.drawRect(SDLRect(x, y, w, h))
        // Intended as a soft bevel shadow, but the renderer ignores drawColor alpha — a
        // translucent black renders as a solid black band. Use a mid-gray line instead.
        renderer.drawColor = SDLColor(64, 64, 76)
        renderer.drawRect(SDLRect(x + 1, y + 1, w - 2, h - 2))
    }

    /** Reveals [fullText] up to the current typewriter position, or the whole text if done. */
    private fun typewriterShown(fullText: String): String {
        if (diaTypeDone || fullText.isEmpty()) return fullText
        val revealMs = (fullText.length * 1000L) / DIALOGUE_TYPE_CHARS_PER_SEC
        val count = ((game.elapsedMs - diaTypeStartMs) * DIALOGUE_TYPE_CHARS_PER_SEC / 1000L).toInt()
            .coerceIn(0, fullText.length)
        if (count >= fullText.length) {
            diaTypeDone = true
            return fullText
        }
        return fullText.substring(0, count)
    }

    /** Wraps [input] to fit [maxWidth] pixels, splitting on word boundaries. */
    private fun wrapText(input: String, maxWidth: Int, sizePx: Int = 17): List<String> {
        val words = input.split(" ")
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (text.measure(candidate, sizePx) <= maxWidth || current.isEmpty()) {
                current.setLength(0); current.append(candidate)
            } else {
                lines.add(current.toString()); current.setLength(0); current.append(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}

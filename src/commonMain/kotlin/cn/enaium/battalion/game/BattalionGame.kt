package cn.enaium.battalion.game

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.TileManager
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.ui.AudioManager
import cn.enaium.battalion.game.ui.Dialogue
import cn.enaium.battalion.game.ui.StartScreen
import cn.enaium.battalion.game.ui.TextRenderer
import cn.enaium.battalion.game.ui.TextureManager
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType

/**
 * The Battalion Vengeance desktop app: initialises SDL, loads a mission, and runs the game loop.
 *
 * The loop is fully synchronous and mirrors the original Flash `enterFrame` handler:
 * poll events -> advance the game state ([GameManager.update]) -> draw -> wait for the next
 * frame. Player input is translated into [HumanController] dispatches (move / attack / build /
 * extract / end turn).
 */
class BattalionGame(
    private val assetsDir: String,
    private val missionIndex: Int? = null,
    private val skirmishMap: String? = null,
) {
    private lateinit var window: SDLWindow
    private lateinit var renderer: SDLRenderer
    private lateinit var text: TextRenderer
    private lateinit var audio: AudioManager
    private lateinit var textures: TextureManager

    private var game: GameManager? = null
    private var render: BattalionRenderer? = null
    private var dialogue: Dialogue? = null

    private var lastTick: Long = 0
    private var running = true

    /**
     * Set when a mouse-button press advanced the dialogue. On macOS SDL can swallow the press
     * event when it focuses an unfocused window, delivering only the release — we advance the
     * dialogue on release as a fallback, but only when no press already did so.
     */
    private var dialogueAdvancedOnPress = false

    /** Last build message shown in the side panel (e.g. "Cannot afford"). */
    private var buildMessage: String = ""

    /** True once the player-name intro animation has been shown for this mission. */
    private var playerIntroShown = false

    private fun setBuildMessage(msg: String) {
        buildMessage = msg
        render?.buildMessage = msg
    }

    /**
     * After the mission brief ends (control passes to the player), reveals the current
     * player's name with the intro animation.
     */
    private fun onDialogueAdvanced() {
        val dia = dialogue ?: return
        if (dia.isActive() || playerIntroShown) return
        playerIntroShown = true
        val team = game?.activeController?.team ?: return
        render?.showPlayerIntro(team.teamName.ifEmpty { team.name })
    }

    /**
     * Advances the dialogue from player input, mirroring the original ChatWindow:
     * while the line is still being typed out, a click/key fast-forwards the reveal instead
     * of advancing to the next line.
     */
    private fun advanceDialogueFromInput() {
        val dia = dialogue ?: return
        if (dia.isActive() && render?.isDialogueTyping() == true) {
            render?.skipDialogueTyping()
            return
        }
        dia.advance()
        onDialogueAdvanced()
    }

    /** Runs the game until the window is closed. */
    fun run() {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.AUDIO or SDLInitFlags.EVENTS)) {
            error("SDL_Init failed: ${SDL.error()}")
        }

        window = SDL.createWindow(
            title = "BattalionX",
            width = BattalionRenderer.WINDOW_W,
            height = BattalionRenderer.WINDOW_H,
        )
        renderer = SDL.createRenderer(window)
        text = TextRenderer(renderer)
        text.load()

        // Audio is best-effort: if it fails to initialise, the game still runs silently.
        audio = AudioManager(assetsDir)
        audio.load()

        // Texture manager loads unit / terrain sprites from the images directory.
        textures = TextureManager(assetsDir, renderer)
        textures.load()

        // Pick the mission: an explicit CLI mission/skirmish skips the menu, otherwise show the
        // start screen so the player can choose.
        var mission = missionIndex
        var skirmish = skirmishMap
        if (mission == null && skirmish == null) {
            val start = StartScreen(assetsDir, renderer, text, textures)
            val selection = start.run()
            if (selection.quit) {
                text.close(); audio.close(); textures.close(); renderer.close(); window.close(); SDL.quit()
                return
            }
            mission = selection.missionIndex
            skirmish = selection.skirmishMap
        }

        // Load the game. Skirmish mode plays any map as a free-for-all; otherwise a campaign mission.
        game = if (skirmish != null) {
            GameLoader.loadSkirmish(assetsDir, skirmish)
        } else {
            GameLoader.loadCampaignMission(assetsDir, mission ?: 0)
        }
        render = BattalionRenderer(game!!, text, renderer, textures)
        render!!.setupTextures()
        game!!.startGame()

        // Load the mission briefing and play it over the start of the match.
        dialogue = Dialogue(game!!.map.getScript())
        render!!.dialogue = dialogue
        dialogue!!.play("startGame")

        lastTick = SDL.getTicks().toLong()
        gameLoop()
    }

    /**
     * Scrolls the camera when the mouse is held near the edge of the map viewport, letting the
     * player pan over maps larger than the on-screen 10×10 tile grid.
     */
    private fun handleEdgeScroll() {
        val r = render ?: return
        val mouse = SDL.mouseState
        val edge = 20
        val speed = 1
        var dx = 0
        var dy = 0
        if (mouse.x < BattalionRenderer.MAP_X + edge) dx -= speed
        else if (mouse.x > BattalionRenderer.MAP_X + BattalionRenderer.MAP_W - edge) dx += speed
        if (mouse.y < BattalionRenderer.MAP_Y + edge) dy -= speed
        else if (mouse.y > BattalionRenderer.MAP_Y + BattalionRenderer.MAP_H - edge) dy += speed
        if (dx != 0 || dy != 0) r.scrollCamera(dx, dy)
    }

    private fun gameLoop() {
        while (running) {
            val now = SDL.getTicks().toLong()
            val dt = (now - lastTick).toInt()
            lastTick = now

            pollEvents()
            game?.update(dt.toLong())
            handleEdgeScroll()
            render?.render()

            SDL.delay(16)
        }

        text.close()
        audio.close()
        textures.close()
        renderer.close()
        window.close()
        SDL.quit()
    }

    private fun pollEvents() {
        while (true) {
            val event = SDL.pollEvent() ?: break
            when (event) {
                is SDLEvent.Quit -> running = false
                is SDLEvent.Window ->
                    if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false
                is SDLEvent.Key -> {
                    if (event.down && !event.repeat) {
                        if (dialogue?.isActive() == true) {
                            // Any key advances the briefing (or fast-forwards the typewriter).
                            advanceDialogueFromInput()
                        } else {
                            onKeyDown(event.keycode)
                        }
                    }
                }
                is SDLEvent.MouseButton -> {
                    if (event.down) {
                        if (dialogue?.isActive() == true) {
                            dialogueAdvancedOnPress = true
                            advanceDialogueFromInput()
                        } else {
                            onMouseDown(event.button, event.x.toInt(), event.y.toInt())
                        }
                    } else if (event.button == 1) {
                        if (dialogue?.isActive() == true) {
                            if (!dialogueAdvancedOnPress) {
                                // macOS SDL can swallow the press (it focuses the window first) and only
                                // deliver the release. Advance the dialogue then, but never twice per click.
                                advanceDialogueFromInput()
                            }
                            dialogueAdvancedOnPress = false
                        } else {
                            // Release on a panel button triggers the action (press only shows
                            // the pressed frame).
                            val btn = render?.panelButtonAt(event.x.toInt(), event.y.toInt())
                            if (btn != null && render?.pressedButton == btn) {
                                onPanelButton(btn)
                            }
                            render?.pressedButton = null
                        }
                    } else {
                        dialogueAdvancedOnPress = false
                    }
                }
                else -> Unit
            }
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    private fun human(): HumanController? =
        game?.activeController as? HumanController

    private fun tileFromScreen(sx: Int, sy: Int): Pair<Int, Int>? {
        if (sx < BattalionRenderer.MAP_X || sy < BattalionRenderer.MAP_Y) return null
        val tx = (sx - BattalionRenderer.MAP_X) / TileManager.TILE_WIDTH + (render?.cameraX ?: 0)
        val ty = (sy - BattalionRenderer.MAP_Y) / TileManager.TILE_HEIGHT + (render?.cameraY ?: 0)
        val g = game ?: return null
        if (tx < 0 || ty < 0 || tx >= g.map.width || ty >= g.map.height) return null
        return tx to ty
    }

    private fun onMouseDown(button: Int, sx: Int, sy: Int) {
        val g = game ?: return

        // Panel buttons: record the press (the action fires on release).
        if (button == 1) {
            val btn = render?.panelButtonAt(sx, sy)
            if (btn != null) {
                render?.pressedButton = btn
                return
            }
        }

        val human = human() ?: return
        val (tx, ty) = tileFromScreen(sx, sy) ?: return
        val tile = g.tileAt(tx, ty) ?: return

        if (button == 3) { // right click: clear selection
            human.selectUnit(null)
            return
        }
        if (button != 1) return // left click only

        val selected = human.selectedUnit

        // 1. If a unit is selected and can still act, try to attack the clicked tile.
        //    Supports both attacking in place and walking into weapon range first.
        if (selected != null && selected.isAvailable()) {
            val target = tile.unit
            if (target != null && g.unitsAttackableThisTurn(selected).contains(target)) {
                if (g.canAttack(selected, target)) {
                    // Already in range: fire in place.
                    human.dispatchAttack(tx, ty)
                } else if (!selected.isRanged()) {
                    // Walk to a reachable tile from which the target is in range, then fire.
                    // (Ranged units cannot move and shoot in the original — they fire first.)
                    val via = reachableAttackTile(selected, target)
                    if (via != null) {
                        val (vx, vy) = via
                        val movePath = pathTo(selected, vx, vy)
                        if (movePath != null) {
                            human.dispatchMoveThenAttack(movePath, tx, ty)
                            return
                        }
                    }
                    human.dispatchAttack(tx, ty)
                } else {
                    human.dispatchAttack(tx, ty)
                }
                return
            }
        }

        // 2. If a unit is selected and can still act, try to move to the clicked tile.
        if (selected != null && selected.isAvailable()) {
            val path = pathTo(selected, tx, ty)
            if (path != null) {
                human.dispatchMove(path)
                return
            }
        }

        // 3. Select a friendly unit on the clicked tile. A unit that has already moved or
        //    fired this turn cannot be re-selected to act again (one-action-per-turn rule).
        val unit = tile.unit
        if (unit != null && unit.controller === g.activeController && unit.isSelectable()) {
            human.selectUnit(if (unit.isAvailable()) unit else null)
            return
        }

        // 4. Select a friendly factory (for building).
        if (tile.hasStructure && tile.controller === g.activeController) {
            human.selectUnit(null)
            // Selecting a factory: store it through the selected-unit slot is not possible,
            // so we just clear the selection and let the player press B while hovering.
            setBuildMessage("Press B to build here")
            return
        }

        human.selectUnit(null)
    }

    /** Executes the action of a clicked right-panel button (fires on mouse release). */
    private fun onPanelButton(btn: BattalionRenderer.PanelButton) {
        val human = human() ?: return
        when (btn) {
            BattalionRenderer.PanelButton.UNDO_MOVE -> human.dispatchUndoMove()
            BattalionRenderer.PanelButton.NEXT_UNIT -> selectNextUnit()
            BattalionRenderer.PanelButton.OPTIONS ->
                setBuildMessage("Options menu not available in this build")
            BattalionRenderer.PanelButton.END_TURN -> human.dispatchEndTurn()
        }
    }

    /** Selects the player's next unit that can still act this turn (wrapping). */
    private fun selectNextUnit() {
        val g = game ?: return
        val human = human() ?: return
        val units = g.units.filter { it.controller === human && it.isDeployed() && it.isAvailable() }
        if (units.isEmpty()) return
        val current = human.selectedUnit
        val idx = units.indexOfFirst { it === current }
        human.selectUnit(units[(idx + 1) % units.size])
    }

    /**
     * Returns a tile [unit] can walk to this turn and from which [target] is in weapon range
     * (preferring the current tile when already in range). Null if no such reachable tile exists.
     */
    private fun reachableAttackTile(
        unit: cn.enaium.battalion.data.Unit,
        target: cn.enaium.battalion.data.Unit,
    ): Pair<Int, Int>? {
        val g = game ?: return null
        if (g.canAttack(unit, target)) return unit.x to unit.y
        // Prefer the nearest reachable tile adjacent to / in range of the target.
        val targetTile = g.tileAt(target.x, target.y) ?: return null
        val candidates = g.tilesInRange(unit, target.x, target.y)
            .filter { it.unit == null || it.unit === unit || it.x == unit.x && it.y == unit.y }
        // rank by distance from the attacker
        val sorted = candidates.sortedBy { kotlin.math.abs(it.x - unit.x) + kotlin.math.abs(it.y - unit.y) }
        for (t in sorted) {
            val path = Astar.path(g.tileAt(unit.x, unit.y)!!, t, unit) ?: continue
            if (g.canMove(unit, path)) {
                // Verify from this tile the target is attackable.
                if (g.canWeaponsFireAtTarget(unit, target, true, t.x, t.y)) return t.x to t.y
            }
        }
        return null
    }

    /** Computes a valid move path from [unit] to tile (tx, ty), or null. */
    private fun pathTo(unit: cn.enaium.battalion.data.Unit, tx: Int, ty: Int): List<String>? {
        val g = game ?: return null
        val start = g.tileAt(unit.x, unit.y) ?: return null
        val end = g.tileAt(tx, ty) ?: return null
        if (start === end) return null
        val path = Astar.path(start, end, unit) ?: return null
        if (!g.canMove(unit, path)) return null
        val strings = mutableListOf<String>()
        for (node in path) {
            val tile = node as? cn.enaium.battalion.data.Tile ?: return null
            strings.add(tile.x.toString())
            strings.add(tile.y.toString())
        }
        return strings
    }

    private fun onKeyDown(keycode: Int) {
        val g = game ?: return
        val human = human() ?: return

        when (keycode) {
            SDLKeycode.ESCAPE -> {
                if (g.gameOver) { running = false; return }
                human.selectUnit(null)
            }
            SDLKeycode.N -> human.dispatchNoAttack()
            SDLKeycode.E -> human.dispatchEndTurn()
            SDLKeycode.X -> {
                val unit = human.selectedUnit ?: return
                human.dispatchExtract(unit.x, unit.y)
            }
            SDLKeycode.B -> tryBuild(human)
            SDLKeycode.R -> human.dispatchRepair()
            SDLKeycode.U -> human.dispatchUnload()

            // Camera scrolling: arrow keys and WASD.
            SDLKeycode.UP, SDLKeycode.W -> render?.scrollCamera(0, -1)
            SDLKeycode.DOWN, SDLKeycode.S -> render?.scrollCamera(0, 1)
            SDLKeycode.LEFT, SDLKeycode.A -> render?.scrollCamera(-1, 0)
            SDLKeycode.RIGHT, SDLKeycode.D -> render?.scrollCamera(1, 0)

            else -> Unit
        }
    }

    /** Builds a unit on the currently selected factory tile, if any. */
    private fun tryBuild(human: HumanController) {
        val g = game ?: return
        val selected = human.selectedUnit
        // Build target: the selected unit's tile if it is a factory we control.
        val tile = if (selected != null) g.tileAt(selected.x, selected.y) else null
        if (tile == null || !tile.hasStructure || tile.controller !== g.activeController) {
            setBuildMessage("Select a factory first")
            return
        }
        if (!tile.terrain.hasModifier("Construction")) {
            setBuildMessage("This structure cannot build")
            return
        }

        // Pick the first affordable, buildable unit for this controller.
        val controller = g.activeController!!
        val candidates = UnitManager.getAll()
            .filter { !it.hasModifier("Unbuildable") }
            .filter { controller.canAfford(it.name) }
        val unit = candidates.firstOrNull { type ->
            when {
                type.isAir() -> controller.controlsAir()
                type.isGround() -> controller.controlsGround()
                type.isSea() -> controller.controlsSea()
                else -> false
            }
        }
        if (unit == null) {
            setBuildMessage("Cannot afford any unit")
            return
        }

        human.dispatchBuild(tile.x, tile.y, unit.name)
        setBuildMessage("Built ${unit.name}")
    }
}
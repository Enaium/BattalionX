package cn.enaium.battalion.game

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.TileManager
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.triggers.PlayerTrigger
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
import cn.enaium.sdl.SDLWindowFlags

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

    private fun setBuildMessage(msg: String) {
        buildMessage = msg
        render?.buildMessage = msg
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
    }

    /** Runs the game until the window is closed. */
    fun run() {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.AUDIO or SDLInitFlags.EVENTS)) {
            error("SDL_Init failed: ${SDL.error()}")
        }

        // High-pixel-density window: on Retina (2x) displays SDL then renders into a backing
        // at the display's native pixel scale, which [applyRenderScale] compensates for. Without
        // this, SDL would render a 1x backing that macOS upscales to the Retina grid with
        // smoothing — the residual blur that per-texture NEAREST sampling cannot fix.
        window = SDL.createWindow(
            title = "BattalionX",
            width = BattalionRenderer.WINDOW_W,
            height = BattalionRenderer.WINDOW_H,
            flags = SDLWindowFlags.HIGH_PIXEL_DENSITY,
        )
        renderer = SDL.createRenderer(window)
        applyRenderScale()
        text = TextRenderer(renderer)
        text.load()

        // Audio is best-effort: if it fails to initialise, the game still runs silently.
        audio = AudioManager(assetsDir)
        audio.load()

        // Texture manager loads unit / terrain sprites from the images directory.
        textures = TextureManager(assetsDir, renderer)
        textures.load()

        // Pick the mission: an explicit CLI mission/skirmish skips the menu, otherwise show the
        // start screen so the player can choose between Campaign missions, Bootcamp missions,
        // the bonus map, or quit. After a match ends (game over → ESC) we return to this menu
        // so the player can pick another mission without restarting the app.
        var menuExplicit = missionIndex != null || skirmishMap != null
        while (running) {
            var mission = missionIndex
            var bootcamp = false
            var skirmish = skirmishMap

            if (!menuExplicit) {
                // The mission-select pages reproduce the original SWF's 700×600 CampaignMenu
                // stage, so switch the window to that size while the menu is shown and restore
                // the game size afterwards.
                window.size = cn.enaium.sdl.SDLPoint(700, 600)
                applyRenderScale()
                // Menu BGM (the original MainMenu loops UIWAV on the "background" channel).
                // The in-match START_TURN handler switches to the faction loop when the game
                // starts; this call switches back when the match ends and we return here.
                audio.startMenuMusic()
                val start = StartScreen(assetsDir, renderer, text, textures)
                val selection = start.run()
                window.size = cn.enaium.sdl.SDLPoint(BattalionRenderer.WINDOW_W, BattalionRenderer.WINDOW_H)
                applyRenderScale()
                if (selection.quit) break
                mission = selection.missionIndex
                if (selection.bootcampIndex != null) {
                    mission = selection.bootcampIndex
                    bootcamp = true
                }
                skirmish = selection.skirmishMap
            }

            // Load the game. Skirmish mode plays any map as a free-for-all; a bootcamp mission
            // selects from the bootcamp tutorial campaign; otherwise a campaign mission.
            game = when {
                skirmish != null -> GameLoader.loadSkirmish(assetsDir, skirmish)
                bootcamp -> GameLoader.loadBootcampMission(assetsDir, mission ?: 0)
                else -> GameLoader.loadCampaignMission(assetsDir, mission ?: 0)
            }
            // The GameManager constructor clears the whole trigger event bus, including the
            // audio listeners load() registered — subscribe per match, only now that the bus
            // is stable, so START_TURN starts the BGM and unit actions play their SFX.
            audio.subscribe()
            // Turn-intro animation: fires on EVERY controller's turn (the player's AND the
            // AI's), with that faction's own colour — the original StartTurnAction creates
            // StartTurnAnimation for the active controller each turn.
            TriggerManager.addListener(PlayerTrigger.START_TURN) { trigger ->
                val pt = trigger as? PlayerTrigger ?: return@addListener
                val g = game ?: return@addListener
                val team = g.getController(pt.player)?.team ?: return@addListener
                render?.showPlayerIntro(team.teamName.ifEmpty { team.name })
            }
            render = BattalionRenderer(game!!, text, renderer, textures)
            render!!.setupTextures()
            game!!.startGame()

            // Load the mission briefing and play it over the start of the match. The player-name
            // intro is revealed as soon as the briefing box appears (not after it ends), so it
            // plays behind / around the dialogue while the mission brief is on screen.
            dialogue = Dialogue(game!!.map.getScript())
            render!!.dialogue = dialogue
            dialogue!!.play("startGame")

            // Route mid-game script responses (chat / highlight / scroll) to visible feedback.
            installScriptFeedback()

            lastTick = SDL.getTicks().toLong()
            gameLoop()

            if (menuExplicit) break   // single-shot CLI mode
            // Otherwise loop back to the menu for the next match.
        }

        text.close(); audio.close(); textures.close(); renderer.close(); window.close(); SDL.quit()
    }

    /**
     * Keeps the renderer's output scale in sync with the window's backing pixel ratio.
     *
     * With a high-pixel-density window the drawable is [window.sizeInPixels] (e.g. 2× the
     * logical size on a Retina display). All game drawing uses logical coordinates
     * ([BattalionRenderer.WINDOW_W]×…), so the renderer must be scaled by exactly the backing
     * ratio — an integer 2× upscale that keeps pixel-art textures crisp (NEAREST sampling)
     * instead of the OS compositor's smoothed fractional upscale. Call again after every
     * [SDLWindow.size] change (the menu runs at a different size than the game).
     */
    private fun applyRenderScale() {
        val size = window.size
        if (size.x <= 0 || size.y <= 0) return
        val px = window.sizeInPixels
        renderer.scale = cn.enaium.sdl.SDLFloatPoint(
            px.x.toFloat() / size.x,
            px.y.toFloat() / size.y,
        )
    }

    /**
     * Surfaces scripted `chat` / `highlight` / `scroll` responses (from map `<script>` events)
     * as visible in-game feedback. Chat is shown in the recon bar; highlights are announced the
     * same way; scroll moves the camera to the given tile.
     */
    private fun installScriptFeedback() {
        val human = human()
        cn.enaium.battalion.game.responses.ResponseDisplay.hook =
            object : cn.enaium.battalion.game.responses.ResponseDisplay.Hook {
                override fun showChat(sender: String, message: String, avatar: String, color: Int) {
                    setBuildMessage("$sender: $message")
                }
                override fun highlightTile(x: Int, y: Int, show: Boolean, updateCamera: Boolean) {
                    setBuildMessage(if (show) "Direct your attention to ($x, $y)" else "")
                    if (show && updateCamera) {
                        render?.centerCameraOn(x, y)
                    }
                }
                override fun scrollTo(x: Int, y: Int) {
                    render?.centerCameraOn(x, y)
                }
            }
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

    /** True during the in-match loop; set false to leave the current match. */
    private var inMatch = true

    private fun gameLoop() {
        inMatch = true
        while (inMatch) {
            val now = SDL.getTicks().toLong()
            val dt = (now - lastTick).toInt()
            lastTick = now

            pollEvents()
            game?.update(dt.toLong())
            handleEdgeScroll()
            render?.render()

            SDL.delay(16)
        }

        // Tear down the match's state (the window, renderer, text and textures survive so the
        // menu can reuse them for the next match).
        render = null
        game = null
        dialogue = null
        buildMessage = ""
    }

    private fun pollEvents() {
        while (true) {
            val event = SDL.pollEvent() ?: break
            when (event) {
                is SDLEvent.Quit -> { running = false; inMatch = false }
                is SDLEvent.Window ->
                    if (event.type == SDLWindowEventType.CLOSE_REQUESTED) { running = false; inMatch = false }
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

        // Build page is modal: it swallows every click while open (unit card, back, WM arrows).
        if ((g.activeController as? HumanController)?.isBuildMenuFocused() == true) {
            if (button == 1) handleBuildPageClick(sx, sy)
            return
        }

        // Panel buttons: record the press (the action fires on release).
        if (button == 1) {
            val btn = render?.panelButtonAt(sx, sy)
            if (btn != null) {
                render?.pressedButton = btn
                return
            }
        }

        val human = human() ?: return

        // Floating unit action menu: clicking the "more options" marker above the selected unit
        // toggles the action bar; clicking an action in the open bar dispatches it. These take
        // priority over map clicks so the bar is not occluded by tile selection.
        if (button == 1) {
            if (render?.unitOptionsButtonAt(sx, sy) == true) {
                human.toggleUnitOptions()
                return
            }
            val action = render?.unitMenuActionAt(sx, sy)
            if (action != null) {
                handleUnitMenuAction(human, action)
                return
            }
        }

        // While the floating action menu is open, every map click is swallowed: the player
        // must pick an action (or close the menu) before they can move / attack / reselect.
        // The menu is a modal for the selected unit's actions — map interactions must not fire
        // under it even though the move/attack preview textures are hidden.
        if (human.unitOptionsOpen) return

        val (tx, ty) = tileFromScreen(sx, sy) ?: return
        val tile = g.tileAt(tx, ty) ?: return

        if (button == 3) { // right click: clear selection and blank the info strip
            human.selectUnit(null)
            render?.setFocusTile(null)
            return
        }
        if (button != 1) return // left click only

        // The bottom recon strip follows the clicked tile (mouse-down), not hover.
        render?.setFocusTile(tile)

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
            human.setBuildFocus(tx, ty)
            setBuildMessage("Press B to build here")
            return
        }

        human.selectUnit(null)
    }

    /** Executes a clicked floating unit-menu action. */
    private fun handleUnitMenuAction(human: HumanController, action: BattalionRenderer.UnitMenuAction) {
        val unit = human.selectedUnit ?: return
        when (action) {
            BattalionRenderer.UnitMenuAction.ALBATROSS -> {
                human.dispatchUpgradeTransport(unit, "Albatross Transport")
                human.closeUnitOptions()
            }
            BattalionRenderer.UnitMenuAction.LEVIATHAN -> {
                human.dispatchUpgradeTransport(unit, "Leviathan Barge")
                human.closeUnitOptions()
            }
            BattalionRenderer.UnitMenuAction.UNLOAD -> {
                human.dispatchUnload()
                human.closeUnitOptions()
            }
            BattalionRenderer.UnitMenuAction.BUILD -> {
                human.closeUnitOptions()
                if (human.openBuildMenu()) updateBuildMenuMessage(human) else setBuildMessage("Nothing buildable here")
            }
            BattalionRenderer.UnitMenuAction.EXTRACT -> {
                human.dispatchExtract(unit.x, unit.y)
                human.closeUnitOptions()
            }
            BattalionRenderer.UnitMenuAction.REPAIR -> {
                human.dispatchRepair()
                human.closeUnitOptions()
            }
            BattalionRenderer.UnitMenuAction.CANCEL -> {
                human.closeUnitOptions()
                human.selectUnit(null)
            }
        }
    }

    /**
     * Routes a click on the open build page: a buildable-unit card, the Back button, or (in the
     * WM direction phase) a cardinal arrow or the cancel/back button.
     */
    private fun handleBuildPageClick(sx: Int, sy: Int) {
        val human = human() ?: return
        val render = render ?: return
        val menu = human.buildMenu ?: return

        when (menu.phase) {
            HumanController.BuildPhase.WM_DIRECTION -> {
                val dir = render.buildDirectionAt(sx, sy)
                when (dir) {
                    "left", "right", "up", "down" -> human.chooseWarmachineDirection(dir)
                    "cancel" -> human.backBuildMenuToUnits()
                    null -> {
                        // Back button on the page also returns to units.
                        if (render.buildCancelAt()?.hit(sx, sy) == true) human.backBuildMenuToUnits()
                    }
                }
                return
            }
            HumanController.BuildPhase.UNITS -> {
                // Back (cancel) button closes the whole build page.
                if (render.buildBackAt()?.hit(sx, sy) == true) {
                    human.cancelBuild()
                    return
                }
                // A buildable-unit card: select it, then act on it. A factory build completes
                // immediately; a WM build advances to the direction picker (one click each,
                // mirroring the SWF BuildMenuUnit.clickHandler → menu.complete).
                val idx = render.buildUnitIndexAt(sx, sy)
                if (idx >= 0) {
                    human.cycleBuildUnit(idx - menu.index)
                    // Only a card that is both buildable here AND affordable can be clicked
                    // (the SWF BuildMenuUnit only installs a click handler for available,
                    // affordable units); unavailable / unaffordable cards just focus.
                    if (human.canBuildHere(menu.units[idx]) && human.canAffordBuild(menu.units[idx])) {
                        human.confirmBuild()
                    }
                }
            }
        }
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

        // A build menu is open: intercept navigation / confirm / cancel keys.
        val menu = human.buildMenu
        if (menu != null) {
            when (keycode) {
                SDLKeycode.LEFT, SDLKeycode.A -> {
                    if (menu.phase == HumanController.BuildPhase.WM_DIRECTION) {
                        if (human.chooseWarmachineDirection("left")) setBuildMenuBuilt()
                    } else { human.cycleBuildUnit(-1); updateBuildMenuMessage(human) }
                }
                SDLKeycode.RIGHT, SDLKeycode.D -> {
                    if (menu.phase == HumanController.BuildPhase.WM_DIRECTION) {
                        if (human.chooseWarmachineDirection("right")) setBuildMenuBuilt()
                    } else { human.cycleBuildUnit(1); updateBuildMenuMessage(human) }
                }
                SDLKeycode.UP, SDLKeycode.W -> {
                    if (menu.phase == HumanController.BuildPhase.WM_DIRECTION) {
                        if (human.chooseWarmachineDirection("up")) setBuildMenuBuilt()
                    } else { human.cycleBuildUnit(-1); updateBuildMenuMessage(human) }
                }
                SDLKeycode.DOWN, SDLKeycode.S -> {
                    if (menu.phase == HumanController.BuildPhase.WM_DIRECTION) {
                        if (human.chooseWarmachineDirection("down")) setBuildMenuBuilt()
                    } else { human.cycleBuildUnit(1); updateBuildMenuMessage(human) }
                }
                SDLKeycode.RETURN -> if (human.confirmBuild()) setBuildMenuBuilt()
                SDLKeycode.ESCAPE, SDLKeycode.B -> {
                    // Esc first returns from the direction picker to the unit page, then closes.
                    if (menu.phase == HumanController.BuildPhase.WM_DIRECTION) {
                        human.backBuildMenuToUnits()
                    } else {
                        human.cancelBuild()
                    }
                }
                else -> Unit
            }
            return
        }

        when (keycode) {
            SDLKeycode.ESCAPE -> {
                if (g.gameOver) { inMatch = false; return }
                human.selectUnit(null)
            }
            SDLKeycode.N -> human.dispatchNoAttack()
            SDLKeycode.E -> human.dispatchEndTurn()
            SDLKeycode.X -> {
                val unit = human.selectedUnit ?: return
                human.dispatchExtract(unit.x, unit.y)
            }
            SDLKeycode.B -> {
                if (human.openBuildMenu()) {
                    updateBuildMenuMessage(human)
                } else {
                    setBuildMessage("Nothing buildable here")
                }
            }
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

    /** Shows the current build-menu selection in the bottom recon bar. */
    private fun updateBuildMenuMessage(human: HumanController) {
        val menu = human.buildMenu ?: return
        val name = menu.current?.name ?: ""
        setBuildMessage("Build ${menu.index + 1}/${menu.units.size}: $name (Left/Right pick, Enter build, Esc cancel)")
    }

    /** Announces a successful build in the bottom recon bar. */
    private fun setBuildMenuBuilt() {
        setBuildMessage("Built ${(human()?.buildMenu?.current)?.name ?: "unit"}")
    }
}
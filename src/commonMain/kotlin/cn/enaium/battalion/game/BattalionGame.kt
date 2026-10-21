package cn.enaium.battalion.game

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.MapConfig
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.triggers.PlayerTrigger
import cn.enaium.battalion.game.ui.AudioManager
import cn.enaium.battalion.game.ui.Dialogue
import cn.enaium.battalion.game.ui.SaveManager
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
    /** Campaign generation of [missionIndex] (see [GameLoader.CAMPAIGN_IDS]). */
    private val campaignId: String = GameLoader.DEFAULT_CAMPAIGN,
    /** Team (index into the mission's teams) the player commands, or null for the mission's own. */
    private val playerTeamIndex: Int? = null,
) {
    private lateinit var window: SDLWindow
    private lateinit var renderer: SDLRenderer
    private lateinit var text: TextRenderer
    private lateinit var audio: AudioManager
    private lateinit var textures: TextureManager

    private var game: GameManager? = null
    private var render: BattalionRenderer? = null
    private var dialogue: Dialogue? = null

    /** Whether the end-of-game dialogue has been queued for the current match. */
    private var endGameDialoguePlayed = false

    /** True while the mission brief is on screen and its turn-start camera focus is pending. */
    private var briefFocusPending = false

    /**
     * True from the moment END_GAME fires until the match tears down. START_TURN events
     * queued behind END_GAME must not show the turn-intro (or switch music): gameOver is
     * only set later, when EndGameAction finalises.
     */
    private var matchEnding = false


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

    /** The previous match's spec, kept so "Restart" re-runs it without the menu. */
    private var lastCampaign: String = GameLoader.DEFAULT_CAMPAIGN
    private var lastMission: Int? = null
    private var lastPlayerTeam: Int? = null
    private var lastSkirmish: String? = null
    private var lastResume = false

    /** The map the editor asked to test (the editor's Test button), replayed by "Restart". */
    private var lastEditorTest: MapConfig? = null

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

        // Persist options/saves beside the assets (bind before the menu reads them).
        SaveManager.bind(assetsDir)
        applyOptions()

        // Pick the mission: an explicit CLI mission/skirmish skips the menu, otherwise show the
        // start screen so the player can choose between Campaign missions, Bootcamp missions,
        // the bonus map, resume a save, or quit. After a match ends (game over → ESC) we return
        // to this menu so the player can pick another mission without restarting the app.
        var menuExplicit = missionIndex != null || skirmishMap != null
        while (running) {
            // Hold the current match spec so "Restart" can re-run it without the menu.
            var campaign = campaignId
            var mission = missionIndex
            var playerTeam = playerTeamIndex
            var skirmish = skirmishMap
            var resumeSave = false
            var editorTest: MapConfig? = null

            if (restartRequested) {
                // Restart the previous match (menu skipped, same mission/save spec).
                restartRequested = false
                resumeSave = lastResume
                campaign = lastCampaign
                mission = lastMission
                playerTeam = lastPlayerTeam
                skirmish = lastSkirmish
                editorTest = lastEditorTest
            } else if (!menuExplicit) {
                // The mission-select pages reproduce the original SWF's 700×600 CampaignMenu
                // stage, so switch the window to that size while the menu is shown and restore
                // the game size afterwards.
                window.size = cn.enaium.sdl.SDLPoint(700, 600)
                applyRenderScale()
                // Menu BGM (the original MainMenu loops UIWAV on the "background" channel).
                // The in-match START_TURN handler switches to the faction loop when the game
                // starts; this call switches back when the match ends and we return here.
                audio.startMenuMusic()
                val start = StartScreen(assetsDir, renderer, text, textures,
                    onOptionsChanged = { applyOptions(); if (SaveManager.music) audio.startMenuMusic() })
                val selection = start.run()
                window.size = cn.enaium.sdl.SDLPoint(BattalionRenderer.WINDOW_W, BattalionRenderer.WINDOW_H)
                applyRenderScale()
                if (selection.quit) break
                if (selection.editor) {
                    // The editor has no music of its own: silence the menu loop while it is
                    // open, and bring it back when the menu returns.
                    audio.stopMusic()
                    val editorResult = openMapEditor(window, renderer, assetsDir)
                    window.size = cn.enaium.sdl.SDLPoint(700, 600)
                    applyRenderScale()
                    if (SaveManager.music) audio.startMenuMusic()
                    // The editor's Test button hands its map back for a skirmish; anything else
                    // (Quit, or closing the window) returns to this menu.
                    editorTest = editorResult?.testMap
                    if (editorTest == null) continue
                    // A tested map plays in the game's window (the editor left it at its own size).
                    window.size = cn.enaium.sdl.SDLPoint(
                        BattalionRenderer.WINDOW_W, BattalionRenderer.WINDOW_H,
                    )
                    applyRenderScale()
                }
                resumeSave = selection.resume
                campaign = selection.campaignId ?: campaign
                mission = selection.missionIndex
                playerTeam = selection.playerTeam ?: playerTeam
                skirmish = selection.skirmishMap
            }

            // Load the game. Resume reads the saved game (map re-loaded from its mapurl);
            // skirmish plays any map as a free-for-all; otherwise the chosen campaign's
            // mission at that campaign's generation (boot camp included — every level list
            // is a campaign document).
            //
            // The AI difficulty option (Easy/Normal/Hard) overrides the missions' AI teams.
            // The tutorial's menu has no difficulty panel, so its authored difficulty stays.
            val difficulty = if (campaign == GameLoader.BOOTCAMP_CAMPAIGN) null
                else SaveManager.aiDifficulty.coerceIn(0, 2)
            game = when {
                editorTest != null -> GameLoader.loadEditorTest(assetsDir, editorTest)
                resumeSave && SaveManager.hasSavedGame() ->
                    GameLoader.loadSavedGame(assetsDir, SaveManager.savedGame!!)
                skirmish != null -> GameLoader.loadSkirmish(assetsDir, skirmish)
                else -> GameLoader.loadCampaignMission(
                    assetsDir, mission ?: 0, campaign, difficulty, playerTeam = playerTeam,
                )
            }
            lastCampaign = campaign
            lastMission = mission
            lastPlayerTeam = playerTeam
            lastSkirmish = skirmish
            lastResume = resumeSave
            lastEditorTest = editorTest

            // The GameManager constructor clears the whole trigger event bus, including the
            // audio listeners load() registered — subscribe per match, only now that the bus
            // is stable, so START_TURN starts the BGM and unit actions play their SFX.
            audio.subscribe()
            // Turn-intro animation: fires on EVERY controller's turn (the player's AND the
            // AI's), with that faction's own colour — the original StartTurnAction creates
            // StartTurnAnimation for the active controller each turn.
            // END_GAME fires before gameOver is set (EndGameAction finalises later), so a
            // START_TURN queued behind it would still pass a gameOver check — latch the flag.
            TriggerManager.addListener(PlayerTrigger.END_GAME) {
                matchEnding = true
                render?.dismissPlayerIntro()
            }
            TriggerManager.addListener(PlayerTrigger.START_TURN) { trigger ->
                val pt = trigger as? PlayerTrigger ?: return@addListener
                val g = game ?: return@addListener
                // The match is over: no more turn-intro (a queued START_TURN may still fire
                // while the end-of-game dialogue / results screen is showing).
                if (matchEnding || g.gameOver) return@addListener
                val team = g.getController(pt.player)?.team ?: return@addListener
                render?.showPlayerIntro(team.teamName.ifEmpty { team.name })
            }
            render = BattalionRenderer(game!!, text, renderer, textures)
            render!!.setupTextures()
            render!!.resetGameOverNotification()
            render!!.onGameOverScreenShown = { audio.playEndGameMusic(game?.playerWon == true) }
            applyOptions()  // re-apply sound/music toggles onto this match's audio state
            if (resumeSave) {
                // A saved game resumes mid-turn: fire START_TURN for the saved controller
                // instead of the fresh StartGame/StartRound/StartTurn chain.
                game!!.resumeFromSave()
            } else {
                game!!.startGame()
            }

            // Load the mission briefing and play it over the start of the match (fresh games
            // only — a resume restores mid-match state and skips the intro dialogue). The
            // player-name intro is revealed as soon as the briefing box appears (not after it
            // ends), so it plays behind / around the dialogue while the brief is on screen.
            // The brief is part of campaign flow and always shows on a fresh game — the
            // "Dialogs" option only gates mid-game chat responses, not the mission brief.
            if (!resumeSave) {
                dialogue = Dialogue(game!!.map.script)
                render!!.dialogue = dialogue
                dialogue!!.play("startGame")
                // The turn-start focus is re-applied once the brief is dismissed: the script's
                // own startGame responses (highlight / scroll) drive the camera while the player
                // reads, exactly like the original — but there the turn-start targeting is
                // queued AFTER those responses, so the player ends up looking at their own force
                // when they gain control. Without this the view could be left on the enemy side.
                briefFocusPending = true
            }
            endGameDialoguePlayed = false
            matchEnding = false

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
        applyRenderScale(window, renderer)
    }

    companion object {
        /**
         * Sets the renderer scale from the window's logical size and its backing pixels (1x
         * unless the display is high-density). Falls back to 1:1 when either size is not
         * available yet — right after a resize, or on a driver without a real backing (the
         * headless dummy driver), a stale pixel size would otherwise scale the frame to nothing.
         */
        fun applyRenderScale(window: SDLWindow, renderer: SDLRenderer) {
            val size = window.size
            val px = window.sizeInPixels
            val scale = if (size.x > 0 && size.y > 0 && px.x > 0 && px.y > 0) {
                cn.enaium.sdl.SDLFloatPoint(
                    px.x.toFloat() / size.x,
                    px.y.toFloat() / size.y,
                )
            } else {
                cn.enaium.sdl.SDLFloatPoint(1f, 1f)
            }
            renderer.scale = scale
        }

        /**
         * Opens the map editor on the game's window: the editor needs a bigger canvas than the
         * 700×600 menu, so the window is grown first and the caller restores the menu size when
         * the editor exits. Shared by the game loop (main menu's Level Editor button) and the
         * `--guicheck` diagnostic, so both run the same path.
         */
        fun openMapEditor(
            window: SDLWindow,
            renderer: SDLRenderer,
            assetsDir: String,
            maxFrames: Int = 0,
            capturePath: String? = null,
            mapId: String? = null,
        ): cn.enaium.battalion.editor.MapEditorApp.Result? {
            window.size = cn.enaium.sdl.SDLPoint(
                cn.enaium.battalion.editor.MapEditorApp.WINDOW_W,
                cn.enaium.battalion.editor.MapEditorApp.WINDOW_H,
            )
            applyRenderScale(window, renderer)
            return cn.enaium.battalion.editor.MapEditorApp.run(
                window, renderer, assetsDir,
                mapId = mapId,
                maxFrames = maxFrames,
                capturePath = capturePath,
                onStatus = { println(it) },
            )
        }
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
            // The match ended: play the mission's end-of-game dialogue (victory/defeat
            // voice-lines from the map script's `endGame` events) before the results
            // screen appears, exactly like the original ChatWindow sequence.
            val g = game
            if (g != null && g.gameOver && !endGameDialoguePlayed) {
                endGameDialoguePlayed = true
                if (dialogue != null && dialogue!!.play("endGame")) {
                    render?.dialogue = dialogue
                }
            }
            if (briefFocusPending && dialogue?.isActive() != true) {
                briefFocusPending = false
                game?.focusActiveForce()
            }
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
        // The renderer owns the camera (it can be mid-tile while following a unit), so it maps
        // the screen point to a tile.
        return render?.mapTileAt(sx, sy)
    }

    private fun onMouseDown(button: Int, sx: Int, sy: Int) {
        val g = game ?: return

        // Results screen is modal after game over: only the Finish button closes it (the SWF's
        // GameOverMenu mainMenuButton); everything else is ignored.
        if (g.gameOver) {
            if (button == 1) {
                val hit = render?.gameOverFinishHit
                if (hit != null && sx >= hit.x && sx < hit.x + hit.width && sy >= hit.y && sy < hit.y + hit.height) {
                    inMatch = false
                }
            }
            return
        }

        // In-game options menu is modal: it swallows every click while open (toggles and
        // the save/quit/restart actions route to it; nothing reaches the map below).
        if (render?.inGameMenuOpen == true) {
            if (button == 1) handleInGameMenuClick(sx, sy)
            return
        }

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

        // Floating unit action menu: clicking an action in the open bar dispatches it.
        // (The "more options" marker above a selected unit no longer opens the menu — the
        // menu opens by clicking the selected unit again.) These take priority over map
        // clicks so the bar is not occluded by tile selection.
        if (button == 1) {
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
        //    Clicking the ALREADY-selected unit opens its floating action menu instead.
        val unit = tile.unit
        if (unit != null && unit.controller === g.activeController && unit.isSelectable()) {
            if (unit == selected && unit.isAvailable()) {
                human.toggleUnitOptions()
            } else {
                human.selectUnit(if (unit.isAvailable()) unit else null)
            }
            return
        }

        // 3b. Clicking an ENEMY unit shows its move/attack ranges (display only — the unit is
        //     not selected and no action can be issued on it). selectUnit(null) first: it
        //     clears the preview, so the preview must be set AFTER it.
        if (unit != null && unit.controller !== g.activeController) {
            human.selectUnit(null)
            human.previewUnit = unit
            render?.setFocusTile(tile)
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
            BattalionRenderer.PanelButton.OPTIONS -> {
                // Entering the menu exits the move/attack mode (deselect + close unit bar).
                human.selectUnit(null)
                human.closeUnitOptions()
                render?.setInGameMenuOpen(true)
            }
            BattalionRenderer.PanelButton.END_TURN -> human.dispatchEndTurn()
        }
    }

    /** Routes a click in the in-game options menu (toggles / resume / save / quit / restart). */
    private fun handleInGameMenuClick(sx: Int, sy: Int) {
        val render = render ?: return
        // Quit confirmation is modal: only Yes/No/Close buttons are clickable.
        if (render.showQuitConfirm) {
            when (render.quitConfirmActionAt(sx, sy)) {
                "quitYes" -> {
                    render.setQuitConfirm(false)
                    render.setInGameMenuOpen(false)
                    // SWF IngameOptionsMenu.quitAndNoSaveHandler → quit() →
                    // GameManager.surrenderLocalPlayer(): quitting forfeits the mission (no save),
                    // so the match ends as a defeat and the results screen shows it.
                    game?.surrenderLocalPlayer()
                }
                "quitNo" -> render.setQuitConfirm(false)
            }
            return
        }
        // Quick Help is a full-panel overlay: ANY click inside it dismisses it (SWF behavior).
        if (showQuickHelp) {
            render.showQuickHelp = false
            showQuickHelp = false
            return
        }
        when (render.inGameMenuActionAt(sx, sy)) {
            "sound" -> { SaveManager.sound = !SaveManager.sound; applyOptions() }
            "music" -> { SaveManager.music = !SaveManager.music; applyOptions() }
            "dialog" -> { SaveManager.dialog = !SaveManager.dialog }
            "help" -> {
                render.showQuickHelp = true
                showQuickHelp = true
            }
            "resume" -> render.setInGameMenuOpen(false)
            "save" -> {
                val g = game ?: return
                val save = g.toSave()
                SaveManager.savedGame = save
                render.setInGameMenuOpen(false)
                inMatch = false        // back to the menu; Continue is now enabled
            }
            "quit" -> render.setQuitConfirm(true)
            "restart" -> {
                render.setInGameMenuOpen(false)
                restartRequested = true
                inMatch = false
            }
            else -> Unit
        }
    }

    /** True when the player chose Restart in the in-game menu; [startMatch] re-runs the same game. */
    private var restartRequested = false

    /** True while the in-game menu's "Quick Help" overlay is shown (dismissed by clicking it). */
    private var showQuickHelp = false

    /** Applies the persisted sound/music toggles to the audio manager. */
    private fun applyOptions() {
        audio.effectsEnabled = SaveManager.sound
        audio.musicEnabled = SaveManager.music
        if (SaveManager.music) {
            // Re-enabling music restarts the appropriate loop: the faction's turn music during
            // a match, the menu loop otherwise (the SWF's startMusic is a fresh createLoop).
            if (game != null) audio.startTurnMusic()
            else audio.startMenuMusic()
        } else {
            audio.stopMusic()
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
        val tile = g.attackPositionFor(unit, target) ?: return null
        return tile.x to tile.y
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
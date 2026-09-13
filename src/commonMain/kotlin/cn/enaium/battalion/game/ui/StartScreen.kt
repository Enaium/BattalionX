package cn.enaium.battalion.game.ui

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.GameLoader.MissionInfo
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLTexture

/**
 * The game's menu system, covering the original SWF's main menu and mission select.
 *
 * Flow mirrors the original `MenuManager`/`MainMenu`/`CampaignMenu`:
 *  - Main menu: title art, a row of tile-sheet buttons (Continue / Single Player /
 *    Options), plus corner Credits and Bonus Map buttons and a main-menu background.
 *    "Single Player" advances to the mission-select screen.
 *  - Mission select: a single campaign that serves BOTH the Campaign and the Bootcamp
 *    (like the original `CampaignMenu`, invoked with `CampaignMenuData("Campaign")` or
 *    `("Bootcamp")`). It lists each level with prev/next arrows, shows the mission brief
 *    and name, and Start / Back buttons.
 *
 * The chosen selection is returned to [run]'s caller, which loads the corresponding game.
 * The screen runs its own SDL event loop so it can react to clicks and keys independently of
 * the in-match loop.
 */
class StartScreen(
    private val assetsDir: String,
    private val renderer: SDLRenderer,
    private val text: TextRenderer,
    private val textures: TextureManager,
    /** Called after any option toggle / clear-data so the caller can apply sound+music state. */
    private val onOptionsChanged: () -> Unit = {},
) {

    /**
     * The player's choice: a mission of a named campaign generation, a skirmish map, resume a
     * save, or quit.
     */
    data class Selection(
        /** Campaign document name (e.g. `"vengeance"`, `"nemesis"`, `"bootcamp"`). */
        val campaignId: String? = null,
        val missionIndex: Int? = null,
        /** Team (index into the mission's teams) the player chose to command, or null. */
        val playerTeam: Int? = null,
        val skirmishMap: String? = null,
        /** True when the player chose "Continue" on the main menu to resume a saved game. */
        val resume: Boolean = false,
        val quit: Boolean = false,
    ) {
        companion object {
            val QUIT = Selection(quit = true)
            val RESUME = Selection(resume = true)
        }
    }

    private enum class Screen { MAIN, MISSIONS, CREDITS, OPTIONS }

    companion object {
        /** Campaign generation switch row (mission select): y, height, side margin, gap. */
        const val SWITCH_ROW_Y = 505
        const val SWITCH_ROW_H = 34
        const val SWITCH_ROW_MARGIN = 18
        const val SWITCH_ROW_GAP = 6

        /** Height of a BattalionPanel title band (the heading's height + 5 in the original). */
        private const val TITLE_BAND_H = 21

        /** The chapter-title panel (SWF `CampaignMenu`: 350×70 at y=200) with its arrow buttons. */
        private const val CHAPTER_Y = 200
        private const val CHAPTER_H = 70
        private const val CHAPTER_W = 350
        private const val MISSION_ARROW_W = 33

        /**
         * The credits roll (`CreditsMenu`): the episode name, the Urbansquall team, then the
         * Kotlin Multiplatform port. Blank entries are the original's spacing lines.
         */
        private val CREDITS_LINES = listOf(
            "Battalion: Vengeance", "",
            "", "An Urbansquall production", "",
            "", "", "",
            "Game Design:", "Panayoti Haritatos", "Andrew Pellerano", "",
            "", "Art:", "Tim Wendorf", "",
            "", "Writing:", "Michael Scarpelli", "",
            "", "Sound & Music:", "Matt Sayre", "Zack Parrish", "",
            "", "Programming:", "Christopher Rhodes", "Panayoti Haritatos",
            "Andrew Pellerano", "Marshall Powers", "",
            "", "", "",
            "Thank you for supporting", "independent developers!", "",
            "", "", "",
            "Kotlin Multiplatform port:", "Enaium",
            "github.com/Enaium/BattalionX", "",
            "Kotlin · SDL3 · kotlinx.serialization",
        )

        /**
         * Credits roll speed (pixels per drawn frame). The original moved the text 0.2px per
         * frame at ~30fps; the port's frame rate is higher, and the roll reads better faster.
         */
        private const val CREDITS_SCROLL_PER_FRAME = 0.5f

        /** Mission-select minimap size (`CampaignMenu.MINIMAP_WIDTH`/`HEIGHT`). */
        private const val MM_WIDTH = 140
        private const val MM_HEIGHT = 184

        /**
         * The difficulty marker colours and alphas (`DifficultyPanel.colors`): Easy green,
         * Normal a translucent dark yellow, Hard red.
         */
        private val DIFFICULTY_COLORS = arrayOf(
            DifficultyColor(SDLColor(0x00, 0xFF, 0x00), 0xFF),
            DifficultyColor(SDLColor(0xCC, 0xCC, 0x00), 0xCC),
            DifficultyColor(SDLColor(0xFF, 0x00, 0x00), 0xFF),
        )
    }

    /** A difficulty's marker colour and alpha (`DifficultyPanel` colours[]). */
    private class DifficultyColor(val color: SDLColor, val alpha: Int)

    /** True while the "Clear data?" confirmation dialog is open (over the Options panel). */
    private var confirmingClear = false

    private var screen = Screen.MAIN

    /**
     * Every level list the mission select offers, in display order: the four game generations
     * (Nemesis, Skirmish, Ghosts, Vengeance) followed by the Boot Camp tutorial. The switch row
     * draws one button per entry; the chapter panel, brief and medal row follow the active one.
     */
    private var campaigns: List<GameLoader.Campaign> = emptyList()
    private var campaignIndex = 0
    private var selected = 0
    private var running = true
    private var mouseX = 0
    private var mouseY = 0
    private var chosenSkirmish: String? = null
    private var resumeRequested = false

    private data class Btn(val key: String, val frameW: Int, val frameH: Int, val x: Int, val y: Int, val action: () -> Unit)

    private val buttons = mutableListOf<Btn>()

    /** Cached minimap preview for the currently selected mission. */
    private class MapPreview(
        val cols: Int,
        val rows: Int,
        /** Per-cell terrain swatch name (empty = sea) for colouring. */
        val names: Array<Array<String>>,
        /** Per-cell owning team (0 = neutral). */
        val teams: Array<IntArray>,
    )

    private var preview: MapPreview? = null
    private var previewKey: Pair<String, Int>? = null

    /**
     * The team the player chose to command, per visited mission ("<campaign>#<mission>").
     * Missions default to the side their author assigned to the player; picking the other side
     * swaps the human/AI roles (see [GameLoader.withPlayerTeam]).
     */
    private val playerTeamChoices = mutableMapOf<String, Int>()

    /** How far the credits text has scrolled up, in pixels (CreditsMenu's rolling message). */
    private var creditsScroll = 0f

    /** Runs the menu loop and returns the player's selection. */
    fun run(): Selection {
        reloadCampaigns()
        if (campaigns.isEmpty()) {
            return Selection(campaignId = GameLoader.DEFAULT_CAMPAIGN, missionIndex = 0)
        }

        running = true
        screen = Screen.MAIN
        selected = 0
        while (running) {
            pollEvents()
            draw()
            SDL.delay(16)
        }
        if (resumeRequested) return Selection.RESUME
        if (chosenSkirmish != null) return Selection(skirmishMap = chosenSkirmish)
        val campaign = campaigns.getOrNull(campaignIndex)
        return when (screen) {
            Screen.MAIN, Screen.CREDITS, Screen.OPTIONS -> Selection.QUIT
            Screen.MISSIONS -> Selection(
                campaignId = campaign?.id,
                missionIndex = selected.coerceIn(0, maxOf(0, (campaign?.missions?.size ?: 1) - 1)),
                playerTeam = campaign?.missions?.getOrNull(selected)?.let { playerTeamIndex(it) },
            )
        }
    }

    /** Loads every campaign document the mission select offers (generations + boot camp). */
    private fun reloadCampaigns() {
        campaigns = GameLoader.findCampaigns(assetsDir) +
            listOfNotNull(GameLoader.findCampaign(assetsDir, GameLoader.BOOTCAMP_CAMPAIGN))
        if (campaignIndex !in campaigns.indices) {
            // Open on the port's original campaign (Vengeance) unless told otherwise.
            campaignIndex = campaigns.indexOfFirst { it.id == GameLoader.DEFAULT_CAMPAIGN }
                .coerceAtLeast(0)
        }
    }

    private fun pollEvents() {
        while (true) {
            val event = SDL.pollEvent() ?: break
            when (event) {
                is SDLEvent.Quit -> { running = false }
                is SDLEvent.Window ->
                    if (event.type == cn.enaium.sdl.SDLWindowEventType.CLOSE_REQUESTED) running = false
                is SDLEvent.Key -> {
                    if (event.down && !event.repeat) onKey(event.keycode)
                }
                is SDLEvent.MouseMotion -> { mouseX = event.x.toInt(); mouseY = event.y.toInt() }
                is SDLEvent.MouseButton ->
                    if (event.down) onMouseClick(event.x.toInt(), event.y.toInt())
                else -> Unit
            }
        }
    }

    private fun onKey(keycode: Int) {
        when (keycode) {
            SDLKeycode.UP, SDLKeycode.W -> move(-1)
            SDLKeycode.DOWN, SDLKeycode.S -> move(1)
            SDLKeycode.RETURN, SDLKeycode.SPACE -> activateSelected()
            SDLKeycode.ESCAPE -> when {
                confirmingClear -> confirmingClear = false
                screen == Screen.MAIN -> running = false
                else -> screen = Screen.MAIN
            }
            else -> Unit
        }
    }

    /** The missions of the campaign currently shown (empty on the main/options screens). */
    private fun missionList(): List<MissionInfo> = campaigns.getOrNull(campaignIndex)?.missions.orEmpty()

    /** A mission is playable when its map file is present (every level is unlocked). */
    private fun isPlayable(i: Int): Boolean {
        val list = missionList()
        if (i !in list.indices) return false
        return GameLoader.isMissionLoadable(assetsDir, list[i])
    }

    /** Moves the selection, skipping missions whose map file is missing. */
    private fun move(delta: Int) {
        val list = missionList()
        if (list.isEmpty()) return
        var i = selected
        repeat(list.size) {
            i = (i + delta + list.size) % list.size
            if (isPlayable(i)) { selected = i; return }
        }
    }

    /** Points the selection at the first playable mission of the current campaign. */
    private fun snapToLoadable() {
        val list = missionList()
        if (list.isEmpty()) return
        val first = list.indices.firstOrNull { isPlayable(it) }
        if (first != null) selected = first
    }

    /** Switches the mission select to the campaign at [index] (the generation switch row). */
    private fun openCampaign(index: Int) {
        if (index !in campaigns.indices) return
        campaignIndex = index
        selected = 0
        snapToLoadable()
        previewKey = null
    }

    private fun activateSelected() {
        when (screen) {
            Screen.MAIN, Screen.CREDITS, Screen.OPTIONS ->
                { /* Enter not used on these screens; click buttons instead */ }
            Screen.MISSIONS ->
                // Only start a mission whose map is present; otherwise give feedback.
                if (isPlayable(selected)) running = false
        }
    }

    private fun onMouseClick(x: Int, y: Int) {
        // Clickable buttons drawn this frame. Iterate LAST-drawn first so an overlay (e.g. the
        // Options panel over the main menu) takes priority over the buttons behind it.
        for (b in buttons.asReversed()) {
            if (x >= b.x && x < b.x + b.frameW && y >= b.y && y < b.y + b.frameH) {
                b.action()
                return
            }
        }
        // Medal-row clicks select a mission (medals sit at y=131, centred on the stage).
        if (screen == Screen.MISSIONS) {
            val list = missionList()
            val medalW = 30
            val medalPadding = 8
            val space = list.size * medalW + (list.size - 1) * medalPadding
            val startX = 350 - space / 2
            if (y >= 131 && y < 131 + 30) {
                for (i in list.indices) {
                    val mx = startX + i * (medalW + medalPadding)
                    if (x >= mx && x < mx + medalW) {
                        if (isPlayable(i)) selected = i
                        return
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Drawing                                                            */
    /* ------------------------------------------------------------------ */

    private fun draw() {
        renderer.drawColor = SDLColor(12, 14, 20)
        renderer.clear()
        buttons.clear()

        // The main menu paints its full background+title image; the mission-select pages paint
        // the SWF CampaignMenu composition themselves (header + backdrop), so no shared backdrop
        // is drawn for those. The Options and Credits overlays sit ON the main menu, so its
        // backdrop stays visible behind them (CreditsMenu dims it with its own 60% overlay).
        if (screen == Screen.MAIN || screen == Screen.OPTIONS || screen == Screen.CREDITS) {
            drawMainBackdrop()
        }

        when (screen) {
            Screen.MAIN -> drawMainMenu()
            Screen.CREDITS -> drawCreditsMenu()
            Screen.OPTIONS -> drawOptionsMenu()
            else -> drawMissionSelect()
        }

        // Clear-data confirmation sits on top of the Options panel (SWF ConfirmMenu modal).
        if (screen == Screen.OPTIONS && confirmingClear) drawClearConfirm()

        renderer.present()
    }

    /**
     * Draws the "Clear data?" confirmation dialog (SWF ConfirmMenu): a dimmed overlay with a
     * BattalionPanel, the question text, and Yes / No buttons.
     */
    private fun drawClearConfirm() {
        // Dimmed overlay. The renderer's fill needs BLEND for the alpha to apply.
        val prevBlend = renderer.blendMode
        renderer.blendMode = SDLBlendMode.BLEND
        renderer.drawColor = SDLColor(0, 0, 0, 150)
        renderer.fillRect(SDLRect(0, 0, 700, 600))
        renderer.blendMode = prevBlend

        val w = 410
        val h = 200
        val x = (700 - w) / 2
        val y = (600 - h) / 2
        drawPanel(x, y, w, h, "frame2", 32, "spotted_texture")

        // Yes / No (SWF yesButton / noButton TileSheetButtons in the panel's top-left corner).
        drawButton("yesButton", 126, 71, x + 12, y + 18, {
            SaveManager.clearAll()
            SaveManager.sound = true
            SaveManager.music = true
            SaveManager.dialog = true
            onOptionsChanged()
            confirmingClear = false
        })
        drawButton("noButton", 126, 71, x + 12 + 126 + 8, y + 18, { confirmingClear = false })
        // Close (no change) next to No, like the SWF closeButton.
        drawButton("closeButton", 126, 71, x + 12 + 2 * (126 + 8), y + 18, { confirmingClear = false })

        // Text sits BELOW the button row (SWF ConfirmMenu puts it in a textBox under the
        // buttons) so the 71px-tall buttons never cover it. SWF text colour is 0 (black).
        text.drawCentered("Are you sure you want to clear", x + w / 2, y + 108, 15, SDLColor(20, 20, 20))
        text.drawCentered("your saved game data?", x + w / 2, y + 132, 15, SDLColor(20, 20, 20))
    }

    /** Renders a single frame of the current menu screen without running the event loop.
     *  Used by the headless menu-screenshot diagnostic. */
    fun renderFrameForCapture() {
        draw()
    }

    /** Diagnostic: opens the "Clear data?" confirmation over the Options screen for captures. */
    fun setConfirmingClearForCapture(value: Boolean) {
        confirmingClear = value
    }

    /**
     * Sets the initial screen (e.g. straight to a mission list) for diagnostics. `"campaign"`
     * shows the first generation, or the one named by `"campaign:<id>"` (e.g.
     * `"campaign:nemesis"`); `"bootcamp"` shows the tutorial list.
     */
    fun setScreenForCapture(s: String) {
        reloadCampaigns()
        val target = when {
            s.startsWith("campaign:") -> s.substringAfter(':').substringBefore('#')
            s == "bootcamp" -> GameLoader.BOOTCAMP_CAMPAIGN
            else -> null
        }
        val targetMission = s.substringAfter('#', "").toIntOrNull()
        screen = when (s) {
            "main" -> Screen.MAIN
            "options" -> Screen.OPTIONS
            "credits" -> Screen.CREDITS
            else -> Screen.MISSIONS
        }
        if (target != null) {
            val index = campaigns.indexOfFirst { it.id == target }
            if (index >= 0) openCampaign(index)
        }
        if (targetMission != null && targetMission in missionList().indices) selected = targetMission
    }

    /** Diagnostic: forwards a synthetic click at ([x], [y]) to the current screen. */
    fun clickForCapture(x: Int, y: Int) {
        draw()   // a frame must be drawn first: buttons register as they are painted
        onMouseClick(x, y)
    }

    /** Diagnostic: positions the credits roll (the text starts below the panel and scrolls up). */
    fun setCreditsScrollForCapture(value: Float) {
        creditsScroll = value
    }

    /**
     * The window's size in LOGICAL drawing coordinates.
     *
     * [SDLRenderer.outputSize] is the render target's size in pixels, which is 2× the window
     * size on a high-pixel-density (Retina) display — and the renderer is scaled by that same
     * 2× factor ([SDL scale]) so all drawing uses logical units. Dividing the pixel size by the
     * scale recovers the logical window size (e.g. 700×600 for the menu), which the backdrop
     * and header tiling must stretch over.
     */
    private fun logicalWindowSize(): Pair<Int, Int> {
        val s = renderer.scale
        val sx = if (s.x > 0f) s.x else 1f
        val sy = if (s.y > 0f) s.y else 1f
        return (renderer.outputSize.x / sx).toInt() to (renderer.outputSize.y / sy).toInt()
    }

    private fun drawBackdrop() {
        // Tile the wood over the renderer's real drawing area (not a hardcoded 800×600) so the
        // background always fills the whole window no matter its size.
        val (winW, winH) = logicalWindowSize()
        val wood = textures.texture("wood_texture")
        if (wood != null) {
            val w = wood.size.x.toInt()
            val h = wood.size.y.toInt()
            var y = 0
            while (y < winH) {
                var x = 0
                while (x < winW) {
                    renderer.renderTexture(wood, dst = SDLFRect(x.toFloat(), y.toFloat(),
                        minOf(w, winW - x).toFloat(), minOf(h, winH - y).toFloat()))
                    x += w
                }
                y += h
            }
        }
        // Vignette (BLEND so the 35% alpha darkens instead of covering the tiled wood).
        val prevBlend = renderer.blendMode
        renderer.blendMode = SDLBlendMode.BLEND
        renderer.drawColor = SDLColor(0, 0, 0, 90)
        renderer.fillRect(SDLRect(0, 0, winW, winH))
        renderer.blendMode = prevBlend
    }

    /**
     * Draws the main-menu backdrop: the `title_3` image is the FULL background (background +
     * title art together), so it is stretched across the whole window rather than drawn as a
     * small title bar over the wood texture.
     */
    private fun drawMainBackdrop() {
        val (winW, winH) = logicalWindowSize()
        val title = textures.texture("title_3")
        if (title != null) {
            // Stretch the full background+title image to cover the entire window.
            renderer.renderTexture(
                title,
                dst = SDLFRect(0f, 0f, winW.toFloat(), winH.toFloat()),
            )
        } else {
            // No full background image: fall back to the wood backdrop.
            drawBackdrop()
        }
    }

    private fun drawButton(key: String, frameW: Int, frameH: Int, x: Int, y: Int, action: () -> Unit, disabled: Boolean = false) {
        val tex = textures.texture(key) ?: return
        val hover = !disabled && mouseX >= x && mouseX < x + frameW && mouseY >= y && mouseY < y + frameH
        // Frame index: 0=disabled/up, 1=over(hover), 2=down, 3=down(state). TileSheetButton maps
        // disabled=0, up=1, over=2, down=3. We show: disabled -> 0, hover -> 2, else 1.
        val frame = when {
            disabled -> 0
            hover -> 2
            else -> 1
        }
        renderer.renderTexture(
            tex,
            src = SDLFRect((frame * frameW).toFloat(), 0f, frameW.toFloat(), frameH.toFloat()),
            dst = SDLFRect(x.toFloat(), y.toFloat(), frameW.toFloat(), frameH.toFloat()),
        )
        if (!disabled) buttons.add(Btn(key, frameW, frameH, x, y, action))
    }

    /**
     * Draws a mission-select toggle (Boot Camp / generation buttons, and the bottom action
     * row) in the same VistaButton material as the in-game menu's four actions (Quick Help /
     * Quit / ...): a btn_tile9 nine-slice body with the btn_overlay gloss, a black centred
     * title and a hover brighten. Mirrors
     * [cn.enaium.battalion.game.BattalionRenderer]'s drawVistaButton; the sprite-layer
     * bootCampButton/campaignButton art is NOT used here (it doesn't match that material).
     *
     * [active] marks the mission list currently shown: that button keeps a warm highlight so
     * the generation row shows the current selection.
     */
    private fun drawVistaToggleButton(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        title: String,
        active: Boolean = false,
        action: () -> Unit,
    ) {
        val hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        // btn_tile9 nine-slice with Tile9Data rect (6,8,12,8): 24×24 sheet, corner 6 left/top,
        // 6 right/bottom (right = 12+6, bottom = 8+8) -> srcCols 0/6/18, srcRows 0/8/16.
        val tiles = textures.texture("btn_tile9")
        if (tiles != null) {
            // Tile9Data(new Bitmap(btn_tile9), new Rectangle(6,8,12,8)): tiled 9-slice like
            // the original Tile9 (corners native, edges/centre repeat) — not stretched.
            drawTiled9Grid(tiles, x, y, w, h, left = 6, top = 8, right = 18, bottom = 16)
        } else {
            renderer.drawColor = if (hover) SDLColor(150, 150, 170) else SDLColor(80, 84, 100)
            renderer.fillRect(SDLRect(x, y, w, h))
        }
        // Black centred title (VistaButtonState HeadingTextField).
        text.drawCentered(title, x + w / 2, y + (h - 18) / 2, 16, SDLColor(20, 20, 20))
        val prevBlend = renderer.blendMode
        renderer.blendMode = SDLBlendMode.BLEND
        // Active campaign: warm highlight (same tint as the difficulty panel's selected row).
        if (active) {
            renderer.drawColor = SDLColor(255, 220, 120, 90)
            renderer.fillRect(SDLRect(x, y, w, h))
        }
        // Hover brighten (SWF ColorTransform 1.1): blend-mode alpha wash, not opaque white.
        if (hover) {
            renderer.drawColor = SDLColor(255, 255, 255, 45)
            renderer.fillRect(SDLRect(x, y, w, h))
        }
        renderer.blendMode = prevBlend
        buttons.add(Btn("toggle", w, h, x, y, action))
    }

    private fun drawMainMenu() {
        // Note: the title art is part of the full background image and is drawn by
        // drawMainBackdrop(); nothing extra is drawn here for the title.

        // Button row (the original MainMenu's tab row): Continue / Single Player / Multiplayer
        // (Kongregate — disabled) / Options / Level Editor, spread evenly across the stage.
        val buttonsDef = listOf(
            Triple("restoreButton", 126, 71),      // Continue
            Triple("singleplayerButton", 126, 71), // Single Player
            Triple("multiplayerButton", 126, 71),  // Multiplayer (Kongregate — disabled)
            Triple("optionsMainButton", 126, 71),  // Options
            Triple("levelEditorButton", 126, 71),  // Level Editor (not ported)
        )
        val btnW = 126
        val btnH = 71
        val y = 380
        // MainMenu: leftOver = WIDTH - n·buttonWidth; separation = leftOver / (n + 1).
        val separation = (700 - buttonsDef.size * btnW) / (buttonsDef.size + 1)
        var x = separation

        // Continue (restore): enabled only when a saved game exists.
        val hasSave = SaveManager.hasSavedGame()
        drawButton("restoreButton", btnW, btnH, x, y, { if (hasSave) { resumeRequested = true; running = false } }, disabled = !hasSave); x += separation + btnW
        drawButton("singleplayerButton", btnW, btnH, x, y, { openMissionSelect() }); x += separation + btnW
        drawButton("multiplayerButton", btnW, btnH, x, y, { /* multiplayer exclude */ }, disabled = true); x += separation + btnW
        drawButton("optionsMainButton", btnW, btnH, x, y, { screen = Screen.OPTIONS }); x += separation + btnW
        // Level Editor: the original opens the map editor, which this build does not port
        // (the note below says so) — the button is drawn and clickable, but does nothing.
        drawButton("levelEditorButton", btnW, btnH, x, y, { /* level editor not ported */ })

        // Corner buttons: credits top-right (MainMenu.x = WIDTH - width, y = 0) and the bonus
        // map top-left, both 126×71 like every other main-menu button. Opening the credits
        // restarts the roll (the original sets the text field's y in CreditsMenu.pushed).
        drawButton("creditsButton", 126, 71, 700 - 126, 0, {
            creditsScroll = 0f
            screen = Screen.CREDITS
        })
        drawButton("bonusMapButton", 126, 71, 8, 8, { loadBonusMap() })

        text.drawCentered("Multiplayer / Level Editor excluded in this build", 350, 540, 14, SDLColor(150, 150, 165))
        text.drawCentered("ESC: Quit", 350, 566, 14, SDLColor(140, 140, 160))
    }

    /**
     * Draws the Credits screen, reproducing the original `CreditsMenu`: a 60%-alpha black
     * overlay, a wood BattalionPanel (400×300) at (150, 150) holding a 350×200 spotted panel
     * with the credits text scrolling up, and the close button at the panel's top-right.
     */
    private fun drawCreditsMenu() {
        // Dim the main menu behind the panel (CreditsMenu's 60% black overlay). The renderer's
        // fill needs BLEND for the alpha to apply — otherwise the dim would be opaque.
        val prevBlend = renderer.blendMode
        renderer.blendMode = SDLBlendMode.BLEND
        renderer.drawColor = SDLColor(0, 0, 0, 153)
        renderer.fillRect(SDLRect(0, 0, 700, 600))
        renderer.blendMode = prevBlend

        val frameX = 150
        val frameY = 150
        val frameW = 400
        val frameH = 300
        drawPanel(frameX, frameY, frameW, frameH, "frame1", 16, "wood_texture")

        val boxW = 350
        val boxH = 200
        val boxX = frameX + 25
        val boxY = frameY + 75
        drawPanel(boxX, boxY, boxW, boxH, "frame2", 32, "spotted_texture")

        // Scrolling credits: the text starts below the box and rolls up, wrapping once it has
        // fully left it (see CREDITS_SCROLL_PER_FRAME).
        creditsScroll += CREDITS_SCROLL_PER_FRAME
        val lines = CREDITS_LINES
        val lineH = 18
        val totalH = lines.size * lineH
        if (creditsScroll > totalH + boxH) creditsScroll = 0f
        val firstY = boxY + 5 + boxH - creditsScroll
        for (i in lines.indices) {
            val ly = firstY + i * lineH
            // Cull the lines outside the box (the original clips them with a mask).
            if (ly < boxY + 4 || ly > boxY + boxH - lineH) continue
            text.drawCentered(lines[i], boxX + boxW / 2, ly.toInt(), 14, SDLColor(0, 0, 0))
        }

        // Close button at the frame's top-right (CreditsMenu: x = frame.x + WIDTH - w - 5,
        // y = frame.y - h / 2 + 10).
        drawButton("closeButton", 126, 71, frameX + frameW - 126 - 5, frameY - 71 / 2 + 10,
            { screen = Screen.MAIN })
    }

    /**
     * Draws the Options screen: sound / music / dialog toggles, a clear-data button, and Back.
     * Mirrors the SWF `OptionsMenu` (a modal BattalionPanel overlay) — the port keeps the same
     * toggles and persistence keys through [SaveManager], and applies sound/music immediately
     * via the audio manager hooks set up in [run]'s caller.
     */
    private fun drawOptionsMenu() {
        // See-through: the main menu stays visible BEHIND the options panel (the SWF shows
        // OptionsMenu as an overlay on the main menu, not over a black backdrop). Buttons are
        // added afterwards so onMouseClick's reversed iteration gives them priority.
        drawMainMenu()
        // (The main backdrop was already painted by draw() so the menu art shows through.)
        // The original OptionsMenu's panel carries no heading (its toggles sit at the panel's
        // top-left), so none is drawn here either.
        drawPanel(55, 238, 590, 125, "frame1", 16, "spotted_texture")

        data class ToggleRow(val label: String, val offKey: String, val onKey: String, val toggle: () -> Unit)
        val rows = listOf(
            ToggleRow("Sound", "soundButton", "soundButtonToggled", { SaveManager.sound = !SaveManager.sound; onOptionsChanged() }),
            ToggleRow("Music", "musicButton", "musicButtonToggled", { SaveManager.music = !SaveManager.music; onOptionsChanged() }),
            ToggleRow("Dialogs", "dialogButton", "dialogButtonToggled", { SaveManager.dialog = !SaveManager.dialog; onOptionsChanged() }),
        )

        // The four buttons sit in ONE horizontal row, centred on the panel's vertical
        // centreline (instead of the SWF's left-anchored row).
        val toggleW = 76
        val toggleH = 89
        val gap = 25
        val rowW = 4 * toggleW + 3 * gap   // sound + music + dialogs + clear data
        val x0 = 55 + (590 - rowW) / 2
        val y0 = 238 + (125 - toggleH) / 2 // vertically centred in the 125px panel
        var x = x0
        for ((label, offKey, onKey, toggle) in rows) {
            val enabled = when (label) {
                "Sound" -> SaveManager.sound
                "Music" -> SaveManager.music
                else -> SaveManager.dialog
            }
            drawButton(if (enabled) onKey else offKey, toggleW, toggleH, x, y0, toggle)
            x += toggleW + gap
        }

        // Clear data completes the horizontal row (SWF clearHandler pushes a confirm menu).
        drawButton("clearDataButton", toggleW, toggleH, x, y0, {
            confirmingClear = true
        })

        // Close (back) button — top-right of the panel, like the SWF closeButton.
        drawButton("closeButton", 126, 71, 55 + 590 - 126 - 4, 238 - 71 / 2, { screen = Screen.MAIN })
    }

    /** "Single Player" from the main menu opens the mission select (generations + boot camp). */
    private fun openMissionSelect() {
        reloadCampaigns()
        screen = Screen.MISSIONS
        snapToLoadable()
    }

    /** The main-menu bonus map button: plays the Kongregate bonus map as a skirmish. */
    private fun loadBonusMap() {
        if (GameLoader.findMapPublic(assetsDir, "ep3/Kongregate_Bonus_Map") != null) {
            chosenSkirmish = "ep3/Kongregate_Bonus_Map"
            running = false
        }
    }

    /**
     * Draws the mission-select page, reproducing the original SWF `CampaignMenu` (a 700×600
     * stage):
     *
     *  - Campaign header image across the top 175px (the generation's `headerBackground`,
     *    `default_header` when it has none), with the `bg_bootcamp` backdrop below it.
     *  - A medal row near y=131 (medalBackground + medal sheet frames).
     *  - A chapter-title BattalionPanel (350×70, FRAME1) at y=200 with prev/next mission
     *    TileSheetButtons flanking it.
     *  - A "Mission Brief" BattalionPanel (330×200, FRAME2) at (188, 297).
     *  - A "Mission Preview" BattalionPanel with a terrain minimap at (24, 297).
     *  - Difficulty options (campaigns only, not boot camp) at the right.
     *  - A generation switch row (one button per campaign) above the action row.
     *  - Bottom action buttons (Main Menu left, Start right).
     */
    private fun drawMissionSelect() {
        val campaign = campaigns.getOrNull(campaignIndex)
        val missions = missionList()
        if (campaign == null || missions.isEmpty()) {
            text.drawCentered("No missions available", 350, 280, 24, SDLColor(200, 200, 200))
            drawButton("optionsMainButton", 126, 71, 8, 8, { screen = Screen.MAIN })
            return
        }
        val m = missions[selected.coerceIn(0, missions.lastIndex)]
        ensurePreview(m)

        // ---- Header + bottom backdrop (like CampaignMenu's header + bg). ----
        drawFullWidth(campaign.headerBackground.ifEmpty { "default_header" }, 0, 0, 175)
        drawFullWidth("bg_bootcamp", 0, 175, 600 - 175)

        drawMedalRow(missions)
        drawChapterPanel(campaign, m)
        drawFactionPicker(m)
        drawMissionBriefPanel(m)
        drawMissionPreviewPanel()
        if (campaign.id != GameLoader.BOOTCAMP_CAMPAIGN) drawDifficultyPanel()

        // ---- Generation switch row: one button per level list, the active one highlighted. ----
        drawCampaignSwitchRow()

        // ---- Bottom action buttons (SWF: Main Menu left, Start right). Both share the
        // VistaButton material (btn_tile9 body + gloss + hover brighten). ----
        val btnW = 200
        val btnH = 44
        val bottomY = 600 - btnH - 8
        // Main Menu (Back).
        drawVistaToggleButton(8, bottomY, btnW, btnH, "Main Menu") { screen = Screen.MAIN }
        // Start Game (right). Missions whose map file is missing cannot be started.
        drawVistaToggleButton(700 - btnW - 8, bottomY, btnW, btnH, "Start Game") {
            if (isPlayable(selected)) running = false
        }
    }

    /**
     * Draws the row of generation buttons (Nemesis / Skirmish / Ghosts / Vengeance / Boot Camp)
     * that switches the mission list. The active campaign's button stays highlighted, so the
     * row doubles as the current-selection indicator.
     */
    private fun drawCampaignSwitchRow() {
        if (campaigns.isEmpty()) return
        var x = SWITCH_ROW_MARGIN
        for (i in campaigns.indices) {
            val active = i == campaignIndex
            drawVistaToggleButton(x, SWITCH_ROW_Y, switchButtonWidth(), SWITCH_ROW_H,
                campaigns[i].displayName, active = active, action = { openCampaign(i) })
            x += switchButtonWidth() + SWITCH_ROW_GAP
        }
    }

    /** Width of one generation button: the row spans the stage minus its margins. */
    private fun switchButtonWidth(): Int {
        val totalW = 700 - 2 * SWITCH_ROW_MARGIN
        return (totalW - SWITCH_ROW_GAP * (campaigns.size - 1)) / maxOf(1, campaigns.size)
    }

    /** Draws a texture stretched across the full window width at [y]..[y+h]. */
    private fun drawFullWidth(key: String, x: Int, y: Int, h: Int) {
        val tex = textures.texture(key) ?: return
        val winW = logicalWindowSize().first
        renderer.renderTexture(tex, dst = SDLFRect(x.toFloat(), y.toFloat(), winW.toFloat(), h.toFloat()))
    }

    /**
     * Reproduces [BattalionPanel]/[FrameMaker.createFrame]: a nine-slice border around a panel
     * filled with a tiled [fillKey] texture. [frameKey] is the frame1/frame2 sprite with
     * [corner]px copied corners.
     */
    private fun drawPanel(x: Int, y: Int, w: Int, h: Int, frameKey: String, corner: Int, fillKey: String, title: String? = null) {
        // Interior fill, tiled.
        val fill = textures.texture(fillKey)
        if (fill != null) {
            val tw = maxOf(1, fill.size.x.toInt())
            val th = maxOf(1, fill.size.y.toInt())
            var yy = y
            while (yy < y + h) {
                var xx = x
                while (xx < x + w) {
                    renderer.renderTexture(fill, dst = SDLFRect(
                        xx.toFloat(), yy.toFloat(),
                        minOf(tw, x + w - xx).toFloat(), minOf(th, y + h - yy).toFloat()))
                    xx += tw
                }
                yy += th
            }
        } else {
            renderer.drawColor = SDLColor(30, 34, 44)
            renderer.fillRect(SDLRect(x, y, w, h))
        }

        // Nine-slice frame.
        val frame = textures.texture(frameKey)
        if (frame != null) {
            val fw = frame.size.x.toInt()
            val fh = frame.size.y.toInt()
            val c = corner
            if (fw >= c * 2 + 1 && fh >= c * 2 + 1) {
                // Corners.
                renderer.renderTexture(frame, src = SDLFRect(0f, 0f, c.toFloat(), c.toFloat()), dst = SDLFRect((x - c / 2).toFloat(), (y - c / 2).toFloat(), c.toFloat(), c.toFloat()))
                renderer.renderTexture(frame, src = SDLFRect((fw - c).toFloat(), 0f, c.toFloat(), c.toFloat()), dst = SDLFRect((x + w - c / 2).toFloat(), (y - c / 2).toFloat(), c.toFloat(), c.toFloat()))
                renderer.renderTexture(frame, src = SDLFRect(0f, (fh - c).toFloat(), c.toFloat(), c.toFloat()), dst = SDLFRect((x - c / 2).toFloat(), (y + h - c / 2).toFloat(), c.toFloat(), c.toFloat()))
                renderer.renderTexture(frame, src = SDLFRect((fw - c).toFloat(), (fh - c).toFloat(), c.toFloat(), c.toFloat()), dst = SDLFRect((x + w - c / 2).toFloat(), (y + h - c / 2).toFloat(), c.toFloat(), c.toFloat()))
                // Edges.
                renderer.renderTexture(frame, src = SDLFRect(c.toFloat(), 0f, (fw - c * 2).toFloat(), c.toFloat()), dst = SDLFRect((x + c / 2).toFloat(), (y - c / 2).toFloat(), (w - c).toFloat(), c.toFloat()))
                renderer.renderTexture(frame, src = SDLFRect(c.toFloat(), (fh - c).toFloat(), (fw - c * 2).toFloat(), c.toFloat()), dst = SDLFRect((x + c / 2).toFloat(), (y + h - c / 2).toFloat(), (w - c).toFloat(), c.toFloat()))
                renderer.renderTexture(frame, src = SDLFRect(0f, c.toFloat(), c.toFloat(), (fh - c * 2).toFloat()), dst = SDLFRect((x - c / 2).toFloat(), (y + c / 2).toFloat(), c.toFloat(), (h - c).toFloat()))
                renderer.renderTexture(frame, src = SDLFRect((fw - c).toFloat(), c.toFloat(), c.toFloat(), (fh - c * 2).toFloat()), dst = SDLFRect((x + w - c / 2).toFloat(), (y + c / 2).toFloat(), c.toFloat(), (h - c).toFloat()))
            }
        } else {
            renderer.drawColor = SDLColor(120, 120, 140)
            renderer.drawRect(SDLRect(x, y, w, h))
        }

        // BattalionPanel title: a white 16px heading centred at the panel's top edge, drawn on
        // a black 50%-alpha band spanning the panel width (the original's `bgBorder`:
        // lineStyle(1,0,0) + beginFill(0,0.5), sized rWidth × headingHeight+5). The fill needs
        // BLEND for the alpha to apply — without it the band would hide the panel texture.
        if (title != null) {
            val bandH = TITLE_BAND_H
            val prevBlend = renderer.blendMode
            renderer.blendMode = SDLBlendMode.BLEND
            renderer.drawColor = SDLColor(0, 0, 0, 128)
            renderer.fillRect(SDLRect(x, y, w, bandH))
            renderer.blendMode = prevBlend
            renderer.drawColor = SDLColor(0, 0, 0)
            renderer.drawRect(SDLRect(x, y, w, bandH))
            text.drawCentered(title, x + w / 2, y + 2, 16, SDLColor(255, 255, 255))
        }
    }

    /**
     * Draws only a [FrameMaker.createFrame] frame (no fill) into the exact rect
     * ([x], [y], [w], [h]): [cornerW]×[cornerH] corners copied, edges and centre tiled. Used by
     * the mission select's standalone frames (minimap, difficulty panel).
     */
    private fun drawNineSliceFrame(frameKey: String, x: Int, y: Int, w: Int, h: Int, cornerW: Int, cornerH: Int) {
        val tex = textures.texture(frameKey) ?: return
        val fw = tex.size.x.toInt()
        val fh = tex.size.y.toInt()
        if (fw < cornerW * 2 + 1 || fh < cornerH * 2 + 1) return

        fun tile(sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
            if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return
            var oy = 0
            while (oy < dh) {
                var ox = 0
                while (ox < dw) {
                    val cw = minOf(sw, dw - ox)
                    val ch = minOf(sh, dh - oy)
                    renderer.renderTexture(
                        tex,
                        src = SDLFRect(sx.toFloat(), sy.toFloat(), cw.toFloat(), ch.toFloat()),
                        dst = SDLFRect((dx + ox).toFloat(), (dy + oy).toFloat(), cw.toFloat(), ch.toFloat()),
                    )
                    ox += sw
                }
                oy += sh
            }
        }

        val midW = fw - cornerW * 2
        val midH = fh - cornerH * 2
        // Edges + centre (corners are copied last so they always win).
        tile(cornerW, 0, midW, cornerH, x + cornerW, y, w - cornerW * 2, cornerH)
        tile(cornerW, fh - cornerH, midW, cornerH, x + cornerW, y + h - cornerH, w - cornerW * 2, cornerH)
        tile(0, cornerH, cornerW, midH, x, y + cornerH, cornerW, h - cornerH * 2)
        tile(fw - cornerW, cornerH, cornerW, midH, x + w - cornerW, y + cornerH, cornerW, h - cornerH * 2)
        tile(cornerW, cornerH, midW, midH, x + cornerW, y + cornerH, w - cornerW * 2, h - cornerH * 2)
        // Corners.
        renderer.renderTexture(tex, src = SDLFRect(0f, 0f, cornerW.toFloat(), cornerH.toFloat()), dst = SDLFRect(x.toFloat(), y.toFloat(), cornerW.toFloat(), cornerH.toFloat()))
        renderer.renderTexture(tex, src = SDLFRect((fw - cornerW).toFloat(), 0f, cornerW.toFloat(), cornerH.toFloat()), dst = SDLFRect((x + w - cornerW).toFloat(), y.toFloat(), cornerW.toFloat(), cornerH.toFloat()))
        renderer.renderTexture(tex, src = SDLFRect(0f, (fh - cornerH).toFloat(), cornerW.toFloat(), cornerH.toFloat()), dst = SDLFRect(x.toFloat(), (y + h - cornerH).toFloat(), cornerW.toFloat(), cornerH.toFloat()))
        renderer.renderTexture(tex, src = SDLFRect((fw - cornerW).toFloat(), (fh - cornerH).toFloat(), cornerW.toFloat(), cornerH.toFloat()), dst = SDLFRect((x + w - cornerW).toFloat(), (y + h - cornerH).toFloat(), cornerW.toFloat(), cornerH.toFloat()))
    }

    /** Draws the chapter-title panel with prev/next mission TileSheetButtons (SWF: top at y=200). */
    private fun drawChapterPanel(campaign: GameLoader.Campaign, m: MissionInfo) {
        val x = 350 - CHAPTER_W / 2
        val y = CHAPTER_Y
        drawPanel(x, y, CHAPTER_W, CHAPTER_H, "frame1", 16, "spotted_texture")
        // Text on the light spotted panel reads better in black (SWF panel text is dark).
        text.drawCentered(m.name, 350, y + 18, 22, SDLColor(20, 20, 20))
        text.drawCentered("${campaign.displayName}   •   Mission ${m.number + 1}", 350, y + 44, 14,
            SDLColor(60, 60, 70))

        // Prev / next tilesheet buttons flank the chapter panel.
        drawButton("previousMissionButton", MISSION_ARROW_W, 46, x - MISSION_ARROW_W - 4, y + (CHAPTER_H - 46) / 2, { move(-1) })
        drawButton("nextMissionButton", MISSION_ARROW_W, 46, x + CHAPTER_W + 4, y + (CHAPTER_H - 46) / 2, { move(1) })
    }

    /* ------------------------------------------------------------------ */
    /* Faction picker (next to the mission arrows)                        */
    /* ------------------------------------------------------------------ */

    /** The mission's own player team: the one its author marked human. */
    private fun defaultPlayerTeam(m: MissionInfo): Int =
        m.settings.teams.indexOfFirst { it.controllerType.equals("Human", ignoreCase = true) }
            .coerceAtLeast(0)

    /** The team the player commands for [m] (the picker's selection, default = the mission's). */
    private fun playerTeamIndex(m: MissionInfo): Int {
        val teams = m.settings.teams
        if (teams.isEmpty()) return -1
        val key = campaigns.getOrNull(campaignIndex)?.let { "${it.id}#${m.number}" }
        val chosen = key?.let { playerTeamChoices[it] } ?: defaultPlayerTeam(m)
        return chosen.coerceIn(0, teams.lastIndex)
    }

    /** The team the player faces: the first team they do not command, in mission order. */
    private fun enemyTeamIndex(m: MissionInfo, playerTeam: Int): Int =
        m.settings.teams.indices.firstOrNull { it != playerTeam } ?: -1

    /** Switches the player's side for the selected mission (both picker buttons call this). */
    private fun choosePlayerTeam(team: Int) {
        val campaign = campaigns.getOrNull(campaignIndex) ?: return
        val m = missionList().getOrNull(selected) ?: return
        if (team !in m.settings.teams.indices) return
        playerTeamChoices["${campaign.id}#${m.number}"] = team
    }

    /** ARGB team colour as an [SDLColor]. */
    private fun teamColorOf(color: String): SDLColor {
        val argb = cn.enaium.battalion.data.parseArgb(color)
        return SDLColor((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff)
    }

    /**
     * Draws the faction picker: the team the player commands to the left of the previous
     * mission button, the team(s) they face to the right of the next one. Each button is the
     * faction's own colour; the selected one is boxed. Clicking either side commands that team
     * (the border follows the choice, so the two groups swap when the other side is picked).
     */
    private fun drawFactionPicker(m: MissionInfo) {
        val teams = m.settings.teams
        if (teams.size < 2) return
        val chosen = playerTeamIndex(m)
        if (chosen < 0) return
        val swatch = 22
        val gap = 4
        val pad = 8
        val y = CHAPTER_Y + (CHAPTER_H - swatch) / 2

        // Our side: right-aligned against the previous-mission button.
        val prevX = 350 - CHAPTER_W / 2 - MISSION_ARROW_W - 4
        var x = prevX - pad - (teams.size * (swatch + gap) - gap)
        for (i in teams.indices) {
            drawTeamSwatch(x, y, swatch, teams[i], selected = i == chosen) { choosePlayerTeam(i) }
            x += swatch + gap
        }

        // Their side: left-aligned after the next-mission button, the chosen team left out (it
        // is the player's). The first of the rest is the mission's opponent.
        val nextX = 350 + CHAPTER_W / 2 + 4 + MISSION_ARROW_W
        val enemy = enemyTeamIndex(m, chosen)
        var ex = nextX + pad
        for (i in teams.indices) {
            if (i == chosen) continue
            drawTeamSwatch(ex, y, swatch, teams[i], selected = i == enemy) { choosePlayerTeam(i) }
            ex += swatch + gap
        }
    }

    /** One faction button: the team colour, hover brighten and a box around the selection. */
    private fun drawTeamSwatch(x: Int, y: Int, size: Int, team: cn.enaium.battalion.data.TeamConfig, selected: Boolean, action: () -> Unit) {
        val hover = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size
        val color = teamColorOf(team.color)
        renderer.drawColor = color
        renderer.fillRect(SDLRect(x, y, size, size))
        if (hover) {
            val prevBlend = renderer.blendMode
            renderer.blendMode = SDLBlendMode.BLEND
            renderer.drawColor = SDLColor(255, 255, 255, 60)
            renderer.fillRect(SDLRect(x, y, size, size))
            renderer.blendMode = prevBlend
        }
        // Dark outline for the swatch itself; the selected side gets a bright box around it.
        renderer.drawColor = SDLColor(20, 20, 20)
        renderer.drawRect(SDLRect(x, y, size, size))
        if (selected) {
            renderer.drawColor = SDLColor(255, 255, 224)
            renderer.drawRect(SDLRect(x - 2, y - 2, size + 4, size + 4))
            renderer.drawColor = SDLColor(20, 20, 20)
            renderer.drawRect(SDLRect(x - 1, y - 1, size + 2, size + 2))
        }
        buttons.add(Btn("faction", size, size, x, y, action))
    }

    /** Draws the "Mission Brief" BattalionPanel (SWF: at (188, 297), 330×200). */
    private fun drawMissionBriefPanel(m: MissionInfo) {
        val x = 188
        val y = 297
        val w = 330
        drawPanel(x, y, w, 200, "frame2", 32, "spotted_texture", title = "Mission Brief")
        val brief = m.brief
        val (silver, gold) = medalThresholds(assetsDir, m)
        // CustomTextField at (panel.x + 5, panel.y + 24), width rWidth - 10, black 12px text.
        val textW = w - 10
        var ty = y + 24
        for (wrapped in wrapText(brief, textW, 13)) {
            if (ty > y + 140) break
            text.draw(wrapped, x + 8, ty, 13, SDLColor(0, 0, 0))
            ty += 17
        }
        if (silver != null && gold != null) {
            text.draw("Silver: $silver    Gold: $gold", x + 8, y + 168, 13, SDLColor(90, 60, 20))
        }
    }

    /** Draws the medal row (SWF: starting x = WIDTH/2 - space/2, y = 131). */
    private fun drawMedalRow(missions: List<MissionInfo>) {
        val medalW = 30
        val medalPadding = 8
        val space = missions.size * medalW + (missions.size - 1) * medalPadding
        var x = 350 - space / 2
        for (i in missions.indices) {
            val isSelected = i == selected
            // medalBackground under the selected level's medal (removed from others).
            val bg = textures.texture("medalBackground")
            if (isSelected && bg != null) {
                renderer.renderTexture(bg, dst = SDLFRect((x - 10).toFloat(), (131 - 11).toFloat(), 50f, 57f))
            }
            // Medal sheet: 6 frames × 30px — 0 empty, 1 bronze, 2 silver, 3 gold, 4 locked,
            // 5 selected. Every level is unlocked (the mission list has no progression gate),
            // so only the earned medal / selection frames are ever shown.
            val medal = textures.texture("medal")
            if (medal != null) {
                val frame = when {
                    isSelected -> 5
                    else -> when (SaveManager.medalFor(missionList()[i].mapUrl)) {
                        "gold" -> 3
                        "silver" -> 2
                        "bronze" -> 1
                        else -> 0
                    }
                }
                renderer.renderTexture(medal, src = SDLFRect((frame * 30).toFloat(), 0f, 30f, 30f), dst = SDLFRect(x.toFloat(), 131f, 30f, 30f))
            }
            x += medalW + medalPadding
        }
    }

    /** Returns the terrain colour for a palette entry name (matches the in-game minimap look). */
    private fun terrainColorFor(name: String): SDLColor {
        val n = name.lowercase()
        return when {
            "sea" in n || "water" in n || "reef" in n || "shore" in n -> SDLColor(40, 90, 160)
            "forest" in n -> SDLColor(45, 120, 60)
            "hill" in n || "mountain" in n || "canyon" in n || "cliff" in n -> SDLColor(120, 90, 55)
            "plains" in n || "road" in n || "bridge" in n -> SDLColor(150, 145, 110)
            "volcano" in n || "lava" in n -> SDLColor(180, 80, 30)
            "ore" in n -> SDLColor(70, 120, 150)
            "desert" in n || "wasteland" in n || "waste" in n -> SDLColor(190, 175, 110)
            else -> SDLColor(120, 130, 120)
        }
    }

    /** Builds (or refreshes) the terrain-colour minimap preview for the current selection. */
    private fun ensurePreview(m: MissionInfo) {
        val key = (campaigns.getOrNull(campaignIndex)?.id ?: "") to selected
        if (previewKey == key) return
        previewKey = key
        preview = null
        val mapCfg = GameLoader.findMapPublic(assetsDir, m.mapId) ?: return
        try {
            val map = cn.enaium.battalion.data.GameMap(mapCfg, m.mapUrl, m.mapUrl)
            val cols = map.width
            val rows = map.height
            if (cols <= 0 || rows <= 0) return
            val names = Array(rows) { Array(cols) { "" } }
            val teams = Array(rows) { IntArray(cols) }
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    names[r][c] = map.terrainNameAt(c, r)
                    teams[r][c] = map.ownedBy(c, r)
                }
            }
            preview = MapPreview(cols, rows, names, teams)
        } catch (t: Throwable) {
            preview = null
        }
    }

    /**
     * Draws the mission preview exactly like `CampaignMenu.createMinimap`: a small "Mission
     * Preview" title panel (124×64, FRAME2) at (24, 297) and — 22px below it — the 140×184
     * minimap bitmap on a black backing, wrapped in a 43px-corner frame1 frame.
     */
    private fun drawMissionPreviewPanel() {
        val mmFrameX = 24
        val mmFrameY = 297
        drawPanel(mmFrameX, mmFrameY, MM_WIDTH - 16, 64, "frame2", 32, "spotted_texture",
            title = "Mission Preview")

        // The minimap sprite: black backing + terrain + frame.
        val mmX = mmFrameX - 9
        val mmY = mmFrameY + 22
        renderer.drawColor = SDLColor(0, 0, 0)
        renderer.fillRect(SDLRect(mmX, mmY, MM_WIDTH, MM_HEIGHT))

        val p = preview
        if (p != null) {
            val cell = minOf(MM_WIDTH.toDouble() / p.cols, MM_HEIGHT.toDouble() / p.rows)
            val ox = mmX + ((MM_WIDTH - cell * p.cols) / 2).toInt()
            val oy = mmY + ((MM_HEIGHT - cell * p.rows) / 2).toInt()
            val step = kotlin.math.ceil(cell).toInt()
            for (r in 0 until p.rows) {
                for (c in 0 until p.cols) {
                    val team = p.teams[r][c]
                    renderer.drawColor = if (team > 0) teamColor(team) else terrainColorFor(p.names[r][c])
                    renderer.fillRect(SDLRect((ox + c * cell).toInt(), (oy + r * cell).toInt(), step, step))
                }
            }
        }

        drawNineSliceFrame("frame1", mmX - 8, mmY - 8, MM_WIDTH + 16, MM_HEIGHT + 16, 43, 43)
    }

    /** A fixed team-colour palette for the minimap (P1..P4). */
    private fun teamColor(team: Int): SDLColor = when (team) {
        1 -> SDLColor(90, 200, 90)
        2 -> SDLColor(90, 130, 220)
        3 -> SDLColor(220, 90, 90)
        4 -> SDLColor(210, 170, 70)
        else -> SDLColor(150, 150, 150)
    }

    /**
     * The currently selected AI difficulty index for the campaign (0=Easy, 1=Normal, 2=Hard),
     * persisted as `aidifficulty` — mirrors `DifficultyPanel` reading the stored value.
     */
    private var difficultyIndex: Int = SaveManager.aiDifficulty.coerceIn(0, 2)

    /**
     * Reproduces `DifficultyPanel`/`DifficultyOption` for the campaign screen:
     *
     *  - a 72×50 "AI Difficulty" title panel at (585, 315);
     *  - three 80×37 option rows at (582, 340 + i·37): spotted backing, a colour bar on the
     *    left (`colorBar`, tinted per difficulty), the rank badge (frame = rank 1/2/6),
     *    and the name in black at (row.x + 38, row.y + 9);
     *  - a 39px-corner frame1 frame around the whole panel;
     *  - the left-arrow marker next to the selected row.
     *
     * The SWF colours are Easy `0xFF00FF00`, Normal `0xCCCCCC00`, Hard `0xFFFF0000`.
     */
    private fun drawDifficultyPanel() {
        val panelX = 580
        val panelY = 340
        val rowW = 80
        val rowH = 37
        val names = listOf("Easy", "Normal", "Hard")

        // Whole-panel frame behind the rows.
        drawNineSliceFrame("frame1", panelX - 8, panelY - 11, rowW + 18, rowH * 3 + 5, 39, 39)

        // Title panel (WIDTH - 8 = 72 × 50 at (panel.x + 5, panel.y - 25)).
        drawPanel(panelX + 5, panelY - 25, rowW - 8, 50, "frame2", 32, "spotted_texture",
            title = "AI Difficulty")

        var selectedRowY = panelY
        for (i in names.indices) {
            val rowX = panelX + 2
            val rowY = panelY + i * rowH
            // Row backing: the spotted texture tiled over 80×37.
            val bg = textures.texture("spotted_texture")
            if (bg != null) {
                renderer.renderTexture(bg, dst = SDLFRect(rowX.toFloat(), rowY.toFloat(), rowW.toFloat(), rowH.toFloat()))
            }
            // Left colour bar (10×32 at (row.x, row.y + 2)), repainted in the difficulty colour.
            val colorBar = textures.texture("colorBar")
            if (colorBar != null) {
                val dc = DIFFICULTY_COLORS[i]
                val prevMod = colorBar.colorMod
                val prevAlpha = colorBar.alphaMod
                colorBar.colorMod = dc.color
                colorBar.alphaMod = dc.alpha
                renderer.renderTexture(colorBar, dst = SDLFRect(rowX.toFloat(), (rowY + 2).toFloat(), 10f, 32f))
                colorBar.colorMod = prevMod
                colorBar.alphaMod = prevAlpha
            }
            // Rank badge: frame = rank (1/2/6) in the 18px 4-column badge sheet.
            val rank = when (i) { 1 -> 2; 2 -> 6; else -> 1 }
            val rb = textures.texture("rankBadges")
            if (rb != null) {
                renderer.renderTexture(
                    rb,
                    src = SDLFRect((rank % 4 * 18).toFloat(), (rank / 4 * 18).toFloat(), 18f, 18f),
                    dst = SDLFRect((rowX + 15).toFloat(), (rowY + 9).toFloat(), 18f, 18f),
                )
            }
            text.draw(names[i], rowX + 38, rowY + 9, 14, SDLColor(0, 0, 0))

            if (i == difficultyIndex) selectedRowY = rowY
            // Clickable row -> select + persist (like DifficultyPanel.changeSelection).
            buttons.add(Btn("difficulty", rowW, rowH, rowX, rowY) {
                difficultyIndex = i
                SaveManager.aiDifficulty = i
            })
        }

        // Selection marker: the original mirrors the 64×64 arrow sprite to half size next to
        // the active row (the visible triangle lands just left of the row).
        val arrow = textures.texture("ui_left_arrow")
        if (arrow != null) {
            renderer.renderTexture(
                arrow,
                dst = SDLFRect((panelX - 14).toFloat(), (selectedRowY + 2).toFloat(), 32f, 32f),
            )
        }
    }

    /** Wraps [input] to fit [maxWidth] pixels, splitting on word boundaries. */
    private fun wrapText(input: String, maxWidth: Int, sizePx: Int = 13): List<String> {
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

    /**
     * Tiled 9-slice (Flash Tile9 material): corners native, edges and centre repeat their
     * source patch — same helper as BattalionRenderer.drawTiled9Grid.
     */
    private fun drawTiled9Grid(tex: SDLTexture, x: Int, y: Int, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val tw = tex.size.x.toInt()
        val th = tex.size.y.toInt()
        val midW = right - left
        val midH = bottom - top
        val edgeR = tw - right
        val edgeB = th - bottom

        fun patch(sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
            if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return
            var oy = 0
            while (oy < dh) {
                var ox = 0
                while (ox < dw) {
                    val cw = minOf(sw, dw - ox)
                    val ch = minOf(sh, dh - oy)
                    renderer.renderTexture(
                        tex,
                        src = SDLFRect(sx.toFloat(), sy.toFloat(), cw.toFloat(), ch.toFloat()),
                        dst = SDLFRect((dx + ox).toFloat(), (dy + oy).toFloat(), cw.toFloat(), ch.toFloat()),
                    )
                    ox += sw
                }
                oy += sh
            }
        }

        patch(0, 0, left, top, x, y, left, top)
        patch(right, 0, edgeR, top, x + w - edgeR, y, edgeR, top)
        patch(0, bottom, left, edgeB, x, y + h - edgeB, left, edgeB)
        patch(right, bottom, edgeR, edgeB, x + w - edgeR, y + h - edgeB, edgeR, edgeB)
        patch(left, 0, midW, top, x + left, y, w - left - edgeR, top)
        patch(left, bottom, midW, edgeB, x + left, y + h - edgeB, w - left - edgeR, edgeB)
        patch(0, top, left, midH, x, y + top, left, h - top - edgeB)
        patch(right, top, edgeR, midH, x + w - edgeR, y + top, edgeR, h - top - edgeB)
        patch(left, top, midW, midH, x + left, y + top, w - left - edgeR, h - top - edgeB)
    }

    /** Reads the silver/gold medal thresholds from the mission's settings. */
    private fun medalThresholds(assetsDir: String, m: MissionInfo): Pair<Int?, Int?> {
        val silver = m.settings.silverMedal.takeIf { it >= 0 }
        val gold = m.settings.goldMedal.takeIf { it >= 0 }
        return silver to gold
    }
}

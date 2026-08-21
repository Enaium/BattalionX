package cn.enaium.battalion.game.ui

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.data.GameLoader.MissionInfo
import cn.enaium.battalion.util.Fio
import cn.enaium.battalion.util.XmlParser
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer

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
) {

    /** The player's choice: a campaign mission, a bootcamp mission, a skirmish map, or quit. */
    data class Selection(
        val missionIndex: Int? = null,
        val bootcampIndex: Int? = null,
        val skirmishMap: String? = null,
        val quit: Boolean = false,
    ) {
        companion object {
            val QUIT = Selection(quit = true)
        }
    }

    private enum class Screen { MAIN, CAMPAIGN, BOOTCAMP }

    private var screen = Screen.MAIN
    private var campaignMissions: List<MissionInfo> = emptyList()
    private var bootcampMissions: List<MissionInfo> = emptyList()
    private var selected = 0
    private var running = true
    private var mouseX = 0
    private var mouseY = 0
    private var chosenSkirmish: String? = null

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
    private var previewKey: Pair<Screen, Int>? = null

    /** Runs the menu loop and returns the player's selection. */
    fun run(): Selection {
        campaignMissions = GameLoader.findCampaign(assetsDir).orEmpty()
        bootcampMissions = GameLoader.findBootcamp(assetsDir).orEmpty()
        if (campaignMissions.isEmpty() && bootcampMissions.isEmpty()) {
            return Selection(missionIndex = 0)
        }

        running = true
        screen = Screen.MAIN
        selected = 0
        while (running) {
            pollEvents()
            draw()
            SDL.delay(16)
        }
        if (chosenSkirmish != null) return Selection(skirmishMap = chosenSkirmish)
        return when (screen) {
            Screen.MAIN -> Selection.QUIT
            Screen.CAMPAIGN -> Selection(missionIndex = selected.coerceIn(0, campaignMissions.lastIndex))
            Screen.BOOTCAMP -> Selection(bootcampIndex = selected.coerceIn(0, bootcampMissions.lastIndex))
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
            SDLKeycode.ESCAPE -> when (screen) {
                Screen.MAIN -> running = false
                else -> screen = Screen.MAIN
            }
            else -> Unit
        }
    }

    private fun missionList(): List<MissionInfo> = missionListOf(screen)

    private fun isLoadable(i: Int): Boolean {
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
            if (isLoadable(i)) { selected = i; return }
        }
    }

    /** Points the selection at the first loadable mission on the current screen. */
    private fun snapToLoadable() {
        val list = missionList()
        if (list.isEmpty()) return
        val first = list.indices.firstOrNull { isLoadable(it) }
        if (first != null) selected = first
    }

    private fun activateSelected() {
        when (screen) {
            Screen.MAIN -> { /* Enter not used on main menu; click buttons instead */ }
            Screen.CAMPAIGN, Screen.BOOTCAMP ->
                // Only start a mission whose map is actually present; otherwise give feedback.
                if (isLoadable(selected)) running = false
        }
    }

    private fun onMouseClick(x: Int, y: Int) {
        // Clickable buttons drawn this frame.
        for (b in buttons) {
            if (x >= b.x && x < b.x + b.frameW && y >= b.y && y < b.y + b.frameH) {
                b.action()
                return
            }
        }
        // Medal-row clicks select a mission (medals sit at y=131, centred on the stage).
        if (screen != Screen.MAIN) {
            val list = missionList()
            val medalW = 30
            val medalPadding = 8
            val space = list.size * medalW + (list.size - 1) * medalPadding
            val startX = 350 - space / 2
            if (y >= 131 && y < 131 + 30) {
                for (i in list.indices) {
                    val mx = startX + i * (medalW + medalPadding)
                    if (x >= mx && x < mx + medalW) {
                        if (isLoadable(i)) selected = i
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
        // is drawn for those.
        if (screen == Screen.MAIN) drawMainBackdrop()

        when (screen) {
            Screen.MAIN -> drawMainMenu()
            else -> drawMissionSelect()
        }

        renderer.present()
    }

    /** Renders a single frame of the current menu screen without running the event loop.
     *  Used by the headless menu-screenshot diagnostic. */
    fun renderFrameForCapture() {
        draw()
    }

    /** Sets the initial screen (e.g. straight to a mission list) for diagnostics. */
    fun setScreenForCapture(s: String) {
        screen = when (s) {
            "bootcamp" -> Screen.BOOTCAMP
            "campaign" -> Screen.CAMPAIGN
            else -> Screen.MAIN
        }
        campaignMissions = GameLoader.findCampaign(assetsDir).orEmpty()
        bootcampMissions = GameLoader.findBootcamp(assetsDir).orEmpty()
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
        // Vignette.
        renderer.drawColor = SDLColor(0, 0, 0, 90)
        renderer.fillRect(SDLRect(0, 0, winW, winH))
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

    private fun drawMainMenu() {
        // Note: the title art is part of the full background image and is drawn by
        // drawMainBackdrop(); nothing extra is drawn here for the title.

        // Button column (like the original main-menu tab row).
        val buttonsDef = listOf(
            Triple("restoreButton", 126, 71),     // Continue
            Triple("singleplayerButton", 126, 71), // Single Player
            Triple("multiplayerButton", 126, 71), // Multiplayer (Kongregate — disabled)
            Triple("optionsMainButton", 126, 71), // Options
        )
        val btnW = 126
        val btnH = 71
        val gap = 18
        val totalW = btnW * buttonsDef.size + gap * (buttonsDef.size - 1)
        var x = (800 - totalW) / 2
        val y = 380

        // Continue (restore) — disabled in the port (no save system).
        drawButton("restoreButton", btnW, btnH, x, y, { }, disabled = true); x += btnW + gap
        drawButton("singleplayerButton", btnW, btnH, x, y, { openCampaign() }); x += btnW + gap
        drawButton("multiplayerButton", btnW, btnH, x, y, { /* multiplayer exclude */ }, disabled = true); x += btnW + gap
        drawButton("optionsMainButton", btnW, btnH, x, y, { /* options */ })

        // Corner buttons.
        drawButton("creditsButton", 132, 28, 800 - 132 - 8, 8, { /* credits */ })
        drawButton("bonusMapButton", 126, 71, 8, 8, { loadBonusMap() })

        text.drawCentered("Multiplayer / Level Editor excluded in this build", 400, 540, 14, SDLColor(150, 150, 165))
        text.drawCentered("ESC: Quit", 400, 566, 14, SDLColor(140, 140, 160))
    }

    /** "Single Player" from the main menu opens the campaign mission select. */
    private fun openCampaign() {
        if (campaignMissions.isNotEmpty()) {
            screen = Screen.CAMPAIGN
            snapToLoadable()
        } else if (bootcampMissions.isNotEmpty()) {
            screen = Screen.BOOTCAMP
            snapToLoadable()
        }
    }

    /** The main-menu bonus map button: plays the Kongregate bonus map as a skirmish. */
    private fun loadBonusMap() {
        val files = Fio.listDir("$assetsDir/binaryData").orEmpty()
        val bonus = files.firstOrNull {
            it.contains("Kongregate_Bonus_Map", ignoreCase = true) && it.endsWith(".bin")
        }
        if (bonus != null) {
            chosenSkirmish = bonus
            running = false
        }
    }

    /**
     * Draws the mission-select page, reproducing the original SWF `CampaignMenu` (a 700×600
     * stage):
     *
     *  - Campaign / Bootcamp header image across the top 175px (ep3_campaign_header /
     *    default_header), with the `bg_bootcamp` backdrop below it.
     *  - A medal row near y=131 (medalBackground + medal sheet frames).
     *  - A chapter-title BattalionPanel (350×70, FRAME1) at y=200 with prev/next mission
     *    TileSheetButtons flanking it.
     *  - A "Mission Brief" BattalionPanel (330×200, FRAME2) at (188, 297).
     *  - A "Mission Preview" BattalionPanel with a terrain minimap at (24, 297).
     *  - Difficulty options (campaign only) at the right.
     *  - Bottom action buttons (Main Menu / Start, plus Go to Bootcamp|Campaign).
     */
    private fun drawMissionSelect() {
        val missions = missionList()
        if (missions.isEmpty()) {
            text.drawCentered("No missions available", 350, 280, 24, SDLColor(200, 200, 200))
            drawButton("optionsMainButton", 126, 71, 8, 8, { screen = Screen.MAIN })
            return
        }
        val m = missions[selected.coerceIn(0, missions.lastIndex)]
        ensurePreview(m)

        // ---- Header + bottom backdrop (like CampaignMenu's header + bg). ----
        val headerKey = if (screen == Screen.CAMPAIGN) "ep3_campaign_header" else "default_header"
        drawFullWidth(headerKey, 0, 0, 175)
        drawFullWidth("bg_bootcamp", 0, 175, 600 - 175)

        drawMedalRow(missions)
        drawChapterPanel(m)
        drawMissionBriefPanel(m)
        drawMissionPreviewPanel()
        if (screen == Screen.CAMPAIGN) drawDifficultyPanel()

        // ---- Bottom action buttons (SWF: Main Menu left, Start right, toggle centre). ----
        val btnW = 200
        val btnH = 60
        val bottomY = 600 - btnH - 8
        // Main Menu (Back).
        drawButton("bonusMapButton", 126, 71, 8, bottomY + 5, { screen = Screen.MAIN })
        text.draw("Main Menu", 8 + 20, bottomY + 5 + 22, 14, SDLColor(10, 10, 10))
        // Start Game (right).
        drawButton("bonusMapButton", 126, 71, 700 - 126 - 8, bottomY + 5, { if (isLoadable(selected)) running = false })
        text.draw("Start Game", 700 - 126 - 8 + 20, bottomY + 5 + 22, 14, SDLColor(10, 10, 10))
        // Go to Bootcamp / Campaign toggle (centre).
        val toggleKey = if (screen == Screen.CAMPAIGN) "bootCampButton" else "campaignButton"
        val toggleW = 164
        val toggleH = 94
        val toggleX = (700 - toggleW) / 2
        val toggleY = 590 - 94
        val other = if (screen == Screen.CAMPAIGN) Screen.BOOTCAMP else Screen.CAMPAIGN
        drawButton(toggleKey, toggleW, toggleH, toggleX, toggleY, {
            screen = other
            snapToLoadable()
        }, disabled = missionListOf(other).isEmpty())
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

        // Centred heading (BattalionPanel title).
        if (title != null) {
            val headingY = y - 10
            text.drawCentered(title, x + w / 2, headingY, 16, SDLColor(250, 250, 250))
        }
    }

    /** Draws the chapter-title panel with prev/next mission TileSheetButtons (SWF: top at y=200). */
    private fun drawChapterPanel(m: MissionInfo) {
        val x = 350 - 175
        val y = 200
        drawPanel(x, y, 350, 70, "frame1", 16, "spotted_texture")
        text.drawCentered(m.name, 350, y + 18, 22, SDLColor(230, 235, 240))
        if (screen == Screen.CAMPAIGN) text.drawCentered("Campaign   •   Mission ${m.number + 1}", 350, y + 44, 14, SDLColor(160, 170, 180))

        // Prev / next tilesheet buttons flank the chapter panel.
        drawButton("previousMissionButton", 33, 46, x - 33 - 4, y + (70 - 46) / 2, { move(-1) })
        drawButton("nextMissionButton", 33, 46, x + 350 + 4, y + (70 - 46) / 2, { move(1) })
    }

    /** Draws the "Mission Brief" BattalionPanel (SWF: at (188, 297), 330×200). */
    private fun drawMissionBriefPanel(m: MissionInfo) {
        val x = 188
        val y = 297
        drawPanel(x, y, 330, 200, "frame2", 32, "spotted_texture", title = "Mission Brief")
        val brief = m.brief
        val (silver, gold) = medalThresholds(assetsDir, m)
        var ty = y + 14
        for (wrapped in wrapText(brief, 300, 13)) {
            if (ty > y + 130) break
            text.draw(wrapped, x + 12, ty, 13, SDLColor(230, 232, 236))
            ty += 17
        }
        if (silver != null && gold != null) {
            text.draw("Silver: $silver    Gold: $gold", x + 12, y + 168, 13, SDLColor(200, 205, 90))
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
            // Medal sheet: 6 frames × 30px. Selected = unlocked(5), others = locked(4).
            val medal = textures.texture("medal")
            if (medal != null) {
                val frame = if (isSelected) 5 else 4
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
        val key = screen to selected
        if (previewKey == key) return
        previewKey = key
        preview = null
        val mapXml = GameLoader.findMapXmlPublic(assetsDir, m.mapUrl) ?: return
        try {
            val map = cn.enaium.battalion.data.GameMap(mapXml, m.mapUrl, m.mapUrl)
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

    /** Draws the "Mission Preview" BattalionPanel with a terrain minimap (SWF: at (24, 297)). */
    private fun drawMissionPreviewPanel() {
        val x = 24
        val y = 297
        drawPanel(x, y, 140, 200, "frame2", 32, "spotted_texture", title = "Mission Preview")
        val p = preview ?: return
        val cell = minOf(120.0 / p.cols, 170.0 / p.rows)
        val ox = x + 9
        val oy = y + 16
        var hasTeamOwned = false
        for (r in 0 until p.rows) {
            for (c in 0 until p.cols) {
                val team = p.teams[r][c]
                if (team > 0) hasTeamOwned = true
                val color = if (team > 0) teamColor(team) else terrainColorFor(p.names[r][c])
                renderer.drawColor = color
                renderer.fillRect(SDLRect((ox + c * cell).toInt(), (oy + r * cell).toInt(), kotlin.math.ceil(cell).toInt(), kotlin.math.ceil(cell).toInt()))
            }
        }
    }

    /** A fixed team-colour palette for the minimap (P1..P4). */
    private fun teamColor(team: Int): SDLColor = when (team) {
        1 -> SDLColor(90, 200, 90)
        2 -> SDLColor(90, 130, 220)
        3 -> SDLColor(220, 90, 90)
        4 -> SDLColor(210, 170, 70)
        else -> SDLColor(150, 150, 150)
    }

    /** Reproduces the SWF DifficultyPanel (Easy/Normal/Hard rows) for the campaign screen. */
    private fun drawDifficultyPanel() {
        val x = 580
        val y = 340
        drawPanel(x - 5, y - 25, 80 - 8, 50, "frame2", 32, "spotted_texture", title = "AI Difficulty")
        val names = listOf("Easy", "Normal", "Hard")
        for (i in names.indices) {
            val rowY = y + i * 37
            val bg = textures.texture("spotted_texture")
            if (bg != null) {
                renderer.renderTexture(bg, dst = SDLFRect(x.toFloat(), rowY.toFloat(), 80f, 37f))
            }
            // rankBadges: 72×37 sheet, frames = ? rows; rank 1/2/6 -> row selection.
            val rank = when (i) { 1 -> 2; 2 -> 6; else -> 1 }
            val rb = textures.texture("rankBadges")
            if (rb != null) {
                val cellH = 18
                val row = (rank - 1) % 4
                renderer.renderTexture(rb, src = SDLFRect(0f, (row * cellH).toFloat(), 18f, 18f), dst = SDLFRect((x + 15).toFloat(), (rowY + 9).toFloat(), 18f, 18f))
            }
            text.draw(names[i], x + 38, rowY + 9, 14, SDLColor(20, 20, 20))
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

    private fun missionListOf(s: Screen): List<MissionInfo> = when (s) {
        Screen.CAMPAIGN -> campaignMissions
        Screen.BOOTCAMP -> bootcampMissions
        Screen.MAIN -> emptyList()
    }

    /** Reads the silver/gold medal thresholds from the mission's mapsettings. */
    private fun medalThresholds(assetsDir: String, m: MissionInfo): Pair<Int?, Int?> {
        val silver = m.mapSettingsXml.attr("silverMedal").toIntOrNull()
        val gold = m.mapSettingsXml.attr("goldMedal").toIntOrNull()
        return silver to gold
    }
}

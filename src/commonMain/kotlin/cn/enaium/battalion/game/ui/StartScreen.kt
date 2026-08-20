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
        // Target-row clicks on the mission-select list (coords match drawMissionSelect).
        if (screen != Screen.MAIN) {
            val list = missionList()
            val rowTop = 145
            val rowH = 30
            if (y >= rowTop && y < rowTop + list.size * rowH) {
                val idx = (y - rowTop) / rowH
                if (idx in list.indices && isLoadable(idx)) {
                    selected = idx
                    running = false
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

        // Common dark backdrop (BattalionPanel wood feel).
        drawBackdrop()

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

    private fun drawBackdrop() {
        val wood = textures.texture("wood_texture")
        if (wood != null) {
            val w = wood.size.x.toInt()
            val h = wood.size.y.toInt()
            var y = 0
            while (y < 600) {
                var x = 0
                while (x < 800) {
                    renderer.renderTexture(wood, dst = SDLFRect(x.toFloat(), y.toFloat(),
                        minOf(w, 800 - x).toFloat(), minOf(h, 600 - y).toFloat()))
                    x += w
                }
                y += h
            }
        }
        // Vignette.
        renderer.drawColor = SDLColor(0, 0, 0, 90)
        renderer.fillRect(SDLRect(0, 0, 800, 600))
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
        // Title art.
        textures.texture("title_3")?.let { t ->
            val tw = 640
            val th = 175
            renderer.renderTexture(t, dst = SDLFRect((800f - tw) / 2f, 40f, tw.toFloat(), th.toFloat()))
        } ?: run {
            text.drawCentered("BATTALION VENGEANCE", 400, 90, 40, SDLColor(230, 220, 180))
        }

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

    private fun drawMissionSelect() {
        val missions = missionList()
        if (missions.isEmpty()) {
            text.drawCentered("No missions available", 400, 280, 24, SDLColor(200, 200, 200))
            drawButton("optionsMainButton", 126, 71, 8, 8, { screen = Screen.MAIN })
            return
        }
        val m = missions[selected.coerceIn(0, missions.lastIndex)]

        val title = if (screen == Screen.CAMPAIGN) "Campaign" else "Bootcamp"
        text.drawCentered(title, 400, 60, 36, SDLColor(230, 220, 180))
        text.drawCentered(m.name, 400, 104, 22, SDLColor(200, 210, 230))

        // Mission list (single column, prev/next arrows).
        val rowTop = 145
        val rowH = 30
        val listLeft = 90
        val listRight = 710
        var y = rowTop
        for (i in missions.indices) {
            val isSel = i == selected
            if (isSel) {
                renderer.drawColor = SDLColor(70, 100, 70)
                renderer.fillRect(SDLRect(listLeft, y, listRight - listLeft, rowH - 4))
            }
            val label = if (GameLoader.isMissionLoadable(assetsDir, missions[i])) {
                "Mission ${missions[i].number}: ${missions[i].name}"
            } else {
                "Mission ${missions[i].number}: ${missions[i].name}  (map missing)"
            }
            text.draw(label, listLeft + 12, y + 6, 16, if (isSel) SDLColor(230, 255, 230) else SDLColor(180, 185, 195))
            y += rowH
        }

        // Prev/next arrows.
        drawButton("previousMissionButton", 33, 46, 760, 150, { move(-1) })
        drawButton("nextMissionButton", 33, 46, 760, 200, { move(1) })

        // Mission brief panel (bottom).
        renderer.drawColor = SDLColor(30, 34, 44, 235)
        renderer.fillRect(SDLRect(60, 420, 680, 140))

        // Difficulty/medal line.
        val (silver, gold) = medalThresholds(assetsDir, m)
        renderer.drawColor = SDLColor(60, 60, 75)
        renderer.drawRect(SDLRect(60, 470, 680, 40))
        if (silver != null && gold != null) {
            text.draw("Silver Medal: $silver    Gold Medal: $gold", 80, 482, 15, SDLColor(190, 195, 205))
        } else {
            text.draw("No medal thresholds", 80, 482, 15, SDLColor(150, 155, 165))
        }

        // Bottom buttons: Back (left), Campaign⇄Bootcamp toggle (centre), Start (right).
        drawButton("optionsMainButton", 126, 71, 90, 510, { screen = Screen.MAIN }, disabled = false)
        val toggleKey = if (screen == Screen.CAMPAIGN) "bootCampButton" else "campaignButton"
        val toggleW = 164
        val toggleH = 94
        val toggleX = (800 - toggleW) / 2
        val toggleY = 498
        val other = if (screen == Screen.CAMPAIGN) Screen.BOOTCAMP else Screen.CAMPAIGN
        drawButton(toggleKey, toggleW, toggleH, toggleX, toggleY, {
            screen = other
            snapToLoadable()
        }, disabled = missionListOf(other).isEmpty())
        drawButton("bonusMapButton", 126, 71, 800 - 90 - 126, 510, { running = false })

        text.draw("Back", 90 + 63, 510 + 52, 12, SDLColor(220, 220, 220))
        text.draw("Start", 800 - 90 - 126 + 63, 510 + 52, 12, SDLColor(220, 220, 220))
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

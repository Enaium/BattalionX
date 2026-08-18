package cn.enaium.battalion.game.ui

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.util.Fio
import cn.enaium.battalion.util.XmlParser
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindowEventType

/**
 * The game's title / mission-select screen, shown before a match begins.
 *
 * It displays the campaign header image and the game title, then lets the player pick a
 * campaign mission (arrow keys / mouse) or a skirmish map, and press Play. The chosen
 * selection is returned to [run]'s caller, which loads the corresponding game.
 *
 * The screen runs its own SDL event loop so it can react to clicks and keys independently of
 * the in-match loop.
 */
class StartScreen(
    private val assetsDir: String,
    private val renderer: SDLRenderer,
    private val text: TextRenderer,
    private val textures: TextureManager,
) {

    /** The player's choice: a campaign mission, a skirmish map, or quit. */
    data class Selection(
        val missionIndex: Int? = null,
        val skirmishMap: String? = null,
        val quit: Boolean = false,
    )

    private data class Option(val label: String, val missionIndex: Int? = null, val skirmishMap: String? = null)

    private val options = mutableListOf<Option>()
    private var selected = 0
    private var running = true

    /** Builds the option list (campaign missions first, then skirmish maps). */
    private fun buildOptions() {
        options.clear()
        val missions = GameLoader.findCampaign(assetsDir).orEmpty()
        for (m in missions) {
            options.add(Option("Mission ${m.number}: ${m.name.ifEmpty { "Untitled" }}", missionIndex = missions.indexOf(m)))
        }
        // Skirmish maps: any map XML in the assets directory.
        for (file in Fio.listDir("$assetsDir/binaryData").orEmpty()) {
            if (!file.endsWith(".bin")) continue
            val text = Fio.readText("$assetsDir/binaryData/$file") ?: continue
            val root = XmlParser.parseOrNull(text) ?: continue
            if (root.name != "map") continue
            val name = root.child("name")?.text?.ifEmpty { "Skirmish" } ?: "Skirmish"
            options.add(Option("Skirmish: $name", skirmishMap = file))
        }
    }

    /** Runs the menu loop and returns the player's selection. */
    fun run(): Selection {
        buildOptions()
        if (options.isEmpty()) return Selection(missionIndex = 0)

        running = true
        selected = 0
        while (running) {
            pollEvents()
            draw()
            SDL.delay(16)
        }
        val chosen = options[selected.coerceIn(0, options.lastIndex)]
        return Selection(chosen.missionIndex, chosen.skirmishMap)
    }

    private fun pollEvents() {
        while (true) {
            val event = SDL.pollEvent() ?: break
            when (event) {
                is SDLEvent.Quit -> { running = false; selected = 0 }
                is SDLEvent.Window ->
                    if (event.type == SDLWindowEventType.CLOSE_REQUESTED) { running = false; selected = 0 }
                is SDLEvent.Key -> {
                    if (!event.down || event.repeat) continue
                    when (event.keycode) {
                        SDLKeycode.UP, SDLKeycode.W -> move(-1)
                        SDLKeycode.DOWN, SDLKeycode.S -> move(1)
                        SDLKeycode.RETURN, SDLKeycode.SPACE -> running = false
                        SDLKeycode.ESCAPE -> { running = false; selected = 0 }
                        else -> Unit
                    }
                }
                is SDLEvent.MouseButton -> {
                    if (event.down) handleClick(event.x.toInt(), event.y.toInt())
                }
                else -> Unit
            }
        }
    }

    private fun move(delta: Int) {
        selected = (selected + delta).coerceIn(0, options.lastIndex)
    }

    private fun handleClick(x: Int, y: Int) {
        // Clicking an option row selects it; clicking the Play button starts the game.
        val rowH = 22
        val listX = 90
        val listY = 278
        if (x >= listX && x < listX + 620) {
            val visible = visibleCount()
            val offset = scrollOffset(visible)
            val idx = offset + (y - listY) / rowH
            if (idx in options.indices && (y - listY) % rowH >= 0) {
                selected = idx
                running = false
            }
        }
        // Play button.
        if (x in 90..390 && y in 470..530) running = false
    }

    private fun visibleCount(): Int {
        val listTop = 278
        val listBottom = 462
        return (listBottom - listTop) / 22
    }

    private fun scrollOffset(visible: Int): Int =
        (selected - visible / 2).coerceIn(0, (options.size - visible).coerceAtLeast(0))

    private fun draw() {
        renderer.drawColor = SDLColor(18, 22, 30)
        renderer.clear()

        // Campaign header image.
        val header = textures.textureFromPath("startHeader", "$assetsDir/images/151_ep3_campaign_header_ep3_campaign_header.jpg")
        if (header != null) {
            renderer.renderTexture(header, dst = SDLFRect(50f, 30f, 700f, 175f))
        }

        // Title.
        text.drawCentered("BATTALION VENGEANCE", 400, 212, 28, SDLColor(230, 220, 180))
        text.drawCentered("Choose a mission — ${options.size} available", 400, 246, 16, SDLColor(150, 160, 185))

        // Option list (windowed / scrollable).
        val visible = visibleCount()
        val offset = scrollOffset(visible)
        var y = 278
        for (i in offset until minOf(offset + visible, options.size)) {
            val opt = options[i]
            val isSel = i == selected
            val color = if (isSel) SDLColor(120, 220, 120) else SDLColor(200, 200, 210)
            if (isSel) {
                renderer.drawColor = SDLColor(60, 80, 60)
                renderer.fillRect(SDLRect(90, y - 1, 620, 20))
            }
            text.draw(opt.label, 100, y, 16, color)
            y += 22
        }

        // Play button.
        renderer.drawColor = SDLColor(40, 90, 50)
        renderer.fillRect(SDLRect(90, 470, 300, 60))
        renderer.drawColor = SDLColor(80, 160, 90)
        renderer.drawRect(SDLRect(90, 470, 300, 60))
        text.drawCentered("PLAY", 240, 488, 26, SDLColor(230, 255, 230))

        // Quit hint.
        text.draw("ESC: Quit   |   Up/Down: Select   |   Enter / Click: Play", 90, 545, 14, SDLColor(140, 140, 160))

        renderer.present()
    }
}
package cn.enaium.battalion.editor

import cn.enaium.battalion.data.MapConfig
import cn.enaium.battalion.game.ui.TextRenderer
import cn.enaium.battalion.game.ui.configurePresentation
import cn.enaium.battalion.game.ui.pointerToLogical
import cn.enaium.battalion.game.ui.TextureManager
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLKeymod
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.battalion.game.ui.saveSurfacePng

/**
 * The map editor's frame loop: owns the editor's input forwarding and its own texture/font
 * managers, drives [MapEditor], and saves a capture when a frame limit is set.
 *
 * Both entry points use it: the CLI's `--editor` (its own window) and the main menu's Level
 * Editor button (the game's window, resized).
 */
object MapEditorApp {

    /** The original editor's stage (`EditorInterface.MOVIE_WIDTH` / `MOVIE_HEIGHT`). */
    const val WINDOW_W = MapEditor.W
    const val WINDOW_H = MapEditor.H

    /** What an editor session returns to its caller. */
    class Result(
        /** The map that was open when the session ended. */
        val mapId: String?,
        /** The map document to play when the user pressed Test, or null. */
        val testMap: MapConfig?,
    )

    /**
     * One editor session: its own texture and font managers, the model and the editor, with
     * [frame] to advance one frame and [capture] to save the current one. [run] is a loop over
     * this; `--editorcheck` drives the same session step by step.
     */
    class EditorSession(
        val window: SDLWindow,
        val renderer: SDLRenderer,
        assetsDir: String,
        mapId: String? = null,
        onStatus: (String) -> Unit = {},
        /**
         * An existing, already-loaded manager to draw with (the `--assetreport` diagnostic
         * records the editor's requests through its own). When null the session owns one and
         * closes it; a passed-in manager is left open.
         */
        textureManager: TextureManager? = null,
    ) : AutoCloseable {
        // The editor's own managers: closing them must never touch the game's textures.
        private val ownsTextures = textureManager == null
        private val textures = textureManager ?: TextureManager(assetsDir, renderer).also { it.load() }
        private val text = TextRenderer(renderer).also { it.load() }
        val model = EditorModel(assetsDir)
        val editor = MapEditor(model, renderer, textures, text)

        init {
            configurePresentation(renderer, MapEditor.W, MapEditor.H)
            if (mapId != null && model.open(mapId)) {
                onStatus("EDITOR: opened $mapId (${model.cols()}×${model.rows()})")
            } else {
                model.newMap("Untitled Map", EditorModel.MIN_COLS, EditorModel.MIN_ROWS)
                onStatus("EDITOR: new ${model.cols()}×${model.rows()} map (Ctrl+1 loads a game map)")
            }
        }

        /** Polls SDL events; returns false when the window asked to close. */
        fun pollEvents(): Boolean {
            var open = true
            while (true) {
                val event = SDL.pollEvent() ?: break
                val shift = (SDL.modState and SDLKeymod.SHIFT) != 0
                when (event) {
                    is SDLEvent.Quit -> open = false
                    is SDLEvent.Window ->
                        if (event.type == SDLWindowEventType.CLOSE_REQUESTED) open = false
                    is SDLEvent.Key -> {
                        if (event.down && !event.repeat) editor.onKeyDown(event.keycode)
                        editor.setShift((event.modifiers and SDLKeymod.SHIFT) != 0)
                    }
                    is SDLEvent.MouseMotion -> {
                        editor.setShift(shift)
                        val (mx, my) = pointerToLogical(renderer, event.x.toInt(), event.y.toInt())
                        editor.onMouseMove(mx, my)
                    }
                    is SDLEvent.MouseButton -> {
                        editor.setShift(shift)
                        val (mx, my) = pointerToLogical(renderer, event.x.toInt(), event.y.toInt())
                        if (event.down) editor.onMouseDown(event.button, mx, my)
                        else editor.onMouseUp(event.button, mx, my)
                    }
                    is SDLEvent.MouseWheel -> editor.onWheel(event.y)
                    else -> {}
                }
            }
            return open
        }

        /** Advances and draws one frame. */
        fun frame(dtMs: Float = 16f) {
            editor.update(dtMs / 1000f)
            editor.render()
            renderer.present()
        }

        /** Saves the current frame as a PNG; returns the path (or null when it failed). */
        fun capture(path: String): String? {
            // Read the back buffer BEFORE presenting: on macOS/Metal a presented drawable reads
            // back as black, and the editor's frame loop presents every frame. Re-draw into the
            // back buffer, read it, then present so the window also shows the captured frame.
            editor.render()
            val surface = renderer.renderReadPixels(null) ?: return null
            saveSurfacePng(surface, path)
            surface.close()
            renderer.present()
            return path
        }

        override fun close() {
            text.close()
            if (ownsTextures) textures.close()
        }
    }

    /**
     * Runs the editor on [window]/[renderer] until the window is closed, the editor asks to quit,
     * the user asks for Test, or [maxFrames] frames were drawn. With [maxFrames] a PNG of the
     * last frame is written to [capturePath].
     */
    fun run(
        window: SDLWindow,
        renderer: SDLRenderer,
        assetsDir: String,
        mapId: String? = null,
        maxFrames: Int = 0,
        capturePath: String? = null,
        onStatus: (String) -> Unit = {},
    ): Result {
        val session = EditorSession(window, renderer, assetsDir, mapId, onStatus)
        session.use {
            var running = true
            var frame = 0
            var lastTick = SDL.getTicks().toLong()
            while (running) {
                running = session.pollEvents()
                if (!running) break
                val now = SDL.getTicks().toLong()
                val dt = (now - lastTick).coerceIn(1, 100).toFloat()
                lastTick = now
                session.frame(dt)
                if (session.editor.testRequested) {
                    return Result(session.editor.model.mapId, session.editor.model.doc)
                }
                if (session.editor.quitRequested) break
                frame++
                if (maxFrames > 0 && frame >= maxFrames) {
                    if (capturePath != null) {
                        if (session.capture(capturePath) != null) onStatus("saved $capturePath")
                    }
                    onStatus(
                        "EDITOR: frame $frame, map=${session.model.mapId}, " +
                            "size=${session.model.cols()}×${session.model.rows()}, " +
                            "players=${session.model.countPlayers()}",
                    )
                    break
                }
                SDL.delay(16)
            }
            return Result(session.model.mapId, null)
        }
    }
}

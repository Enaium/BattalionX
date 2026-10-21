package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindow

/**
 * The fixed logical canvas the game draws to. On Android the window is the whole screen, so
 * the renderer letterboxes this canvas; desktop windows already match it.
 */
object Presentation {
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    internal fun set(width: Int, height: Int) {
        this.width = width
        this.height = height
    }
}

/** Platform part of [configurePresentation] (no-op on desktop). */
expect fun configurePlatformPresentation(renderer: SDLRenderer, width: Int, height: Int)

/** Platform window setup right after creation (Android: fullscreen/immersive). */
expect fun configurePlatformWindow(window: SDLWindow)

/** Sets the logical canvas and asks the platform to present it (letterboxed on Android). */
fun configurePresentation(renderer: SDLRenderer, width: Int, height: Int) {
    Presentation.set(width, height)
    configurePlatformPresentation(renderer, width, height)
}

/**
 * Maps a window-coordinate point to the logical canvas. Returns the input unchanged when the
 * platform does not use logical presentation (desktop) or no rectangle is reported.
 */
fun pointerToLogical(renderer: SDLRenderer, x: Int, y: Int): Pair<Int, Int> {
    val rect = renderer.logicalPresentationRect ?: return x to y
    if (rect.width <= 0f || rect.height <= 0f) return x to y
    val w = Presentation.width
    val h = Presentation.height
    if (w <= 0 || h <= 0) return x to y
    val sx = w / rect.width
    val sy = h / rect.height
    return ((x - rect.x) * sx).toInt() to ((y - rect.y) * sy).toInt()
}

/** The mouse position converted to the logical canvas. */
fun logicalMousePosition(renderer: SDLRenderer): Pair<Int, Int> {
    val m = SDL.mouseState
    return pointerToLogical(renderer, m.x.toInt(), m.y.toInt())
}

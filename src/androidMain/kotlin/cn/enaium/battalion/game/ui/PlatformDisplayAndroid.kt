package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDLFloatPoint
import cn.enaium.sdl.SDLLogicalPresentation
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindow

actual fun configurePlatformWindow(window: SDLWindow) {
    // SDL's Android layer hides the system bars for a fullscreen window.
    window.fullscreen = true
}

actual fun configurePlatformPresentation(renderer: SDLRenderer, width: Int, height: Int) {
    // The Android surface is the whole screen; draw the game's fixed canvas letterboxed.
    renderer.scale = SDLFloatPoint(1f, 1f)
    renderer.setLogicalPresentation(width, height, SDLLogicalPresentation.LETTERBOX)
}

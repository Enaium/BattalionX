package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindow

actual fun configurePlatformPresentation(renderer: SDLRenderer, width: Int, height: Int) = Unit

actual fun configurePlatformWindow(window: SDLWindow) = Unit

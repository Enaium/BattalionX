package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDLSurface
import cn.enaium.sdl.image.SDLImage

actual fun saveSurfacePng(surface: SDLSurface, path: String): Boolean = try {
    SDLImage.savePNG(surface, path)
    true
} catch (t: Throwable) {
    false
}

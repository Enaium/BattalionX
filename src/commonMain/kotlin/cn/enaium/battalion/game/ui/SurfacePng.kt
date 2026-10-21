package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDLSurface

/** Writes a renderer read-back surface to a PNG file. Desktop uses SDL_image; Android uses Bitmap. */
expect fun saveSurfacePng(surface: SDLSurface, path: String): Boolean

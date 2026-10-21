package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDLColor

/**
 * A decoded image in a known layout: four bytes per pixel (R, G, B, A), rows tightly
 * packed, top row first. Every platform decoder produces this shape so the palette-swap and
 * texture-upload code is shared.
 */
class RgbaImage(val width: Int, val height: Int, val bytes: ByteArray) {
    val pitch: Int get() = width * 4
}

/** A rasterized text line: RGBA bytes in the same layout as [RgbaImage]. */
class RgbaText(val width: Int, val height: Int, val bytes: ByteArray) {
    val pitch: Int get() = width * 4
}

/** Decodes a PNG/JPEG file into [RgbaImage], or null when it cannot be read. */
expect fun decodeRgbaImage(path: String): RgbaImage?

/**
 * Rasterizes text for [TextRenderer]. Desktop uses SDL_ttf; Android uses the platform's
 * `Paint`/`Canvas` (the SDL_ttf JNI library is not published for the Android JVM target).
 *
 * Glyphs are rendered at [superSample] times the requested size and drawn back down by
 * [TextRenderer], which keeps text crisp.
 */
expect class TextRasterizer() {
    val superSample: Int

    /** The rendered width of [text] at the logical size [sizePx]. */
    fun measure(text: String, sizePx: Int): Int

    /** Rasterizes one line at `sizePx * superSample` device pixels. */
    fun render(text: String, sizePx: Int, color: SDLColor): RgbaText?

    fun close()
}

/**
 * One-shot effects and looping background music. Desktop uses SDL_mixer; Android uses
 * `SoundPool` + `MediaPlayer` (the SDL_mixer JNI library is not published for the Android JVM
 * target). Implementations cache decoded audio internally.
 */
interface AudioBackend {
    fun playSound(path: String, volume: Float)
    fun playMusic(path: String, volume: Float)
    fun stopMusic()
    fun stopAll()
    fun close()
}

/** Creates the platform backend, or null when audio cannot be initialised (silent game). */
expect fun createAudioBackend(): AudioBackend?

package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.image.SDLImage
import cn.enaium.sdl.mixer.SDLAudio
import cn.enaium.sdl.SDLAudioDeviceID
import cn.enaium.sdl.mixer.SDLMixer
import cn.enaium.sdl.mixer.SDLTrack
import cn.enaium.sdl.ttf.SDLTTF
import cn.enaium.sdl.ttf.SDLTTFFont

// SDL_image / SDL_ttf / SDL_mixer implementations, shared by the desktop JVM and the native
// targets (all three libraries are published for those). Android JVM has no such artifacts and
// uses the platform implementations in androidMain instead.

actual fun decodeRgbaImage(path: String): RgbaImage? {
    val surface = try {
        SDLImage.load(path)
    } catch (t: Throwable) {
        null
    } ?: return null
    val rgba = try {
        surface.convert(SDLPixelFormat.RGBA32)
    } catch (t: Throwable) {
        surface.close()
        return null
    }
    val w = rgba.width
    val h = rgba.height
    val pitch = rgba.pitch
    val bytes = if (rgba.lock()) {
        val px = rgba.pixels
        val out = ByteArray(w * 4 * h)
        for (y in 0 until h) {
            px.copyInto(out, y * w * 4, y * pitch, y * pitch + w * 4)
        }
        rgba.unlock()
        out
    } else {
        null
    }
    rgba.close()
    surface.close()
    return if (bytes == null) null else RgbaImage(w, h, bytes)
}

actual class TextRasterizer actual constructor() {
    actual val superSample: Int = 4

    private var baseFont: SDLTTFFont? = null
    private val fontCache = HashMap<Int, SDLTTFFont>()

    init {
        if (!SDLTTF.init()) error("TTF_Init failed: ${SDLTTF.error()}")
        val fontPath = resolveFont() ?: error("no usable system font found")
        baseFont = SDLTTF.openFont(fontPath, 16f)
    }

    /** Probes well-known system fonts and returns the first readable one. */
    private fun resolveFont(): String? {
        val candidates = listOf(
            // macOS
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
            // Linux
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
            // Windows
            "C:/Windows/Fonts/arial.ttf",
            "C:/Windows/Fonts/segoeui.ttf",
        )
        for (path in candidates) {
            val font = try {
                SDLTTF.openFont(path, 12f)
            } catch (t: Throwable) {
                continue
            }
            font.close()
            return path
        }
        return null
    }

    private fun fontFor(sizePx: Int): SDLTTFFont? {
        val base = baseFont ?: return null
        return fontCache.getOrPut(sizePx) {
            SDLTTF.copyFont(base).also { it.size = sizePx * superSample.toFloat() }
        }
    }

    actual fun measure(text: String, sizePx: Int): Int {
        val f = fontFor(sizePx) ?: return 0
        return ((f.getStringSize(text)?.x ?: 0) + superSample / 2) / superSample
    }

    actual fun render(text: String, sizePx: Int, color: SDLColor): RgbaText? {
        val font = fontFor(sizePx) ?: return null
        val surface = SDLTTF.renderTextBlended(font, text, color) ?: return null
        val w = surface.width
        val h = surface.height
        val pitch = surface.pitch
        val bytes = ByteArray(w * 4 * h)
        val px = surface.pixels
        for (y in 0 until h) {
            var src = y * pitch
            var dst = y * w * 4
            for (x in 0 until w) {
                bytes[dst] = px[src]
                bytes[dst + 1] = px[src + 1]
                bytes[dst + 2] = px[src + 2]
                bytes[dst + 3] = px[src + 3]
                src += 4
                dst += 4
            }
        }
        surface.close()
        return RgbaText(w, h, bytes)
    }

    actual fun close() {
        fontCache.values.forEach { it.close() }
        fontCache.clear()
        baseFont?.close()
        baseFont = null
        SDLTTF.quit()
    }
}

actual fun createAudioBackend(): AudioBackend? {
    if (!SDLMixer.init()) return null
    val mixer = try {
        SDLMixer.createMixerDevice(SDLAudioDeviceID.DEFAULT_PLAYBACK)
    } catch (t: Throwable) {
        SDLMixer.quit()
        return null
    }
    return object : AudioBackend {
        private val cache = mutableMapOf<String, SDLAudio>()
        private var music: SDLTrack? = null

        private fun audio(path: String): SDLAudio? = cache.getOrPut(path) {
            try {
                mixer.loadAudio(path) ?: error("cannot load $path")
            } catch (t: Throwable) {
                return null
            }
        }

        override fun playSound(path: String, volume: Float) {
            try {
                val a = audio(path) ?: return
                val track = mixer.createTrack() ?: return
                if (!track.setAudio(a)) return
                track.gain = volume
                track.play()
            } catch (t: Throwable) {
                // Best effort.
            }
        }

        override fun playMusic(path: String, volume: Float) {
            try {
                stopMusic()
                val a = audio(path) ?: return
                val track = mixer.createTrack() ?: return
                if (!track.setAudio(a)) return
                track.gain = volume
                track.tag("music")
                if (!track.play()) return
                // Set loops AFTER play(): MIX_SetTrackLoops does not stick on a stopped track.
                track.loops = -1
                music = track
            } catch (t: Throwable) {
                // Best effort.
            }
        }

        override fun stopMusic() {
            try {
                music?.stop()
            } catch (t: Throwable) {
                // ignore
            }
            music = null
        }

        override fun stopAll() {
            stopMusic()
            try {
                mixer.stopAllTracks()
            } catch (t: Throwable) {
                // ignore
            }
        }

        override fun close() {
            stopAll()
            for (a in cache.values) a.close()
            cache.clear()
            try {
                mixer.close()
            } catch (t: Throwable) {
                // ignore
            }
            SDLMixer.quit()
        }
    }
}

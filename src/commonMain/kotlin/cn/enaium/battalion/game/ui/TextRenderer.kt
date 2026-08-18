package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.ttf.SDLTTF
import cn.enaium.sdl.ttf.SDLTTFFont

/**
 * Text rendering via sdl-ttf-kmp (SDL_ttf bindings).
 *
 * Every draw call rasterizes the string with FreeType into an alpha surface, uploads it into a
 * texture and blits it at 1:1 scale. Textures are cached per (text, size, color) so static
 * strings cost one rasterization each; the cache is bounded and drops the least recently used
 * entries. Glyphs are supersampled (rendered at [SUPER_SAMPLE]x) so text stays crisp when
 * downscaled.
 */
class TextRenderer(private val renderer: SDLRenderer) {

    private var baseFont: SDLTTFFont? = null
    private val fontCache = HashMap<Int, SDLTTFFont>() // sizePx -> font copy
    private val textureCache = HashMap<String, SDLTexture>()
    private val cacheOrder = ArrayDeque<String>()

    private fun putTexture(key: String, tex: SDLTexture) {
        if (textureCache.containsKey(key)) return
        textureCache[key] = tex
        cacheOrder.addLast(key)
        while (cacheOrder.isNotEmpty() && textureCache.size > MAX_CACHE) {
            val oldest = cacheOrder.removeFirst()
            textureCache.remove(oldest)?.close()
        }
    }

    /** Initialises SDL_ttf and opens the first available system font. */
    fun load() {
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

    fun close() {
        textureCache.values.forEach { it.close() }
        textureCache.clear()
        fontCache.values.forEach { it.close() }
        fontCache.clear()
        baseFont?.close()
        baseFont = null
        SDLTTF.quit()
    }

    /** A font copy rasterized at [sizePx] * [SUPER_SAMPLE] (supersampled). */
    private fun fontFor(sizePx: Int): SDLTTFFont? {
        val base = baseFont ?: return null
        return fontCache.getOrPut(sizePx) {
            SDLTTF.copyFont(base).also { it.size = sizePx * SUPER_SAMPLE.toFloat() }
        }
    }

    /** The rendered width of [text] at the given pixel size. */
    fun measure(text: String, sizePx: Int): Int {
        val f = fontFor(sizePx) ?: return 0
        return ((f.getStringSize(text)?.x ?: 0) + SUPER_SAMPLE / 2) / SUPER_SAMPLE
    }

    /** Rasterizes [text] into a cached texture. */
    private fun textureFor(text: String, sizePx: Int, color: SDLColor): SDLTexture? {
        val font = fontFor(sizePx) ?: return null
        val key = "$sizePx|${color.r},${color.g},${color.b}|$text"
        textureCache[key]?.let { return it }
        val surface = SDLTTF.renderTextBlended(font, text, color) ?: return null
        val tex = renderer.createTexture(
            format = surface.format,
            access = SDLTextureAccess.STATIC,
            width = surface.width,
            height = surface.height,
        )
        tex.update(null, surface.pixels, surface.pitch)
        tex.blendMode = SDLBlendMode.BLEND
        tex.scaleMode = SDLScaleMode.LINEAR
        surface.close()
        putTexture(key, tex)
        return tex
    }

    private fun dstRect(tex: SDLTexture, x: Int, y: Int): SDLFRect {
        val size = tex.size
        return SDLFRect(x.toFloat(), y.toFloat(), size.x / SUPER_SAMPLE, size.y / SUPER_SAMPLE)
    }

    /** Draws [text] with its top-left at (x, y), glyphs [sizePx] pixels tall. */
    fun draw(text: String, x: Int, y: Int, sizePx: Int, color: SDLColor = SDLColor(255, 255, 255)) {
        if (text.isEmpty()) return
        val tex = textureFor(text, sizePx, color) ?: return
        renderer.renderTexture(tex, dst = dstRect(tex, x, y))
    }

    /** Draws [text] centered horizontally at (cx, y). */
    fun drawCentered(text: String, cx: Int, y: Int, sizePx: Int, color: SDLColor = SDLColor(255, 255, 255)) {
        draw(text, cx - measure(text, sizePx) / 2, y, sizePx, color)
    }

    companion object {
        const val SUPER_SAMPLE = 4
        const val MAX_CACHE = 256
    }
}
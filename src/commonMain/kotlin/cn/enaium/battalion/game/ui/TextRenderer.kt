package cn.enaium.battalion.game.ui

import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess

/**
 * Text rendering: the platform rasterizer ([TextRasterizer]) turns a string into RGBA pixels;
 * this class uploads them into a texture and draws them 1:1.
 *
 * Glyphs are supersampled by the backend and drawn back down here, so text stays crisp.
 * Textures are cached per (text, size, color) so static strings cost one rasterization each;
 * the cache is bounded and drops the least recently used entries.
 */
class TextRenderer(private val renderer: SDLRenderer) {

    private val rasterizer = TextRasterizer()
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

    /**
     * Kept for the call sites that initialise the renderer before use. The backend is created
     * in the constructor (and probes its font/typeface there), so there is nothing to load.
     */
    fun load() = Unit

    fun close() {
        textureCache.values.forEach { it.close() }
        textureCache.clear()
        cacheOrder.clear()
        rasterizer.close()
    }

    /** The rendered width of [text] at the given pixel size. */
    fun measure(text: String, sizePx: Int): Int = rasterizer.measure(text, sizePx)

    /** Rasterizes [text] into a cached texture. */
    private fun textureFor(text: String, sizePx: Int, color: SDLColor): SDLTexture? {
        val key = "$sizePx|${color.r},${color.g},${color.b}|$text"
        textureCache[key]?.let { return it }
        val raster = rasterizer.render(text, sizePx, color) ?: return null
        if (raster.width <= 0 || raster.height <= 0) return null
        val tex = renderer.createTexture(
            format = SDLPixelFormat.RGBA32,
            access = SDLTextureAccess.STATIC,
            width = raster.width,
            height = raster.height,
        )
        tex.update(null, raster.bytes, raster.pitch)
        tex.blendMode = SDLBlendMode.BLEND
        tex.scaleMode = SDLScaleMode.LINEAR
        putTexture(key, tex)
        return tex
    }

    private fun dstRect(tex: SDLTexture, x: Int, y: Int): SDLFRect {
        val size = tex.size
        val ss = rasterizer.superSample
        return SDLFRect(x.toFloat(), y.toFloat(), size.x / ss, size.y / ss)
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
        const val MAX_CACHE = 256
    }
}

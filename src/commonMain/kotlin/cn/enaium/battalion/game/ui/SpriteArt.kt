package cn.enaium.battalion.game.ui

import cn.enaium.battalion.data.SpriteKey
import cn.enaium.battalion.data.TileManager
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLTexture

/** One frame of a sprite sheet: the texture it lives in plus the source rect of the frame. */
class SpriteFrame(
    val texture: SDLTexture,
    val src: SDLFRect,
    val textureWidth: Int,
    val textureHeight: Int,
)

/**
 * How a tile's art fills its cell (the original `TileSwatch.getSprite` rules):
 *
 *  - [FILL]  — one cell-sized frame covers the cell exactly.
 *  - [TALL]  — the art is taller than the cell and is anchored to the cell's bottom edge, so it
 *              overflows upward (mountains 56×65, hills, structures, volcano's 112px frames).
 *  - [STRIP] — the art is SHORTER than the cell (the sea/shore pieces are 112×28): it is drawn
 *              as it is, without stretching, at the cell's top edge.
 */
enum class TileArtMode { FILL, TALL, STRIP }

/**
 * Frame resolution shared by the map pass, the minimap and the editor's palette icons.
 *
 * A bitmap **wider** than one tile is sliced into tile-wide frames that play left→right (the
 * animated sheets: reef / archipelago / rock formation are 224×56); a bitmap that is *taller* is
 * drawn whole, bottom-anchored; a strip shorter than a tile is repeated sideways. Showing such a
 * bitmap as one image — which the minimap and the palette used to do — squashes the whole strip
 * into a single tile, so the frame has to come from here.
 */
object SpriteArt {

    /** The same frame with a different source rect (an icon shows one tile-wide piece). */
    fun withSrc(frame: SpriteFrame, src: SDLFRect): SpriteFrame =
        SpriteFrame(frame.texture, src, frame.textureWidth, frame.textureHeight)

    /**
     * The [frame]-th frame of tile art [key] drawn into a [cellW]×[cellH] cell, plus how the
     * caller should place it. Null when the texture cannot be resolved.
     */
    fun tileFrame(
        textures: TextureManager,
        key: String,
        cellW: Int = TileManager.TILE_WIDTH,
        cellH: Int = TileManager.TILE_HEIGHT,
        frame: Int = 0,
    ): Pair<SpriteFrame, TileArtMode>? {
        val tex = textures.textureOrFallback(key) ?: return null
        // The texture's OWN size, not the index's: a fallback key (sea_0_v1 → sea_v1) resolves to
        // a different image, and the slicing below has to use the image that is actually drawn.
        val texW = tex.size.x.toInt().takeIf { it > 0 } ?: cellW
        val texH = tex.size.y.toInt().takeIf { it > 0 } ?: cellH
        return when {
            // Shorter than the cell: the piece is drawn as it is (no stretch) at the cell's top.
            // The sea/shore pieces are 112×28 — four 28×28 frames side by side (the original's
            // `generateJoinedTile` copies `height`-sized pieces stepping by `height`), so the
            // frame is the SQUARE piece, not the whole strip.
            texH < cellH -> {
                val piece = if (texW % texH == 0 && texH > 0) texH else texW
                val frames = if (piece > 0) texW / piece else 1
                val i = if (frames > 0) (((frame % frames) + frames) % frames) else 0
                SpriteFrame(
                    tex,
                    SDLFRect((i * piece).toFloat(), 0f, piece.toFloat(), texH.toFloat()),
                    texW, texH,
                ) to TileArtMode.STRIP
            }

            // Tall: wider than one tile is impossible to slice here, so draw it whole.
            texH > cellH && texW <= cellW ->
                SpriteFrame(
                    tex,
                    SDLFRect(0f, 0f, minOf(texW, cellW).toFloat(), texH.toFloat()),
                    texW, texH,
                ) to TileArtMode.TALL

            // Horizontal frame grid: the sheet is a strip of tile-wide, full-height frames.
            texW > cellW && cellW > 0 && texW % cellW == 0 -> {
                val frames = texW / cellW
                val i = (((frame % frames) + frames) % frames)
                SpriteFrame(
                    tex,
                    SDLFRect((i * cellW).toFloat(), 0f, cellW.toFloat(), texH.toFloat()),
                    texW, texH,
                ) to if (texH > cellH) TileArtMode.TALL else TileArtMode.FILL
            }

            else -> SpriteFrame(
                tex,
                SDLFRect(0f, 0f, minOf(texW, cellW).toFloat(), minOf(texH, cellH).toFloat()),
                texW, texH,
            ) to TileArtMode.FILL
        }
    }

    /**
     * The idle frame (column 0, row 0) of a unit sheet: the sprite a unit is drawn with before any
     * facing or animation is applied. Cell geometry comes from the unit's [SpriteKey] — 6 columns
     * of [SpriteKey.cellWidth] and [SpriteKey.rows] animation rows.
     */
    fun unitFrame(
        textures: TextureManager,
        key: SpriteKey,
        sheet: String,
        teamColor: SDLColor? = null,
        facing: Int = -1,
    ): SpriteFrame? {
        val tex = if (teamColor != null) {
            textures.textureForTeam(sheet, teamColor) ?: textures.textureOrFallback(sheet)
        } else {
            textures.textureOrFallback(sheet)
        } ?: return null
        // Read the size from the texture itself: the sheet may not have been loaded (and thus not
        // sized) before this call — a unit the current map does not spawn, for instance.
        val texW = tex.size.x.toInt().takeIf { it > 0 } ?: return null
        val texH = tex.size.y.toInt().takeIf { it > 0 } ?: return null
        val cellW = if (key.cellWidth > 0) key.cellWidth else texW
        val cellH = texH / key.rows.coerceAtLeast(1)
        val src = if (texW == cellW * 6) {
            // Column 0 is the idle pose; [facing] selects the compass direction when asked for
            // (the editor's brush preview shows the direction it will place).
            val column = if (facing >= 0) key.facingColumns.getOrElse(facing.coerceIn(0, 3)) { 0 } else 0
            SDLFRect((column * cellW).toFloat(), 0f, cellW.toFloat(), cellH.toFloat())
        } else {
            // Unknown sheet geometry: show what fits rather than stretching the whole sheet.
            SDLFRect(0f, 0f, minOf(texW, cellW).toFloat(), minOf(texH, cellH).toFloat())
        }
        return SpriteFrame(tex, src, texW, texH)
    }

    /**
     * [src] placed inside the box ([x],[y],[w],[h]) at its natural aspect ratio (centred), so a
     * full-height frame or a wide strip is shown whole instead of being squashed to fit.
     */
    fun fitInto(src: SDLFRect, x: Float, y: Float, w: Float, h: Float): SDLFRect {
        if (src.width <= 0f || src.height <= 0f) return SDLFRect(x, y, w, h)
        val scale = minOf(w / src.width, h / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        return SDLFRect(x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh)
    }
}

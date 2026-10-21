package cn.enaium.battalion.game.ui

import cn.enaium.battalion.data.AssetLoader
import cn.enaium.battalion.data.SpriteAtlasConfig
import cn.enaium.battalion.data.SpriteRegionConfig
import cn.enaium.battalion.mod.ModRegistry
import cn.enaium.battalion.util.Fio
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.image.SDLImage

/**
 * Loads and caches the game's image textures from the project's `assets/images/` directory.
 *
 * Each file is stored under its sprite key — `scorpion.png`, `sea_0_nw.png`,
 * `unitOptionsButton.png` — so the key is simply the file base name. This manager scans the
 * directory once, indexes every file by base name, and loads textures on demand. Loaded
 * textures are cached and released on [close]. Mods add a sprite by dropping
 * `<sprite-key>.png` into the directory.
 *
 * Sheet slicing (regions/grids for multi-frame art: avatars, explosions, cursor cells) comes
 * from `assets/data/sprites.json` ([SpriteAtlasConfig]) — no pixel offsets live in the code.
 *
 * Mods extend this index: every file under `<mod>/images/` is addressable by its id (its path
 * without the extension, e.g. `items/crystal_sword`), and a mod's file overrides a game sprite
 * of the same id. Later-loaded mods override earlier ones.
 */
class TextureManager(
    private val assetsDir: String,
    private val renderer: SDLRenderer,
) {

    private val imagesDir: String = "$assetsDir/images"

    /** The parsed `data/sprites.json` sheet registry (regions/grid slicing). */
    val atlas: SpriteAtlasConfig = AssetLoader.sprites(assetsDir) ?: SpriteAtlasConfig()

    private val index = mutableMapOf<String, String>() // sprite name -> file path
    private val cache = mutableMapOf<String, SDLTexture>()
    private val sizes = mutableMapOf<String, Pair<Int, Int>>() // sprite name -> (width, height)
    private val teamCache = mutableMapOf<Pair<String, Int>, SDLTexture>() // (sprite, teamARGB) -> tinted
    private var loaded = false

    /**
     * Mod-diagnostic hook: while [recording] is true, every sprite name the game asks for is
     * collected into [requested] (the `--assetreport` CLI diagnostic uses this to verify the
     * pruned `assets/images/` set covers all reachable content, and it tells a mod author which
     * sprites a custom map/unit needs).
     */
    var recording: Boolean = false
    val requested = mutableSetOf<String>()

    /** Scans the images directory and builds the sprite-name -> file index. */
    fun load() {
        if (loaded) return
        // Make sure the mod layers exist before the index is built (idempotent per assets dir).
        ModRegistry.ensureLoaded(assetsDir)
        for (file in Fio.listDir(imagesDir).orEmpty()) {
            if (!file.endsWith(".png") && !file.endsWith(".jpg") && !file.endsWith(".jpeg")) continue
            // Assets are stored under their sprite key already (e.g. `scorpion.png`,
            // `sea_0_nw.png`), so the key is simply the file base name.
            val name = file.substringBeforeLast('.')
            if (name.isNotEmpty() && !index.containsKey(name)) {
                index[name] = "$imagesDir/$file"
            }
        }
        // Mod images, in load order, so a later mod overrides the base game and earlier mods.
        for (mod in ModRegistry.mods) {
            for ((id, path) in mod.images) index[id] = path
        }
        loaded = true
    }

    /**
     * Rebuilds the sprite index (and drops cached textures) after the mod set changed, so
     * switching a mod off/on takes effect without restarting the game.
     *
     * The textures themselves are RETIRED, not closed: a renderer keeps the texture objects it
     * resolved (a match's terrain and unit textures, the editor's preview) and closes them out
     * from under itself — `SDL texture is closed` — the moment anything draws with them. Retired
     * textures are released together with the manager instead.
     */
    fun reloadIndex() {
        index.clear()
        retired += cache.values
        cache.clear()
        retired += teamCache.values
        teamCache.clear()
        sizes.clear()
        loaded = false
        load()
    }

    /** Textures dropped by [reloadIndex], kept alive until the manager itself is closed. */
    private val retired = mutableListOf<SDLTexture>()

    /** The file path for a sprite name, or null (used for diagnostics / fallbacks). */
    fun pathFor(name: String): String? = index[name]

    /** Returns the (width, height) of a loaded texture, or null if not loaded. */
    fun textureSize(name: String): Pair<Int, Int>? = sizes[name]

    /**
     * Resolves a named region inside an atlas sheet from `data/sprites.json`:
     * [sheet] is the sheet key, [region] either a named region or a grid cell index.
     * Returns the source rect in pixels, or null when the sheet/region is unknown.
     */
    fun region(sheet: String, region: String): SpriteRegionConfig? {
        val s = atlas.sheets[sheet] ?: return null
        s.regions[region]?.let { return it }
        // Grid fallback: region is a cell index ("3", or "r,c").
        if (s.cellWidth <= 0 || s.cellHeight <= 0) return null
        val idx = region.toIntOrNull()
        return when {
            idx != null && idx >= 0 -> SpriteRegionConfig(
                x = (idx % s.columns) * s.cellWidth,
                y = (idx / s.columns) * s.cellHeight,
                width = s.cellWidth,
                height = s.cellHeight,
            )
            else -> null
        }
    }

    /** The image key backing an atlas sheet (for [texture]/[pathFor]). */
    fun sheetImage(sheet: String): String? = atlas.sheets[sheet]?.image

    /**
     * Loads (and caches) the texture for a sprite [name]. Returns null if the sprite is
     * unknown or fails to load.
     */
    fun texture(name: String): SDLTexture? {
        if (recording) requested.add(name)
        val path = index[name] ?: return null
        return textureFromPath(name, path)
    }

    /**
     * Like [texture], but falls back to the closest available sprite when [name] is not in the
     * index.  The fallback strips the leading variant number (e.g. `sea_0_v1` → `sea_v1`) so
     * that directional / numbered variants resolve to the plain base texture.
     */
    fun textureOrFallback(name: String): SDLTexture? {
        if (recording) requested.add(name)
        texture(name)?.let { return it }
        // Try dropping a leading `<variant>_` segment: sea_0_v1 → sea_v1.
        val rest = name.substringAfter('_')
        if (rest != name && rest.contains('_')) {
            texture(rest)?.let { return it }
        }
        // Try stripping the trailing `_v1` version suffix.
        if (name.endsWith("_v1")) {
            val base = name.removeSuffix("_v1")
            texture(base)?.let { return it }
        }
        // Handle sea/shore directional variants: sea_0_v1 → sea_v1 (plain sea).
        // The directional pieces (sea_0_nw, sea_0_ne, etc.) are 112×28 strips,
        // but sea_0_v1 as a composite key should resolve to the plain 56×56 sea.
        if (name.startsWith("sea_") && name.endsWith("_v1")) {
            val base = "sea_v1"
            texture(base)?.let { return it }
        }
        // For shore variants, try to find any available shore texture as fallback.
        if (name.startsWith("shore_") && name.endsWith("_v1")) {
            // Try shore_1_v1 as a common fallback.
            texture("shore_1_v1")?.let { return it }
        }
        return null
    }

    /**
     * Loads a texture directly from a file path (for sprites not in the index, e.g. the start
     * screen header). Cached by [name].
     *
     * The image is decoded into a surface and uploaded through the renderer so the returned
     * texture is owned by this renderer and can be drawn with [SDLRenderer.renderTexture].
     *
     * NOTE: the image module's `JvmImageSurface` is a *different* class from the base module's
     * `JvmSDLSurface`, so `renderer.createTextureFromSurface` throws for these surfaces. We
     * therefore upload the pixels manually via `createTexture` + `update`, exactly like
     * [TextRenderer] does for TTF surfaces.
     */
    fun textureFromPath(name: String, path: String): SDLTexture? {
        cache[name]?.let { return it }
        val tex = try {
            val surface = SDLImage.load(path) ?: return null
            val t = renderer.createTexture(
                format = surface.format,
                access = SDLTextureAccess.STATIC,
                width = surface.width,
                height = surface.height,
            )
            t.update(null, surface.pixels, surface.pitch)
            sizes[name] = surface.width to surface.height
            surface.close()
            t.blendMode = SDLBlendMode.BLEND
            // The source art is pixel art — nearest sampling keeps it crisp when scaled;
            // linear filtering blurred the sprites/menus.
            t.scaleMode = SDLScaleMode.NEAREST
            t
        } catch (t: Throwable) {
            null
        }
        if (tex != null) cache[name] = tex
        return tex
    }

    /**
     * Returns a team-coloured texture for the unit sprite [name], or null if the sprite is
     * unknown. The returned texture has the sprite's material *repainted* into [teamColor]
     * (CPU palettization), so it is drawn un-tinted.
     *
     * This reproduces the original `PaletteSwapper.swapPalette` + the [PaletteEntry] ramps:
     * the unit art is painted with the RED palette (4 exact colours); each pixel exactly equal
     * to a RED ramp entry is swapped for the matching entry of the team's palette. An exact
     * per-pixel swap preserves the material's shading and highlights — a brightness remap does
     * not, which is why the units previously looked "flat".
     *
     * The team palette for the given team colour is one of the canonical [PaletteEntry] ramps
     * (GREEN → matcha greens, BLUE → sky blues, etc.); unresolved colours fall back to a
     * hand-built ramp from the team colour.
     */
    fun textureForTeam(name: String, teamColor: SDLColor): SDLTexture? {
        if (recording) requested.add(name)
        val p = index[name] ?: return null
        val key = name to ((teamColor.r shl 16) or (teamColor.g shl 8) or teamColor.b)
        teamCache[key]?.let { return it }
        val ramp = teamRamp(teamColor)
        val tex = try {
            val surface = SDLImage.load(p) ?: return null
            // Convert to a known packed layout (ABGR8888 = R,G,B,A byte order on little-endian).
            val rgba = surface.convert(SDLPixelFormat.RGBA32)
            surface.close()
            val w = rgba.width
            val h = rgba.height
            val pitch = rgba.pitch
            var pxIn: ByteArray
            if (rgba.lock()) {
                pxIn = rgba.pixels.copyOf()   // copy: SDL may return a detached array
                rgba.unlock()
            } else {
                rgba.close()
                return null
            }
            // Build a new repainted buffer, then upload it (does not depend on write-through).
            val out = pxIn.copyOf()
            for (y in 0 until h) {
                val row = y * pitch
                for (x in 0 until w) {
                    val o = row + x * 4
                    if (o + 3 >= out.size) break
                    val r = out[o].toInt() and 0xff
                    val g = out[o + 1].toInt() and 0xff
                    val b = out[o + 2].toInt() and 0xff
                    // Only exact RED-ramp colours are remapped; everything else (outlines,
                    // whites, greys) is left untouched.
                    val swap = swapTarget(r, g, b, ramp)
                    if (swap != null) {
                        out[o] = swap.first.toByte()
                        out[o + 1] = swap.second.toByte()
                        out[o + 2] = swap.third.toByte()
                    }
                }
            }
            rgba.close()
            val t = renderer.createTexture(
                format = SDLPixelFormat.RGBA32,
                access = SDLTextureAccess.STATIC,
                width = w,
                height = h,
            )
            t.update(null, out, pitch)
            sizes[name] = w to h
            t.blendMode = SDLBlendMode.BLEND
            // Pixel-art source: nearest sampling (see textureFromPath).
            t.scaleMode = SDLScaleMode.NEAREST
            t
        } catch (t: Throwable) {
            null
        }
        if (tex != null) teamCache[key] = tex
        return tex
    }

    // The source unit material's palette: exact 8-bit RGB entries (PaletteEntry.RED).
    private val RED_RAMP = listOf(
        Triple(102, 26, 94),
        Triple(170, 22, 44),
        Triple(233, 51, 46),
        Triple(255, 144, 133),
    )

    typealias Rgb = Triple<Int, Int, Int>

    /**
     * Canonical team ramps (PaletteEntry): each is the same 4-step ramp shape as [RED_RAMP],
     * keyed by the team's ARGB colour value as stored in the mission XML (`<color value="..."/>`,
     * e.g. `ff0000ff`). The ramp values are copied verbatim from the unpacked
     * `PaletteEntry.as` so team colours (matcha green, sky blue, …) match the original game.
     */
    private val TEAM_RAMPS: Map<UInt, List<Rgb>> = mapOf(
        0xff0000ffu to listOf(Triple(61, 49, 127), Triple(43, 95, 199), Triple(69, 164, 225), Triple(169, 207, 255)), // BLUE sky
        0xff00ff00u to listOf(Triple(30, 91, 35), Triple(95, 147, 95), Triple(143, 222, 101), Triple(241, 246, 95)), // GREEN matcha
        0xffff0000u to listOf(Triple(102, 26, 94), Triple(170, 22, 44), Triple(233, 51, 46), Triple(255, 144, 133)), // RED
        0xffff00ffu to listOf(Triple(101, 32, 96), Triple(177, 38, 156), Triple(241, 68, 222), Triple(255, 145, 233)), // PINK
        0xff00ffffu to listOf(Triple(23, 100, 120), Triple(42, 153, 181), Triple(104, 221, 242), Triple(170, 245, 255)), // TEAL
        0xffffff00u to listOf(Triple(122, 104, 30), Triple(205, 183, 34), Triple(250, 231, 30), Triple(255, 255, 128)), // YELLOW
    )

    /** Returns the swap target for an exact RED-ramp pixel, or null if the pixel is not material. */
    private fun swapTarget(src: Int, g: Int, b: Int, ramp: List<Rgb>): Rgb? {
        for (i in RED_RAMP.indices) {
            val red = RED_RAMP[i]
            if (red.first == src && red.second == g && red.third == b) {
                return ramp[i]
            }
        }
        return null
    }

    /**
     * Resolves the team palette for a team colour (the mission's ARGB value): a canonical
     * palette when the colour matches one of [TEAM_RAMPS], otherwise a hand-built 4-step ramp
     * interpolating the team colour so any custom mission colour still palettizes.
     */
    private fun teamRamp(teamColor: SDLColor): List<Rgb> {
        val argb = 0xff000000u or (teamColor.r.toUInt() shl 16) or
            (teamColor.g.toUInt() shl 8) or teamColor.b.toUInt()
        TEAM_RAMPS[argb]?.let { return it }
        // Fallback: build a shaded ramp from the raw team colour.
        val r = teamColor.r; val g = teamColor.g; val b = teamColor.b
        return listOf(
            Triple(r * 40 / 255, g * 40 / 255, b * 40 / 255),
            Triple(r * 90 / 255, g * 90 / 255, b * 90 / 255),
            Triple(r, g, b),
            Triple(r + (255 - r) * 2 / 3, g + (255 - g) * 2 / 3, b + (255 - b) * 2 / 3),
        )
    }

    /** Releases every cached texture (including the ones retired by a mod reload). */
    fun close() {
        cache.values.forEach { it.close() }
        teamCache.values.forEach { it.close() }
        cache.clear()
        teamCache.clear()
        retired.forEach { it.close() }
        retired.clear()
    }
}
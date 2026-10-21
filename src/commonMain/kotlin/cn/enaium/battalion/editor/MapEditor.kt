package cn.enaium.battalion.editor

import cn.enaium.battalion.data.PaletteEntryConfig
import cn.enaium.battalion.data.TerrainSprites
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.ui.SpriteArt
import cn.enaium.battalion.game.ui.TextRenderer
import cn.enaium.battalion.game.ui.TextureManager
import cn.enaium.battalion.game.ui.TileArtMode
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLDialogFileFilter
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLKeymod
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLTexture

/**
 * The map editor, ported from the original's own interface (`EditorInterface` in
 * BattalionVengeanceEdit):
 *
 * ```
 * 0                                                             700
 * +---------------------------------------------------------------+
 * | Save | Fill | Resize | Script | Test |                 Quit   |  y 1..37
 * +-------------------------------+-------------------------------+
 * |                               | tabs (T/U/B) at 568,611,654   |  y 52..105
 * |                               |-------------------------------|
 * |        map viewport           | rows, 124 wide, paged         |
 * |        560 × 560 at (0,40)    |                               |
 * |                               | page controls / delete button |
 * +-------------------------------+-------------------------------+
 * ```
 *
 * Chrome, tabs, highlight, panel rows, page controls, the colour radio buttons and every popup
 * follow the original numbers (`EditorInterface`, `TerrainPanel`, `UnitsPanel`, `BuildingsPanel`).
 * Interaction is the original's too: a click or a drag paints the selected material, Shift-click
 * picks the terrain under the cursor into the palette, the arrow keys scroll the view, and the
 * rules re-join the 5×5 area around every edit.
 */
class MapEditor(
    val model: EditorModel,
    private val renderer: SDLRenderer,
    private val textures: TextureManager,
    private val text: TextRenderer,
) {

    // ---------------------------------------------------------------------
    // Layout (the original's numbers)
    // ---------------------------------------------------------------------

    private val mapX = 0
    private val mapY = 40
    private val viewW = TILE * EditorModel.MIN_COLS
    private val viewH = TILE * EditorModel.MIN_ROWS
    private val panelX = W - 130
    private val panelW = 124
    private val tabY = mapY + 12
    private val tabW = 44
    private val tabH = 53
    private val panelY = tabY + tabH - 2
    private val rowH = 24

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    /** Layer being edited: 0 terrain, 1 buildings, 2 units (the original's layer numbers). */
    var depth = 0
        private set

    /** Selected row per layer, within the rows the panel currently shows. */
    private val selected = IntArray(3) { 0 }
    private val page = IntArray(3) { 0 }

    /** The selected unit colour (0..3, the panel's radio buttons). */
    var unitColor = 0
        private set

    /** The selected building colour (0..4; the last one is neutral). */
    var buildingColor = 0
        private set

    /** The delete brush armed by the units/buildings panel's delete buttons. */
    private var deleteMode = false

    /** Camera offset inside the map (the original's `scrollRect`). */
    var scrollX = 0
        private set
    var scrollY = 0
        private set

    var hoverCol = -1
        private set
    var hoverRow = -1
        private set

    private var mouseX = 0
    private var mouseY = 0
    private var leftDown = false
    private var shiftDown = false
    private var strokeActive = false
    private var lastStrokeCell: Pair<Int, Int>? = null

    /** The open popup, if any (the original's Save/Fill/Resize/Script/Test/Quit/Load). */
    private var popup: Popup = Popup.NONE
    private var popupScroll = 0

    /** Resize popup state. */
    private var resizePage = 0
    private var resizeCols = EditorModel.MIN_COLS
    private var resizeRows = EditorModel.MIN_ROWS
    private var newRectX = 0
    private var newRectY = 0
    private var draggingSize = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private var message: String? = null

    /** A path chosen in the Save panel's file dialog, acted on by [update] on the main thread. */
    private var pendingSavePath: String? = null

    /** True when the app should close the editor (Quit). */
    var quitRequested = false

    /** Set when the user asks for the map to be played (Test). */
    var testRequested = false

    private class Hit(val id: String, val x: Int, val y: Int, val w: Int, val h: Int) {
        fun hit(px: Int, py: Int): Boolean = px >= x && px < x + w && py >= y && py < y + h
    }

    private fun hit(id: String, x: Int, y: Int, w: Int, h: Int) {
        hits += Hit(id, x, y, w, h)
    }

    private val hits = mutableListOf<Hit>()
    private val popupHits = mutableListOf<Hit>()

    private enum class Popup { NONE, FILL, RESIZE, SCRIPT, TEST, LOAD, SAVE, QUIT }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    fun onMouseMove(x: Int, y: Int) {
        mouseX = x
        mouseY = y
        updateHover()
        if (popup == Popup.RESIZE && resizePage == 1 && draggingSize) {
            val grid = resizeGrid()
            newRectX = ((x - grid.x - dragOffsetX) / GRID_CELL).coerceIn(0, (grid.cols - resizeCols).coerceAtLeast(0)) * GRID_CELL
            newRectY = ((y - grid.y - dragOffsetY) / GRID_CELL).coerceIn(0, (grid.rows - resizeRows).coerceAtLeast(0)) * GRID_CELL
            return
        }
        if (popup != Popup.NONE) return
        if (leftDown && strokeActive && hoverCol >= 0) {
            val cell = hoverCol to hoverRow
            if (cell != lastStrokeCell) {
                lastStrokeCell = cell
                paint(hoverCol, hoverRow)
            }
        }
    }

    fun onMouseDown(button: Int, x: Int, y: Int) {
        mouseX = x
        mouseY = y
        updateHover()
        if (button == 1) leftDown = true
        if (popup != Popup.NONE) {
            if (button != 1) return
            val hit = popupHits.firstOrNull { it.hit(x, y) }
            if (hit != null) onPopupAction(hit.id)
            return
        }
        if (button != 1) return
        val hit = hits.firstOrNull { it.hit(x, y) }
        if (hit != null) {
            onChromeHit(hit.id)
            return
        }
        if (hoverCol >= 0) {
            strokeActive = true
            lastStrokeCell = hoverCol to hoverRow
            if (shiftDown) pickTerrain(hoverCol, hoverRow) else paint(hoverCol, hoverRow)
        }
    }

    fun onMouseUp(button: Int, x: Int, y: Int) {
        if (button == 1) {
            leftDown = false
            strokeActive = false
            lastStrokeCell = null
            draggingSize = false
        }
        mouseX = x
        mouseY = y
    }

    fun onWheel(y: Float) {
        when {
            popup == Popup.LOAD || popup == Popup.SCRIPT ->
                popupScroll = (popupScroll - y.toInt()).coerceAtLeast(0)
            popup != Popup.NONE -> {}
            shiftDown -> scrollBy(0, (y * TILE).toInt())
            else -> stepSelection(if (y > 0) -1 else 1)
        }
    }

    fun setShift(down: Boolean) {
        shiftDown = down
    }

    fun onKeyDown(keycode: Int) {
        when (keycode) {
            SDLKeycode.ESCAPE -> {
                if (popup != Popup.NONE) {
                    popup = Popup.NONE
                } else {
                    popup = if (model.dirty) Popup.QUIT else Popup.NONE
                    if (popup == Popup.NONE) quitRequested = true
                }
            }
            SDLKeycode.TAB -> {
                // Cycle in tab order: terrain -> units -> buildings -> terrain.
                depth = when (depth) {
                    0 -> 2
                    2 -> 1
                    else -> 0
                }
                deleteMode = false
            }
            SDLKeycode.Q, SDLKeycode.E -> stepSelection(if (keycode == SDLKeycode.Q) -1 else 1)
            0x31 -> if ((SDL.modState and SDLKeymod.CTRL) != 0) openLoadPopup()
        }
    }

    /**
     * Per-frame camera movement: the arrow keys (the original `EditorCamera`) and the mouse-
     * to-edge scroll for maps larger than the 10×10 viewport. The original puts invisible
     * scroll buttons on the map edges; hovering there scrolls here, which is what an author
     * expects after resizing a map and finding tiles outside the viewport.
     */
    fun update(dtSeconds: Float) {
        pendingSavePath?.let { path ->
            pendingSavePath = null
            if (model.saveAs(path)) popup = Popup.NONE
            message = model.status
        }
        if (popup != Popup.NONE) return

        // Mouse at a map edge: scroll while it stays there (diagonals included).
        if (mouseX in mapX until mapX + viewW && mouseY in mapY until mapY + viewH) {
            val step = TILE * 5 * dtSeconds
            var edgeX = 0f
            var edgeY = 0f
            if (mouseX < mapX + EDGE_SCROLL) edgeX -= step
            else if (mouseX >= mapX + viewW - EDGE_SCROLL) edgeX += step
            if (mouseY < mapY + EDGE_SCROLL) edgeY -= step
            else if (mouseY >= mapY + viewH - EDGE_SCROLL) edgeY += step
            if (edgeX != 0f || edgeY != 0f) {
                scrollX += edgeX.toInt()
                scrollY += edgeY.toInt()
                clampScroll()
            }
        }

        val keys = SDL.keyboardState
        fun down(key: Int): Boolean {
            val scancode = SDL.getScancodeFromKey(key)
            return scancode in keys.indices && keys[scancode].toInt() != 0
        }
        val speed = TILE * 5 * dtSeconds
        var dx = 0f
        var dy = 0f
        if (down(SDLKeycode.LEFT) || down(SDLKeycode.A)) dx -= speed
        if (down(SDLKeycode.RIGHT) || down(SDLKeycode.D)) dx += speed
        if (down(SDLKeycode.UP) || down(SDLKeycode.W)) dy -= speed
        if (down(SDLKeycode.DOWN) || down(SDLKeycode.S)) dy += speed
        if (dx != 0f || dy != 0f) {
            scrollX += dx.toInt()
            scrollY += dy.toInt()
            clampScroll()
        }
    }

    private fun updateHover() {
        hoverCol = -1
        hoverRow = -1
        if (popup != Popup.NONE) return
        if (mouseX in mapX until mapX + viewW && mouseY in mapY until mapY + viewH) {
            val c = (mouseX - mapX + scrollX) / TILE
            val r = (mouseY - mapY + scrollY) / TILE
            if (model.inBounds(c, r)) {
                hoverCol = c
                hoverRow = r
            }
        }
    }

    private fun scrollBy(dx: Int, dy: Int) {
        scrollX += dx
        scrollY += dy
        clampScroll()
    }

    private fun clampScroll() {
        scrollX = scrollX.coerceIn(0, maxOf(0, model.cols() * TILE - viewW))
        scrollY = scrollY.coerceIn(0, maxOf(0, model.rows() * TILE - viewH))
    }

    private fun paint(c: Int, r: Int) {
        if (deleteMode) {
            val removed = if (depth == 2) model.deleteUnit(c, r) else model.deleteBuilding(c, r)
            if (removed) message = null
            return
        }
        val item = selectedItem() ?: return
        if (model.place(c, r, item)) message = null
    }

    /** Shift-click: the terrain under the cursor becomes the selected palette row. */
    private fun pickTerrain(c: Int, r: Int) {
        val name = model.terrainNameAt(c, r) ?: return
        depth = 0
        deleteMode = false
        val rows = visibleRows()
        val index = rows.indexOfFirst { it.name == name }
        if (index >= 0) {
            selected[0] = index
            page[0] = index / rowsPerPage()
        } else {
            message = "$name has no rule document"
        }
    }

    // ---------------------------------------------------------------------
    // Panel selection
    // ---------------------------------------------------------------------

    /** The layer a tab edits: the original's tab order is terrain, units, buildings. */
    private fun layerForTab(tab: Int): Int = when (tab) {
        0 -> 0
        1 -> 2
        else -> 1
    }

    private fun rowsPerPage(): Int = if (depth == 0) 17 else 12

    private fun visibleRows(): List<EditorItem> = when (depth) {
        0 -> model.terrainItems()
        1 -> model.buildingItems(if (buildingColor <= 3) buildingColor + 1 else 0)
        else -> model.unitItems(unitColor + 1)
    }

    fun selectedItem(): EditorItem? {
        if (deleteMode) return null
        return visibleRows().getOrNull(selected[depth])
    }

    private fun stepSelection(delta: Int) {
        val rows = visibleRows().size
        if (rows == 0) return
        selected[depth] = ((selected[depth] + delta) % rows + rows) % rows
        deleteMode = false
        page[depth] = selected[depth] / rowsPerPage()
    }

    private fun onChromeHit(id: String) {
        when {
            id.startsWith("tab:") -> {
                depth = layerForTab(id.substringAfter("tab:").toInt())
                deleteMode = false
            }
            id.startsWith("row:") -> {
                selected[depth] = id.substringAfter("row:").toInt()
                deleteMode = false
            }
            id.startsWith("radio:") -> {
                val index = id.substringAfter("radio:").toInt()
                if (depth == 2) unitColor = index else buildingColor = index
                deleteMode = false
                selected[depth] = 0
                page[depth] = 0
            }
            id == "page:prev" -> page[depth] = (page[depth] - 1).coerceAtLeast(0)
            id == "page:next" -> page[depth] += 1
            id == "delete" -> {
                if (depth != 0) {
                    deleteMode = true
                    message = if (depth == 2) "Delete Unit armed" else "Delete Building armed"
                }
            }
            id == "btn:save" -> onSave()
            id == "btn:fill" -> {
                popupScroll = 0
                popup = Popup.FILL
            }
            id == "btn:resize" -> {
                resizeCols = model.cols()
                resizeRows = model.rows()
                resizePage = 0
                popup = Popup.RESIZE
            }
            id == "btn:script" -> {
                popupScroll = 0
                popup = Popup.SCRIPT
            }
            id == "btn:test" -> popup = Popup.TEST
            id == "btn:quit" -> {
                popup = if (model.dirty) Popup.QUIT else Popup.NONE
                if (popup == Popup.NONE) quitRequested = true
            }
        }
    }

    /** The Save button: validate, write, and show the document for copying (the original flow). */
    private fun onSave() {
        if (model.countPlayers().size < 2) {
            message = "A map needs at least 2 players to save."
            return
        }
        model.save()
        message = if (model.targetPath == null) {
            "This map has no file: copy the JSON below (Ctrl+C on the selection)"
        } else {
            model.status
        }
        popupScroll = 0
        popup = Popup.SAVE
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    fun render() {
        hits.clear()
        popupHits.clear()
        renderer.clipRect = null
        renderer.drawColor = SDLColor(0, 0, 0)
        renderer.clear()

        textures.texture("editor_frame")?.let {
            renderer.renderTexture(it, dst = SDLFRect(0f, 0f, W.toFloat(), H.toFloat()))
        }

        drawMap()
        drawButtons()
        drawTabs()
        drawPanel()
        drawHighlight()
        drawMessage()
        if (popup != Popup.NONE) drawPopup()
    }

    private fun drawMap() {
        clampScroll()
        renderer.clipRect = SDLRect(mapX, mapY, viewW, viewH)
        val fromCol = scrollX / TILE
        val fromRow = scrollY / TILE
        val toCol = minOf(model.cols() - 1, (scrollX + viewW) / TILE)
        val toRow = minOf(model.rows() - 1, (scrollY + viewH) / TILE)
        for (r in fromRow..toRow) {
            for (c in fromCol..toCol) {
                val x = mapX + c * TILE - scrollX
                val y = mapY + r * TILE - scrollY
                drawCell(c, r, x, y)
            }
        }
        renderer.clipRect = null
    }

    /** One cell: terrain, then building, then unit — the game's own order. */
    private fun drawCell(col: Int, row: Int, x: Int, y: Int) {
        model.entryAt(col, row, 0)?.let { drawTerrainEntry(it, col, row, x, y) }
        model.entryAt(col, row, 1)?.let { drawBuildingEntry(it, x, y) }
        model.entryAt(col, row, 2)?.let { drawUnitEntry(it, col, row, x, y) }
    }

    private fun drawTerrainEntry(entry: PaletteEntryConfig, col: Int, row: Int, x: Int, y: Int) {
        val sprites = entry.sprites
        when {
            sprites.composite.size == 4 -> {
                val half = TILE / 2
                val ox = intArrayOf(0, half, 0, half)
                val oy = intArrayOf(0, 0, half, half)
                for (i in 0 until 4) {
                    val key = if (sprites.composite[i] == TerrainSprites.SEA_EDGE ||
                        sprites.composite[i] == TerrainSprites.SEA_EDGE_LEGACY
                    ) SEA_EDGE_STRIPS[i] else sprites.composite[i]
                    drawTileArt(key, x + ox[i], y + oy[i], half, half)
                }
            }
            sprites.single.isNotEmpty() -> drawTileArt(sprites.single, x, y, TILE, TILE)
            sprites.variants.isNotEmpty() -> {
                val index = variantIndex(col, row, sprites.variants.size)
                drawTileArt(sprites.variants[index], x, y, TILE, TILE)
            }
        }
    }

    /** The same per-tile variant choice the game makes (`Tile`'s position hash). */
    private fun variantIndex(col: Int, row: Int, count: Int): Int {
        if (count <= 1) return 0
        return (((row * 73856093) xor (col * 19349663)) and 0x7fffffff) % count
    }

    /**
     * One terrain sprite, sliced like `BattalionRenderer.terrainFrameOps`: short strips at the
     * cell's top edge, tall art anchored to the cell's bottom, frames filling the cell.
     */
    private fun drawTileArt(key: String, x: Int, y: Int, w: Int, h: Int) {
        val art = SpriteArt.tileFrame(textures, key, w, h) ?: run {
            renderer.drawColor = SDLColor(40, 60, 70)
            renderer.fillRect(SDLRect(x, y, w, h))
            return
        }
        val frame = art.first
        when (art.second) {
            TileArtMode.STRIP -> renderer.renderTexture(
                frame.texture, frame.src,
                SDLFRect(x.toFloat(), y.toFloat(), frame.src.width, frame.src.height),
            )
            TileArtMode.TALL -> renderer.renderTexture(
                frame.texture, frame.src,
                SDLFRect(x.toFloat(), y + h - frame.src.height, frame.src.width, frame.src.height),
            )
            TileArtMode.FILL -> renderer.renderTexture(
                frame.texture, frame.src,
                SDLFRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()),
            )
        }
    }

    private fun drawBuildingEntry(entry: PaletteEntryConfig, x: Int, y: Int) {
        val key = entry.sprites.single
        if (key.isEmpty()) return
        val tex = textures.textureOrFallback(key) ?: return
        val size = tex.size
        val w = minOf(size.x.toInt(), TILE)
        val h = size.y.toInt()
        renderer.renderTexture(
            tex, SDLFRect(0f, 0f, w.toFloat(), h.toFloat()),
            SDLFRect(x.toFloat(), (y + TILE - h).toFloat(), w.toFloat(), h.toFloat()),
        )
    }

    private fun drawUnitEntry(entry: PaletteEntryConfig, col: Int, row: Int, x: Int, y: Int) {
        val unit = UnitManager.get(entry.name) ?: return
        val sheet = entry.sprites.single.ifEmpty { unit.spriteKey.sheet }
        val facing = if (entry.facing in 0..5) entry.facing
        else if (col < model.cols() / 2) 1 else 3
        val frame = SpriteArt.unitFrame(textures, unit.spriteKey, sheet, teamColor(entry.team), facing)
            ?: return
        val w = frame.src.width
        val h = frame.src.height
        renderer.renderTexture(
            frame.texture, frame.src,
            SDLFRect(x + (TILE - w) / 2f, y + TILE - h, w, h),
        )
    }

    // ---------------------------------------------------------------------
    // Chrome
    // ---------------------------------------------------------------------

    private fun drawButtons() {
        // The original's row: 保存 填充 调整 脚本 测试 退出 at x 1/114/227/345/458/587.
        drawVistaButton("Save", 1, 1, 112, 36) { hit("btn:save", 1, 1, 112, 36) }
        drawVistaButton("Fill", 114, 1, 112, 36) { hit("btn:fill", 114, 1, 112, 36) }
        drawVistaButton("Resize", 227, 1, 117, 36) { hit("btn:resize", 227, 1, 117, 36) }
        drawVistaButton("Script", 345, 1, 112, 36) { hit("btn:script", 345, 1, 112, 36) }
        val canTest = model.countPlayers().size > 1
        drawVistaButton("Test", 458, 1, 112, 36, enabled = canTest) {
            hit("btn:test", 458, 1, 112, 36)
        }
        drawVistaButton("Quit", 587, 1, 112, 36) { hit("btn:quit", 587, 1, 112, 36) }
    }

    private fun drawVistaButton(
        title: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        enabled: Boolean = true,
        register: () -> Unit,
    ) {
        val hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        val tiles = textures.texture("btn_tile9")
        if (tiles != null) {
            drawTiled9(tiles, x, y, w, h, dim = !enabled)
        } else {
            renderer.drawColor = if (hover) SDLColor(150, 150, 170) else SDLColor(80, 84, 100)
            renderer.fillRect(SDLRect(x, y, w, h))
        }
        text.drawCentered(title, x + w / 2, y + (h - 18) / 2, 15, if (enabled) SDLColor(20, 20, 20) else SDLColor(90, 90, 90))
        if (hover) {
            val previous = renderer.blendMode
            renderer.blendMode = SDLBlendMode.BLEND
            renderer.drawColor = SDLColor(255, 255, 255, 40)
            renderer.fillRect(SDLRect(x, y, w, h))
            renderer.blendMode = previous
        }
        if (enabled) register()
    }

    /** `btn_tile9` nine-slice (Tile9Data rect 6,8,12,8 on a 24×24 sheet). */
    private fun drawTiled9(tex: SDLTexture, x: Int, y: Int, w: Int, h: Int, dim: Boolean = false) {
        val left = 6
        val top = 8
        val right = 6
        val bottom = 8
        val srcX = intArrayOf(0, left, left + 12)
        val srcY = intArrayOf(0, top, top + 8)
        val srcW = intArrayOf(left, 12, right)
        val srcH = intArrayOf(top, 8, bottom)
        val dstX = intArrayOf(x, x + left, x + w - right)
        val dstY = intArrayOf(y, y + top, y + h - bottom)
        val dstW = intArrayOf(left, w - left - right, right)
        val dstH = intArrayOf(top, h - top - bottom, bottom)
        if (dim) {
            tex.alphaMod = 120
        }
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                if (dstW[c] <= 0 || dstH[r] <= 0) continue
                renderer.renderTexture(
                    tex,
                    SDLFRect(srcX[c].toFloat(), srcY[r].toFloat(), srcW[c].toFloat(), srcH[r].toFloat()),
                    SDLFRect(dstX[c].toFloat(), dstY[r].toFloat(), dstW[c].toFloat(), dstH[r].toFloat()),
                )
            }
        }
        if (dim) tex.alphaMod = 255
    }

    private fun drawTabs() {
        val tabs = listOf(
            "editor_tab_terrain" to "editor_tab_terrain_on",
            "editor_tab_units" to "editor_tab_units_on",
            "editor_tab_buildings" to "editor_tab_buildings_on",
        )
        var x = panelX - 2
        for ((index, art) in tabs.withIndex()) {
            val hover = mouseX >= x && mouseX < x + tabW && mouseY >= tabY && mouseY < tabY + tabH
            val active = depth == layerForTab(index)
            // The original TabManager shows the plain button art on the active tab and the
            // *faded* (gray) overlay art on the others; the `_on` files are that faded state.
            val key = if (active) art.first else art.second
            val tex = textures.texture(key)
            if (tex != null) {
                renderer.renderTexture(tex, dst = SDLFRect(x.toFloat(), tabY.toFloat(), tabW.toFloat(), tabH.toFloat()))
            } else {
                renderer.drawColor = if (active) SDLColor(230, 216, 128) else SDLColor(90, 84, 70)
                renderer.fillRect(SDLRect(x, tabY, tabW, tabH))
            }
            if (hover && !active) {
                val previous = renderer.blendMode
                renderer.blendMode = SDLBlendMode.BLEND
                renderer.drawColor = SDLColor(255, 255, 255, 40)
                renderer.fillRect(SDLRect(x, tabY, tabW, tabH))
                renderer.blendMode = previous
            }
            hit("tab:$index", x, tabY, tabW, tabH)
            x += tabW - 1
        }
    }

    private fun drawPanel() {
        val rows = visibleRows()
        val top = panelY + if (depth == 0) 8 else 36
        val perPage = rowsPerPage()
        val pages = maxOf(1, (rows.size + perPage - 1) / perPage)
        page[depth] = page[depth].coerceIn(0, pages - 1)
        selected[depth] = selected[depth].coerceIn(0, maxOf(0, rows.size - 1))

        if (depth != 0) drawColorRadios()

        val first = page[depth] * perPage
        var y = top
        for (i in first until minOf(first + perPage, rows.size)) {
            val item = rows[i]
            val isSelected = !deleteMode && i == selected[depth]
            val hover = mouseX in panelX until panelX + panelW && mouseY in y until y + rowH - 2
            if (isSelected) {
                renderer.drawColor = SDLColor(0xFF, 0xD8, 0x00)
                renderer.fillRect(SDLRect(panelX, y, panelW, rowH - 2))
                renderer.drawColor = SDLColor(0, 0, 0)
                renderer.drawRect(SDLRect(panelX, y, panelW, rowH - 2))
            } else if (hover) {
                renderer.drawColor = SDLColor(255, 255, 255, 60)
                val previous = renderer.blendMode
                renderer.blendMode = SDLBlendMode.BLEND
                renderer.fillRect(SDLRect(panelX, y, panelW, rowH - 2))
                renderer.blendMode = previous
            }
            drawRowIcon(item, panelX + 2, y + (rowH - 18) / 2)
            text.draw(fitLabel(item.name, panelW - 27, 13), panelX + 23, y + 4, 13, SDLColor(0, 0, 0))
            hit("row:$i", panelX, y, panelW, rowH - 2)
            y += rowH
        }

        drawPageControls(pages)
        if (depth != 0) drawDeleteButton()
    }

    private fun drawColorRadios() {
        val count = if (depth == 2) EditorModel.UNIT_TEAMS else EditorModel.BUILDING_TEAMS
        val selectedIndex = if (depth == 2) unitColor else buildingColor
        val radio = textures.texture("editor_radio")
        val startX = if (depth == 2) panelX + 7 else panelX + 2
        val step = if (depth == 2) 22 + 8 else 22 + 3
        for (i in 0 until count) {
            val x = startX + i * step
            val y = panelY + 8
            val color = if (i == count - 1 && depth == 1) NEUTRAL_COLOR else TEAM_COLORS[i]
            if (radio != null) {
                val tinted = textures.textureForTeam("editor_radio", color) ?: radio
                renderer.renderTexture(
                    tinted, dst = SDLFRect(x.toFloat(), y.toFloat(), radio.size.x, radio.size.y),
                )
            } else {
                renderer.drawColor = color
                renderer.fillRect(SDLRect(x, y, 18, 18))
            }
            if (i == selectedIndex) {
                renderer.drawColor = SDLColor(255, 255, 255)
                renderer.drawRect(SDLRect(x - 1, y - 1, 20, 20))
            }
            hit("radio:$i", x, y, 22, 23)
        }
    }

    private fun drawRowIcon(item: EditorItem, x: Int, y: Int) {
        when (item.depth) {
            2 -> {
                val unit = UnitManager.get(item.name) ?: return
                val frame = SpriteArt.unitFrame(
                    textures, unit.spriteKey, item.icon, teamColor(item.team),
                ) ?: return
                val dst = SpriteArt.fitInto(frame.src, x.toFloat(), y.toFloat(), 18f, 18f)
                renderer.renderTexture(frame.texture, src = frame.src, dst = dst)
            }
            1 -> {
                val tex = textures.textureOrFallback(item.icon) ?: return
                val size = tex.size
                val dst = SpriteArt.fitInto(SDLFRect(0f, 0f, size.x, size.y), x.toFloat(), y.toFloat(), 18f, 18f)
                renderer.renderTexture(tex, dst = dst)
            }
            else -> {
                val art = SpriteArt.tileFrame(textures, item.icon) ?: return
                renderer.renderTexture(
                    art.first.texture, art.first.src,
                    dst = SDLFRect(x.toFloat(), y.toFloat(), 18f, 18f),
                )
            }
        }
    }

    /** [value] shortened with an ellipsis so it stays inside [maxWidth] at [sizePx]. */
    private fun fitLabel(value: String, maxWidth: Int, sizePx: Int): String {
        if (text.measure(value, sizePx) <= maxWidth) return value
        var end = value.length
        while (end > 1) {
            end--
            val candidate = value.substring(0, end).trimEnd() + "…"
            if (text.measure(candidate, sizePx) <= maxWidth) return candidate
        }
        return value.take(1)
    }

    private fun drawPageControls(pages: Int) {
        if (pages <= 1) return
        val y = if (depth == 0) panelY + 449 else panelY + 414
        text.drawCentered("Page ${page[depth] + 1} / $pages", panelX + panelW / 2, y + 3, 12, SDLColor(0, 0, 0))
        drawVistaButton("<", panelX + 2, y, 20, 20, enabled = page[depth] > 0) {
            hit("page:prev", panelX + 2, y, 20, 20)
        }
        drawVistaButton(">", panelX + panelW - 22, y, 20, 20, enabled = page[depth] < pages - 1) {
            hit("page:next", panelX + panelW - 22, y, 20, 20)
        }
    }

    private fun drawDeleteButton() {
        val y = panelY + 448
        val title = if (depth == 2) "Delete Unit" else "Delete Building"
        drawVistaButton(title, panelX + 4, y, panelW - 8, 34) { hit("delete", panelX + 4, y, panelW - 8, 34) }
        if (deleteMode) {
            renderer.drawColor = SDLColor(0xFF, 0xD8, 0x00)
            renderer.drawRect(SDLRect(panelX + 3, y - 1, panelW - 6, 36))
        }
    }

    private fun drawHighlight() {
        if (popup != Popup.NONE || hoverCol < 0) return
        val x = mapX + hoverCol * TILE - scrollX
        val y = mapY + hoverRow * TILE - scrollY
        textures.texture("editor_highlight")?.let {
            renderer.renderTexture(it, dst = SDLFRect(x.toFloat(), y.toFloat(), TILE.toFloat(), TILE.toFloat()))
        } ?: run {
            renderer.drawColor = SDLColor(255, 255, 255)
            renderer.drawRect(SDLRect(x, y, TILE, TILE))
        }
        text.draw("$hoverCol,$hoverRow", x + 2, y, 9, SDLColor(255, 255, 255))
    }

    private fun drawMessage() {
        val line = message ?: model.status
        if (line.isEmpty()) return
        val previous = renderer.blendMode
        renderer.blendMode = SDLBlendMode.BLEND
        renderer.drawColor = SDLColor(0, 0, 0, 170)
        renderer.fillRect(SDLRect(0, H - 18, W - 132, 18))
        renderer.blendMode = previous
        text.draw(fitLabel(line, W - 140, 12), 4, H - 16, 12, SDLColor(230, 230, 220))
    }

    // ---------------------------------------------------------------------
    // Popups
    // ---------------------------------------------------------------------

    private fun drawPopup() {
        val previous = renderer.blendMode
        renderer.blendMode = SDLBlendMode.BLEND
        renderer.drawColor = SDLColor(0, 0, 0, 153)
        renderer.fillRect(SDLRect(0, 0, W, H))
        renderer.blendMode = previous
        when (popup) {
            Popup.FILL -> drawFillPopup()
            Popup.RESIZE -> drawResizePopup()
            Popup.SCRIPT -> drawScriptPopup()
            Popup.TEST -> drawTestPopup()
            Popup.LOAD -> drawLoadPopup()
            Popup.SAVE -> drawSavePopup()
            Popup.QUIT -> drawQuitPopup()
            Popup.NONE -> {}
        }
    }

    private fun panel(x: Int, y: Int, w: Int, h: Int) {
        renderer.drawColor = SDLColor(74, 56, 37)
        renderer.fillRect(SDLRect(x, y, w, h))
        renderer.drawColor = SDLColor(190, 160, 110)
        renderer.drawRect(SDLRect(x, y, w, h))
        renderer.drawColor = SDLColor(120, 95, 60)
        renderer.drawRect(SDLRect(x + 2, y + 2, w - 4, h - 4))
    }

    private fun popupButton(x: Int, y: Int, w: Int, h: Int, title: String, id: String, enabled: Boolean = true) {
        val hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        val tiles = textures.texture("btn_tile9")
        if (tiles != null) drawTiled9(tiles, x, y, w, h, dim = !enabled)
        else {
            renderer.drawColor = if (hover) SDLColor(150, 150, 170) else SDLColor(90, 80, 64)
            renderer.fillRect(SDLRect(x, y, w, h))
        }
        text.drawCentered(title, x + w / 2, y + (h - 16) / 2, 14, if (enabled) SDLColor(20, 20, 20) else SDLColor(95, 90, 80))
        if (enabled) popupHits += Hit(id, x, y, w, h)
    }

    private fun drawFillPopup() {
        val terrains = EditorModel.FILL_TERRAINS.filter { model.rulesFor(it) != null || it == "Plains" }
        val w = 300
        val h = 360
        val x = (W - w) / 2
        val y = (H - h) / 2
        panel(x, y, w, h)
        text.drawCentered("Fill map with", x + w / 2, y + 10, 16, SDLColor(245, 235, 210))
        val iconSize = 56
        val cellW = iconSize + 12
        val cellH = iconSize + 24
        for ((index, name) in terrains.withIndex()) {
            val ix = x + 18 + (index % 2) * (cellW + 60)
            val iy = y + 44 + (index / 2) * cellH
            val rule = model.rulesFor(name)
            val key = rule?.icon?.ifEmpty {
                rule.rules.firstOrNull()?.let { r -> r.images.firstOrNull() ?: r.composite.firstOrNull().orEmpty() }
            }.orEmpty()
            if (key.isNotEmpty()) {
                val art = SpriteArt.tileFrame(textures, key, iconSize, iconSize)
                if (art != null) {
                    renderer.renderTexture(
                        art.first.texture, art.first.src,
                        dst = SDLFRect(ix.toFloat(), iy.toFloat(), iconSize.toFloat(), iconSize.toFloat()),
                    )
                }
            }
            text.drawCentered(name, ix + iconSize / 2, iy + iconSize + 2, 12, SDLColor(240, 235, 220))
            popupHits += Hit("fill:$name", ix, iy, iconSize, iconSize + 18)
        }
        popupButton(x + 8, y + h - 46, 120, 36, "Cancel", "popup:close")
    }

    private fun resizeGrid(): Grid {
        val cols = maxOf(model.cols(), resizeCols)
        val rows = maxOf(model.rows(), resizeRows)
        val w = cols * GRID_CELL
        val h = rows * GRID_CELL
        return Grid((W - w) / 2, 130, cols, rows, w, h)
    }

    private class Grid(val x: Int, val y: Int, val cols: Int, val rows: Int, val w: Int, val h: Int)

    private fun drawResizePopup() {
        val w = 380
        val h = 420
        val x = (W - w) / 2
        val y = (H - h) / 2
        panel(x, y, w, h)
        if (resizePage == 0) {
            text.draw("Columns:", x + 16, y + 24, 15, SDLColor(245, 235, 210))
            text.draw("$resizeCols", x + 130, y + 24, 15, SDLColor(255, 255, 255))
            popupButton(x + 180, y + 18, 32, 26, "-", "resize:cols-", resizeCols > EditorModel.MIN_COLS)
            popupButton(x + 216, y + 18, 32, 26, "+", "resize:cols+", resizeCols < EditorModel.MAX_COLS)
            text.draw("Rows:", x + 16, y + 66, 15, SDLColor(245, 235, 210))
            text.draw("$resizeRows", x + 130, y + 66, 15, SDLColor(255, 255, 255))
            popupButton(x + 180, y + 60, 32, 26, "-", "resize:rows-", resizeRows > EditorModel.MIN_ROWS)
            popupButton(x + 216, y + 60, 32, 26, "+", "resize:rows+", resizeRows < EditorModel.MAX_ROWS)
            text.draw("10 to 20 columns / rows", x + 16, y + 104, 12, SDLColor(210, 200, 180))
            popupButton(x + 8, y + h - 46, 100, 32, "Cancel", "popup:close")
            popupButton(x + w - 108, y + h - 46, 100, 32, "Next", "resize:next")
        } else {
            text.draw("Drag the new size over the old one", x + 16, y + 10, 13, SDLColor(245, 235, 210))
            val grid = resizeGrid()
            renderer.drawColor = SDLColor(30, 30, 30)
            renderer.fillRect(SDLRect(grid.x, grid.y, grid.w, grid.h))
            for (c in 0..grid.cols) {
                renderer.drawColor = SDLColor(80, 80, 80)
                renderer.drawRect(SDLRect(grid.x + c * GRID_CELL, grid.y, 1, grid.h))
            }
            for (r in 0..grid.rows) {
                renderer.drawColor = SDLColor(80, 80, 80)
                renderer.drawRect(SDLRect(grid.x, grid.y + r * GRID_CELL, grid.w, 1))
            }
            val oldW = model.cols() * GRID_CELL
            val oldH = model.rows() * GRID_CELL
            val oldX = grid.x + (grid.w - oldW) / 2
            val oldY = grid.y + (grid.h - oldH) / 2
            val previous = renderer.blendMode
            renderer.blendMode = SDLBlendMode.BLEND
            renderer.drawColor = SDLColor(120, 160, 190, 150)
            renderer.fillRect(SDLRect(oldX, oldY, oldW, oldH))
            renderer.drawColor = SDLColor(255, 255, 255)
            renderer.fillRect(SDLRect(grid.x + newRectX, grid.y + newRectY, resizeCols * GRID_CELL, resizeRows * GRID_CELL))
            renderer.drawColor = SDLColor(255, 255, 255, 220)
            renderer.drawRect(SDLRect(grid.x + newRectX, grid.y + newRectY, resizeCols * GRID_CELL, resizeRows * GRID_CELL))
            renderer.blendMode = previous
            text.draw("New size", grid.x + newRectX + 4, grid.y + newRectY + 3, 12, SDLColor(20, 20, 20))
            text.draw("Current", oldX + 4, oldY + 3, 12, SDLColor(20, 20, 20))
            popupHits += Hit("resize:drag", grid.x + newRectX, grid.y + newRectY, resizeCols * GRID_CELL, resizeRows * GRID_CELL)
            popupButton(x + 8, y + h - 46, 100, 32, "Cancel", "popup:close")
            popupButton(x + 116, y + h - 46, 100, 32, "Back", "resize:back")
            popupButton(x + w - 108, y + h - 46, 100, 32, "Done", "resize:done")
        }
    }

    private fun drawScriptPopup() {
        val w = 550
        val h = 430
        val x = (W - w) / 2
        val y = (H - h) / 2
        panel(x, y, w, h)
        val empty = model.scriptIsEmpty()
        text.draw(if (empty) "Script — no events" else "Script", x + 12, y + 10, 16, SDLColor(245, 235, 210))
        text.draw(model.scriptSummary(), x + 12, y + 30, 12,
            if (empty) SDLColor(240, 190, 130) else SDLColor(210, 200, 180))
        text.draw(
            if (empty) {
                "Terrain, units and buildings are map tiles, not script content. " +
                    "The empty document below is the script's shape."
            } else {
                "Copy puts this document on the clipboard."
            },
            x + 12, y + 46, 11, SDLColor(200, 190, 170),
        )
        val script = model.scriptJson()
        val lines = script.lines().flatMap { line -> line.chunked(96).ifEmpty { listOf("") } }
        val visible = 24
        val maxScroll = maxOf(0, lines.size - visible)
        popupScroll = popupScroll.coerceIn(0, maxScroll)
        var lineY = y + 66
        for (line in lines.drop(popupScroll).take(visible)) {
            text.draw(line, x + 12, lineY, 10, SDLColor(230, 230, 220))
            lineY += 12
        }
        val first = if (lines.isEmpty()) 0 else popupScroll + 1
        text.draw(
            "Lines $first-${minOf(lines.size, popupScroll + visible)} of ${lines.size}" +
                if (maxScroll > 0) " — wheel scrolls" else "",
            x + 12, y + h - 64, 11, SDLColor(190, 180, 160),
        )
        popupButton(x + 8, y + h - 46, 100, 32, "Reset", "script:reset")
        popupButton(x + 116, y + h - 46, 100, 32, "Copy", "script:copy")
        popupButton(x + w - 108, y + h - 46, 100, 32, "Close", "popup:close")
    }

    private fun drawTestPopup() {
        val w = 380
        val h = 220
        val x = (W - w) / 2
        val y = (H - h) / 2
        panel(x, y, w, h)
        text.draw("Test", x + 12, y + 10, 18, SDLColor(245, 235, 210))
        val players = model.countPlayers()
        val ok = players.size > 1
        text.draw(
            if (ok) "Players: ${players.joinToString(", ") { "P$it" }}"
            else "Not enough players: place units or buildings for two teams.",
            x + 12, y + 52, 13, if (ok) SDLColor(190, 240, 190) else SDLColor(240, 180, 180),
        )
        text.draw("Play starts a skirmish with this map.", x + 12, y + 76, 12, SDLColor(200, 190, 170))
        popupButton(x + 8, y + h - 46, 120, 36, "Play", "test:play", enabled = ok)
        popupButton(x + w - 128, y + h - 46, 120, 36, "Cancel", "popup:close")
    }

    private fun drawLoadPopup() {
        val w = 550
        val h = 430
        val x = (W - w) / 2
        val y = (H - h) / 2
        panel(x, y, w, h)
        text.draw("Load map", x + 12, y + 10, 16, SDLColor(245, 235, 210))
        val maps = model.availableMaps()
        val perPage = 18
        val pages = maxOf(1, (maps.size + perPage - 1) / perPage)
        popupScroll = popupScroll.coerceIn(0, pages - 1)
        var lineY = y + 36
        for (id in maps.drop(popupScroll * perPage).take(perPage)) {
            val hover = mouseX in x + 8 until x + w - 8 && mouseY in lineY until lineY + 17
            if (hover) {
                renderer.drawColor = SDLColor(120, 100, 70)
                renderer.fillRect(SDLRect(x + 8, lineY, w - 16, 17))
            }
            text.draw(id, x + 12, lineY + 2, 12, SDLColor(240, 235, 220))
            popupHits += Hit("load:$id", x + 8, lineY, w - 16, 17)
            lineY += 17
        }
        text.draw("${popupScroll + 1} / $pages", x + w / 2 - 20, y + h - 66, 12, SDLColor(210, 200, 180))
        popupButton(x + 8, y + h - 46, 100, 32, "Cancel", "popup:close")
        popupButton(x + 120, y + h - 46, 40, 32, "<", "load:prev", popupScroll > 0)
        popupButton(x + w - 168, y + h - 46, 40, 32, ">", "load:next", popupScroll < pages - 1)
    }

    private fun drawSavePopup() {
        val w = 620
        val h = 520
        val x = (W - w) / 2
        val y = (H - h) / 2
        panel(x, y, w, h)
        text.draw("Map ${model.mapId ?: ""}", x + 12, y + 10, 16, SDLColor(245, 235, 210))
        text.draw(model.status, x + 12, y + 30, 12, SDLColor(210, 200, 180))
        val doc = model.documentJson()
        var lineY = y + 50
        for (line in doc.chunked(96).take(34)) {
            text.draw(line, x + 12, lineY, 10, SDLColor(225, 225, 215))
            lineY += 11
        }
        popupButton(x + 8, y + h - 46, 120, 34, "Write file…", "save:file")
        popupButton(x + 136, y + h - 46, 100, 34, "Copy", "save:copy")
        popupButton(x + w - 128, y + h - 46, 120, 34, "Close", "popup:close")
    }

    private fun drawQuitPopup() {
        val w = 340
        val h = 190
        val x = (W - w) / 2
        val y = (H - h) / 2
        panel(x, y, w, h)
        text.draw("Quit?", x + 12, y + 12, 20, SDLColor(245, 235, 210))
        text.draw("This map has unsaved changes.", x + 12, y + 56, 13, SDLColor(220, 210, 190))
        text.draw("Quit anyway?", x + 12, y + 76, 13, SDLColor(220, 210, 190))
        popupButton(x + 10, y + h - 48, 110, 36, "Yes", "quit:yes")
        popupButton(x + w - 120, y + h - 48, 110, 36, "No", "popup:close")
    }

    /** Routes a popup button click. */
    fun onPopupAction(id: String) {
        when {
            id == "popup:close" -> popup = Popup.NONE
            id.startsWith("fill:") -> {
                model.fill(id.removePrefix("fill:"))
                popup = Popup.NONE
            }
            id == "resize:cols-" -> resizeCols = (resizeCols - 1).coerceAtLeast(EditorModel.MIN_COLS)
            id == "resize:cols+" -> resizeCols = (resizeCols + 1).coerceAtMost(EditorModel.MAX_COLS)
            id == "resize:rows-" -> resizeRows = (resizeRows - 1).coerceAtLeast(EditorModel.MIN_ROWS)
            id == "resize:rows+" -> resizeRows = (resizeRows + 1).coerceAtMost(EditorModel.MAX_ROWS)
            id == "resize:next" -> {
                resizePage = 1
                val grid = resizeGrid()
                newRectX = (grid.w - resizeCols * GRID_CELL) / 2
                newRectY = (grid.h - resizeRows * GRID_CELL) / 2
            }
            id == "resize:back" -> resizePage = 0
            id == "resize:drag" -> {
                val grid = resizeGrid()
                draggingSize = true
                dragOffsetX = mouseX - (grid.x + newRectX)
                dragOffsetY = mouseY - (grid.y + newRectY)
            }
            id == "resize:done" -> {
                val grid = resizeGrid()
                val oldX = (grid.w - model.cols() * GRID_CELL) / 2
                val oldY = (grid.h - model.rows() * GRID_CELL) / 2
                val left = (oldX - newRectX) / GRID_CELL
                val top = (oldY - newRectY) / GRID_CELL
                val right = resizeCols - model.cols() - left
                val bottom = resizeRows - model.rows() - top
                model.resize(top, left, bottom, right)
                popup = Popup.NONE
            }
            id == "script:reset" -> model.resetScript()
            id == "script:copy" -> {
                SDL.setClipboardText(model.scriptJson())
                message = "Script copied to the clipboard"
            }
            id == "test:play" -> {
                testRequested = true
                popup = Popup.NONE
            }
            id == "load:next" -> popupScroll += 1
            id == "load:prev" -> popupScroll = (popupScroll - 1).coerceAtLeast(0)
            id.startsWith("load:") -> {
                val map = id.removePrefix("load:")
                if (model.open(map)) {
                    scrollX = 0
                    scrollY = 0
                    page.fill(0)
                    selected.fill(0)
                    message = null
                }
                popup = Popup.NONE
            }
            id == "save:file" -> {
                // A new document has no file yet; the original exports its text instead. This
                // port writes the same JSON, through the platform's save dialog.
                SDL.showSaveFileDialog(
                    null,
                    listOf(SDLDialogFileFilter("Map document", "json")),
                    "${model.assetsDir}/data/maps",
                ) { paths -> pendingSavePath = paths.firstOrNull() }
            }
            id == "save:copy" -> {
                SDL.setClipboardText(model.documentJson())
                message = "Map JSON copied to the clipboard"
            }
            id == "quit:yes" -> quitRequested = true
        }
    }

    fun openLoadPopup() {
        popupScroll = 0
        popup = Popup.LOAD
    }

    fun closePopup() {
        popup = Popup.NONE
    }

    fun isPopupOpen(): Boolean = popup != Popup.NONE

    /** Rect of a button drawn in the last frame (used by the headless checks). */
    fun hitRect(id: String): IntArray? =
        (popupHits + hits).firstOrNull { it.id == id }?.let { intArrayOf(it.x, it.y, it.w, it.h) }

    fun setMessage(value: String) {
        message = value
    }

    companion object {
        const val W = 700
        const val H = 600
        const val TILE = 56
        private const val GRID_CELL = 12

        /** Width of the map-edge strip that scrolls the editor camera while hovered. */
        private const val EDGE_SCROLL = 18
        private val SEA_EDGE_STRIPS = listOf("sea_0_nw", "sea_0_ne", "sea_0_se", "sea_0_sw")

        /** The original `VALID_UNIT_COLORS` (red, blue, green, yellow). */
        val TEAM_COLORS = listOf(
            SDLColor(255, 0, 0),
            SDLColor(0, 0, 255),
            SDLColor(0, 255, 0),
            SDLColor(255, 216, 0),
        )

        /** The original's neutral building colour (`VALID_BUILDING_COLORS[4]`). */
        val NEUTRAL_COLOR = SDLColor(238, 225, 156)

        fun teamColor(team: Int): SDLColor = when (team) {
            in 1..4 -> TEAM_COLORS[team - 1]
            0 -> NEUTRAL_COLOR
            else -> SDLColor(200, 200, 200)
        }
    }
}

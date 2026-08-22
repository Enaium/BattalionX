package cn.enaium.battalion

import cn.enaium.battalion.data.GameLoader
import cn.enaium.battalion.game.BattalionGame
import cn.enaium.battalion.game.BattalionRenderer
import cn.enaium.battalion.game.ui.TextRenderer
import cn.enaium.battalion.game.ui.TextureManager
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLInitFlags
import io.github.vinceglb.filekit.FileKit

/**
 * JVM entry point.
 *
 * Usage:
 *   ./gradlew jvmRun --args="<assetsDir> [--mission N] [--skirmish map] [--selftest]"
 *
 *   <assetsDir>       the project's asset directory (default `assets`): JSON data under
 *                     `data/` (terrain/units/modifiers/ballistics/campaigns/maps), sprites
 *                     under `images/`, sounds under `sounds/`. Mods drop files into these
 *                     folders; see GameLoader for the data formats.
 *   --mission N       play campaign mission N (0-based, default 0).
 *   --skirmish file   play a free-for-all skirmish on the map `maps/<file>.json` (name only,
 *                     e.g. `Mission_1`).
 *   --selftest        load the mission, print diagnostics and tick the game loop without
 *                     opening a window (used to verify data loading / startup).
 *   --assetreport     drive every mission/menu and report which requested sprites have no
 *                     backing file under `assets/images/` (verifies the asset set is complete).
 */
fun main(args: Array<String>) {
    FileKit.init(appId = "BattalionX")

    val dir = args.firstOrNull { !it.startsWith("--") } ?: "assets"
    val selftest = args.contains("--selftest")
    val missionArg = args.indexOf("--mission")
    val mission = if (missionArg >= 0 && missionArg + 1 < args.size) args[missionArg + 1].toIntOrNull() ?: 0 else 0
    val skirmishArg = args.indexOf("--skirmish")
    val skirmish = if (skirmishArg >= 0 && skirmishArg + 1 < args.size) args[skirmishArg + 1] else null

    if (args.contains("--events")) {
        eventsDiag()
        return
    }
    if (args.contains("--diag")) {
        diag(dir, mission, skirmish)
        return
    }
    if (args.contains("--movetest")) {
        movetest(dir, mission, skirmish)
        return
    }
    if (args.contains("--facetest")) {
        facetest(dir, mission, skirmish)
        return
    }
    if (args.contains("--texcheck")) {
        texcheck(dir, mission, skirmish)
        return
    }
    if (args.contains("--imgcheck")) {
        imgcheck(dir)
        return
    }
    if (args.contains("--assetreport")) {
        assetreport(dir)
        return
    }
    if (args.contains("--shot")) {
        shot(dir, mission, skirmish)
        return
    }
    if (args.contains("--menushot")) {
        menushot(dir)
        return
    }
    if (args.contains("--carddiag")) {
        carddiag(dir)
        return
    }
    val bootcampArg = args.indexOf("--bootselftest")
    val bootMission = if (bootcampArg >= 0) args[bootcampArg + 1].toIntOrNull() ?: 0 else -1
    if (bootMission >= 0) {
        bootselftest(dir, bootMission)
        return
    }
    if (selftest) {
        selftest(dir, mission, skirmish)
        return
    }
    // The game shows the start menu by default; only a real --mission/--skirmish flag pins a
    // specific mission (passing a non-null default would skip the menu and jump into mission 0).
    val playMission = if (missionArg >= 0) mission else null
    BattalionGame(dir, playMission, skirmish).run()
}

/**
 * Diagnostic: renders one frame of each StartScreen menu (main / campaign / bootcamp) and
 * saves PNGs so the menu presentation can be verified headlessly.
 */
private fun menushot(dir: String) {
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("menushot", 800, 600)
    val r = SDL.createRenderer(win)
    val text = cn.enaium.battalion.game.ui.TextRenderer(r); text.load()
    val tm = cn.enaium.battalion.game.ui.TextureManager(dir, r); tm.load()

    fun snap(screen: String, name: String) {
        val start = cn.enaium.battalion.game.ui.StartScreen(dir, r, text, tm)
        start.setScreenForCapture(screen)
        // Draw several frames with present() + a delay so the backbuffer delivers the current
        // frame (renderReadPixels lags one present otherwise).
        for (i in 1..4) { start.renderFrameForCapture(); SDL.delay(20) }
        val surf = r.renderReadPixels(null)
        if (surf != null) {
            cn.enaium.sdl.image.SDLImage.savePNG(surf, "/Users/enaium/.claude/jobs/19cccc80/tmp/$name.png")
            println("saved /Users/enaium/.claude/jobs/19cccc80/tmp/$name.png")
            surf.close()
        } else println("capture failed for $name")
    }

    snap("main", "menu_main")
    snap("campaign", "menu_campaign")
    snap("bootcamp", "menu_bootcamp")

    tm.close(); text.close(); r.close(); win.close(); SDL.quit()
}

/**
 * Diagnostic: opens a window and prints every SDL event for ~15 seconds so we can verify
 * whether mouse events reach the game loop.
 */
private fun eventsDiag() {
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("events", 400, 300)
    val r = SDL.createRenderer(win)
    val end = System.currentTimeMillis() + 15000
    while (System.currentTimeMillis() < end) {
        while (true) {
            val e = SDL.pollEvent() ?: break
            println("EVENT: ${e::class.simpleName} ${e}")
        }
        SDL.delay(16)
    }
    r.close(); win.close(); SDL.quit()
}

/**
 * Diagnostic: loads the game, resolves terrain textures, and reports which terrain swatches
 * failed to resolve to an image. Exits without opening a window.
 */
private fun diag(dir: String, mission: Int, skirmish: String?) {
    val game = if (skirmish != null) {
        GameLoader.loadSkirmish(dir, skirmish)
    } else {
        GameLoader.loadCampaignMission(dir, mission)
    }
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("diag", 800, 590)
    val r = SDL.createRenderer(win)
    val text = TextRenderer(r); text.load()
    val tm = TextureManager(dir, r); tm.load()
    val render = BattalionRenderer(game, text, r, tm)
    render.setupTextures()
    for (sw in game.map.palette) {
        if (sw.getTeam() != 0) continue
        val f = sw.imageFiles.firstOrNull() ?: "NONE"
        val key = f.substringBeforeLast('.')
        val path = tm.pathFor(key)
        val resolved = tm.textureSize(key)  // checks the original key
        val fallbackKey = if (resolved != null) key else {
            // Simulate the fallback logic
            val rest = key.substringAfter('_')
            when {
                rest != key && rest.contains('_') && tm.textureSize(rest) != null -> rest
                key.endsWith("_v1") && tm.textureSize(key.removeSuffix("_v1")) != null -> key.removeSuffix("_v1")
                key.startsWith("sea_") && key.endsWith("_v1") && tm.textureSize("sea_v1") != null -> "sea_v1"
                else -> key
            }
        }
        val status = when {
            resolved != null -> "OK"
            tm.textureSize(fallbackKey) != null -> "FALLBACK→$fallbackKey"
            else -> "MISSING"
        }
        println("terrain '${sw.name}' img='$f' $status")
    }
    val names = game.map.palette.filter { it.getTeam() == 0 }.map { it.name }.toSet()
    var mism = 0
    for (row in 0 until game.map.height) for (col in 0 until game.map.width) {
        val t = game.tileAt(col, row) ?: continue
        if (t.terrain.name !in names) mism++
    }
    println("tile terrain names not in palette: $mism")
    tm.close(); text.close(); r.close(); win.close(); SDL.quit()
}

/**
 * Loads the game and simulates the human's click-to-move flow for every friendly unit:
 * select a unit, then try to path it to each of its four orthogonal neighbours. Prints the
 * result so we can see exactly where the click-to-move path fails (Astar vs canMove vs action).
 */
private fun movetest(dir: String, mission: Int, skirmish: String?) {
    val game = if (skirmish != null) {
        GameLoader.loadSkirmish(dir, skirmish)
    } else {
        GameLoader.loadCampaignMission(dir, mission)
    }
    game.startGame()
    for (i in 1..30) game.update(16)

    val human = game.activeController as? cn.enaium.battalion.game.controllers.HumanController
    println("activeController=${game.activeController?.team?.name} isHuman=${human != null}")
    if (human == null) return

    for (unit in game.units.filter { it.controller === human && it.isDeployed() }) {
        val start = game.tileAt(unit.x, unit.y) ?: continue
        println("UNIT ${unit.name} @${unit.x},${unit.y} moved=${unit.moved} range=${unit.getRange()} deployed=${unit.isDeployed()}")
        for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val end = game.tileAt(unit.x + dx, unit.y + dy) ?: continue
            val path = cn.enaium.battalion.data.Astar.path(start, end, unit)
            val canMove = path != null && game.canMove(unit, path)
            val mc = end.getMovementCost(unit)
            println("  -> (${end.x},${end.y}) terrain=${end.terrain.name} moveCost=$mc path=${path != null} canMove=$canMove")
        }
    }
    println("MOVETEST DONE")
}

/**
 * Diagnostic: fires a real [MoveUnitAction] for each friendly unit toward each of its four
 * orthogonal neighbours (restoring the unit afterwards) and prints the facing that movement
 * produced, so we can verify units turn to face the direction they move.
 */
private fun facetest(dir: String, mission: Int, skirmish: String?) {
    val game = if (skirmish != null) {
        GameLoader.loadSkirmish(dir, skirmish)
    } else {
        GameLoader.loadCampaignMission(dir, mission)
    }
    game.startGame()
    for (i in 1..30) game.update(16)

    val human = game.activeController as? cn.enaium.battalion.game.controllers.HumanController
    println("activeController=${game.activeController?.team?.name} isHuman=${human != null}")
    if (human == null) return

    val am = cn.enaium.battalion.game.ActionManager
    for (unit in game.units.filter { it.controller === human && it.isDeployed() }) {
        val ox = unit.x; val oy = unit.y
        println("UNIT ${unit.name} @${ox},${oy} startFacing=${unit.facing}")
        for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val end = game.tileAt(unit.x + dx, unit.y + dy) ?: continue
            val path = cn.enaium.battalion.data.Astar.path(game.tileAt(unit.x, unit.y)!!, end, unit)
            if (path == null || !game.canMove(unit, path)) {
                println("  ($dx,$dy) blocked")
                continue
            }
            val pathStrings = mutableListOf<String>()
            for (node in path) {
                val t = node as? cn.enaium.battalion.data.Tile ?: continue
                pathStrings.add(t.x.toString())
                pathStrings.add(t.y.toString())
            }
            val move = cn.enaium.battalion.game.actions.MoveUnitAction(
                unit.x, unit.y,
                cn.enaium.battalion.game.actions.MoveUnitAction.stringizePath(pathStrings))
            move.isNetworked = false
            am.add(move)
            while (!am.isEmpty()) am.update()
            val expected = when {
                dy < 0 -> 0; dy > 0 -> 2; dx > 0 -> 1; else -> 3
            }
            println("  move ($dx,$dy): pos (${unit.x},${unit.y}) facing=${unit.facing} expect=$expected ${if (unit.facing == expected) "OK" else "MISMATCH"}")
            // restore unit for the next direction
            game.tileAt(unit.x, unit.y)?.unit = null
            unit.x = ox; unit.y = oy
            game.tileAt(ox, oy)?.unit = unit
            unit.moved = false
            unit.fired = false
        }
        am.clear()
    }
    println("FACETEST DONE")
}

/**
 * Diagnostic: for every deployed unit, resolve its sprite texture and report whether it loaded.
 * Verifies the unit sprites actually render (vs. the colored-square fallback).
 */
private fun texcheck(dir: String, mission: Int, skirmish: String?) {
    val game = if (skirmish != null) GameLoader.loadSkirmish(dir, skirmish)
               else GameLoader.loadCampaignMission(dir, mission)
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("texcheck", 800, 590)
    val r = SDL.createRenderer(win)
    val tm = cn.enaium.battalion.game.ui.TextureManager(dir, r); tm.load()
    val spriteKeys = mapOf(
        "Warmachine" to "warmachine", "Jammer Truck" to "jammer", "Lancer Tank" to "lancer",
        "Spider Tank" to "spider", "Heavy Commando" to "hcommando", "Strike Commando" to "hcommando",
        "Flak Tank" to "flak", "Scorpion Tank" to "scorpion", "Mortar Truck" to "mortar",
        "Rocket Truck" to "rocket", "Annihilator Tank" to "annihilator", "Turret" to "turret",
        "Stealth Tank" to "stealth", "Albatross Transport" to "albatross", "Raptor Fighter" to "raptor",
        "Vulture Drone" to "vulture", "Condor Bomber" to "condor", "Leviathan Barge" to "leviathan",
        "Hunter Support" to "hunter", "Corvette Fighter" to "corvette", "U-Boat" to "uboat",
        "Battlecruiser" to "battlecruiser", "Intrepid" to "intrepid",
    )
    val seen = mutableSetOf<String>()
    for (unit in game.units.filter { it.isDeployed() }) {
        val key = spriteKeys[unit.unitData.name] ?: continue
        if (!seen.add(key)) continue
        val path = tm.pathFor(key)
        val tex = tm.texture(key)
        val size = tm.textureSize(key)
        println("unit ${unit.unitData.name} key=$key path=${path != null} loaded=${tex != null} size=${size}")
    }
    tm.close(); r.close(); win.close(); SDL.quit()
}

/**
 * Diagnostic: directly attempts SDLImage.load on a unit sprite vs a terrain sprite and prints
 * the outcome (and any exception), to find why unit textures fail to load.
 */
private fun imgcheck(dir: String) {
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("imgcheck", 800, 590)
    val r = SDL.createRenderer(win)
    val candidates = listOf("scorpion", "flak", "lancer", "warmachine", "sea_v1", "road_2_v1", "plains_v4")
    val tm = cn.enaium.battalion.game.ui.TextureManager(dir, r); tm.load()
    for (key in candidates) {
        val path = tm.pathFor(key)
        if (path == null) { println("$key: NO PATH"); continue }
        val surf = try {
            cn.enaium.sdl.image.SDLImage.load(path)
        } catch (t: Throwable) {
            println("$key: load threw ${t::class.simpleName}: ${t.message}"); null
        }
        if (surf == null) {
            println("$key: SDLImage.load null (path=$path)")
        } else {
            println("$key: OK ${surf.width}x${surf.height}")
            // try the upload path TextureManager uses
            try {
                val tex = r.createTextureFromSurface(surf)
                println("$key: upload OK ${tex != null}")
                tex?.close()
            } catch (t: Throwable) {
                println("$key: upload threw ${t::class.simpleName}: ${t.message}")
            }
            surf.close()
        }
    }
    tm.close(); r.close(); win.close(); SDL.quit()
}

/**
 * Diagnostic: records every sprite name the game requests across ALL content — all ten
 * campaign missions, all eight bootcamp missions, the Kongregate bonus-map skirmish, the five
 * StartScreen surfaces (main / campaign / bootcamp / options / clear-data confirm), and per
 * game: dialogue, unit selection + range markers, the floating unit-options bar, the factory
 * build page, the in-game options menu with Quick Help, the recon strip over unit/terrain/no
 * tile, and a ticked AI turn (ai_thinking + explosions). Then reports:
 *
 *  - every requested name with no backing file under `assets/images/` (MISSING), and
 *  - every file under `assets/images/` that was never requested (UNUSED).
 *
 * This verifies the asset set is complete with nothing extra — the mod author's checklist:
 * a mod map/unit that needs a sprite adds `<key>.png`; any UNUSED line is a candidate for
 * removal.
 */
private fun assetreport(dir: String) {
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("assetreport", 800, 600)
    val r = SDL.createRenderer(win)
    val text = cn.enaium.battalion.game.ui.TextRenderer(r); text.load()
    val tm = cn.enaium.battalion.game.ui.TextureManager(dir, r); tm.load()
    tm.recording = true
    val green = cn.enaium.sdl.SDLColor(143, 222, 101) // matcha green

    fun snap(screen: String, confirm: Boolean = false) {
        val start = cn.enaium.battalion.game.ui.StartScreen(dir, r, text, tm)
        start.setScreenForCapture(screen)
        if (confirm) start.setConfirmingClearForCapture(true)
        for (i in 1..4) { start.renderFrameForCapture(); SDL.delay(10) }
    }
    snap("main"); snap("campaign"); snap("bootcamp")
    snap("options"); snap("options", confirm = true)

    fun exercise(load: () -> cn.enaium.battalion.game.GameManager) {
        val game = load()
        // Mission brief dialogue (startGame event) -> dialogue panel + avatars + continue.
        val script = game.map.getScript()
        if (script != null && script.children("event").any { it.child("condition")?.attr("type") == "startGame" }) {
            val dlg = cn.enaium.battalion.game.ui.Dialogue(script)
            if (dlg.play("startGame")) {
                val brief = BattalionRenderer(game, text, r, tm)
                brief.dialogue = dlg
                brief.render()
            }
        }

        val render = BattalionRenderer(game, text, r, tm)
        render.setupTextures()
        // Every registered unit sprite incl. firing sheet (build page / deps can show all).
        for (u in cn.enaium.battalion.data.UnitManager.getAll()) {
            val key = render.debugSpriteKeyFor(u.name, u.sprite)
            if (key.isEmpty()) continue
            tm.texture(key)
            tm.texture("${key}_firing")
            tm.textureForTeam(key, green)
        }
        // Structure sprites (drawStructure pulls them from the structure's terrain sprite).
        for (t in cn.enaium.battalion.data.TileManager.all()) {
            if (t.sprite.isNotEmpty()) tm.textureOrFallback(t.sprite)
        }

        val human = game.activeController as? cn.enaium.battalion.game.controllers.HumanController
        var unitTile: cn.enaium.battalion.data.Tile? = null
        if (human != null) {
            // Selection + range markers, then the floating unit-options bar.
            val unit = game.units.firstOrNull { it.controller === human && it.isDeployed() }
            if (unit != null) {
                human.selectUnit(unit)
                render.render()
                human.toggleUnitOptions()
                render.render()
                human.toggleUnitOptions()
                unitTile = game.tileAt(unit.x, unit.y)
            }
            // Factory build page (all buildable-units grid + frames + back/cancel buttons).
            val structure = game.structures.firstOrNull { it.controller === human }
            if (structure != null) {
                human.setBuildFocus(structure.x, structure.y)
                if (human.openBuildMenu()) {
                    render.render()
                    human.buildMenu = null
                }
            }
            // In-game options menu + Quick Help overlay (Vista buttons, toggles, icons).
            render.setInGameMenuOpen(true)
            render.showQuickHelp = true
            render.render()
            render.setInGameMenuOpen(false)
            render.showQuickHelp = false
            // Recon strip over a unit tile, a plain terrain tile, and nothing.
            val tiles = game.map.generateTileMap()
            val plain = tiles.firstOrNull { row -> row.any { it.unit == null && it.terrain.name.isNotEmpty() } }
                ?.firstOrNull { it.unit == null && it.terrain.name.isNotEmpty() }
            render.setFocusTile(unitTile)
            render.render()
            render.setFocusTile(plain)
            render.render()
            render.setFocusTile(null)
        }
        // Warmachine build page: fabricate a Constructor on a clear tile, open the menu, render
        // the unit grid, advance to the direction picker, render the arrows, close.
        val wmType = cn.enaium.battalion.data.UnitManager.getAll().firstOrNull { it.hasModifier("Constructor") }
        if (wmType != null && human != null) {
            outer@ for (y in 0 until game.map.height - 1) {
                for (x in 0 until game.map.width - 2) {
                    val t = game.tileAt(x, y)
                    val dest = game.tileAt(x + 1, y)
                    if (t != null && dest != null && t.unit == null && dest.unit == null &&
                        !t.isStructure() && !dest.isStructure() &&
                        t.getMovementCost(cn.enaium.battalion.data.Unit(wmType)) != Double.POSITIVE_INFINITY
                    ) {
                        val wm = cn.enaium.battalion.data.Unit(wmType, x, y, human)
                        wm.deployed = true
                        wm.resourcePool = 4000
                        game.tileAt(x, y)!!.unit = wm
                        game.addUnit(human, wm)
                        human.selectUnit(wm)
                        if (human.openBuildMenu()) {
                            render.render()
                            if (human.confirmBuild() && human.buildMenu != null) render.render()
                            human.cancelBuild()
                        }
                        break@outer
                    }
                }
            }
        }

        render.render()

        // Let the AI play a turn: auto-end the human's turn when control returns to it, and
        // render during the AI's turn so ai_thinking (and any attack explosions) draw.
        var guard = 0
        var lastTurn = -1
        var aiRenders = 0
        while (guard++ < 4000 && aiRenders < 3) {
            game.update(16)
            if (game.activeController is cn.enaium.battalion.game.controllers.HALController) {
                render.render()
                aiRenders++
            } else if (game.currentController == 0 && human != null &&
                game.activeController is cn.enaium.battalion.game.controllers.HumanController &&
                cn.enaium.battalion.game.ActionManager.isEmpty() &&
                game.turn != lastTurn
            ) {
                lastTurn = game.turn
                human.dispatchEndTurn()
            }
        }
        render.render()
    }

    for (m in 0 until (GameLoader.findCampaign(dir)?.size ?: 0)) {
        exercise { GameLoader.loadCampaignMission(dir, m) }
    }
    for (m in 0 until (GameLoader.findBootcamp(dir)?.size ?: 0)) {
        exercise { GameLoader.loadBootcampMission(dir, m) }
    }
    exercise { GameLoader.loadSkirmish(dir, "Kongregate_Bonus_Map") }

    val files = cn.enaium.battalion.util.Fio.listDir("$dir/images").orEmpty()
        .filter { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") }
        .sorted()
    val unused = files.filter { it.substringBeforeLast('.') !in tm.requested }
    val missing = tm.requested.filter { tm.pathFor(it) == null }.sorted()

    tm.close(); text.close(); r.close(); win.close(); SDL.quit()

    println("ASSETREPORT requested=${tm.requested.size} images=${files.size} unused=${unused.size} missing=${missing.size}")
    for (name in unused) println("  UNUSED: $name")
    for (name in missing) println("  MISSING: $name")
    println("ASSETREPORT DONE")
}

/**
 * Diagnostic: renders each unit's team-coloured sprite cell EXACTLY as the build card slices
 * it (src (0,0,56,cellH), cellH = texH/4 for 336-wide sheets, capped at 56) onto a black
 * window and saves one PNG per unit, to isolate what the build page actually shows.
 */
private fun carddiag(dir: String) {
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("carddiag", 800, 800)
    val r = SDL.createRenderer(win)
    val tm = cn.enaium.battalion.game.ui.TextureManager(dir, r); tm.load()
    val teamColor = cn.enaium.sdl.SDLColor(143, 222, 101) // green ramp
    val units = listOf(
        "Scorpion Tank" to "scorpion",
        "Stealth Tank" to "stealth",
        "Hunter Support" to "hunter",
        "Strike Commando" to "commando",
        "Heavy Commando" to "hcommando",
        "Flak Tank" to "flak",
        "Mortar Truck" to "mortar",
        "Intrepid" to "intrepid",
        "Leviathan Barge" to "leviathan",
    )
    var y = 0
    for ((name, key) in units) {
        val tex = tm.textureForTeam(key, teamColor)
        val size = tm.textureSize(key)
        if (tex == null || size == null) {
            println("CARDDIAG $name key=$key MISSING")
            continue
        }
        val tw = size.first; val th = size.second
        val cellH = if (tw == 336) th / 4 else th
        val bodyH = minOf(cellH, 56)
        r.drawColor = cn.enaium.sdl.SDLColor(0, 0, 0)
        r.fillRect(cn.enaium.sdl.SDLRect(0, y, 800, 76))
        // Reproduce drawCard EXACTLY: frame texture first (92x97), then sprite on top.
        val frame = "build_unitFrame_overlay"
        tm.texture(frame)?.let {
            r.renderTexture(it, dst = cn.enaium.sdl.SDLFRect(y % 800 * 0f, 0f, 0f, 0f)) // placeholder
        }
        val frameTex = tm.texture(frame)
        if (frameTex != null) {
            r.renderTexture(frameTex, dst = cn.enaium.sdl.SDLFRect(0f, y.toFloat(), 92f, 97f))
        } else {
            println("CARDDIAG frame $frame MISSING")
        }
        // sprite drawn at x+18, y+10
        val dw = 56; val dh = bodyH
        r.renderTexture(
            tex,
            src = cn.enaium.sdl.SDLFRect(0f, 0f, 56f, bodyH.toFloat()),
            dst = cn.enaium.sdl.SDLFRect(18f, (y + 10).toFloat(), dw.toFloat(), dh.toFloat()),
        )
        println("CARDDIAG $name key=$key sheet=${tw}x$th cellH=$cellH bodyH=$bodyH")
        y += 76
    }
    val surf = r.renderReadPixels(null)
    if (surf != null) {
        cn.enaium.sdl.image.SDLImage.savePNG(surf, "/Users/enaium/.claude/jobs/19cccc80/tmp/carddiag.png")
        surf.close()
    }
    tm.close(); r.close(); win.close(); SDL.quit()
}

/**
 * Opens the window, renders the game, captures screenshots to verify the rendering fixes
 * (RGB/BGR, shore strips, unit facing) and simulates a click-to-move to verify movement.
 * Saves PNGs to the job tmp dir.
 */
private fun shot(dir: String, mission: Int, skirmish: String?) {
    val game = if (skirmish != null) GameLoader.loadSkirmish(dir, skirmish)
                else GameLoader.loadCampaignMission(dir, mission)
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("shot", 800, 602)
    val r = SDL.createRenderer(win)
    val text = cn.enaium.battalion.game.ui.TextRenderer(r); text.load()
    val tm = cn.enaium.battalion.game.ui.TextureManager(dir, r); tm.load()
    val renderer = cn.enaium.battalion.game.BattalionRenderer(game, text, r, tm)
    renderer.setupTextures()
    game.startGame()
    val dia = cn.enaium.battalion.game.ui.Dialogue(game.map.getScript())
    renderer.dialogue = dia
    dia.play("startGame")

    fun snap(name: String) {
        for (i in 1..3) { game.update(16); renderer.render() }
        val surf = r.renderReadPixels(null)
        if (surf != null) {
            cn.enaium.sdl.image.SDLImage.savePNG(surf, "/Users/enaium/.claude/jobs/19cccc80/tmp/$name.png")
            println("saved /Users/enaium/.claude/jobs/19cccc80/tmp/$name.png")
            surf.close()
        } else {
            println("capture failed for $name")
        }
    }

    // Let the dialogue slide-in (500ms) complete so the panel is fully on-screen.
    for (i in 1..40) { game.update(16); renderer.render() }

    // Shot 1: after startup, dialogue still active (fully slid in, typing).
    snap("shot1_startup")

    // Dismiss dialogue.
    while (dia.isActive()) dia.advance()

    // Shot 2: map rendered, no selection.
    snap("shot2_map")

    // Simulate click-to-move: select the human's first unit, move it to its right neighbour.
    val human = game.activeController as? cn.enaium.battalion.game.controllers.HumanController
    if (human != null) {
        val unit = game.units.firstOrNull { it.controller === human && it.isDeployed() }
        if (unit != null) {
            human.selectUnit(unit)
            snap("shot3_selected")
            // Move the selected unit to a passable neighbour so shot4 shows the moved/acting unit.
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val dest = game.tileAt(unit.x + dx, unit.y + dy)
                val src = game.tileAt(unit.x, unit.y)
                val path = if (src != null && dest != null) cn.enaium.battalion.data.Astar.path(src, dest, unit) else null
                if (path != null && game.canMove(unit, path)) {
                    val strings = mutableListOf<String>()
                    for (n in path) { val t = n as? cn.enaium.battalion.data.Tile; if (t != null) { strings.add(t.x.toString()); strings.add(t.y.toString()) } }
                    human.dispatchMove(strings)
                    snap("shot4_aftermove")
                    println("MOVED to (${dest?.x},${dest?.y}) moved=${unit.moved}")
                    break
                }
            }
        } else {
            println("no human unit found")
        }

        // Build-page verification: open a Warmachine's build page, snap the unit grid, then
        // confirm into the direction picker stage and snap that too.
        val wmType = cn.enaium.battalion.data.UnitManager.getAll()
            .firstOrNull { it.hasModifier("Constructor") }
        if (wmType != null) {
            // Find a clear tile for the constructor (empty, passable, no structure).
            var wx = -1; var wy = -1
            outer@ for (y in 0 until game.map.height - 1) {
                for (x in 0 until game.map.width - 2) {
                    val t = game.tileAt(x, y)
                    val dest = game.tileAt(x + 1, y)
                    if (t != null && dest != null && t.unit == null && dest.unit == null &&
                        !t.isStructure() && !dest.isStructure() &&
                        t.getMovementCost(cn.enaium.battalion.data.Unit(wmType)) != Double.POSITIVE_INFINITY) {
                        wx = x; wy = y; break@outer
                    }
                }
            }
            if (wx >= 0) {
                val wm = cn.enaium.battalion.data.Unit(wmType, wx, wy, human)
                wm.deployed = true
                wm.resourcePool = 4000
                game.tileAt(wx, wy)!!.unit = wm
                game.addUnit(human, wm)
                human.selectUnit(wm)
                if (human.openBuildMenu()) {
                    // DIAG: dump every card's resolved sprite key + texture size to find the
                    // build page's blank / mis-sliced thumbnails.
                    val menu = human.buildMenu
                    if (menu != null) {
                        for ((i, u) in menu.units.withIndex()) {
                            val key = renderer.debugSpriteKeyFor(u.name, u.sprite)
                            val path = tm.pathFor(key)
                            val tex = tm.texture(key)
                            val size = tm.textureSize(key)
                            val tc = human.team.color
                            val teamColor = cn.enaium.sdl.SDLColor(
                                ((tc shr 16) and 0xffu).toInt(),
                                ((tc shr 8) and 0xffu).toInt(),
                                (tc and 0xffu).toInt(),
                            )
                            val teamTex = tm.textureForTeam(key, teamColor)
                            println("BUILDCARD $i ${u.name} key=$key path=${path != null} loaded=${tex != null} size=$size team=${teamTex != null}")
                        }
                    }
                    snap("shot5_buildpage_units")
                    // Confirm the focused unit -> direction picker stage.
                    if (human.confirmBuild() && human.buildMenu != null) {
                        snap("shot6_buildpage_direction")
                    } else {
                        println("build page did not advance to direction stage")
                    }
                    // Close it cleanly.
                    human.cancelBuild()
                } else {
                    println("no build menu opened for the warmachine")
                }
            } else {
                println("no clear tile found for build-page verification")
            }
        } else {
            println("no Constructor unit type in roster for build-page verification")
        }
    }

    tm.close(); text.close(); r.close(); win.close(); SDL.quit()
}

/**
 * Loads a bootcamp mission (headless) and ticks the loop to confirm the bootcamp campaign
 * loads and the turn loop runs, like [selftest] does for the main campaign.
 */
private fun bootselftest(dir: String, mission: Int) {
    val game = GameLoader.loadBootcampMission(dir, mission)
    println("Map: ${game.map.name} (${game.map.width}x${game.map.height})")
    println("Controllers: " + game.controllers.mapIndexed { i, c ->
        "${i}=${c?.team?.name ?: "?"}(${c?.team?.controllerType ?: "?"})"
    }.joinToString(", "))
    game.startGame()
    for (i in 1..30) game.update(16)
    println("After 30 ticks: turn=${game.turn} round=${game.round} active=${game.activeController?.team?.name}")
    println("BOOTSELFTEST OK")
}

/**
 * Loads the game and exercises the startup path without SDL, printing diagnostics. Also plays
 * a few full rounds (auto-ending the human's turn) to confirm the AI and the turn loop work.
 */
private fun selftest(dir: String, mission: Int, skirmish: String?) {
    val game = if (skirmish != null) {
        GameLoader.loadSkirmish(dir, skirmish)
    } else {
        GameLoader.loadCampaignMission(dir, mission)
    }
    println("Map: ${game.map.name} (${game.map.width}x${game.map.height})")
    println("Controllers: " + game.controllers.mapIndexed { i, c -> "$i=${c?.team?.name}(${c?.team?.controllerType})" }.joinToString(", "))
    println("Units: ${game.units.size}")
    println("Structures: ${game.structures.size}")

    game.startGame()
    // Let the startup actions (start game / round / turn) drain.
    for (i in 1..30) game.update(16)
    println("After 30 ticks: turn=${game.turn} round=${game.round} active=${game.activeController?.team?.name}")

    // Auto-end the human's turn whenever control returns to it, so the AI plays a few rounds.
    val human = game.activeController as? cn.enaium.battalion.game.controllers.HumanController
    if (human != null) {
        var rounds = 0
        var lastTurn = -1
        for (i in 1..4000) {
            game.update(16)
            if (game.currentController == 0 &&
                game.activeController is cn.enaium.battalion.game.controllers.HumanController &&
                cn.enaium.battalion.game.ActionManager.isEmpty()
            ) {
                if (game.turn != lastTurn) {
                    lastTurn = game.turn
                    rounds++
                }
                if (rounds >= 3) break
                human.dispatchEndTurn()
            }
        }
        println("After AI play: turn=${game.turn} round=${game.round} active=${game.activeController?.team?.name} units=${game.units.count { it.isAlive() }}")
    }
    println("SELFTEST OK")
}

// TEMPORARY health+panel verification — removed after verification.

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
 *   ./gradlew jvmRun --args="<assetsDir> [--campaign id] [--mission N] [--skirmish map] [--selftest]"
 *
 *   <assetsDir>       the project's asset directory (default `assets`): JSON data under
 *                     `data/` (terrain/units/modifiers/ballistics/campaigns/maps), sprites
 *                     under `images/`, sounds under `sounds/`. Mods drop files into these
 *                     folders; see GameLoader for the data formats.
 *   --campaign id     campaign generation for `--mission` (nemesis | skirmish | ghosts |
 *                     vengeance | bootcamp; default vengeance).
 *   --mission N       play mission N of `--campaign` (0-based, default 0).
 *   --skirmish file   play a free-for-all skirmish on the map `maps/<file>.json` (name only,
 *                     e.g. `ep3/Mission_1`).
 *   --selftest        load the mission, print diagnostics and tick the game loop without
 *                     opening a window (used to verify data loading / startup).
 *   --unit name       (with `--shot`) select that unit type before capturing, e.g.
 *                     `--shot --unit Warmachine`.
 *   --playerteam N    command team N of the mission (the mission select's faction picker),
 *                     e.g. `--campaign vengeance --mission 0 --playerteam 1`.
 *   --assetreport     drive every mission/menu and report which requested sprites have no
 *                     backing file under `assets/images/` (verifies the asset set is complete).
 */
fun main(args: Array<String>) {
    FileKit.init(appId = "BattalionX")

    val dir = args.firstOrNull { !it.startsWith("--") } ?: "assets"
    val selftest = args.contains("--selftest")
    val campaignArg = args.indexOf("--campaign")
    val campaign = if (campaignArg >= 0 && campaignArg + 1 < args.size) args[campaignArg + 1]
    else GameLoader.DEFAULT_CAMPAIGN
    val playerTeamArg = args.indexOf("--playerteam")
    val playerTeam = if (playerTeamArg >= 0 && playerTeamArg + 1 < args.size) args[playerTeamArg + 1].toIntOrNull() else null
    val missionArg = args.indexOf("--mission")
    val mission = if (missionArg >= 0 && missionArg + 1 < args.size) args[missionArg + 1].toIntOrNull() ?: 0 else 0
    val skirmishArg = args.indexOf("--skirmish")
    val skirmish = if (skirmishArg >= 0 && skirmishArg + 1 < args.size) args[skirmishArg + 1] else null

    if (args.contains("--events")) {
        eventsDiag()
        return
    }
    if (args.contains("--diag")) {
        diag(dir, mission, skirmish, campaign)
        return
    }
    if (args.contains("--movetest")) {
        movetest(dir, mission, skirmish, campaign)
        return
    }
    if (args.contains("--facetest")) {
        facetest(dir, mission, skirmish, campaign)
        return
    }
    if (args.contains("--texcheck")) {
        texcheck(dir, mission, skirmish, campaign)
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
        val unitArg = args.indexOf("--unit")
        val unitName = if (unitArg >= 0 && unitArg + 1 < args.size) args[unitArg + 1] else null
        shot(dir, mission, skirmish, campaign, unitName)
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
    if (selftest) {
        selftest(dir, mission, skirmish, campaign, playerTeam)
        return
    }
    // The game shows the start menu by default; only a real --mission/--skirmish flag pins a
    // specific mission (passing a non-null default would skip the menu and jump into mission 0).
    val playMission = if (missionArg >= 0) mission else null
    BattalionGame(dir, playMission, skirmish, campaign, playerTeam).run()
}

/**
 * Directory the `--shot` / `--menushot` / `--carddiag` diagnostics write their PNGs to.
 */
private val SHOT_DIR: String = System.getProperty("java.io.tmpdir").trimEnd('/') + "/battalionx-shots"

/** Saves a diagnostic capture to [SHOT_DIR]. */
private fun saveShot(surf: cn.enaium.sdl.SDLSurface, name: String) {
    java.io.File(SHOT_DIR).mkdirs()
    val path = "$SHOT_DIR/$name.png"
    cn.enaium.sdl.image.SDLImage.savePNG(surf, path)
    println("saved $path")
}

/**
 * Diagnostic: renders one frame of each StartScreen menu (main, every campaign generation's,
 * boot camp and options) and saves PNGs so the menu presentation can be verified headlessly.
 */
private fun menushot(dir: String) {
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("menushot", 800, 600)
    val r = SDL.createRenderer(win)
    val text = cn.enaium.battalion.game.ui.TextRenderer(r); text.load()
    val tm = cn.enaium.battalion.game.ui.TextureManager(dir, r); tm.load()

    fun snap(
        screen: String,
        name: String,
        prep: (cn.enaium.battalion.game.ui.StartScreen) -> Unit = {},
    ) {
        val start = cn.enaium.battalion.game.ui.StartScreen(dir, r, text, tm)
        start.setScreenForCapture(screen)
        prep(start)
        // Draw several frames with present() + a delay so the backbuffer delivers the current
        // frame (renderReadPixels lags one present otherwise).
        for (i in 1..4) { start.renderFrameForCapture(); SDL.delay(20) }
        val surf = r.renderReadPixels(null)
        if (surf != null) {
            saveShot(surf, name)
            surf.close()
        } else println("capture failed for $name")
    }

    snap("main", "menu_main")
    snap("credits", "menu_credits")
    // The credits roll starts below the panel; capture it mid-scroll too.
    snap("credits", "menu_credits_roll") { it.setCreditsScrollForCapture(150f) }
    snap("credits", "menu_credits_port") { it.setCreditsScrollForCapture(850f) }
    snap("options", "menu_options")
    snap("options", "menu_options_confirm") { it.setConfirmingClearForCapture(true) }
    for (id in GameLoader.CAMPAIGN_IDS + GameLoader.BOOTCAMP_CAMPAIGN) {
        snap("campaign:$id", "menu_$id")
    }
    // Three-team level: the picker must show all three faction colours (Ghosts M6 adds the
    // green Dragoons to the blue Akadians and red N. Federation).
    snap("campaign:ghosts#5", "menu_ghosts_3teams")

    // Faction picker: the enemy swatch sits right of the next-mission arrow (a 22px button at
    // x = 570 for a two-team mission, vertically centred on the chapter panel).
    val faction = cn.enaium.battalion.game.ui.StartScreen(dir, r, text, tm)
    faction.setScreenForCapture("campaign:vengeance")
    for (i in 1..4) { faction.renderFrameForCapture(); SDL.delay(20) }
    faction.clickForCapture(580, 235)
    for (i in 1..4) { faction.renderFrameForCapture(); SDL.delay(20) }
    val swapped = r.renderReadPixels(null)
    if (swapped != null) {
        saveShot(swapped, "menu_faction_swapped")
        swapped.close()
    } else println("capture failed for menu_faction_swapped")

    // Switch generations through the real click path: park the pointer on the "Nemesis"
    // button of the Vengeance page and click it, then capture the resulting list.
    val start = cn.enaium.battalion.game.ui.StartScreen(dir, r, text, tm)
    start.setScreenForCapture("campaign:vengeance")
    for (i in 1..4) { start.renderFrameForCapture(); SDL.delay(20) }
    val clickX = cn.enaium.battalion.game.ui.StartScreen.SWITCH_ROW_MARGIN + 40
    val clickY = cn.enaium.battalion.game.ui.StartScreen.SWITCH_ROW_Y + 17
    start.clickForCapture(clickX, clickY)
    for (i in 1..4) { start.renderFrameForCapture(); SDL.delay(20) }
    val afterClick = r.renderReadPixels(null)
    if (afterClick != null) {
        saveShot(afterClick, "menu_click_switch")
        afterClick.close()
    } else println("capture failed for menu_click_switch")

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
private fun diag(dir: String, mission: Int, skirmish: String?, campaign: String) {
    val game = if (skirmish != null) {
        GameLoader.loadSkirmish(dir, skirmish)
    } else {
        GameLoader.loadCampaignMission(dir, mission, campaign)
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
        if (sw.ownerTeam() != 0) continue
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
    val names = game.map.palette.filter { it.ownerTeam() == 0 }.map { it.name }.toSet()
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
private fun movetest(dir: String, mission: Int, skirmish: String?, campaign: String) {
    val game = if (skirmish != null) {
        GameLoader.loadSkirmish(dir, skirmish)
    } else {
        GameLoader.loadCampaignMission(dir, mission, campaign)
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
private fun facetest(dir: String, mission: Int, skirmish: String?, campaign: String) {
    val game = if (skirmish != null) {
        GameLoader.loadSkirmish(dir, skirmish)
    } else {
        GameLoader.loadCampaignMission(dir, mission, campaign)
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
            var guard = 0
            while (!am.isEmpty() && guard++ < 10000) {
                game.update(16)   // advance the clock so the walk animation can complete
            }
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
private fun texcheck(dir: String, mission: Int, skirmish: String?, campaign: String) {
    val game = if (skirmish != null) GameLoader.loadSkirmish(dir, skirmish)
               else GameLoader.loadCampaignMission(dir, mission, campaign)
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
    snap("main")
    snap("credits")
    for (id in GameLoader.CAMPAIGN_IDS + GameLoader.BOOTCAMP_CAMPAIGN) snap("campaign:$id")
    snap("options"); snap("options", confirm = true)

    fun exercise(load: () -> cn.enaium.battalion.game.GameManager) {
        val game = load()
        // Mission brief dialogue (startGame event) -> dialogue panel + avatars + continue.
        val script = game.map.script
        if (script.events.any { e -> e.conditions.any { it.type == "startGame" } }) {
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
            val key = u.spriteKey.sheet
            if (key.isEmpty()) continue
            tm.texture(key)
            tm.texture(u.spriteKey.firingSheet.ifEmpty { "no_such_sprite" })
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

    // Every campaign generation's missions, plus the boot camp tutorial and the bonus map.
    for (campaign in GameLoader.findCampaigns(dir) +
        listOfNotNull(GameLoader.findCampaign(dir, GameLoader.BOOTCAMP_CAMPAIGN))) {
        for (m in campaign.missions.indices) {
            exercise { GameLoader.loadCampaignMission(dir, m, campaign.id) }
        }
    }
    exercise { GameLoader.loadSkirmish(dir, "ep3/Kongregate_Bonus_Map") }

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
        saveShot(surf, "carddiag")
        surf.close()
    }
    tm.close(); r.close(); win.close(); SDL.quit()
}

/**
 * Opens the window, renders the game, captures screenshots to verify the rendering fixes
 * (RGB/BGR, shore strips, unit facing) and simulates a click-to-move to verify movement.
 * Saves PNGs to the job tmp dir.
 */
private fun shot(dir: String, mission: Int, skirmish: String?, campaign: String, unitName: String?) {
    val game = if (skirmish != null) GameLoader.loadSkirmish(dir, skirmish)
                else GameLoader.loadCampaignMission(dir, mission, campaign)
    SDL.setMainReady()
    SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
    val win = SDL.createWindow("shot", 800, 602)
    val r = SDL.createRenderer(win)
    val text = cn.enaium.battalion.game.ui.TextRenderer(r); text.load()
    val tm = cn.enaium.battalion.game.ui.TextureManager(dir, r); tm.load()
    val renderer = cn.enaium.battalion.game.BattalionRenderer(game, text, r, tm)
    renderer.setupTextures()
    game.startGame()
    val dia = cn.enaium.battalion.game.ui.Dialogue(game.map.script)
    renderer.dialogue = dia
    dia.play("startGame")

    fun snap(name: String) {
        // A few frames, not one: renderReadPixels returns the frame before the last present,
        // so the extra frames keep each capture showing the state it was taken for.
        for (i in 1..5) {
            game.update(16)
            try { renderer.render() } catch (t: Throwable) {
                println("RENDER THREW during $name: $t"); t.printStackTrace(); break
            }
            SDL.delay(16)
        }
        val surf = r.renderReadPixels(null)
        if (surf != null) {
            saveShot(surf, name)
            surf.close()
        } else {
            println("capture failed for $name")
        }
    }

    // Let the dialogue slide-in (500ms) complete so the panel is fully on-screen.
    for (i in 1..40) { game.update(16); renderer.render() }
    println("STARTUP camera=${renderer.cameraX},${renderer.cameraY} target=${game.cameraTarget?.unitData?.name}@${game.cameraTarget?.x},${game.cameraTarget?.y}")

    // Shot 1: after startup, dialogue still active (fully slid in, typing).
    snap("shot1_startup")

    // Dismiss dialogue.
    while (dia.isActive()) dia.advance()
    // The game routes the script's highlight / scroll responses to the camera while the brief
    // plays (BattalionGame's script-feedback hook) and re-focuses the player's force once it is
    // dismissed. Simulate that pair here to check the ordering: a scripted "look over there"
    // must not leave the player staring at the wrong side of the map when they gain control.
    renderer.centerCameraOn(10, 6)
    println("SCRIPT SNAP camera=${renderer.cameraX},${renderer.cameraY}")
    game.focusActiveForce()
    // The opening pan must be free of frame hitches (textures are prewarmed by setupTextures).
    var worstFrameMs = 0L
    for (i in 1..120) {
        val t0 = System.nanoTime()
        game.update(16); renderer.render()
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms > worstFrameMs) worstFrameMs = ms
    }
    println("STARTUP PAN worst frame ${worstFrameMs}ms")
    println("AFTER BRIEF camera=${renderer.cameraX},${renderer.cameraY} target=${game.cameraTarget?.unitData?.name}@${game.cameraTarget?.x},${game.cameraTarget?.y}")

    // Shot 2: map rendered, no selection.
    snap("shot2_map")

    // Simulate click-to-move: select the human's first unit (or the one named by --unit),
    // move it to its right neighbour.
    val human = game.activeController as? cn.enaium.battalion.game.controllers.HumanController
    if (human != null) {
        val unit = game.units.firstOrNull {
            it.controller === human && it.isDeployed() && (unitName == null || it.unitData.name == unitName)
        }
        if (unit != null) {
            human.selectUnit(unit)
            snap("shot3_selected")

            // Hovering an attackable enemy must keep previewing the walk: the path to the tile
            // the unit strikes from ("nomove" when it can already fire from where it stands).
            val enemy = game.unitsAttackableThisTurn(unit).firstOrNull()
            if (enemy != null) {
                // First hover a plain empty tile so a path is on screen, then the enemy: an
                // attackable target with no reachable firing tile must keep that path (the
                // original's chooseMove returns without clearing it).
                val startTile = game.tileAt(unit.x, unit.y)!!
                var emptyPath = -1
                outerEmpty@ for (dy in -3..3) {
                    for (dx in -3..3) {
                        if (dx == 0 && dy == 0) continue
                        val t = game.tileAt(unit.x + dx, unit.y + dy) ?: continue
                        if (t.unit != null) continue
                        val p = cn.enaium.battalion.data.Astar.path(startTile, t, unit) ?: continue
                        if (!game.canMove(unit, p)) continue
                        val (ex, ey) = renderer.tileCenterScreenForCapture(t.x, t.y)
                        if (ex !in 0..560 || ey !in 0..560) continue
                        SDL.warpMouseInWindow(win.id, ex.toFloat(), ey.toFloat())
                        for (i in 1..3) { game.update(16); renderer.render() }
                        emptyPath = renderer.hoverPathSizeForCapture()
                        break@outerEmpty
                    }
                }
                val (hx, hy) = renderer.tileCenterScreenForCapture(enemy.x, enemy.y)
                if (hx in 0..560 && hy in 0..560) {
                    SDL.warpMouseInWindow(win.id, hx.toFloat(), hy.toFloat())
                    for (i in 1..3) { game.update(16); renderer.render() }
                    snap("shot3b_hover_enemy")
                    println("HOVER ENEMY ${enemy.unitData.name}@(${enemy.x},${enemy.y}) " +
                        "path=${renderer.hoverPathSizeForCapture()} nomove=${renderer.hoverNomoveForCapture()} " +
                        "emptyHover=$emptyPath")
                } else {
                    println("HOVER ENEMY off-screen at ($hx,$hy)")
                }
            } else {
                println("HOVER ENEMY none attackable for ${unit.unitData.name}")
            }

            // Attack-from-here marker: a unit that can already strike an enemy without moving
            // must show movement_nomove on its own tile (the original's single-tile path).
            hoverNomove@ for (u in game.units.filter { it.controller === human && it.isDeployed() }) {
                for (e in game.unitsAttackableThisTurn(u)) {
                    if (!u.isRanged() && !game.canAttack(u, e)) continue
                    val (nx, ny) = renderer.tileCenterScreenForCapture(e.x, e.y)
                    if (nx !in 0..560 || ny !in 0..560) continue
                    // The marker lands on the unit's own tile — require it to be on screen too.
                    val (ux, uy) = renderer.tileCenterScreenForCapture(u.x, u.y)
                    if (ux !in 40..520 || uy !in 40..520) continue
                    human.selectUnit(u)
                    SDL.warpMouseInWindow(win.id, nx.toFloat(), ny.toFloat())
                    for (i in 1..3) { game.update(16); renderer.render() }
                    snap("shot3c_hover_nomove")
                    println("HOVER NOMOVE ${u.unitData.name} on ${e.unitData.name}: " +
                        "nomove=${renderer.hoverNomoveForCapture()} path=${renderer.hoverPathSizeForCapture()} " +
                        "tex=${renderer.hoverNomoveTextureForCapture()} unitScreen=($ux,$uy)")
                    break@hoverNomove
                }
            }
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
                            val key = u.spriteKey.sheet
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
    // Camera pan check: aim the view at the furthest unit with a hold that expires almost
    // immediately — the pan must still glide all the way there (never stranded halfway).
    val farUnit = game.units.filter { it.isDeployed() && it.isAlive() }
        .maxByOrNull { kotlin.math.abs(it.x - renderer.cameraX) + kotlin.math.abs(it.y - renderer.cameraY) }
    if (farUnit != null) {
        val before = "${renderer.cameraX},${renderer.cameraY}"
        game.setCameraTarget(farUnit, 30)
        for (i in 1..200) { game.update(16); renderer.render() }
        println(
            "PAN CHECK ${farUnit.unitData.name}@${farUnit.x},${farUnit.y} camera $before -> " +
                "${renderer.cameraX},${renderer.cameraY} (target ${game.cameraTarget?.unitData?.name ?: "released"})",
        )
    }

    // AI turn: the view must follow the AI's units as they move (the camera eases toward the
    // moving unit while its hold time runs). Ends the player's turn and watches the AI play.
    val aiHuman = game.activeController as? cn.enaium.battalion.game.controllers.HumanController
    if (aiHuman != null && !game.gameOver) {
        val camBefore = renderer.cameraX to renderer.cameraY
        aiHuman.dispatchEndTurn()
        var ticks = 0
        var following: cn.enaium.battalion.data.Unit? = null
        while (ticks++ < 6000 && !game.gameOver) {
            game.update(16)
            renderer.render()
            val target = game.cameraTarget
            if (target != null && target.isMoving) {
                following = target
                break
            }
        }
        if (following != null) {
            for (i in 1..12) { game.update(16); renderer.render() }
            val midPan = "${renderer.cameraX},${renderer.cameraY}"
            snap("shot11_ai_turn")
            // Let the pan settle (the hold may expire; the view still finishes moving to the
            // unit it was following).
            for (i in 1..240) { game.update(16); renderer.render() }
            println(
                "AI camera follow: ${following.unitData.name} at ${following.x},${following.y} " +
                    "camera ${camBefore.first},${camBefore.second} -> $midPan -> " +
                    "${renderer.cameraX},${renderer.cameraY}",
            )
        } else {
            println("AI camera follow: no AI unit movement observed in $ticks ticks")
        }
    }

    // Recon strip on a plain terrain tile (the `recon_terrain` layout, no unit).
    val plainTile = game.map.generateTileMap().firstNotNullOfOrNull { row ->
        row.firstOrNull { it.unit == null && !it.isStructure() && it.terrain.name.isNotEmpty() }
    }
    if (plainTile != null) {
        renderer.setFocusTile(plainTile)
        snap("shot10_recon_terrain")
        renderer.setFocusTile(null)
    }

    // In-game options menu with the four Vista buttons (mouse parked over Quit for hover).
    renderer.setInGameMenuOpen(true)
    SDL.warpMouseInWindow(win.id, 459f, 313f)
    snap("shot9_ingamemenu")
    renderer.setQuitConfirm(true)
    snap("shot8_quitconfirm")
    // Quick Help card: the original blits the 700×600 bitmap over the whole stage.
    renderer.setQuitConfirm(false)
    renderer.setInGameMenuOpen(true)
    renderer.showQuickHelp = true
    snap("shot7_quickhelp")
    renderer.showQuickHelp = false
    renderer.setInGameMenuOpen(false)

    // Results screen (GameOverMenu). Seed statistics so every column is legible, then capture
    // the victory variant (medal grading + chapter panel) and the defeat variant.
    val team = cn.enaium.battalion.data.Team
    for ((i, c) in game.controllers.filterNotNull().withIndex()) {
        val t = c.team
        t.addStatistic(team.UNITS_BUILT, 7 + i * 3)
        t.addStatistic(team.GROUND_UNITS_BUILT, 4 + i)
        t.addStatistic(team.SEA_UNITS_BUILT, 3 + i)
        t.addStatistic(team.AIR_UNITS_BUILT, 1)
        t.addStatistic(team.RESOURCES_SPENT, 4200 + i * 900)
        t.addStatistic(team.KILLED_PLAYER1_UNIT, 3 + i)
        t.addStatistic(team.KILLED_PLAYER2_UNIT, 2)
        t.addStatistic(team.KILLED_PLAYER4_UNIT, 1)
        t.addStatistic(team.RESOURCE_DAMAGE_CAUSED, 1500 + i * 700)
        t.addStatistic(team.ORE_EXTRACTED, 3200 + i * 400)
        t.addStatistic(team.STRUCTURES_CAPTURED, 2 * (2 - i))
        t.addStatistic(team.STRUCTURES_LOST, 1 + i)
        t.addStatistic(team.UNITS_KILLED, 6 - i * 2)
        t.addStatistic(team.RESOURCES_COLLECTED, 9000)
        t.addStatistic(team.ROUNDS_TAKEN, 3)
    }
    // The real quit flow: the in-game menu's Quit surrenders the local player, so every unit
    // and structure they own blasts on the way out, and the results screen then shows a defeat.
    val blastCount = game.units.count { it.controller === game.controllers.filterNotNull().firstOrNull { c -> c is cn.enaium.battalion.game.controllers.HumanController } }
    game.surrenderLocalPlayer()
    // The blast sheet runs 16 frames × 75 ms; capture early and at the fireball's peak.
    for (i in 1..10) { game.update(16); renderer.render(); SDL.delay(16) }
    snap("shot14_surrender_blasts")
    for (i in 1..16) { game.update(16); renderer.render(); SDL.delay(16) }
    snap("shot15_surrender_blasts_peak")
    println("SURRENDER: units before=$blastCount units after=${game.units.size} gameOver=${game.gameOver} playerWon=${game.playerWon}")

    // The blast sheet runs out after ~1.2s and the results screen takes over only then
    // (EndGameAction waits for the original's TweenManager to drain).
    for (i in 1..80) { game.update(16); renderer.render(); SDL.delay(4) }
    snap("shot13_gameover_defeat")
    game.forceGameOverForCapture(true)
    snap("shot12_gameover_victory")



    tm.close(); text.close(); r.close(); win.close(); SDL.quit()
}

/**
 * Loads the game and exercises the startup path without SDL, printing diagnostics. Also plays
 * a few full rounds (auto-ending the human's turn) to confirm the AI and the turn loop work.
 */
private fun selftest(dir: String, mission: Int, skirmish: String?, campaign: String, playerTeam: Int? = null) {
    val game = if (skirmish != null) {
        GameLoader.loadSkirmish(dir, skirmish)
    } else {
        GameLoader.loadCampaignMission(dir, mission, campaign, playerTeam = playerTeam)
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

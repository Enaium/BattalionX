package cn.enaium.battalion.game

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.BallisticsTable
import cn.enaium.battalion.data.GameMap
import cn.enaium.battalion.data.MapSettings
import cn.enaium.battalion.data.MoraleChangeAmounts
import cn.enaium.battalion.data.Node
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.actions.EndGameAction
import cn.enaium.battalion.game.actions.EndTurnAction
import cn.enaium.battalion.game.actions.SurrenderAction
import cn.enaium.battalion.game.actions.StartGameAction
import cn.enaium.battalion.game.actions.StartRoundAction
import cn.enaium.battalion.game.actions.StartTurnAction
import cn.enaium.battalion.game.controllers.HALController
import cn.enaium.battalion.game.controllers.HumanController
import cn.enaium.battalion.game.triggers.PlayerTrigger
import cn.enaium.battalion.game.triggers.TimePassedTrigger

/**
 * Central game-state manager. A singleton that owns the tile map, controllers, unit list,
 * and drives the per-frame update loop (triggers → actions → controllers → UI).
 *
 * This is a logic-only port — the original also handled display sprites, tween animation,
 * camera movement, mini-map updates, and network synchronisation. The renderer will read
 * the game state exposed here.
 */
class GameManager(
    val mapSettings: MapSettings,
    private val script: cn.enaium.battalion.util.XmlNode? = null,
) {
    companion object {
        const val UNIT_CHUNKS = 4
        const val TILE_CHUNKS = 8
        const val GAME_WIDTH = 560
        const val GAME_HEIGHT = 560

        lateinit var instance: GameManager
            private set
    }

    val map: GameMap = mapSettings.map!!
    var tileMap: Array<Array<Tile>> = map.generateTileMap()
        private set

    var turn: Int = 0
    var round: Int = 0
    var currentController: Int = 0

    /** Total game time elapsed in ms, accumulated in [update]. Used to drive animations. */
    var elapsedMs: Long = 0
        private set

    val controllers = mutableListOf<Controller?>()
    val units = mutableListOf<Unit>()
    val structures = mutableListOf<Tile>()

    var isMoraleEnabled: Boolean = true
    var isSoloGame: Boolean = true
    val surrenderedControllers = mutableListOf<Controller>()

    val movementCombiner: MovementCombiner by lazy { MovementCombiner() }
    /** Recommends what a factory/warmachine should build to counter a target (build UI + AI). */
    val killerSuggestionEngine = KillerSuggestionEngine

    /** Owns the map-script [GameEvent]s (conditions/responses for campaign & tutorials). */
    val gameEventManager = GameEventManager()

    /** Standalone manager for chat/highlight/scroll script responses (visible/sim output). */
    val responseDisplay = cn.enaium.battalion.game.responses.ResponseDisplay

    // ActionManager is exposed for Unit.isInActionQueue().  In the original, ActionManager
    // was a separate singleton.  We keep it as a direct property here for simplicity.
    val actionManager = ActionManager

    init {
        // Start each game with a clean event bus and script queue. This matches the original
        // app-lifetime singletons being (re)initialised per match, and keeps tests independent
        // (they share one JVM and these are global objects).
        TriggerManager.clearAll()
        ResponseManager.clear()
        // Dump any actions still queued from a previous match/game. Without this, stale
        // actions bleed between games (and between tests sharing one JVM), which wedged the
        // action queue behind leftover work from an earlier turn.
        ActionManager.clear()

        instance = this

        // Build controllers from the teams.
        val teams = mapSettings.teams
        var numHumans = 0
        isSoloGame = true
        for (team in teams) {
            val c = team.createController()
            if (team.controllerType == "Human") {
                numHumans++
            }
            controllers.add(c)
        }
        if (numHumans > 1) isSoloGame = false

        // Generate structures and units from the map.
        val structureTiles = map.generateStructures(tileMap, controllers.filterNotNull())
        for (tile in structureTiles) {
            addStructure(tile.controller, tile)
        }
        val unitList = map.generateUnits(controllers.filterNotNull())
        for (u in unitList) {
            u.deployed = true
            if (u.unitData.hasModifier("Cloaking")) {
                u.setCloaked(true, false, false)
            }
            addUnit(u.controller!!, u)
            tileMap[u.y][u.x].unit = u
        }

        // Parse the map script (triggers, events, etc.).
        map.getScript()?.let { ScriptManager.parseScript(it) }

        // Register default surrender conditions (command center captured or no units left).
        // The original GameManager registers a NoUnitsCondition + CaptureTerrainCondition
        // ("Command Center") per player, each responding with a SurrenderAction. Here we listen
        // for the [PlayerTrigger.NO_UNITS] trigger (fired by [removeUnit] when a controller runs
        // out of mobile units) and surrender the player, which advances the game toward a
        // win/loss decision via [surrender] → [isOneActiveAlliance] → [EndGameAction].
        TriggerManager.addListener(PlayerTrigger.NO_UNITS) { trigger ->
            val playerTrigger = trigger as? PlayerTrigger
            val idx = playerTrigger?.player ?: -1
            if (idx in controllers.indices) {
                val c = controllers[idx]
                if (c?.team?.active == true) {
                    val surrender = SurrenderAction(idx)
                    surrender.isNetworked = false
                    ActionManager.add(surrender, toFront = true)
                }
            }
        }

        // Similarly, if a player's Command Center (Mission Critical structure) is captured by an
        // enemy, they immediately surrender (a captured capital is a decisive loss).
        TriggerManager.addListener(cn.enaium.battalion.game.triggers.CaptureTerrainTrigger.NAME) { trigger ->
            val cap = trigger as? cn.enaium.battalion.game.triggers.CaptureTerrainTrigger ?: return@addListener
            // Only a *completed* capture of a Mission Critical structure triggers surrender.
            if (cap.complete && cap.tile.terrain.hasModifier("Mission Critical")) {
                val old = cap.oldPlayer
                if (old in controllers.indices) {
                    val c = controllers[old]
                    if (c?.team?.active == true) {
                        val surrender = SurrenderAction(old)
                        surrender.isNetworked = false
                        ActionManager.add(surrender, toFront = true)
                    }
                }
            }
        }

        // Load the ballistics table.
        resetBallisticsTable()
    }

    // -----------------------------------------------------------------------
    // Tile helpers
    // -----------------------------------------------------------------------

    fun tileAt(x: Int, y: Int): Tile? {
        if (x < 0 || y < 0 || x > map.width - 1 || y > map.height - 1) return null
        return tileMap[y][x]
    }

    fun unitAt(x: Int, y: Int): Unit? = tileAt(x, y)?.unit

    fun unitTile(unit: Unit): Tile? = tileAt(unit.x, unit.y)

    /** Converts a flat ["x","y","x","y",...] path string to a list of tiles. */
    fun nodePath(pathStrings: List<String>): List<Tile>? {
        val result = mutableListOf<Tile>()
        for (i in pathStrings.indices step 2) {
            val x = pathStrings[i].toIntOrNull() ?: return null
            val y = pathStrings[i + 1].toIntOrNull() ?: return null
            val tile = tileAt(x, y) ?: return null
            result.add(tile)
        }
        return result
    }

    fun pointPath(path: List<Tile>?): List<Int>? {
        if (path == null) return null
        val result = mutableListOf<Int>()
        for (tile in path) {
            result.add(tile.x)
            result.add(tile.y)
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Controller helpers
    // -----------------------------------------------------------------------

    fun getController(index: Int): Controller? =
        if (index in controllers.indices) controllers[index] else null

    /** The controller whose turn it currently is. */
    val activeController: Controller? get() = getController(currentController)

    fun getPlayer(controller: Controller): Int = controllers.indexOf(controller)

    fun getControllerIndex(controller: Controller): Int = controllers.indexOf(controller)

    fun activePlayers(): Int = controllers.count { it?.team?.active == true }

    fun isOneActiveAlliance(): Boolean {
        val actives = controllers.filter { it?.team?.active == true }.filterNotNull()
        if (actives.size < 2) return true
        for (i in actives.indices) {
            for (j in i + 1 until actives.size) {
                if (!actives[i].isAllied(actives[j])) return false
            }
        }
        return true
    }

    fun isAlliedToThePlayer(controller: Controller): Boolean {
        val human = controllers.filterIsInstance<HumanController>().firstOrNull() ?: return false
        return human.isAllied(controller)
    }

    // -----------------------------------------------------------------------
    // Unit and structure management
    // -----------------------------------------------------------------------

    fun addUnit(controller: Controller, unit: Unit) {
        controller.addUnit(unit)
        units.add(unit)
    }

    fun removeUnit(unit: Unit) {
        unit.controller?.removeUnit(unit)
        if (unit.isDeployed()) {
            tileMap[unit.y][unit.x].unit = null
        }
        unit.deployed = false
        units.remove(unit)

        // If the controller has no non-fixed units left, fire a NO_UNITS trigger.
        val controllerUnits = unit.controller?.units ?: return
        val mobileUnits = controllerUnits.filter {
            it !== unit && !it.unitData.hasModifier("Fixed")
        }
        if (unit.controller?.team?.active == true && mobileUnits.isEmpty()) {
            val playerIndex = getPlayer(unit.controller!!)
            if (playerIndex != -1) {
                TriggerManager.add(PlayerTrigger(PlayerTrigger.NO_UNITS, playerIndex))
            }
            return
        }

        // Linchpin morale penalty.
        if (unit.controller?.team?.active == true && unit.baseUnitData.hasModifier("Linchpin")) {
            changeMorale(unit.controller!!, MoraleChangeAmounts.LINCHPIN_LOST, MoraleChangeAmounts.LINCHPIN_LOST_DESCRIPTION)
            // If this was the last linchpin, the player effectively has no units.
            val hasLinchpinLeft = controllerUnits.any {
                it !== unit && it.baseUnitData.hasModifier("Linchpin")
            }
            if (!hasLinchpinLeft) {
                val playerIndex = getPlayer(unit.controller!!)
                if (playerIndex != -1) {
                    TriggerManager.add(PlayerTrigger(PlayerTrigger.NO_UNITS, playerIndex))
                }
            }
        }
    }

    fun addStructure(controller: Controller?, tile: Tile) {
        controller?.addStructure(tile)
        structures.add(tile)
    }

    fun removeStructure(tile: Tile) {
        tile.controller?.removeStructure(tile)
        tile.downgrade()
        structures.remove(tile)
    }

    fun addControllerToSurrenderedList(controller: Controller) {
        surrenderedControllers.add(controller)
    }

    // -----------------------------------------------------------------------
    // Movement and pathfinding
    // -----------------------------------------------------------------------

    fun canMove(unit: Unit, path: List<Node>, ignoreEnd: Boolean = false): Boolean {
        if (!unit.isAlive() || !unit.isDeployed() || unit.hasMoved()) return false
        if (path.isEmpty()) return false
        if (!ignoreEnd) {
            val lastTile = path[path.lastIndex] as? Tile ?: return false
            if (lastTile.unit != null &&
                lastTile.unit != unit &&
                !(lastTile.unit?.isCloaked() == true)
            ) return false
        }
        val start = path[0] as? Tile ?: return false
        if (start.unit !== unit) return false
        var current: Tile = start
        var cost = 0.0
        for (i in 1 until path.size) {
            val check = path[i] as? Tile ?: return false
            if (!current.isConnected(check)) return false
            cost += check.getCost(unit, 0, false)
            current = check
        }
        return cost <= unit.getRange()
    }

    fun moveCost(unit: Unit, path: List<Tile>): Double {
        if (!canMove(unit, path, ignoreEnd = true)) return Double.POSITIVE_INFINITY
        var cost = 0.0
        for (i in 1 until path.size) {
            cost += path[i].getCost(unit, 0, false)
        }
        return cost
    }

    // -----------------------------------------------------------------------
    // Combat
    // -----------------------------------------------------------------------

    fun canWeaponsFireAtTarget(
        unit: Unit,
        targetUnit: Unit,
        strict: Boolean = false,
        attackerX: Int = -1,
        attackerY: Int = -1,
        targetX: Int = -1,
        targetY: Int = -1,
    ): Boolean {
        val ux = if (attackerX == -1) unit.x else attackerX
        val uy = if (attackerY == -1) unit.y else attackerY
        val tx = if (targetX == -1) targetUnit.x else targetX
        val ty = if (targetY == -1) targetUnit.y else targetY

        if (unit.unitData.damage <= 0) return false
        if (unit.unitData.weaponRangeMin <= 0 && unit.unitData.weaponRangeMax <= 0) return false
        if (targetUnit.isCloaked()) return false

        if (targetUnit.unitData.isAir()) {
            return unit.unitData.hasModifier("Skysweeper")
        }
        if (unit.unitData.hasModifier("Air Bound")) return false
        if (!targetUnit.unitData.isGround() && unit.unitData.hasModifier("Ground Bound")) return false
        if (!targetUnit.unitData.isSea() && unit.unitData.hasModifier("Sea Bound")) return false
        if (targetUnit.unitData.hasModifier("Submerged") && !unit.unitData.hasModifier("Anti-Sub")) return false

        if (strict && unit.isRanged()) {
            val attackerTile = tileAt(ux, uy)
            if (attackerTile?.terrain?.hasModifier("Canopy") == true) return false
            val targetTile = tileAt(tx, ty)
            if (targetUnit.unitData.isGround() && targetTile?.terrain?.hasModifier("Shielded") == true) return false
        }

        return true
    }

    fun canAttackTile(
        unit: Unit,
        targetTile: Tile,
        ignoreChecks: Boolean = false,
        attackerX: Int = -1,
        attackerY: Int = -1,
        unitCanMove: Boolean = false,
    ): Boolean {
        if (!unit.isAlive() || !unit.isDeployed()) return false
        if (!ignoreChecks) {
            if (unit.hasFired()) return false
            if (unit.isRanged() && unit.hasMoved()) return false
        }
        val tiles = if (unitCanMove) tilesInMoveRange(unit, attackerX, attackerY)
                    else tilesInRange(unit, attackerX, attackerY)
        return tiles.contains(targetTile)
    }

    fun canAttack(
        unit: Unit,
        targetUnit: Unit,
        ignoreChecks: Boolean = false,
        attackerX: Int = -1,
        attackerY: Int = -1,
        unitCanMove: Boolean = false,
    ): Boolean {
        if (!canWeaponsFireAtTarget(unit, targetUnit, true, attackerX, attackerY)) return false
        if (unit.controller?.isAllied(targetUnit.controller) == true) return false
        if (targetUnit.isCloaked()) return false
        val targetTile = tileAt(targetUnit.x, targetUnit.y) ?: return false
        return canAttackTile(unit, targetTile, ignoreChecks, attackerX, attackerY, unitCanMove)
    }

    fun resolveAttack(
        attackerUnit: Unit,
        defenderUnit: Unit,
        defenderTile: Tile? = null,
        attackerTile: Tile? = null,
        isAttack: Boolean = true,
        useTiles: Boolean = true,
    ): Double {
        val defTile = if (useTiles) defenderTile ?: tileAt(defenderUnit.x, defenderUnit.y) else defenderTile
        val atkTile = if (useTiles) attackerTile ?: tileAt(attackerUnit.x, attackerUnit.y) else attackerTile

        var damage = attackerUnit.unitData.applyModifiers("damage", defenderUnit, attackerUnit.unitData.damage.toDouble())

        if (useTiles && atkTile != null && atkTile.isStructure()) {
            damage = attackerUnit.unitData.applyModifiers("structureBonus", defenderUnit, damage)
        }

        if (isAttack) {
            damage = attackerUnit.unitData.applyModifiers("attackBonus", defenderUnit, damage)
        } else {
            damage = attackerUnit.unitData.applyModifiers("returnAttackBonus", defenderUnit, damage)
        }

        val scale = attackerUnit.getChunks(UNIT_CHUNKS).toDouble() / UNIT_CHUNKS
        val damageScale = BallisticsTable.getModifier(attackerUnit.unitData.weaponType, defenderUnit.unitData.armorType)

        var defenseScale = 1.0
        if (useTiles && defTile != null) {
            defenseScale = defTile.terrain.applyModifiers("defense", defenderUnit, defenseScale)
            if (attackerUnit.isRanged()) {
                defenseScale = defTile.terrain.applyModifiers("rangedDefense", defenderUnit, defenseScale)
            }
        }

        return damage * damageScale * scale * defenseScale * attackerUnit.controller?.getDifficultyAttackScale()!!
    }

    fun resolveAttackInResources(
        attackerUnit: Unit,
        defenderUnit: Unit,
        isAttack: Boolean = true,
    ): Double {
        if (!canWeaponsFireAtTarget(attackerUnit, defenderUnit)) return 0.0
        var damage = resolveAttack(attackerUnit, defenderUnit, null, null, isAttack, useTiles = false)
        val cappedDamage = if (damage > defenderUnit.getHealth()) defenderUnit.getHealth().toDouble() else damage
        return cappedDamage / defenderUnit.baseUnitData.health.toDouble() * defenderUnit.baseUnitData.cost.toDouble()
    }

    fun resolveAttackCycleInResources(attackerUnit: Unit, defenderUnit: Unit): Double {
        if (!canWeaponsFireAtTarget(attackerUnit, defenderUnit)) return 0.0
        var resources = 0.0
        var damage = resolveAttack(attackerUnit, defenderUnit, null, null, true, useTiles = false)
        resources += damage / defenderUnit.unitData.health.toDouble() * defenderUnit.unitData.cost.toDouble()

        if (defenderUnit.getHealth() - damage > 0) {
            // Counterattack check.
            val returnUnit = defenderUnit.clone()
            returnUnit.takeDamage(damage)
            var returnFire = true
            if (returnUnit.isRanged() && !returnUnit.unitData.hasModifier("Counter Battery")) returnFire = false
            if (returnUnit.unitData.hasModifier("Blind Spot")) returnFire = false
            if (attackerUnit.isRanged()) returnFire = false
            if (returnFire) {
                resources -= resolveAttackInResources(returnUnit, attackerUnit, false)
            }
        }
        return resources
    }

    // -----------------------------------------------------------------------
    // Range queries
    // -----------------------------------------------------------------------

    fun getNodesInAttackRange(x: Int, y: Int, unit: Unit): List<Node> {
        if (unit.isRanged()) {
            val minRange = unit.unitData.weaponRangeMin.toDouble()
            var maxRange = unit.unitData.weaponRangeMax.toDouble()
            val tile = tileAt(x, y)!!
            maxRange = tile.terrain.applyModifiers("attackRange", unit, maxRange)
            return Astar.range(tileMap[y][x], minRange, maxRange)
        }
        return Astar.search(tileMap[y][x], null, unit, 0.0, additional = true) ?: emptyList()
    }

    fun tilesInMoveRange(
        unit: Unit,
        startX: Int = -1,
        startY: Int = -1,
    ): List<Tile> {
        val sx = if (startX == -1) unit.x else startX
        val sy = if (startY == -1) unit.y else startY
        if (unit.isRanged()) return tilesInRange(unit, sx, sy)
        return (Astar.search(tileMap[sy][sx], null, unit, 0.0, additional = true) ?: emptyList()).filterIsInstance<Tile>()
    }

    fun tilesInRange(
        unit: Unit,
        startX: Int = -1,
        startY: Int = -1,
        forceMinRange: Int = -1,
        forceMaxRange: Int = -1,
    ): List<Tile> {
        val sx = if (startX == -1) unit.x else startX
        val sy = if (startY == -1) unit.y else startY
        val minRange: Double
        val maxRange: Double
        if (forceMinRange != -1 && forceMaxRange != -1) {
            minRange = forceMinRange.toDouble()
            maxRange = forceMaxRange.toDouble()
        } else {
            minRange = unit.unitData.weaponRangeMin.toDouble()
            var r = unit.unitData.weaponRangeMax.toDouble()
            if (unit.isRanged()) {
                val tile = tileAt(unit.x, unit.y)!!
                r = tile.terrain.applyModifiers("attackRange", unit, r)
            }
            maxRange = r
        }
        return Astar.range(tileMap[sy][sx], minRange, maxRange).filterIsInstance<Tile>()
    }

    fun unitsInRange(
        unit: Unit,
        x: Int = -1,
        y: Int = -1,
        forceMinRange: Int = -1,
        forceMaxRange: Int = -1,
    ): List<Unit> {
        val tiles = tilesInRange(unit, x, y, forceMinRange, forceMaxRange)
        return tiles.mapNotNull { it.unit }.filter { it.controller?.isAllied(unit.controller) != true }
    }

    /**
     * Every enemy unit the given unit can attack THIS TURN — i.e. within weapon range of some
     * tile the unit can reach by walking this turn (including its current tile). This is what
     * the original human controller highlights (it places attack buttons on these targets),
     * rather than only showing what is in range from the unit's current position.
     */
    fun unitsAttackableThisTurn(
        unit: Unit,
        startX: Int = -1,
        startY: Int = -1,
    ): List<Unit> {
        if (!unit.canAttack() || unit.hasFired()) return emptyList()
        val result = mutableListOf<Unit>()

        if (unit.isRanged()) {
            // Ranged unit: attack from its current tile (and, if it hasn't moved, also tiles it
            // could move to). The original lets a ranged unit fire from reachable positions.
            val tiles = if (unit.hasMoved()) listOf((tileAt(unit.x, unit.y) ?: return emptyList()))
                        else tilesInMoveRange(unit, startX, startY)
            val seen = hashSetOf<Unit>()
            for (t in tiles) {
                val inRange = tilesInRange(unit, t.x, t.y)
                for (tile in inRange) {
                    val target = tile.unit ?: continue
                    if (target.controller?.isAllied(unit.controller) != true &&
                        !seen.contains(target) &&
                        canWeaponsFireAtTarget(unit, target, true, t.x, t.y)
                    ) {
                        seen.add(target)
                        result.add(target)
                    }
                }
            }
            return result
        }

        // Non-ranged (adjacent-only) attacker: it can attack an enemy on any tile adjacent to a
        // tile it can reach this turn.
        val reachable = tilesInMoveRange(unit, startX, startY).toMutableSet()
        reachable.add(tileAt(unit.x, unit.y) ?: return emptyList())
        val resultSet = hashSetOf<Unit>()
        for (t in reachable) {
            for (n in t.connections) {
                val nt = n as? Tile ?: continue
                val target = nt.unit ?: continue
                if (target.controller?.isAllied(unit.controller) != true &&
                    !resultSet.contains(target) &&
                    canWeaponsFireAtTarget(unit, target, true, t.x, t.y)
                ) {
                    resultSet.add(target)
                    result.add(target)
                }
            }
        }
        return result
    }

    fun unitsInMoveRange(unit: Unit, startX: Int = -1, startY: Int = -1): List<Unit> {
        val nodes = tilesInMoveRange(unit, startX, startY)
        val result = mutableListOf<Unit>()
        for (node in nodes) {
            val tile = node as? Tile ?: continue
            if (tile.unit != null) {
                val linked = tile.connections.any {
                    it in nodes && ((it as? Tile)?.unit == null || (it as? Tile)?.unit == unit)
                }
                if (linked && tile.unit?.controller?.isAllied(unit.controller) != true) {
                    result.add(tile.unit!!)
                }
            }
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Morale
    // -----------------------------------------------------------------------

    fun changeMorale(controller: Controller?, amount: Double, description: String) {
        if (!isMoraleEnabled || controller == null) return
        val wasAboveZero = controller.morale > 0
        controller.setMorale(controller.morale + amount)
        if (wasAboveZero && controller.morale <= 0) {
            val playerIndex = getPlayer(controller)
            if (playerIndex != -1) {
                val surrender = SurrenderAction(playerIndex)
                surrender.isNetworked = false
                ActionManager.add(surrender, toFront = true)
            }
        }
    }

    fun healPercentage(unit: Unit): Double {
        var perc = (unit.unitData.health - unit.getHealth()).toDouble() / unit.unitData.health.toDouble()
        if (perc > 0.25) perc = 0.25
        return perc
    }

    // -----------------------------------------------------------------------
    // Stealth / radar
    // -----------------------------------------------------------------------

    fun exposeStealthUnitsAround(unit: Unit) {
        val startTile = unit.getTile() ?: return
        val range = if (unit.unitData.hasModifier("Jamming")) 2 else 1
        val tiles = Astar.range(startTile, 0.0, range.toDouble())
        for (node in tiles) {
            val tile = node as? Tile ?: continue
            val otherUnit = tile.unit ?: continue
            if (otherUnit.controller?.isAllied(unit.controller) != true) {
                otherUnit.cloaked = false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Ballistics
    // -----------------------------------------------------------------------

    fun getBallisticsModifier(weaponType: Int, armorType: Int): Double =
        BallisticsTable.getModifier(weaponType, armorType)

    fun resetBallisticsTable() {
        // BallisticsTable is reset when reloaded from XML; this is a no-op in the current port.
    }

    // -----------------------------------------------------------------------
    // Surrender
    // -----------------------------------------------------------------------

    fun surrender(player: Int) {
        val controller = getController(player) ?: return
        controller.team.active = false
        controller.deactivate()
        if (isOneActiveAlliance()) {
            val endGame = EndGameAction()
            endGame.isNetworked = false
            ActionManager.add(endGame, toFront = true)
        }
    }

    // -----------------------------------------------------------------------
    // Game lifecycle
    // -----------------------------------------------------------------------

    fun startGame() {
        val game = StartGameAction()
        game.isNetworked = false
        ActionManager.add(game)

        val roundStart = StartRoundAction()
        roundStart.isNetworked = false
        ActionManager.add(roundStart)

        val turnStart = StartTurnAction()
        turnStart.isNetworked = false
        ActionManager.add(turnStart)

        // The original disables morale for non-arena (solo single-player) games — morale
        // (and morale-driven surrender) only applies in the multiplayer arena.
        isMoraleEnabled = !isSoloGame
    }

    fun endGame() {
        // In the original this called parent.endGame(true) to transition to the results screen.
        // The port signals game-over via a flag; the renderer reads this and shows the score.
        playerWon = isPlayerWinner()
        gameOver = true
    }
    var gameOver: Boolean = false
        private set

    /**
     * True if the game ended and the human (allied) side won. Set by [endGame].
     * Mirrors the original `GameManager.isPlayerWinner`.
     */
    var playerWon: Boolean = false
        private set

    /**
     * Whether the human player (or a surviving ally of the human) is still alive at the end of
     * the game. A human who surrendered, or whose team lost every unit, is a defeat.
     */
    fun isPlayerWinner(): Boolean {
        val human = controllers.filterIsInstance<HumanController>().firstOrNull() ?: return false
        val alivePlayers = controllers.filter { it != null && surrenderedControllers.indexOf(it) < 0 }.filterNotNull()
        if (alivePlayers.contains(human)) {
            return true
        }
        return alivePlayers.any { it.isAllied(human) }
    }

    // -----------------------------------------------------------------------
    // Per-frame update
    // -----------------------------------------------------------------------

    fun update(ms: Long) {
        elapsedMs += ms
        // 1. Dispatch queued triggers.
        TriggerManager.update()

        // 1a. Feed real elapsed time to time-based script conditions (TimePassedTrigger is not
        //     part of the event-bus flush above; it is fired directly every frame).
        TriggerManager.add(TimePassedTrigger(ms))
        TriggerManager.update()

        // 2. Evaluate scripted game events (conditions) and run their responses.
        gameEventManager.update()
        ResponseManager.update()

        // 3. Run the next queued action.
        ActionManager.update()

        // 4. Update every active controller (AI thinks during its turn here).
        for (controller in controllers) {
            if (controller?.team?.active == true) {
                controller.update(ms)
            }
        }
    }
}

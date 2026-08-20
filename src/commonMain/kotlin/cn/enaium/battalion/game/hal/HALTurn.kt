package cn.enaium.battalion.game.hal

import cn.enaium.battalion.data.Astar
import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitData
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.actions.EndTurnAction
import cn.enaium.battalion.game.actions.AttackUnitAction
import cn.enaium.battalion.game.actions.BuildUnitAction
import cn.enaium.battalion.game.actions.CallbackAction
import cn.enaium.battalion.game.actions.WMBuildUnitAction
import cn.enaium.battalion.game.actions.ExtractResourcesAction
import cn.enaium.battalion.game.actions.MoveUnitAction
import cn.enaium.battalion.game.actions.NoAttackAction
import cn.enaium.battalion.game.controllers.HALController

/**
 * AI turn logic — a simplified port of the original HALTurn.as.
 *
 * The AI processes its units one at a time: each unit gets a chance to attack, move,
 * build, or capture. Units that haven't acted after processing are marked as spent.
 *
 * The original HALTurn uses a complex goal/worker/builder system with priority queues.
 * This simplified version achieves functional gameplay with much less code.
 */
class HALTurn(private val hal: HAL) {
    private val game: GameManager = GameManager.instance
    private val controller: HALController = hal.getController()
    private var tick: Int = 0
    private var unitsToProcess: List<Unit> = emptyList()
    private var processedIndex: Int = 0
    private var phase: Phase = Phase.CREATE_GOALS

    enum class Phase {
        CREATE_GOALS,
        PROCESS_UNITS,
        FINISH
    }

    init {
        // Queue the goal-creation callback first.
        ActionManager.add(CallbackAction({ createGoals() }, "HAL:createGoals"))
        ActionManager.add(CallbackAction({ startUnitProcessing() }, "HAL:startProcessing"))
    }

    private fun createGoals() {
        // Goal creation is implicit in this simplified AI — we just process units.
    }

    private fun startUnitProcessing() {
        // Collect all units that belong to this controller and haven't acted yet.
        unitsToProcess = game.units.filter {
            it.controller === controller && it.isAvailable() && it.isDeployed()
        }
        processedIndex = 0
        phase = Phase.PROCESS_UNITS
    }

    fun tick() {
        when (phase) {
            Phase.PROCESS_UNITS -> processNextUnit()
            Phase.FINISH -> finish()
            else -> {}
        }
    }

    private fun processNextUnit() {
        if (processedIndex >= unitsToProcess.size) {
            phase = Phase.FINISH
            return
        }

        val unit = unitsToProcess[processedIndex]
        processedIndex++

        if (!unit.isAlive() || !unit.isDeployed() || !unit.isAvailable()) {
            return
        }

        // 1. Attack an in-range target from the unit's current tile (weapon validity enforced:
        //    only targets the weapon can actually fire at are chosen, so no wasted turns).
        if (unit.canAttack() && !unit.hasFired()) {
            val target = findBestTarget(unit)
            if (target != null) {
                val attack = AttackUnitAction(unit.x, unit.y, target.x, target.y)
                attack.isNetworked = false
                ActionManager.add(attack)
                return
            }

            // 1b. Move-then-attack (the original KillUnitGoal.processAttacker): a unit that
            // cannot fire from its current tile moves into a position from which it can, then
            // attacks the same turn. Ranged units may only attack from their own tile (they
            // cannot move then shoot), so this path is for adjacent-only (melee) attackers.
            if (!unit.isRanged() && unit.canMove() && !unit.hasMoved()) {
                val moveTo = findAttackMove(unit)
                if (moveTo != null) {
                    val path = findPath(unit, moveTo)
                    if (path != null) {
                        val pathStrings = mutableListOf<String>()
                        for (tile in path) {
                            pathStrings.add(tile.x.toString())
                            pathStrings.add(tile.y.toString())
                        }
                        val move = MoveUnitAction(unit.x, unit.y, MoveUnitAction.stringizePath(pathStrings))
                        move.isNetworked = false
                        ActionManager.add(move)

                        // Attack from the move's destination tile (where the unit stands next).
                        val target = bestAdjacentTarget(unit, moveTo.x, moveTo.y)
                        if (target != null) {
                            val attack = AttackUnitAction(moveTo.x, moveTo.y, target.x, target.y)
                            attack.isNetworked = false
                            ActionManager.add(attack)
                        }
                        return
                    }
                }
            }
        }

        // 2. Otherwise move toward enemies / objectives.
        if (unit.canMove() && !unit.hasMoved()) {
            val moveTarget = findMoveTarget(unit)
            if (moveTarget != null) {
                val path = findPath(unit, moveTarget)
                if (path != null) {
                    val pathStrings = mutableListOf<String>()
                    for (tile in path) {
                        pathStrings.add(tile.x.toString())
                        pathStrings.add(tile.y.toString())
                    }
                    val move = MoveUnitAction(unit.x, unit.y, MoveUnitAction.stringizePath(pathStrings))
                    move.isNetworked = false
                    ActionManager.add(move)
                    return
                }
            }
        }

        // 3. For constructors: try to build units.
        if (unit.unitData.hasModifier("Constructor")) {
            // A Warmachine builds onto an adjacent clear tile from its own resource pool via
            // WMBuildUnitAction. A factory constructor builds onto its (Construction) tile using
            // the controller's resources. We only build when we can afford the cheapest unit.
            val buildTarget = if (unit.resourcePool >= 500) findBuildTarget(unit) else findFactoryBuildTarget(unit)
            if (buildTarget != null) {
                val builderTile = game.unitTile(unit)
                val onFactory = builderTile?.terrain?.hasModifier("Construction") == true
                if (!onFactory) {
                    // Warmachine constructor on open terrain: place the new unit adjacent to it.
                    val adjacent = builderTile?.let { findAdjacentTile(unit, it) }
                    if (adjacent != null) {
                        val build = WMBuildUnitAction(
                            controllerIndex = game.getControllerIndex(controller),
                            destinationTileX = adjacent.x,
                            destinationTileY = adjacent.y,
                            unitName = buildTarget.name,
                            builderTileX = unit.x,
                            builderTileY = unit.y,
                        )
                        build.isNetworked = false
                        ActionManager.add(build)
                        return
                    }
                } else {
                    // Factory constructor: build onto the factory tile (controller resources).
                    val build = BuildUnitAction(unit.x, unit.y, buildTarget.name)
                    build.isNetworked = false
                    ActionManager.add(build)
                    return
                }
            }
        }

        // 4. For extractors: try to extract resources.
        if (unit.unitData.hasModifier("Extractor") && unit.resourcePool < 2000) {
            val tile = game.unitTile(unit)
            if (tile != null && tile.terrain.hasModifier("Extractable")) {
                val extract = ExtractResourcesAction(unit.x, unit.y)
                extract.isNetworked = false
                ActionManager.add(extract)
                return
            }
        }

        // 5. For capture units: try to capture structures.
        if (unit.unitData.hasModifier("Capture")) {
            // The structure this unit can reach this turn.
            val target = findCaptureTarget(unit)

            // Already standing on an enemy structure → capture it (crossing the enemy's
            // structure tile is allowed for a Capture unit).
            val currentTile = game.unitTile(unit)
            val onStructure = currentTile != null && currentTile.isStructure() &&
                currentTile.controller !== unit.controller

            if (target != null || onStructure) {
                val dest = if (target != null) target else currentTile!!
                if (dest.unit != unit) {
                    val path = findPath(unit, dest)
                    if (path != null) {
                        val pathStrings = mutableListOf<String>()
                        for (tile in path) {
                            pathStrings.add(tile.x.toString())
                            pathStrings.add(tile.y.toString())
                        }
                        val move = MoveUnitAction(unit.x, unit.y, MoveUnitAction.stringizePath(pathStrings))
                        move.isNetworked = false
                        ActionManager.add(move)
                        return
                    }
                } else if (onStructure) {
                    // NoAttack lets the unit wait on the structure; the capture itself
                    // progresses (this unit ends its turn parked on the enemy HQ).
                    val noAttack = NoAttackAction(unit.x, unit.y)
                    noAttack.isNetworked = false
                    ActionManager.add(noAttack)
                    return
                }
            }
        }

        // If nothing to do, mark as not attacking this turn.
        val noAttack = NoAttackAction(unit.x, unit.y)
        noAttack.isNetworked = false
        ActionManager.add(noAttack)
    }

    private fun findBestTarget(unit: Unit): Unit? {
        val attackRange = game.tilesInRange(unit)
        var bestTarget: Unit? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (tile in attackRange) {
            val target = tile.unit ?: continue
            if (target.controller === unit.controller || unit.controller?.isAllied(target.controller) == true) continue
            if (target.isCloaked()) continue
            // Only targets the weapon can actually fire at (air vs Skysweeper, submerged,
            // canopy/shielded blocks, etc.) — otherwise AttackUnitAction rejects the attack
            // and the turn is wasted.
            if (!game.canWeaponsFireAtTarget(unit, target, true, unit.x, unit.y)) continue

            // Score based on damage we can deal vs health remaining.
            val damage = game.resolveAttack(unit, target, useTiles = false)
            val score = damage / target.getHealth().coerceAtLeast(1)
            if (score > bestScore) {
                bestScore = score
                bestTarget = target
            }
        }
        return bestTarget
    }

    /**
     * For a melee unit that cannot fire from its current tile: the closest tile in its move
     * range from which it can attack a valid target this turn. Scans each reachable tile and
     * returns the one minimizing distance to the nearest fireable enemy.
     */
    private fun findAttackMove(unit: Unit): Tile? {
        val moveRange = game.tilesInMoveRange(unit)
        var bestTile: Tile? = null
        var bestDist = Double.MAX_VALUE

        for (tile in moveRange) {
            if (tile.unit != null && tile.unit != unit) continue
            val path = game.unitTile(unit)?.let { Astar.path(it, tile, unit) } ?: continue
            if (!game.canMove(unit, path)) continue

            val targets = game.tilesInRange(unit, tile.x, tile.y)
            for (t in targets) {
                val target = t.unit ?: continue
                if (target.controller === unit.controller || unit.controller?.isAllied(target.controller) == true) continue
                if (target.isCloaked()) continue
                if (!game.canWeaponsFireAtTarget(unit, target, true, tile.x, tile.y)) continue
                val dist = Math.abs(tile.x - target.x) + Math.abs(tile.y - target.y)
                if (dist < bestDist) {
                    bestDist = dist.toDouble()
                    bestTile = tile
                }
            }
        }
        return bestTile
    }

    /** The best adjacent enemy the melee unit can fire at from (x,y), or null. */
    private fun bestAdjacentTarget(unit: Unit, x: Int, y: Int): Unit? {
        var best: Unit? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val neighbors = listOf(
            game.tileAt(x - 1, y), game.tileAt(x + 1, y),
            game.tileAt(x, y - 1), game.tileAt(x, y + 1),
        )
        for (t in neighbors) {
            val target = t?.unit ?: continue
            if (target.controller === unit.controller || unit.controller?.isAllied(target.controller) == true) continue
            if (target.isCloaked()) continue
            if (!game.canWeaponsFireAtTarget(unit, target, true, x, y)) continue
            val damage = game.resolveAttack(unit, target, useTiles = false)
            val score = damage / target.getHealth().coerceAtLeast(1)
            if (score > bestScore) {
                bestScore = score
                best = target
            }
        }
        return best
    }

    private fun findMoveTarget(unit: Unit): Tile? {
        val moveRange = game.tilesInMoveRange(unit)
        val start = game.unitTile(unit)
        var bestTile: Tile? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (tile in moveRange) {
            if (tile.unit != null && tile.unit != unit) continue
            // The move-range flood can include tiles no ordered path reaches (e.g. behind a
            // jammer's no-fly zone for air units): only consider tiles the unit can actually
            // walk to, so the queued MoveUnitAction is valid and the walk animation plays.
            val path = start?.let { Astar.path(it, tile, unit) } ?: continue
            if (!game.canMove(unit, path)) continue
            var score = 0.0

            // Prefer tiles closer to enemies.
            for (other in game.units) {
                if (other.controller !== unit.controller && unit.controller?.isAllied(other.controller) != true) {
                    val dist = kotlin.math.abs(tile.x - other.x) + kotlin.math.abs(tile.y - other.y)
                    score += 10.0 / (dist + 1)
                }
            }

            // Prefer capturing structures.
            if (tile.isStructure() && tile.controller !== unit.controller) {
                score += 5.0
            }

            if (score > bestScore) {
                bestScore = score
                bestTile = tile
            }
        }
        return bestTile
    }

    private fun findPath(unit: Unit, target: Tile): List<Tile>? {
        // A real ordered path within the unit's movement range (Astar.search returns the
        // flood-fill of visited nodes, not a walkable path — feeding that into a
        // MoveUnitAction made every AI move fail its `canMove` check, so enemy units never
        // walked and the walk animation never played).
        val path = Astar.path(
            game.unitTile(unit)!!,
            target,
            unit
        ) ?: return null
        return path.filterIsInstance<Tile>()
    }

    /** The cheapest unit the warmachine can afford from its own [Unit.resourcePool]. */
    private fun findBuildTarget(unit: Unit): UnitData? {
        var bestUnit: UnitData? = null
        var bestCost = Int.MAX_VALUE
        for (unitEntry in UnitManager.getAll()) {
            if (unit.controller?.isUnitEnabled(unitEntry.name) != true) continue
            val cost = unitEntry.getCost(unit.controller)
            if (cost > 0 && cost <= unit.resourcePool && cost < bestCost) {
                bestUnit = unitEntry
                bestCost = cost
            }
        }
        return bestUnit
    }

    /** The cheapest buildable unit when standing on a factory (spent against controller resources). */
    private fun findFactoryBuildTarget(unit: Unit): UnitData? {
        val factories = game.structures.filter {
            it.terrain.hasModifier("Construction") &&
                it.controller === unit.controller &&
                it.unit == null
        }
        if (factories.isEmpty()) return null
        var bestUnit: UnitData? = null
        var bestCost = Int.MAX_VALUE
        for (unitEntry in UnitManager.getAll()) {
            if (unit.controller?.isUnitEnabled(unitEntry.name) != true) continue
            val cost = unitEntry.getCost(unit.controller)
            if (cost > 0 && cost <= (unit.controller?.getResources() ?: 0) && cost < bestCost) {
                bestUnit = unitEntry
                bestCost = cost
            }
        }
        return bestUnit
    }

    private fun findCaptureTarget(unit: Unit): Tile? {
        val moveRange = game.tilesInMoveRange(unit)
        for (tile in moveRange) {
            if (tile.isStructure() && tile.controller !== unit.controller) {
                return tile
            }
        }
        return null
    }

    private fun findAdjacentTile(unit: Unit, target: Tile): Tile? {
        val tiles = listOf(
            game.tileAt(target.x - 1, target.y),
            game.tileAt(target.x + 1, target.y),
            game.tileAt(target.x, target.y - 1),
            game.tileAt(target.x, target.y + 1),
        ).filterNotNull()
        for (tile in tiles) {
            if (tile.unit == null && tile.canBeMovedOnBy(unit)) {
                return tile
            }
        }
        return null
    }

    private fun finish() {
        // All units processed — end the turn.
        controller.endTurn(EndTurnAction.REASON_VOLUNTARY)
    }

    fun dispose() {
        // Cleanup if needed.
    }
}
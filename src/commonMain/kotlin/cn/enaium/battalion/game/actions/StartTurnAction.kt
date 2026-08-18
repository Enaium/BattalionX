package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.ModifierManager
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.ActionManager
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.KillUnitTrigger
import cn.enaium.battalion.game.triggers.PlayerTrigger
import cn.enaium.battalion.game.triggers.ResourcesAcquiredTrigger

/**
 * Starts a new turn for the active controller.
 *
 * This action handles:
 * - Resetting unit states (moved, fired, deployed flags)
 * - Applying terrain damage to deployed units
 * - Healing repairing units
 * - Radar reveal of cloaked enemy units
 * - Applying cloaking damage to cloaked units
 * - Fixing unit facing directions
 * - Structure resource extraction
 * - Capture processing for units on enemy structures
 * - Dispatching the start turn trigger
 */
class StartTurnAction : Action() {
    init {
        isNetworked = false
    }

    override fun fire() {
        super.fire()
        doFire()
    }

    private fun doFire() {
        val gameManager = GameManager.instance
        val controller = gameManager.activeController ?: return

        // Process units owned by the current controller
        val units = controller.units.toList()
        for (unit in units) {
            if (unit.isDeployed() && unit.isAlive()) {
                // Reset unit state for the new turn
                unit.reset()

                // Apply terrain damage
                val tile = gameManager.tileAt(unit.x, unit.y)
                if (tile != null) {
                    val terrainDamage = tile.terrain.applyModifiers("damage", unit, 0.0)
                    if (terrainDamage > 0) {
                        unit.takeDamage(terrainDamage)
                        if (!unit.isAlive()) {
                            TriggerManager.add(KillUnitTrigger(unit.x, unit.y, unit))
                            gameManager.removeUnit(unit)
                            continue
                        }
                    }
                }

                // Handle repairing units
                if (unit.isRepairing()) {
                    if (unit.getHealth() < unit.unitData.health) {
                        val healPerc = gameManager.healPercentage(unit)
                        val healAmount = unit.unitData.health * healPerc
                        unit.healDamage(healAmount)
                        unit.repairing = false
                    }
                }

                // Camera targeting for visible units
                if (!unit.isCloaked() || (unit.controller?.let { gameManager.isAlliedToThePlayer(it) } ?: false)) {
                    // In the original, this would set camera target
                }

                // Radar reveal of cloaked enemy units
                if (unit.unitData.name != "Blockade") {
                    val radarRange = unit.unitData.applyModifiers("radar", unit, 1.0).toInt()
                    val area = gameManager.unitsInRange(unit, -1, -1, 1, radarRange)
                    for (otherUnit in area) {
                        if (otherUnit.controller?.isAllied(unit.controller) != true) {
                            if (otherUnit.unitData.hasModifier("Cloaking")) {
                                otherUnit.cloaked = false
                            }
                        }
                    }
                }

                // Apply cloaking damage to cloaked units
                if (unit.unitData.hasModifier("Cloaking")) {
                    if (unit.isCloaked()) {
                        val cloakDamageModifier = ModifierManager.get("Cloak Damage")
                        if (cloakDamageModifier != null) {
                            unit.unitData.addTemporaryModifier(cloakDamageModifier)
                        }
                    }
                }
            }
        }

        // Process all units for facing direction and healing
        val allUnits = gameManager.units.toList()
        for (unit in allUnits) {
            if (unit.isDeployed() && unit.isAlive()) {
                // Fix facing direction based on controller
                unit.fixFacingDirection(controller == unit.controller)

                // Apply healing from structures
                val healAmount = unit.unitData.applyModifiers("heal", unit, 0.0)
                if (healAmount > 0 && unit.controller == controller) {
                    if (unit.getHealth() < unit.unitData.health) {
                        unit.healDamage(healAmount)
                    }
                }
            }
        }

        // Process resource extraction from structures
        val controllers = gameManager.controllers.filterNotNull()
        for (c in controllers) {
            val structures = c.structures.toList()
            var totalResources = 0.0

            for (structure in structures) {
                val resourcesGained = structure.takeResources(1.0 / controllers.size)
                totalResources += resourcesGained

                // In the original, this would show a resources gained effect
            }

            // Apply difficulty scaling to resources
            val scaledResources = (totalResources * c.getDifficultyResourcesScale()).toInt()
            c.addResources(scaledResources)

            // Update team statistics
            c.team.addStatistic(cn.enaium.battalion.data.Team.RESOURCES_COLLECTED, scaledResources)

            // Dispatch resources acquired trigger
            val playerIndex = gameManager.getPlayer(c)
            if (playerIndex != -1) {
                TriggerManager.add(ResourcesAcquiredTrigger(playerIndex, scaledResources))
            }
        }

        // Process capture actions for units on enemy structures
        val structures = gameManager.structures.toList()
        for (structure in structures) {
            val unit = structure.unit
            var capture = false

            if (unit != null) {
                val structureControllerIndex = structure.controller?.let { gameManager.getControllerIndex(it) } ?: -1
                if (unit.isDeployed() && unit.isAlive() && unit.controller?.team?.isAllied(structureControllerIndex) != true) {
                    if (unit.unitData.hasModifier("Capture")) {
                        capture = true
                        if (controller.controlsUnit(unit)) {
                            // Add capture action to the front of the queue
                            val captureAction = CaptureTerrainAction(structure.x, structure.y)
                            captureAction.isNetworked = false
                            ActionManager.add(captureAction, toFront = true)
                        }
                    }
                }
            }

            if (!capture) {
                // Reset capture progress if not being captured
                structure.chunks = GameManager.TILE_CHUNKS
                structure.capturer = null
            }
        }

        // Set camera for the current controller
        // In the original, this would set the camera to focus on the controller's units

        // Dispatch start turn trigger
        val currentPlayer = gameManager.currentController
        if (currentPlayer != -1) {
            ActionManager.add(
                SendTriggerAction(PlayerTrigger(PlayerTrigger.START_TURN, currentPlayer)),
                toFront = true
            )
        }

        // This action is fully synchronous: mark it done so the manager can move on.
        completeAction()
    }

    override fun update() {
        // No-op in the original
    }
}
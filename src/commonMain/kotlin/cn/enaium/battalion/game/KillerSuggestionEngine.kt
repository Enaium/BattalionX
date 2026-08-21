package cn.enaium.battalion.game

import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitData
import cn.enaium.battalion.data.UnitManager

/**
 * Suggests which unit to build to best counter a given target unit.
 *
 * Used by the build UI / AI: given a target enemy [Unit] and a build source (a factory or a
 * Warmachine constructor), this evaluates every buildable unit's damage output against the
 * target under the actual ballistics/defense rules, filters out ones that couldn't reach the
 * target, then weights the survivors by army-composition balance (avoiding over-stacking one
 * armour/stealth category) and returns the name of a recommended counter.
 *
 * Ported from the original `com.gamedozer.battalion.game.KillerSuggestionEngine`. The AI-facing
 * BuildMenu's "kill this" recommendations call [suggestKillerFromFactory] / [suggestKillerFromWarmachine].
 */
object KillerSuggestionEngine {

    private const val GUESS_MIN = 0
    private const val MAX_VARIANCE_FROM_ARMY_MAKEUP = 0.15

    /** Buildable units this engine considers; mirrors the original's fake-unit roster. */
    private val fakeUnits: MutableList<Unit> by lazy {
        val list = mutableListOf<Unit>()
        for (ud in UnitManager.getAll()) {
            if (!ud.hasModifier("Unbuildable")) {
                list.add(UnitManager.createFakeUnit(ud.name) ?: Unit(ud))
            }
        }
        list
    }

    private data class KillOption(val killer: Unit, var score: Double) {
        val cost: Int get() = killer.unitData.cost
    }

    /** Recommended unit to build at [aFactory] to counter [aTarget], or empty string. */
    fun suggestKillerFromFactory(aTarget: Unit, aFactory: Tile): String {
        val controller = GameManager.instance.activeController ?: return ""
        return buildFromSource(aTarget, listOf(aFactory), isFactorySource = true, aFactory.controller?.getResources() ?: 0)
    }

    /** Recommended unit the Warmachine [aBuildingWarmachine] should build to counter [aTarget]. */
    fun suggestKillerFromWarmachine(aTarget: Unit, aBuildingWarmachine: Unit): String {
        val wmTile = GameManager.instance.unitTile(aBuildingWarmachine) ?: return ""
        return buildFromSource(
            aTarget,
            wmTile.connections.filterIsInstance<Tile>(),
            isFactorySource = false,
            aBuildingWarmachine.resourcePool,
            wmTile,
        )
    }

    private fun isPremiumUnit(name: String): Boolean = name == "Lancer Tank" || name == "Spider Tank"

    private fun possessFakeUnits(controller: cn.enaium.battalion.game.Controller) {
        for (u in fakeUnits) u.controller = controller
    }

    private fun findKillers(aTarget: Unit, game: GameManager): List<KillOption> {
        val targetTile = game.unitTile(aTarget)
        val possibles = mutableListOf<KillOption>()
        for (unit in fakeUnits) {
            if (game.canWeaponsFireAtTarget(unit, aTarget, strict = false)) {
                val potentialDamage = game.resolveAttack(unit, aTarget, targetTile, targetTile, true, true)
                if (potentialDamage >= 1.0) {
                    val percentageOfHealth = potentialDamage / aTarget.getMaxHealth()
                    possibles.add(KillOption(unit, percentageOfHealth))
                }
            }
        }
        return possibles
    }

    private fun buildFromSource(
        aTarget: Unit,
        aSources: List<Tile>,
        isFactorySource: Boolean,
        aResourcesAvailable: Int,
        wmTile: Tile? = null,
    ): String {
        val game = GameManager.instance
        val controller = game.activeController ?: return ""
        val targetTile = game.unitTile(aTarget) ?: return ""
        possessFakeUnits(controller)

        val killers = findKillers(aTarget, game).toMutableList()
        val iterator = killers.iterator()
        while (iterator.hasNext()) {
            val killOption = iterator.next()
            val killer = killOption.killer
            // Prune by affordability.
            if (killer.unitData.getCost(controller) > aResourcesAvailable) { iterator.remove(); continue }
            if (!controller.isUnitEnabled(killer.name)) { iterator.remove(); continue }
            if (!game.mapSettings.useUnlockedUnits) {
                if (isPremiumUnit(killer.name)) { iterator.remove(); continue }
            }
            if (isFactorySource) {
                if (!controller.hasRequisiteControlStructuresToBuild(killer.unitData)) { iterator.remove(); continue }
            } else {
                if (killer.unitData.isSea() && wmTile?.terrain?.hasModifier("Shore") != true) { iterator.remove(); continue }
            }
            // The killer must be able to reach the target from some available source.
            val sources = aSources.filter { source ->
                source.getCost(killer, Tile.FLAG_IGNORE_UNITS, isLastNode = false) <= killer.getRange()
            }
            if (sources.isEmpty()) { iterator.remove(); continue }
            var canReachTarget = false
            for (source in sources) {
                if (source.getCost(killer, 0, isLastNode = false) != Double.POSITIVE_INFINITY) {
                    val turns = DistanceQueries.guessTurnsToBeingInAttackRange(source, targetTile, killer)
                    if (turns > 0) {
                        canReachTarget = true
                        break
                    }
                }
            }
            if (!canReachTarget) iterator.remove()
        }

        if (killers.isEmpty()) return ""
        if (killers.size == 1) return killers[0].killer.name

        // Balance by the army's current composition (avoid over-stacking one category).
        val existingUnits = controller.units
        var airUnits = 0; var seaUnits = 0; var groundUnits = 0
        var rangedUnits = 0; var stealthUnits = 0; var totalUnits = 0
        for (unit in existingUnits) {
            val ud = unit.unitData
            if (ud.damage > 0 && ud.weaponRangeMax > 0) {
                totalUnits++
                if (ud.hasModifier("Stealth")) stealthUnits++
                if (ud.isAir()) airUnits++
                else if (ud.isSea()) seaUnits++
                else if (ud.isGround()) groundUnits++
                if (ud.weaponRangeMax > 1) rangedUnits++
            }
        }

        val thresholdBeforeTuning = 10
        if (totalUnits > thresholdBeforeTuning) {
            val airP = airUnits.toDouble() / totalUnits
            val seaP = seaUnits.toDouble() / totalUnits
            val groundP = groundUnits.toDouble() / totalUnits
            val rangedP = rangedUnits.toDouble() / totalUnits
            val stealthP = stealthUnits.toDouble() / totalUnits
            val optimalAir = 0.25; val optimalSea = 0.25; val optimalGround = 0.5
            val optimalRanged = 0.3; val optimalStealth = 0.1
            for (option in killers) {
                val ud = option.killer.unitData
                var variance = 0.0
                variance += if (ud.hasModifier("Stealth")) optimalStealth - stealthP else -(optimalStealth - stealthP)
                variance += when {
                    ud.isAir() -> optimalAir - airP
                    ud.isSea() -> optimalSea - seaP
                    ud.isGround() -> optimalGround - groundP
                    else -> 0.0
                }
                variance += if (ud.weaponRangeMax > 1) optimalRanged - rangedP else -(optimalRanged - rangedP)
                variance = kotlin.math.min(variance, 1.0)
                variance = kotlin.math.max(variance, -1.0)
                option.score += MAX_VARIANCE_FROM_ARMY_MAKEUP * variance
            }
        }

        killers.sortByDescending { it.score }
        return killers[0].killer.name
    }
}

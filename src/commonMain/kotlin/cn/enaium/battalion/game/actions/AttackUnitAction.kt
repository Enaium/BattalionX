package cn.enaium.battalion.game.actions

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Action
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.AttackUnitTrigger
import cn.enaium.battalion.game.triggers.KillUnitTrigger
import cn.enaium.battalion.data.MoraleChangeAmounts

/**
 * Resolves an attack from one unit against another.
 *
 * This action handles:
 * - Attack verification (range, fired state, cloaking)
 * - Damage calculation and application
 * - Counterattack resolution
 * - Piercing damage to units behind the target
 * - Scavenging (attacker resets instead of expending)
 * - Kill tracking and team statistics
 * - Morale changes for the involved controllers
 */
class AttackUnitAction(
    private val attackerX: Int,
    private val attackerY: Int,
    private val targetX: Int,
    private val targetY: Int
) : Action() {
    private var activatedScavenge = false

    /** The game-clock ms when the firing animation started (drives [update]'s delay). */
    private var animStartMs: Long = -1

    /** True once the damage/resolution step has run. */
    private var resolved = false

    /** True while a *counterattack* animation is playing (started by [startCounterattack]). */
    private var counterFiring = false
    private var counterStartMs: Long = -1

    override fun usesUnit(aUnit: Unit): Boolean {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(attackerX, attackerY)
        return unit == aUnit
    }

    override fun fire() {
        val gameManager = GameManager.instance
        gameManager.activeController?.resetIdleTime()
        super.fire()
        verifyAction()
        // Kick off the firing animation only; the damage itself is applied in [update]
        // after the animation has played, so enemy attacks and counterattacks are visible
        // instead of resolving in a single frame.
        val attacker = gameManager.unitAt(attackerX, attackerY)
        if (attacker != null) {
            attacker.facing = facingFor(attacker.x, attacker.y, targetX, targetY)
            attacker.animFacing = attacker.facing
            attacker.startAnim(Unit.UnitAnim.FIRE)
        }
        animStartMs = GameManager.instance.elapsedMs
        resolved = false
    }

    override fun update() {
        val gameManager = GameManager.instance
        if (counterFiring) {
            // Counterattack animation: wait it out, then resolve the return fire.
            if (gameManager.elapsedMs - counterStartMs < Unit.ANIM_DURATION_MS) return
            counterFiring = false
            resolveCounterattack()
            completeAction()
            return
        }
        if (resolved) {
            completeAction()
            return
        }
        // Wait out the firing animation before applying damage.
        if (gameManager.elapsedMs - animStartMs < Unit.ANIM_DURATION_MS) return
        resolved = true
        updateGameStateAfterAttack()
    }

    /** Plays the counterattacker's firing animation; damage lands in [update]. */
    private fun startCounterattack() {
        val gameManager = GameManager.instance
        val target = gameManager.unitAt(targetX, targetY) ?: return
        target.facing = facingFor(target.x, target.y, attackerX, attackerY)
        target.animFacing = target.facing
        target.startAnim(Unit.UnitAnim.FIRE)
        counterFiring = true
        counterStartMs = gameManager.elapsedMs
    }

    private fun verifyAction() {
        val gameManager = GameManager.instance
        val attacker = gameManager.unitAt(attackerX, attackerY)
        val target = gameManager.unitAt(targetX, targetY)
        var errMsg: String? = null

        if (attacker == null || target == null) {
            errMsg = "AttackUnitAction::Corrupt attack action"
        } else if (attacker.controller != gameManager.activeController) {
            errMsg = "AttackUnitAction::Only the units controller may attack with the unit"
        } else if (attacker.hasFired()) {
            errMsg = "AttackUnitAction::Unit cannot complete attack action. (Unit has fired) ${gameManager.currentController}"
        } else if (attacker.isRanged() && attacker.hasMoved()) {
            errMsg = "AttackUnitAction::Unit cannot complete attack action. (Ranged unit has already moved) ${gameManager.currentController}"
        } else if (target.isCloaked()) {
            errMsg = "AttackUnitAction::Unit cannot complete attack action. (Target is cloaked!) ${gameManager.currentController}"
        } else if (!gameManager.canAttack(attacker, target)) {
            errMsg = "AttackUnitAction::Unit cannot complete attack action. (Target not in range)${gameManager.currentController}"
        }

        if (errMsg != null) {
            completeAction()
            throw Error(errMsg)
        }
    }

    private fun updateGameStateAfterAttack() {
        val gameManager = GameManager.instance
        val attacker = gameManager.unitAt(attackerX, attackerY) ?: return
        val target = gameManager.unitAt(targetX, targetY) ?: return

        // (The firing animation was started in [fire]; facing is already set.)

        gameManager.activeController?.setMovedAUnitThisTurn(true)
        gameManager.activeController?.resetTurnsSinceLastAttackCounter()

        // Dispatch attack trigger
        TriggerManager.add(AttackUnitTrigger(attackerX, attackerY, targetX, targetY, attacker, target))

        // Resolve the attack
        val resourceDamageCaused = gameManager.resolveAttackInResources(attacker, target, true)
        val damageToInflict = gameManager.resolveAttack(attacker, target)
        target.takeDamage(damageToInflict)

        attacker.controller?.team?.addStatistic(Team.RESOURCE_DAMAGE_CAUSED, resourceDamageCaused.toInt())

        // Morale gain for causing damage
        val moraleGain = resourceDamageCaused * MoraleChangeAmounts.CAUSED_DAMAGED
        gameManager.changeMorale(attacker.controller, moraleGain, MoraleChangeAmounts.CAUSED_DAMAGED_DESCRIPTION)

        if (target.isAlive()) {
            if (target.isRepairing()) {
                // Target was repairing; interrupt the repair and expend the attacker
                target.repairing = false
                expendAttacker()
            } else if (gameManager.canAttack(target, attacker, true)) {
                // Counterattack resolution. Any combat-capable defender returns fire —
                // including ranged units (the original only let ranged units counter when
                // they carried the "Counter Battery" tag; the user wants every capable
                // defender to counterattack).
                var returnFire = true
                if (target.unitData.hasModifier("Blind Spot")) {
                    returnFire = false
                }
                if (attacker.unitData.hasModifier("Stun")) {
                    returnFire = false
                }
                if (returnFire) {
                    startCounterattack()
                } else {
                    expendAttacker()
                }
            } else {
                expendAttacker()
            }
        } else {
            killUnit(targetX, targetY)
        }

        // Piercing damage to units behind the target
        if (attacker.unitData.hasModifier("Piercing")) {
            val pierceX = targetX - attackerX
            val pierceY = targetY - attackerY
            val piercedX = targetX + pierceX
            val piercedY = targetY + pierceY
            val piercedUnit = gameManager.unitAt(piercedX, piercedY)

            if (piercedUnit != null) {
                if (!piercedUnit.unitData.isAir() &&
                    !(piercedUnit.unitData.isSea() && piercedUnit.unitData.hasModifier("Submerged"))
                ) {
                    piercedUnit.takeDamage(gameManager.resolveAttack(attacker, piercedUnit))
                    val pierceResourceDamage = gameManager.resolveAttackInResources(attacker, piercedUnit, true)
                    attacker.controller?.team?.addStatistic(Team.RESOURCE_DAMAGE_CAUSED, pierceResourceDamage.toInt())

                    if (!piercedUnit.isAlive()) {
                        killUnit(piercedX, piercedY)
                    } else if (piercedUnit.isRepairing()) {
                        piercedUnit.repairing = false
                    }
                }
            }
        }
    }

    /** Applies the counterattack damage after its animation has played. */
    private fun resolveCounterattack() {
        val gameManager = GameManager.instance
        val attacker = gameManager.unitAt(attackerX, attackerY) ?: return
        val target = gameManager.unitAt(targetX, targetY) ?: return

        // Dispatch the attack trigger so the impact explosion and weapon sound play on the
        // original attacker (the counterattacker is `target`, the hit unit is `attacker`).
        TriggerManager.add(AttackUnitTrigger(targetX, targetY, attackerX, attackerY, target, attacker))

        // Resolve counterattack damage
        attacker.takeDamage(gameManager.resolveAttack(target, attacker, null, null, false))
        val resourceDamageCaused = gameManager.resolveAttackInResources(target, attacker, false)
        target.controller?.team?.addStatistic(Team.RESOURCE_DAMAGE_CAUSED, resourceDamageCaused.toInt())

        if (!attacker.isAlive()) {
            killUnit(attackerX, attackerY)
            finishAction()
        } else {
            attacker.cloaked = false
            expendAttacker()
        }
    }

    private fun killUnit(targetX: Int, targetY: Int) {
        val gameManager = GameManager.instance
        val unit = gameManager.unitAt(attackerX, attackerY)
        val targetUnit = gameManager.unitAt(targetX, targetY)

        if (targetUnit == null || targetUnit.isAlive()) return

        if (unit?.unitData?.hasModifier("Scavenge") == true) {
            activatedScavenge = true
        }

        // Dispatch kill trigger
        TriggerManager.add(KillUnitTrigger(targetX, targetY, targetUnit))

        // Update statistics
        val controllerIndex = gameManager.controllers.indexOf(targetUnit.controller)
        val statIndex = when (controllerIndex) {
            0 -> Team.KILLED_PLAYER1_UNIT
            1 -> Team.KILLED_PLAYER2_UNIT
            2 -> Team.KILLED_PLAYER3_UNIT
            3 -> Team.KILLED_PLAYER4_UNIT
            else -> -1
        }
        if (statIndex != -1) {
            unit?.controller?.team?.addStatistic(statIndex, 1)
        }
        targetUnit.controller?.team?.addStatistic(Team.UNITS_LOST, 1)
        unit?.controller?.team?.addStatistic(Team.UNITS_KILLED, 1)

        gameManager.removeUnit(targetUnit)

        // Morale changes
        gameManager.changeMorale(targetUnit.controller, MoraleChangeAmounts.UNITS_LOST, MoraleChangeAmounts.UNITS_LOST_DESCRIPTION)
        if (unit != null) {
            gameManager.changeMorale(unit.controller, MoraleChangeAmounts.UNITS_KILLED, MoraleChangeAmounts.UNITS_KILLED_DESCRIPTION)
        }
    }

    private fun expendAttacker() {
        val gameManager = GameManager.instance
        val attacker = gameManager.unitAt(attackerX, attackerY)

        if (!activatedScavenge) {
            attacker?.moved = true
            attacker?.fired = true
            attacker?.darken()
        } else {
            // Scavenge: attacker resets instead of expending
            attacker?.reset()
        }
        finishAction()
    }

    private fun finishAction() {
        GameManager.instance.activeController?.resetIdleTime()
        completeAction()
    }

    /**
     * Compass facing for a vector from (x1,y1) to (x2,y2): 0=N, 1=E, 2=S, 3=W.
     *
     * Mirrors the original `AttackUnitAction.attackDirection`: the axis with the larger
     * separation wins, so a primarily-horizontal target faces east/west regardless of a small
     * vertical offset (and vice versa).
     */
    private fun facingFor(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val sepX = kotlin.math.abs(x1 - x2)
        val sepY = kotlin.math.abs(y1 - y2)
        return when {
            sepX > sepY -> if (x1 < x2) 1 else 3 // east / west
            else -> if (y1 < y2) 2 else 0        // south / north
        }
    }
}
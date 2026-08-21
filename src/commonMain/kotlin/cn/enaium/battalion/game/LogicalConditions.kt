package cn.enaium.battalion.game

/**
 * Logical combinators for [Condition]s — AND / OR / XOR / NOT.
 *
 * Ported from the original `LogicalAndCondition`, `LogicalOrCondition`, `LogicalXorCondition`,
 * `NotCondition` in `com.gamedozer.battalion.game`. Each combines child conditions parsed from
 * the map `<script>` XML.
 */
open class LogicalCondition(protected val childList: MutableList<Condition> = mutableListOf()) : Condition {
    fun add(condition: Condition) {
        childList.add(condition)
    }

    fun getConditions(): List<Condition> = childList

    override fun update() {
        for (c in childList) c.update()
    }

    override fun reset() {
        for (c in childList) c.reset()
    }

    override fun isSatisfied(): Boolean = childList.isEmpty()

    override fun dispose() {
        for (c in childList) c.dispose()
    }
}

/** True when every child condition is satisfied. */
class LogicalAndCondition : LogicalCondition() {
    override fun isSatisfied(): Boolean {
        for (c in childList) {
            if (!c.isSatisfied()) return false
        }
        return true
    }
}

/** True when at least one child condition is satisfied. */
class LogicalOrCondition : LogicalCondition() {
    override fun isSatisfied(): Boolean {
        if (childList.isEmpty()) return true
        for (c in childList) {
            if (c.isSatisfied()) return true
        }
        return false
    }
}

/** True when exactly one child condition is satisfied (exclusive OR). */
class LogicalXorCondition : LogicalCondition() {
    override fun isSatisfied(): Boolean {
        var foundOne = false
        for (c in childList) {
            if (c.isSatisfied()) {
                if (foundOne) return false
                foundOne = true
            }
        }
        return foundOne
    }
}

/** True when the wrapped condition is NOT satisfied. */
class NotCondition(private val child: Condition) : Condition {
    override fun update() {
        child.update()
    }

    override fun reset() {
        child.reset()
    }

    override fun isSatisfied(): Boolean = !child.isSatisfied()

    override fun dispose() {
        child.dispose()
    }
}

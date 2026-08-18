package cn.enaium.battalion.data

import cn.enaium.battalion.game.GameManager

/**
 * A* pathfinding over the tile graph.
 *
 * Because searches are frequent and the graph is small, the original implementation reuses
 * one static open list and an iteration counter. Node search state is tagged with the
 * current iteration so stale results from a previous search are ignored without clearing
 * every node. We reproduce that behaviour here in an [Astar] object.
 *
 * Flags used by the search (defined on [Tile]):
 *  - [Tile.FLAG_IGNORE_LAST_NODE]: the destination tile may be occupied by an enemy unit
 *    (we only need to get next to it)
 *  - [Tile.FLAG_FORCE_COST_1]: treat every tile as costing 1 movement point
 *  - [Tile.FLAG_IGNORE_UNITS]: do not treat units as obstacles
 */
object Astar {
    private val open = mutableListOf<Node>()
    private var iteration: Int = -1

    private fun remove(node: Node) {
        open.remove(node)
    }

    /**
     * Computes the shortest path from [start] to [end]. Returns the ordered list of nodes
     * (including both endpoints), or null if no path exists.
     */
    fun path(
        start: Node,
        end: Node,
        unit: Unit? = null,
        searchDepth: Double = 0.0,
        flags: Int = 0,
    ): List<Node>? {
        if (start === end) return listOf(start)
        if (search(start, end, unit, searchDepth, additional = false, flags) == null) return null
        if (end.iteration != iteration) return null

        val path = mutableListOf<Node>()
        var temp: Node? = end
        while (temp != null && temp !== start) {
            path.add(0, temp)
            temp = temp.previous
        }
        path.add(0, start)
        return path
    }

    /**
     * The longest path within the unit's movement range: the full A* path with any node
     * beyond the unit's range trimmed off.
     */
    fun pathTrim(start: Node, end: Node, unit: Unit, flags: Int = 0): List<Node>? {
        val nodes = path(start, end, unit, Double.POSITIVE_INFINITY, flags)?.toMutableList() ?: return null
        for (i in nodes.indices.reversed()) {
            if (nodes[i].g > unit.getRange()) nodes.removeAt(i)
        }
        return nodes
    }

    /**
     * Core A* search. Returns the list of visited nodes (not necessarily ordered), or null
     * if the search is impossible (start/end blocked).
     */
    fun search(
        start: Node,
        end: Node? = null,
        unit: Unit? = null,
        searchDepth: Double = 0.0,
        additional: Boolean = false,
        flags: Int = 0,
    ): List<Node>? {
        ++iteration

        if (unit != null) {
            if (end != null) {
                if (end.getCost(unit, flags, true) == Double.POSITIVE_INFINITY) return null
            }
            if (start.getCost(unit, flags, false) == Double.POSITIVE_INFINITY) return null
        }
        if (start === end) return null

        val nodes = mutableListOf<Node>()
        open.clear()
        start.reset()
        start.iteration = iteration
        addSorted(start)
        nodes.add(start)

        val range = if (unit != null) unit.getRange() else 0.0

        // For air units (movementType == wings == 5): enemy Jamming units project a no-fly
        // zone; tiles inside it have infinite cost.
        var deniedNodes: MutableList<Node>? = null
        if (unit != null && unit.unitData.movementType == 5) {
            val game = GameManager.instance
            for (other in game.units) {
                if (other.controller?.isAllied(unit.controller) != true && other.unitData.hasModifier("Jamming")) {
                    if (deniedNodes == null) deniedNodes = mutableListOf()
                    val unitTile = game.tileAt(other.x, other.y)
                    // Manhattan-ish ring around the jammer (offset pattern from the original).
                    val xs = intArrayOf(-2, -1, 0, 1, 2, -1, 0, 1, -1, 0, 1, 0, 0)
                    val ys = intArrayOf(0, 0, 0, 0, 0, 1, 1, 1, -1, -1, -1, 2, -2)
                    for (z in xs.indices) {
                        game.tileAt(other.x + xs[z], other.y + ys[z])?.let { deniedNodes.add(it) }
                    }
                }
            }
        }

        var found = false
        while (open.isNotEmpty() && !found) {
            val current = open.removeAt(0)
            current.state = Node.CLOSED
            val connections = current.connections
            for (i in connections.indices) {
                val check = connections[i]
                if (check.iteration == iteration && check.state == Node.CLOSED) continue

                val h = check.getHeuristic(end ?: current)
                var cost = 1.0
                if (unit != null) cost = check.getCost(unit, flags, false)
                if (deniedNodes != null && deniedNodes.contains(check)) {
                    cost = Double.POSITIVE_INFINITY
                }

                val skip =
                    cost == Double.POSITIVE_INFINITY && !additional && flags == 0
                if (!skip) {
                    if (check.iteration == iteration && check.state == Node.OPEN) {
                        // Node already on the open list: update if we found a cheaper g.
                        if (check.g > current.g + cost) {
                            remove(check)
                            check.g = current.g + cost
                            check.f = check.g + check.h
                            check.previous = current
                            addSorted(check)
                        }
                    } else {
                        var g = current.g + cost
                        if (g > range && g > searchDepth) {
                            if (additional) {
                                if (current.g == Double.POSITIVE_INFINITY) continue
                                if (current.g + 1 > range + 1 && current.g + 1 > searchDepth + 1) continue
                            } else {
                                if (!(flags and Tile.FLAG_IGNORE_LAST_NODE != 0 && check === end)) {
                                    continue
                                }
                                cost = check.getCost(unit, flags, true)
                                g = current.g + cost
                                if (g > range && g > searchDepth) continue
                            }
                        }
                        check.reset(current, g, h, g + h)
                        check.iteration = iteration
                        addSorted(check)
                        nodes.add(check)
                        if (check === end) {
                            found = true
                            break
                        }
                    }
                }
            }
        }
        return nodes
    }

    /** All nodes reachable within [maxRange] whose g value is at least [minRange]. */
    fun range(start: Node, minRange: Double, maxRange: Double): List<Node> {
        val nodes = (search(start, null, null, maxRange) ?: return emptyList()).toMutableList()
        for (i in nodes.indices.reversed()) {
            if (nodes[i].g < minRange) nodes.removeAt(i)
        }
        return nodes
    }

    /**
     * Returns every tile from which [a_unit] could attack [a_end] this turn — i.e. tiles
     * within weapon range of the target that the unit can reach. If [a_ignoreUnits] is set,
     * enemy units are not treated as obstacles while pathfinding.
     */
    fun calculatePositionsToTargetEndNode(
        aStart: Node,
        aEnd: Node,
        aUnit: Unit,
        aIgnoreUnits: Boolean,
    ): List<Node> {
        val flags = if (aIgnoreUnits) Tile.FLAG_IGNORE_UNITS else 0
        val nodesInRange = range(aEnd, aUnit.getWeaponRangeMin(), aUnit.getWeaponRangeMax()).toMutableList()
        var bestDestinationNode: Node? = null
        var bestTurnCount = Int.MAX_VALUE
        for (n in nodesInRange.indices.reversed()) {
            val node = nodesInRange[n]
            val turns = calculateTurnsToPath(aStart, node, aUnit, flags)
            if (turns > 0) {
                if (turns < bestTurnCount) {
                    bestDestinationNode = node
                    bestTurnCount = turns
                }
            } else {
                nodesInRange.removeAt(n)
            }
        }
        return nodesInRange
    }

    /** Number of turns it would take [a_unit] to walk from [aStart] to [aEnd]. */
    fun calculateTurnsToPath(aStart: Node, aEnd: Node, aUnit: Unit, flags: Int = 0): Int {
        if (aUnit.getRange() == 0.0) return -1
        val nodes = path(aStart, aEnd, aUnit, Double.POSITIVE_INFINITY, flags)?.toMutableList() ?: return -1
        if (flags and Tile.FLAG_IGNORE_LAST_NODE != 0) {
            if (nodes[nodes.size - 1].g == Double.POSITIVE_INFINITY) {
                nodes.removeAt(nodes.size - 1)
                if (nodes.isEmpty()) return 1
            }
        }
        if (nodes[nodes.size - 1].g == Double.POSITIVE_INFINITY) return -1
        return kotlin.math.ceil(nodes[nodes.size - 1].g / aUnit.getRange()).toInt()
    }

    /** Inserts [aNode] into the open list, keeping it sorted by f value (ascending). */
    private fun addSorted(aNode: Node) {
        for (i in open.indices) {
            if (aNode.compare(open[i]) == 1) {
                open.add(i, aNode)
                return
            }
        }
        open.add(aNode)
    }
}
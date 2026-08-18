package cn.enaium.battalion.data

/**
 * A node in the pathfinding graph. [Tile] extends this: each tile is connected to its four
 * orthogonal neighbours, and the A* search ([Astar]) walks these connections.
 *
 * The fields [f], [g], [h], [state], [previous] and [iteration] are the working state used
 * by the search and are reset between searches. [connections] are the neighbouring nodes.
 */
open class Node(
    var previous: Node? = null,
    var g: Double = 0.0,
    var h: Double = 0.0,
    var f: Double = 0.0,
) {
    var state: Int = OPEN
    var iteration: Int = -1
    val connections: MutableList<Node> = mutableListOf()

    companion object {
        const val OPEN = 0
        const val CLOSED = 1
    }

    /**
     * The cost to enter this node (overridden by [Tile]).
     *
     * @param movingUnit the unit trying to move onto the node (may be null)
     * @param flags      A* flags (see [Tile] constants)
     * @param isLastNode whether this node is the destination of the search
     */
    open fun getCost(movingUnit: Unit?, flags: Int, isLastNode: Boolean): Double = 1.0

    open fun getHeuristic(node: Node): Double = 0.0

    fun addConnection(node: Node) {
        if (!isConnected(node)) connections.add(node)
    }

    fun removeConnection(node: Node) {
        connections.remove(node)
    }

    fun isConnected(node: Node): Boolean = connections.any { it === node }

    /** Comparison used to keep the open list sorted by f value. */
    fun compare(node: Node): Int = when {
        node.f > f -> 1
        node.f < f -> -1
        else -> 0
    }

    fun reset(previous: Node? = null, g: Double = 0.0, h: Double = 0.0, f: Double = 0.0) {
        this.previous = previous
        this.g = g
        this.h = h
        this.f = f
        state = OPEN
        iteration = -1
    }
}
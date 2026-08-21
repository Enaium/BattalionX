package cn.enaium.battalion.game

import cn.enaium.battalion.data.Tile
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.data.UnitData
import cn.enaium.battalion.data.UnitManager
import cn.enaium.battalion.data.GameMap

/**
 * Groups every reachable tile of a given unit movement type into connected "islands".
 *
 * This is a direct port of the original `MovementIslands` (used by the multi-turn /
 * amphibious movement combiner). An *island* is a maximal set of tiles that a given unit's
 * movement type can reach by walking, where moving between any two members is possible
 * without crossing terrain that costs infinity.
 *
 * The combiner uses these islands to reason about transport crossing: a ground unit cannot
 * walk onto water, so its tank/commando islands are the land masses; a Leviathan Barge has
 * its own water islands ("Leviathan Barge" movement); and the choice of which island set a
 * unit belongs to is decided by [GameManager]'s [MovementCombiner].
 */
class MovementIslands(private val unitData: UnitData) {

    /** Each entry is a set of [Tile]s belonging to one connected island. */
    private val islands = mutableListOf<MutableSet<Tile>>()

    init {
        initialize()
    }

    /** Builds all connected islands reachable by [unitData]'s movement across the map. */
    private fun initialize() {
        val game = GameManager.instance
        val tileMap = game.tileMap
        val fakeUnit = Unit(unitData)
        val reachable = mutableListOf<Tile>()
        for (row in tileMap) {
            for (tile in row) {
                if (tile.canBeMovedOnBy(fakeUnit)) {
                    reachable.add(tile)
                }
            }
        }
        // Classic connected-components flood fill over 4-adjacent reachable tiles.
        while (reachable.isNotEmpty()) {
            val seed = reachable.removeAt(0)
            val newSet = mutableSetOf(seed)
            var i = 0
            while (i < reachable.size) {
                val potentialNeighbor = reachable[i]
                if (newSet.any { it.isAdjacentTo(potentialNeighbor) }) {
                    newSet.add(potentialNeighbor)
                    reachable.removeAt(i)
                    i = 0 // restart scan; set membership may have grown
                } else {
                    i++
                }
            }
            islands.add(newSet)
        }
    }

    private fun islandOf(tile: Tile): MutableSet<Tile>? =
        islands.firstOrNull { it.contains(tile) }

    /** True if [a] and [b] lie on the same connected island for this movement type. */
    fun areSharingAnIsland(a: Tile, b: Tile): Boolean {
        val islandA = islandOf(a) ?: return false
        val islandB = islandOf(b) ?: return false
        return islandA === islandB
    }

    /** The island (set of tiles) containing [tile], or empty if none. */
    fun islandOfOrEmpty(tile: Tile): Set<Tile> = islandOf(tile) ?: emptySet()

    /**
     * The union of every island set that intersects [aSet] — i.e. all tiles on the islands
     * touched by any member of [aSet]. Roughly the original `intersect(Set)`.
     */
    fun islandsTouching(aSet: Set<Tile>): Set<Tile> {
        val result = mutableSetOf<Tile>()
        for (island in islands) {
            if (island.any { it in aSet }) {
                result.addAll(island)
            }
        }
        return result
    }

    /** All islands as a list of tile sets. */
    fun getIslands(): List<Set<Tile>> = islands.map { it.toSet() }

    companion object {
        /** Builds an island set for the given unit name (e.g. "Leviathan Barge"). */
        fun forUnitName(name: String): MovementIslands? {
            val ud = UnitManager.get(name) ?: return null
            return MovementIslands(ud)
        }
    }
}

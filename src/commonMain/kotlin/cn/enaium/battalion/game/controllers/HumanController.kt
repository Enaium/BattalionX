package cn.enaium.battalion.game.controllers

import cn.enaium.battalion.data.Team
import cn.enaium.battalion.data.Unit
import cn.enaium.battalion.game.Controller
import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.TriggerManager

/**
 * Human player controller — holds UI state and dispatches player actions to the ActionManager.
 *
 * The original HumanController is a massive class handling sprite selection, menus, path drawing,
 * camera movement, and all UI interaction. This port keeps only the logical state; the SDL
 * renderer will call its methods to dispatch actions when the player clicks.
 */
class HumanController(team: Team) : Controller(team) {

    companion object {
        /** Score threshold for marking a target as "bad" or "OK" in the target picker. */
        const val BAD_TARGET = -25.0
        const val OK_TARGET = 70.0
    }

    var control: Boolean = false
        private set

    /** The unit currently selected by the player, or null. */
    var selectedUnit: Unit? = null
        private set

    /** The path the player has drawn for the selected unit. */
    var currentPath: MutableList<Int> = mutableListOf()

    /** The undo-move action for the current movement. */
    var undoAction: cn.enaium.battalion.game.actions.UndoMoveAction? = null

    init {
        TriggerManager.addListener(cn.enaium.battalion.game.Trigger.CONTROL) {
            controlListener()
        }
    }

    private fun controlListener() {
        control = true
    }

    override fun startTurn(player: Int) {
        control = false
    }

    override fun startCurrentTurn() {
        super.startCurrentTurn()
        selectedUnit = null
        currentPath.clear()
        undoAction = null
    }

    override fun endGame() {
        // No-op for human controller.
    }

    override fun deactivate() {
        super.deactivate()
        selectedUnit = null
        currentPath.clear()
        unitOptionsOpen = false
        buildMenu = null
    }

    /**
     * Whether the floating "build/extract/repair/unload" action menu is open above the selected
     * unit. Mirrors the original `showUnitMenu` toggle: selecting a unit shows a small
     * `unitOptionsButton` above it; clicking it opens (and this flag tracks) the full menu.
     */
    var unitOptionsOpen: Boolean = false
        private set

    /** One entry in the floating unit action menu (a tile-sheet button sprite in the original). */
    class UnitOption(
        /** Sprite name (e.g. "unitBuild", "unitRepair", "unitCancel"). */
        val sprite: String,
        /** Label shown on hover (e.g. "Repair"). */
        val title: String,
        /** Tooltip sub-line shown on hover. */
        val tooltip: String,
        /** Whether the action can be performed right now. */
        val enabled: Boolean,
    )

    /** Closes the floating action menu. */
    fun closeUnitOptions() {
        unitOptionsOpen = false
    }

    /**
     * Builds the floating action menu entries for [unit] — the same option set the original
     * `showUnitMenu` shows: transport pickup (Albatross / Leviathan), Unload, Build (Warmachine),
     * Extract, Repair, and Cancel. Availability mirrors the original checks.
     */
    fun raceUnitOptions(unit: Unit): List<UnitOption> {
        val game = cn.enaium.battalion.game.GameManager.instance
        val tile = game.tileAt(unit.x, unit.y) ?: return emptyList()
        val mapHasStructures = game.map.hasStructures()
        val options = mutableListOf<UnitOption>()

        if (!unit.isUpgraded()) {
            if (unit.unitData.isGround()) {
                if (unit.unitData.name.contains("Commando")) {
                    // Order an Albatross transport pickup (free when no structures on the map).
                    val free = !mapHasStructures
                    val cost = if (free) 0 else cn.enaium.battalion.data.UnitManager.get("Albatross Transport")?.getCost(this) ?: 0
                    val canOrder = free || (controlsAir() && getResources() >= cost)
                    options.add(
                        UnitOption(
                            "unitAlbatross",
                            "Order Albatross",
                            when {
                                !canOrder && controlsAir() -> "You need \$$cost to order an Albatross pickup."
                                !canOrder && game.map.hasAirControl -> "Capture an Air Control to enable Albatross pick up."
                                !free -> "This map does not allow for an Albatross pickup."
                                else -> "Order Albatross pickup for \$$cost."
                            },
                            canOrder,
                        ),
                    )
                }
                if (unit.unitData.range > 0) {
                    // Order a Leviathan Barge pickup to carry this unit across water.
                    val leviathanFree = !mapHasStructures || !game.map.isClassic
                    val cost = if (leviathanFree) 0 else cn.enaium.battalion.data.UnitManager.get("Leviathan Barge")?.getCost(this) ?: 0
                    val shore = tile.terrain.hasModifier("Shore")
                    val canMoveAway = tile.couldUnitMoveAwayFromThisTile("Leviathan Barge")
                    val canOrder = leviathanFree && shore && canMoveAway ||
                        (!leviathanFree && controlsSea() && shore && canMoveAway && getResources() >= cost)
                    options.add(
                        UnitOption(
                            "unitLeviathan",
                            "Order Leviathan",
                            when {
                                mapHasStructures && !game.map.hasSeaControl && !leviathanFree -> "This map does not allow for a Leviathan pickup."
                                !shore -> "You must be standing on a Shore square to call a Leviathan pickup."
                                !canMoveAway -> "You must be standing next to a Sea square to call a Leviathan pickup."
                                !controlsSea() && game.map.hasSeaControl -> "Capture a Sea Control to enable Leviathan pick up."
                                !canOrder -> "You need \$$cost to order a Leviathan pickup."
                                else -> "Order Leviathan pickup for \$$cost."
                            },
                            canOrder,
                        ),
                    )
                }
            }
        } else {
            // Upgraded (riding a transport): Unload / unpack.
            val baseUnit = unit.baseUnitData
            val canUnpack = tile.getCost(cn.enaium.battalion.data.Unit(baseUnit), 0, false) != Double.POSITIVE_INFINITY
            options.add(
                UnitOption(
                    "unitUnload",
                    "Unpack ${baseUnit.name}",
                    if (canUnpack) "Unpack and deploy the ${baseUnit.name}."
                    else "The ${baseUnit.name} cannot be unpacked on this square.",
                    canUnpack,
                ),
            )
        }

        if (unit.unitData.hasModifier("Constructor")) {
            // Warmachine: build a new unit onto an adjacent tile from the constructor's pool.
            val canAfford = cn.enaium.battalion.data.UnitManager.getAll()
                .any { !it.hasModifier("Unbuildable") && it.getCost(null) <= unit.resourcePool }
            val hasOpenNeighbor = tile.connections.any { (it as? cn.enaium.battalion.data.Tile)?.unit == null }
            options.add(
                UnitOption(
                    "unitBuild",
                    "Build",
                    when {
                        !canAfford -> "Out of Resources."
                        !hasOpenNeighbor -> "Build requires at least 1 unoccupied adjacent tile."
                        else -> "Use the Warmachine's Ore to construct a new unit."
                    },
                    canAfford && hasOpenNeighbor,
                ),
            )
        }

        if (unit.unitData.hasModifier("Extractor")) {
            val extractable = tile.terrain.hasModifier("Extractable")
            options.add(
                UnitOption(
                    "unitExtract",
                    "Extract Resources",
                    if (extractable) "Extract resources from this tile."
                    else "No resources can be extracted from this tile.",
                    extractable,
                ),
            )
        }

        if (!unit.isUpgraded()) {
            val atFull = unit.getHealth() >= unit.unitData.health
            val irreparable = unit.unitData.hasModifier("Irreparable")
            val repairCost = kotlin.math.round(unit.unitData.getCost(this) * game.healPercentage(unit)).toInt()
            val canAffordClassic = !game.map.isClassic || getResources() >= repairCost
            options.add(
                UnitOption(
                    "unitRepair",
                    "Repair",
                    when {
                        irreparable -> "This unit can never be repaired."
                        atFull -> "Unit is at full health."
                        !canAffordClassic -> "You need \$$repairCost to repair this unit."
                        game.map.isClassic -> "Start repair for \$$repairCost."
                        game.isMoraleEnabled -> "Repairing this unit will incur a morale penalty."
                        else -> "Repair up to one full chunk of the unit's health."
                    },
                    !irreparable && !atFull && canAffordClassic,
                ),
            )
        }

        options.add(UnitOption("unitCancel", "Cancel", "Cancel unit selection", true))
        return options
    }

    /** Toggles the floating action menu open/closed for the selected unit. */
    fun toggleUnitOptions() {
        unitOptionsOpen = !unitOptionsOpen
    }

    /**
     * A pending unit-construction menu: which unit to build and (for a Warmachine) which
     * adjacent tile to build it onto. The renderer presents the options and the player
     * navigates + confirms through [cycleBuildUnit] / [confirmBuild] / [cancelBuild].
     */
    var buildMenu: BuildMenuState? = null

    /** Whether the pending build is from a factory or a Warmachine constructor. */
    enum class BuildSource { FACTORY, WARMACHINE }

    /** Which sub-screen of the build interaction is currently shown. */
    enum class BuildPhase {
        /** The full build page listing buildable units (ground / air / sea columns). */
        UNITS,
        /**
         * Warmachine position picker: after choosing a unit on the build page, the player
         * clicks one of the cardinal direction arrows around the Warmachine to place it.
         */
        WM_DIRECTION,
    }

    /**
     * Pending construction selection and its interaction phase.
     *
     * For [BuildSource.FACTORY] the build completes on the [UNITS] page by picking a unit.
     * For [BuildSource.WARMACHINE] picking a unit advances to [BuildPhase.WM_DIRECTION] where
     * the player chooses an adjacent tile; [destX]/[destY] hold that tile once chosen.
     */
    class BuildMenuState(
        val sourceX: Int,
        val sourceY: Int,
        val source: BuildSource,
        val units: List<cn.enaium.battalion.data.UnitData>,
        var index: Int = 0,
        var destX: Int = -1,
        var destY: Int = -1,
        var phase: BuildPhase = BuildPhase.UNITS,
    ) {
        /** The unit currently highlighted on the build page. */
        val current: cn.enaium.battalion.data.UnitData? get() = units.getOrNull(index)

        /** Ground / air / sea template lists (each cost-sorted), for the column layout. */
        val ground: List<cn.enaium.battalion.data.UnitData> = units.filter { it.isGround() }
        val air: List<cn.enaium.battalion.data.UnitData> = units.filter { it.isAir() }
        val sea: List<cn.enaium.battalion.data.UnitData> = units.filter { it.isSea() }

        fun cycle(step: Int) {
            if (units.isEmpty()) return
            index = (index + step + units.size) % units.size
        }

        /** Selects the build-page card at the given flat index (used by the renderer on click). */
        fun select(index: Int) {
            if (index in units.indices) this.index = index
        }

        fun setDest(x: Int, y: Int) {
            destX = x
            destY = y
        }

        fun toDirectionPhase() {
            phase = BuildPhase.WM_DIRECTION
        }

        fun backToUnits() {
            phase = BuildPhase.UNITS
        }
    }

    /** The tile whose structure/constructor the build menu operates on, when [selectedUnit]. */
    var buildSourceX: Int = -1
        private set
    var buildSourceY: Int = -1
        private set

    /** Records the tile the player last focused for construction (a factory structure). */
    fun setBuildFocus(x: Int, y: Int) {
        buildSourceX = x
        buildSourceY = y
    }

    /**
     * Opens a build menu for the focused factory structure or a selected Warmachine
     * constructor. Returns false if nothing buildable was found. For a Warmachine, [destX]/
     * [destY] is the adjacent destination tile (defaults to the builder's tile).
     */
    fun openBuildMenu(destX: Int = -1, destY: Int = -1): Boolean {
        val game = cn.enaium.battalion.game.GameManager.instance

        // Prefer a focused factory structure.
        var source = game.tileAt(buildSourceX, buildSourceY)
        var isWarmachine = false
        var selected = selectedUnit
        if (source != null && source.hasStructure && source.controller === this &&
            source.terrain.hasModifier("Construction")) {
            isWarmachine = false
        } else {
            // Fall back to the selected unit: a Warmachine constructor on open terrain, or a
            // unit standing on our factory.
            selected = selectedUnit
            if (selected != null) {
                val tile = game.tileAt(selected.x, selected.y)
                if (tile != null && tile.hasStructure && tile.controller === this &&
                    tile.terrain.hasModifier("Construction")) {
                    source = tile
                    isWarmachine = false
                } else if (selected.baseUnitData.hasModifier("Constructor")) {
                    source = tile
                    isWarmachine = true
                    setBuildFocus(selected.x, selected.y)
                } else {
                    return false
                }
            } else {
                return false
            }
        }
        if (source == null) return false

        val units = if (isWarmachine) {
            val builder = selectedUnit ?: return false
            buildableUnitsFromWarmachine(builder.resourcePool)
                .sortedBy { it.getCost(this) }
        } else {
            // Show every unit the controller's buildings support (SWF BuildMenu lists ALL
            // units, greying out ones the player cannot afford). Affordability is evaluated
            // per-card by the build page.
            buildableUnits()
                .sortedBy { it.getCost(this) }
        }
        if (units.isEmpty()) return false

        val destTile = if (isWarmachine && destX >= 0 && destY >= 0) {
            game.tileAt(destX, destY)
        } else null

        buildMenu = BuildMenuState(
            sourceX = source.x,
            sourceY = source.y,
            source = if (isWarmachine) BuildSource.WARMACHINE else BuildSource.FACTORY,
            units = units,
            destX = destTile?.x ?: source.x,
            destY = destTile?.y ?: source.y,
        )
        return true
    }

    /** Advances the build menu selection by [step] units. */
    fun cycleBuildUnit(step: Int) {
        buildMenu?.cycle(step)
    }

    /**
     * Confirms the current build-menu step. On the [BuildPhase.UNITS] page a FACTORY build is
     * dispatched immediately; a WARMACHINE build advances to the [BuildPhase.WM_DIRECTION]
     * phase instead (a unit must first be placed via [chooseWarmachineDirection]). On the
     * WM_DIRECTION page it (re)dispatches the build for the current [destX]/[destY].
     */
    fun confirmBuild(): Boolean {
        val menu = buildMenu ?: return false
        val unit = menu.current ?: run { cancelBuild(); return false }
        when (menu.source) {
            BuildSource.FACTORY -> {
                dispatchBuild(menu.sourceX, menu.sourceY, unit.name)
                buildMenu = null
                return true
            }
            BuildSource.WARMACHINE -> when (menu.phase) {
                BuildPhase.UNITS -> {
                    // First require a destination: pick the first valid adjacent tile if none
                    // is set, then show the direction picker.
                    if (!selectDestForWarmachine(menu)) return false
                    menu.toDirectionPhase()
                    return true
                }
                BuildPhase.WM_DIRECTION -> {
                    if (menu.destX < 0 || menu.destY < 0) return false
                    dispatchWMBuild(menu.destX, menu.destY, unit.name)
                    buildMenu = null
                    return true
                }
            }
        }
    }

    /**
     * Used by the WM direction picker: valid cardinal directions for the selected Warmachine
     * build target to be placed. A direction is valid when the adjacent tile is on-map, empty,
     * and the current build unit can move onto it (mirrors `WMBuildMenu.canUnitMoveDirection`).
     */
    fun warmachineDirectionAvailable(direction: String): Boolean {
        val menu = buildMenu ?: return false
        if (menu.source != BuildSource.WARMACHINE) return false
        val start = cn.enaium.battalion.game.GameManager.instance.tileAt(menu.sourceX, menu.sourceY) ?: return false
        val unit = menu.current ?: return false
        val (dx, dy) = when (direction) {
            "left" -> start.x - 1 to start.y
            "right" -> start.x + 1 to start.y
            "up" -> start.x to start.y - 1
            "down" -> start.x to start.y + 1
            else -> return false
        }
        val dest = cn.enaium.battalion.game.GameManager.instance.tileAt(dx, dy) ?: return false
        if (dest.unit != null) return false
        return dest.getCost(Unit(unit), 0, false) <= unit.range.toDouble()
    }

    /**
     * Chooses which adjacent tile a Warmachine should build onto (from the WM direction
     * picker). Sets [destX]/[destY] for the currently selected unit and, if [build]
     * is true, dispatches it. Returns false if the direction is not valid.
     */
    fun chooseWarmachineDirection(direction: String, build: Boolean = true): Boolean {
        val menu = buildMenu ?: return false
        if (menu.source != BuildSource.WARMACHINE) return false
        if (menu.phase != BuildPhase.WM_DIRECTION) return false
        val start = cn.enaium.battalion.game.GameManager.instance.tileAt(menu.sourceX, menu.sourceY) ?: return false
        val unit = menu.current ?: return false
        val (dx, dy) = when (direction) {
            "left" -> start.x - 1 to start.y
            "right" -> start.x + 1 to start.y
            "up" -> start.x to start.y - 1
            "down" -> start.x to start.y + 1
            else -> return false
        }
        val dest = cn.enaium.battalion.game.GameManager.instance.tileAt(dx, dy) ?: return false
        if (dest.unit != null) return false
        if (dest.getCost(Unit(unit), 0, false) > unit.range.toDouble()) return false

        menu.setDest(dx, dy)
        if (build) {
            dispatchWMBuild(dx, dy, unit.name)
            buildMenu = null
        }
        return true
    }

    /** Returns the build menu to the unit-selection page (WM cancel arrow). */
    fun backBuildMenuToUnits() {
        buildMenu?.backToUnits()
    }

    /** Dismisses the build menu without building. */
    fun cancelBuild() {
        buildMenu = null
    }

    /**
     * Picks an adjacent destination tile for a Warmachine build. Prefers the current
     * [destX]/[destY], else the first clear adjacent reachable tile; returns false if none.
     */
    private fun selectDestForWarmachine(menu: BuildMenuState): Boolean {
        val game = cn.enaium.battalion.game.GameManager.instance
        val start = game.tileAt(menu.sourceX, menu.sourceY) ?: return false
        val unit = menu.current ?: return false
        val candidate = game.tileAt(menu.destX, menu.destY)
        if (candidate != null && candidate.unit == null && candidate.getCost(Unit(unit), 0, false) <= unit.range.toDouble()) {
            return true
        }
        for (n in start.connections) {
            val t = n as? cn.enaium.battalion.data.Tile ?: continue
            if (t.unit == null && t.getCost(Unit(unit), 0, false) <= unit.range.toDouble()) {
                menu.destX = t.x
                menu.destY = t.y
                return true
            }
        }
        return false
    }

    /** Called by the renderer when the player selects a unit tile. */
    fun selectUnit(unit: Unit?) {
        selectedUnit = unit
        currentPath.clear()
        // The floating action menu belongs to a specific selected unit; changing selection
        // dismisses it (mirrors the original clearing the unit menu on a new selection).
        if (unit != null) unitOptionsOpen = false
    }

    /** Called by the renderer to dispatch a move action. */
    fun dispatchMove(pathStrings: List<String>) {
        val unit = selectedUnit ?: return
        val move = cn.enaium.battalion.game.actions.MoveUnitAction(
            unit.x,
            unit.y,
            cn.enaium.battalion.game.actions.MoveUnitAction.stringizePath(pathStrings)
        )
        move.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(move)
    }

    /** Called by the renderer to dispatch an attack action. */
    fun dispatchAttack(targetX: Int, targetY: Int) {
        val unit = selectedUnit ?: return
        val attack = cn.enaium.battalion.game.actions.AttackUnitAction(unit.x, unit.y, targetX, targetY)
        attack.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(attack)
    }

    /**
     * Moves the selected unit along [pathStrings] to a tile, then attacks the tile at
     * [targetX]/[targetY], mirroring the original `HumanController.selectMove` (a MoveUnitAction
     * followed by an AttackUnitAction, so a melee unit can walk into range and fire this turn).
     */
    fun dispatchMoveThenAttack(pathStrings: List<String>, targetX: Int, targetY: Int) {
        val unit = selectedUnit ?: return
        val move = cn.enaium.battalion.game.actions.MoveUnitAction(
            unit.x,
            unit.y,
            cn.enaium.battalion.game.actions.MoveUnitAction.stringizePath(pathStrings),
        )
        move.isNetworked = false
        // The attack originates from the move's destination tile (the last path point), which is
        // where the unit stands when the AttackUnitAction fires.
        val destX = pathStrings[pathStrings.size - 2].toInt()
        val destY = pathStrings[pathStrings.size - 1].toInt()
        val attack = cn.enaium.battalion.game.actions.AttackUnitAction(
            destX,
            destY,
            targetX,
            targetY,
        )
        attack.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(move)
        cn.enaium.battalion.game.ActionManager.add(attack)
    }

    /** Called by the renderer to dispatch a "no attack" action (unit waits). */
    fun dispatchNoAttack() {
        val unit = selectedUnit ?: return
        val noAttack = cn.enaium.battalion.game.actions.NoAttackAction(unit.x, unit.y)
        noAttack.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(noAttack)
    }

    /** Called by the renderer to dispatch a build action from a factory. */
    fun dispatchBuild(factoryX: Int, factoryY: Int, unitName: String) {
        val build = cn.enaium.battalion.game.actions.BuildUnitAction(factoryX, factoryY, unitName)
        build.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(build)
    }

    /**
     * Called by the renderer to dispatch a build action from a Warmachine constructor onto an
     * adjacent tile [destX]/[destY]. The constructor carries its own [Unit.resourcePool]; the
     * built unit is placed adjacent and the pool is spent. Mirrors the original
     * `HumanController.buildUnitsFromWM`.
     */
    fun dispatchWMBuild(destX: Int, destY: Int, unitName: String) {
        val builder = selectedUnit ?: return
        // The selected unit must be a Warmachine constructor.
        if (!builder.baseUnitData.hasModifier("Constructor")) return
        val wmBuild = cn.enaium.battalion.game.actions.WMBuildUnitAction(
            cn.enaium.battalion.game.GameManager.instance.getControllerIndex(builder.controller ?: return),
            destX,
            destY,
            unitName,
            builder.x,
            builder.y,
        )
        wmBuild.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(wmBuild)
    }

    /** Called by the renderer to dispatch an extract-resources action. */
    fun dispatchExtract(x: Int, y: Int) {
        val extract = cn.enaium.battalion.game.actions.ExtractResourcesAction(x, y)
        extract.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(extract)
    }

    /** Called by the renderer to end the player's turn. */
    fun dispatchEndTurn() {
        endTurn(cn.enaium.battalion.game.actions.EndTurnAction.REASON_VOLUNTARY)
    }

    /** Called by the renderer to undo the last move. */
    fun dispatchUndoMove() {
        undoAction?.let {
            cn.enaium.battalion.game.ActionManager.add(it)
        }
    }

    /** Called by the renderer to repair the selected unit. */
    fun dispatchRepair() {
        val unit = selectedUnit ?: return
        val repair = cn.enaium.battalion.game.actions.RepairUnitAction(unit.x, unit.y)
        repair.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(repair)
    }

    /**
     * Loads an adjacent friendly portable unit (the passenger at [passengerX]/[passengerY])
     * into the selected transport. The original applies the upgrade to the *passenger*'s tile —
     * its [Unit.unitData] swaps to the transport's template, and it is downgraded (its
     * [Unit.baseUnitData] template restored) when unloaded.
     */
    fun dispatchLoad(passengerX: Int, passengerY: Int) {
        val transport = selectedUnit ?: return
        // The selected unit must be the transport (has the "Transport" modifier).
        if (!transport.unitData.hasModifier("Transport")) return
        val passenger = GameManager.instance.unitAt(passengerX, passengerY) ?: return
        if (passenger.controller !== transport.controller) return
        if (passenger.isUpgraded()) return
        // The passenger must be adjacent to (or co-located with) the transport.
        if (kotlin.math.abs(passenger.x - transport.x) > 1 || kotlin.math.abs(passenger.y - transport.y) > 1) return
        val upgradeAction = cn.enaium.battalion.game.actions.UpgradeUnitAction(
            passenger.x, passenger.y,
            upgrade = true,
            unitType = transport.unitData.name,
        )
        upgradeAction.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(upgradeAction)
    }

    /**
     * Unloads the currently loaded unit (the passenger) selected by the player. The selected
     * unit must itself be the upgraded passenger (its [Unit.unitData] is the transport's but
     * [Unit.isUpgraded] is true).
     */
    fun dispatchUnload() {
        val unit = selectedUnit ?: return
        if (!unit.isUpgraded()) return
        val upgradeAction = cn.enaium.battalion.game.actions.UpgradeUnitAction(
            unit.x, unit.y,
            upgrade = false,
        )
        upgradeAction.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(upgradeAction)
    }

    /**
     * Orders a transport pickup for the selected unit: it is loaded into [transportName]
     * (e.g. "Albatross Transport" or "Leviathan Barge"), swapping its effective [Unit.unitData]
     * so it can move with/aboard the transport. Mirrors the original `upgradeUnits`.
     */
    fun dispatchUpgradeTransport(unit: Unit, transportName: String) {
        if (unit.isUpgraded()) return
        val upgradeAction = cn.enaium.battalion.game.actions.UpgradeUnitAction(
            unit.x, unit.y,
            upgrade = true,
            unitType = transportName,
        )
        upgradeAction.isNetworked = false
        cn.enaium.battalion.game.ActionManager.add(upgradeAction)
    }

    // ---- Build-page helpers -------------------------------------------------

    /** True while the build page (or its WM direction phase) is open. */
    fun isBuildMenuFocused(): Boolean = buildMenu != null

    /** Whether a [seaUnit] could move away from the build source tile's neighbours at all. */
    private fun couldUnitMoveAwayFromHere(seaUnit: cn.enaium.battalion.data.UnitData): Boolean {
        val menu = buildMenu ?: return false
        val tile = cn.enaium.battalion.game.GameManager.instance.tileAt(menu.sourceX, menu.sourceY)
        return tile?.couldUnitMoveAwayFromThisTile(seaUnit.name) == true
    }

    /**
     * Whether a unit template can be built at the open menu's source tile at all, regardless
     * of cost. Mirrors the SWF `BuildMenu.canBuildUnitHere` (factory: the controller must
     * control the unit's domain) and `WMBuildMenu.canBuildUnitHere` (Warmachine: sea units
     * must be placed from a Shore square; ground/air are always buildable here).
     */
    fun canBuildHere(data: cn.enaium.battalion.data.UnitData): Boolean {
        val menu = buildMenu ?: return false
        return when (menu.source) {
            BuildSource.FACTORY -> {
                // The SWF factory sea card requires BOTH sea control AND that a sea unit can
                // actually move away from the structure tile's surrounding water.
                if (data.isSea()) {
                    controlsSea() && couldUnitMoveAwayFromHere(data)
                } else {
                    hasRequisiteControlStructuresToBuild(data)
                }
            }
            BuildSource.WARMACHINE -> {
                if (!data.isSea()) return true
                val tile = cn.enaium.battalion.game.GameManager.instance.tileAt(menu.sourceX, menu.sourceY)
                tile?.terrain?.hasModifier("Shore") == true
            }
        }
    }

    /**
     * Whether a unit template on the build page can actually be purchased right now.
     * A factory build needs the controller's global resources; a Warmachine build draws
     * from the constructor's own resource pool.
     */
    fun canAffordBuild(data: cn.enaium.battalion.data.UnitData): Boolean {
        val menu = buildMenu ?: return false
        val cost = data.getCost(this)
        return if (menu.source == BuildSource.WARMACHINE) {
            val builder = selectedUnit?.resourcePool ?: return false
            cost <= builder
        } else {
            cost <= getResources()
        }
    }

    /** Converts a tile offset pair to a WM direction name ("left"/"right"/"up"/"down"). */
    fun warmachineDirectionName(dx: Int, dy: Int): String? = when {
        dx < 0 -> "left"
        dx > 0 -> "right"
        dy < 0 -> "up"
        dy > 0 -> "down"
        else -> null
    }
}
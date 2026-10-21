package cn.enaium.battalion.mod

import cn.enaium.battalion.game.ui.SaveManager

/**
 * The game's mod set: what loaded, what did not, and the two layers mods contribute.
 *
 * [load] is called once per assets directory (from [cn.enaium.battalion.data.GameLoader] before
 * anything reads data), because both the data loader and the texture/sound managers resolve
 * through the layers it builds.
 */
object ModRegistry {

    /**
     * The game version mods declare compatibility against. Bump it when a change makes old mods
     * behave differently, so their `game.maxVersion`/`minVersion` windows mean something.
     */
    const val GAME_VERSION = "1.0.0"

    /**
     * Where mods live, relative to the directory the program is started from — `mods/` next to
     * the launcher/assets, NOT inside `assets/` (assets are game content; mods are what the
     * player dropped in, and they must survive replacing the asset set).
     */
    const val DEFAULT_MODS_ROOT = "mods"

    /** The mods directory the layers were built from. */
    var modsRoot: String = DEFAULT_MODS_ROOT
        private set

    /** The assets directory the current layers were built for (null before the first load). */
    private var loadedAssetsDir: String? = null

    /** Enabled mods in load order (later mods win on conflicts). */
    var mods: List<LoadedMod> = emptyList()
        private set

    /** Every valid mod found on disk, including the ones switched off (for the mod list). */
    var allMods: List<LoadedMod> = emptyList()
        private set

    private var loadProblems: List<ModProblem> = emptyList()

    /**
     * Everything that went wrong, for the mod list screen and the console: what the loader
     * rejected, plus every patch that failed while a document was read (a `test` another mod
     * invalidated, for example).
     */
    val problems: List<ModProblem> get() = loadProblems + ModLog.problems

    /** The JSON-Patch overlay over `assets/data/`, null when no mod is active. */
    var data: ModData? = null
        private set

    /** The namespaced image/sound resolver, null when no mod is active. */
    var resources: ResourceManager? = null
        private set

    val isModded: Boolean get() = mods.isNotEmpty()

    /**
     * Loads the mods under [modsRoot] (defaults to [DEFAULT_MODS_ROOT], i.e. `mods/` beside the
     * program) and rebuilds both layers against [assetsDir].
     */
    fun load(assetsDir: String, modsRoot: String = DEFAULT_MODS_ROOT) {
        this.modsRoot = modsRoot
        loadedAssetsDir = assetsDir
        ModLog.clear()
        val disabled = SaveManager.disabledMods()
        val result = ModLoader.load(modsRoot, GAME_VERSION, disabled)
        mods = result.mods
        // Keep the list in a stable folder order so flipping a switch does not make the row
        // jump to the end of the list.
        allMods = (result.mods + result.disabled).sortedBy { it.dir }
        loadProblems = result.problems
        data = ModData(mods)
        resources = ResourceManager(mods, assetsDir)
        if (mods.isEmpty() && problems.isEmpty()) {
            println("Mods: none found in $modsRoot")
        } else {
            println("Mods: ${mods.size} loaded${if (problems.isEmpty()) "" else ", ${problems.size} problem(s)"}")
            for (mod in mods) println("  + ${mod.manifest.id} ${mod.manifest.version}")
            for (problem in problems) println("  ! [${problem.mod}] ${problem.message}")
        }
    }

    /**
     * Loads the mods once per assets directory (every loader and menu calls this before
     * resolving a document or a resource). An explicit [ModRegistry.load] with a custom root
     * counts as loaded, so tests and embedding tools keep their own directory.
     */
    fun ensureLoaded(assetsDir: String) {
        if (loadedAssetsDir != assetsDir) load(assetsDir, modsRoot)
    }

    /** Forgets every mod (used by tests and by the CLI diagnostics). */
    fun clear() {
        mods = emptyList()
        allMods = emptyList()
        loadProblems = emptyList()
        loadedAssetsDir = null
        data = null
        resources = null
        ModLog.clear()
    }

    fun byId(id: String): LoadedMod? = mods.firstOrNull { it.manifest.id == id }

    /**
     * Documents under a data subdirectory that came from a mod rather than the base game
     * (e.g. a mod's own maps under `maps/`).
     */
    fun modDocuments(assetsDir: String, rel: String): List<String> =
        data?.modDocuments("${assetsDir}/data", rel).orEmpty()
}

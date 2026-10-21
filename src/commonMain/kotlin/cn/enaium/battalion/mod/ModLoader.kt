package cn.enaium.battalion.mod

import cn.enaium.battalion.util.Fio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A mod loaded from disk: its folder, its manifest and the JSON Patch documents it ships.
 *
 * A mod never replaces a game document. It contributes patches keyed by the document path they
 * target, mirroring the game's own layout:
 *
 * ```
 * my-mod/data/patches/units/crystal_sword.json   -> patches data/units/crystal_sword.json
 * my-mod/data/patches/ballistics.json            -> patches data/ballistics.json
 * ```
 *
 * A patch document is either a bare RFC 6902 array, or an object `{"target": "...", "patch": [...]}`
 * when the author wants to name the target explicitly. Patches are applied in load order, so the
 * last loaded mod wins on a conflicting path — exactly like a sequence of `replace` operations.
 * Media lives in `images/` and `sounds/` and is addressed by resource id, not by file path.
 */
class LoadedMod(
    /** Absolute path of the mod's folder. */
    val dir: String,
    val manifest: ModManifest,
    /** Patches keyed by the document path relative to `data/` (e.g. `units/crystal.json`). */
    val patches: Map<String, List<JsonElement>>,
    /** Resource files by id (e.g. `items/crystal.png` -> `<dir>/images/items/crystal.png`). */
    val images: Map<String, String>,
    /** Sound files by id (e.g. `ui/click.mp3` -> `<dir>/sounds/ui/click.mp3`). */
    val sounds: Map<String, String>,
) {
    /** True when the player left this mod enabled (disabled mods contribute nothing). */
    var enabled: Boolean = true

    /** The mod's namespace: its id, used to qualify resource and data ids. */
    val namespace: String get() = manifest.id

    override fun toString(): String = "LoadedMod(${manifest.id}@${manifest.version})"
}

/**
 * Discovers, validates and orders the mods under [modsRoot] (normally `<assets>/mods`).
 *
 * Ordering follows the manifest: `dependencies` and `loadAfter` push a mod after another,
 * `loadBefore` pushes it in front, and ties fall back to the folder name so the order is stable.
 * Problems (unreadable manifest, missing dependency, version mismatch, dependency cycle) are
 * reported and the offending mod is skipped — a broken mod must not take the game down.
 */
object ModLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    /** File extensions searched for a resource id, in priority order. */
    val imageExtensions = listOf("png", "jpg", "jpeg", "bmp")
    val soundExtensions = listOf("mp3", "wav", "ogg", "flac")

    /**
     * Loads every valid mod under [modsRoot], in load order, with everything it rejected.
     * Mods whose id is in [disabled] are discovered and reported but contribute nothing —
     * neither patches nor resources — so the player can switch a mod off without uninstalling it.
     */
    fun load(modsRoot: String, gameVersion: String, disabled: Set<String> = emptySet()): ModLoadResult {
        val names = Fio.listDir(modsRoot).orEmpty()
            .filter { !it.startsWith(".") }
            .sorted()
        val candidates = mutableListOf<LoadedMod>()
        val rejected = mutableListOf<ModProblem>()
        for (name in names) {
            val dir = "$modsRoot/$name"
            val manifestText = Fio.readText("$dir/manifest.json")
            if (manifestText == null) {
                // Folders without a manifest are ignored silently (documentation, .git, ...).
                continue
            }
            val manifest = try {
                json.decodeFromString<ModManifest>(manifestText)
            } catch (t: Throwable) {
                rejected += ModProblem(name, "manifest.json is not readable: ${t.message}")
                continue
            }
            if (manifest.id.isBlank()) {
                rejected += ModProblem(name, "manifest.json has no \"id\"")
                continue
            }
            candidates += LoadedMod(
                dir = dir,
                manifest = manifest,
                patches = readPatches(dir, rejected, name),
                images = readResources(dir, "images", imageExtensions),
                sounds = readResources(dir, "sounds", soundExtensions),
            )
        }

        val ordered = order(candidates, rejected)
        val valid = validate(ordered, gameVersion, rejected)
        for (mod in valid) mod.enabled = !disabled.contains(mod.manifest.id)
        return ModLoadResult(valid.filter { it.enabled }, valid.filterNot { it.enabled }, rejected)
    }

    // ---------------------------------------------------------------------
    // Documents
    // ---------------------------------------------------------------------

    /** Reads every patch document under `data/patches/`, keyed by its target document. */
    private fun readPatches(
        dir: String,
        rejected: MutableList<ModProblem>,
        modName: String,
    ): Map<String, List<JsonElement>> {
        val root = "$dir/data/patches"
        val result = linkedMapOf<String, List<JsonElement>>()
        for (rel in filesUnder(root)) {
            if (!rel.endsWith(".json")) continue
            val text = Fio.readText("$root/$rel") ?: continue
            val element = try {
                json.parseToJsonElement(text)
            } catch (t: Throwable) {
                rejected += ModProblem(modName, "patch $rel is not valid JSON: ${t.message}")
                continue
            }
            val (target, ops) = when (element) {
                is JsonArray -> rel to element
                is JsonObject -> {
                    val target = (element["target"] as? JsonPrimitive)?.content
                        ?: rel.removeSuffix(".json")
                    val ops = element["patch"]?.jsonArray
                        ?: element["operations"]?.jsonArray
                        ?: run {
                            rejected += ModProblem(modName, "patch $rel needs \"patch\": [...] or a bare array")
                            JsonArray(emptyList())
                        }
                    target to ops
                }
                else -> {
                    rejected += ModProblem(modName, "patch $rel must be an array or an object")
                    continue
                }
            }
            if (ops.isEmpty()) continue
            val key = if (target.endsWith(".json")) target else "$target.json"
            result[key] = (result[key] ?: emptyList()) + ops
        }
        return result
    }

    /** All files under [root] (relative paths); empty when the directory does not exist. */
    private fun filesUnder(root: String, prefix: String = ""): List<String> {
        val entries = Fio.listDir(if (prefix.isEmpty()) root else "$root/$prefix") ?: return emptyList()
        val result = mutableListOf<String>()
        for (entry in entries) {
            if (entry.startsWith(".")) continue
            val rel = if (prefix.isEmpty()) entry else "$prefix/$entry"
            // Fio has no "is directory" flag for a listing, so try to descend: a file read fails.
            val nested = Fio.listDir("$root/$rel")
            if (nested != null) result += filesUnder(root, rel) else result += rel
        }
        return result
    }

    /** Resource files keyed by id (path without extension), e.g. `items/crystal.png` -> `items/crystal`. */
    private fun readResources(dir: String, kind: String, extensions: List<String>): Map<String, String> {
        val root = "$dir/$kind"
        val result = linkedMapOf<String, String>()
        for (rel in filesUnder(root)) {
            val ext = rel.substringAfterLast('.', "").lowercase()
            if (ext !in extensions) continue
            val id = rel.removeSuffix(".$ext")
            // First file wins per id (extension priority above), which must hold on every target
            // — putIfAbsent is JVM-only.
            if (!result.containsKey(id)) result[id] = "$root/$rel"
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Ordering / validation
    // ---------------------------------------------------------------------

    /** Stable topological order: dependencies and loadAfter first, loadBefore in front, else name order. */
    private fun order(
        mods: List<LoadedMod>,
        rejected: MutableList<ModProblem>,
    ): List<LoadedMod> {
        val byId = mods.associateBy { it.manifest.id }
        // edges[id] = the mods that must be visited (loaded) before id.
        val edges = mutableMapOf<String, MutableSet<String>>()
        for (mod in mods) {
            edges.getOrPut(mod.manifest.id) { mutableSetOf() }
            for (dep in mod.manifest.dependencies) {
                if (byId.containsKey(dep.id)) edges.getOrPut(mod.manifest.id) { mutableSetOf() }.add(dep.id)
            }
            for (after in mod.manifest.loadAfter) {
                if (byId.containsKey(after)) edges.getOrPut(mod.manifest.id) { mutableSetOf() }.add(after)
            }
            for (before in mod.manifest.loadBefore) {
                if (byId.containsKey(before)) edges.getOrPut(before) { mutableSetOf() }.add(mod.manifest.id)
            }
        }
        val ordered = mutableListOf<LoadedMod>()
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        var cycle = false

        fun visit(id: String) {
            if (id in done) return
            if (!visiting.add(id)) {
                cycle = true
                return
            }
            for (next in edges[id].orEmpty()) visit(next)
            visiting.remove(id)
            done.add(id)
            byId[id]?.let { ordered += it }
        }

        // Iterate in folder order so ties stay stable.
        for (mod in mods) visit(mod.manifest.id)
        if (cycle) {
            rejected += ModProblem("(order)", "dependency cycle between mods; falling back to folder order")
            return mods
        }
        return ordered
    }

    /** Drops mods whose dependencies (or game version) cannot be satisfied. */
    private fun validate(
        mods: List<LoadedMod>,
        gameVersion: String,
        rejected: MutableList<ModProblem>,
    ): List<LoadedMod> {
        val available = mods.associate { it.manifest.id to it.manifest.version }
        val dropped = mutableSetOf<String>()
        for (mod in mods) {
            val id = mod.manifest.id
            for (dep in mod.manifest.dependencies) {
                val have = available[dep.id]
                if (have == null) {
                    if (!dropped.contains(dep.id)) {
                        rejected += ModProblem(id, "missing dependency \"${dep.id}\"")
                        dropped += id
                    }
                    continue
                }
                if (!dep.satisfiedBy(have)) {
                    rejected += ModProblem(id, "needs ${dep.id} ${dep.version} but $have is loaded")
                    dropped += id
                }
            }
            val compat = mod.manifest.game ?: continue
            if (compat.minVersion.isNotEmpty() && !VersionRequirement(">=${compat.minVersion}").matches(gameVersion)) {
                rejected += ModProblem(id, "needs game >= ${compat.minVersion} (this is $gameVersion)")
                dropped += id
            }
            if (compat.maxVersion.isNotEmpty() && !VersionRequirement("<=${compat.maxVersion}").matches(gameVersion)) {
                rejected += ModProblem(id, "needs game <= ${compat.maxVersion} (this is $gameVersion)")
                dropped += id
            }
        }
        return mods.filterNot { dropped.contains(it.manifest.id) }
    }
}

/** Everything [ModLoader.load] found: the enabled mods in load order, the disabled ones, and the rejected ones. */
class ModLoadResult(
    val mods: List<LoadedMod>,
    val disabled: List<LoadedMod>,
    val problems: List<ModProblem>,
)

/** A mod that could not be loaded (or an ordering problem), with the reason. */
data class ModProblem(val mod: String, val message: String)

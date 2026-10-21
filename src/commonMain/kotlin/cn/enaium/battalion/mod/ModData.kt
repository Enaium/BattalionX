package cn.enaium.battalion.mod

import cn.enaium.battalion.util.Fio
import kotlinx.serialization.json.Json

/**
 * The JSON-Patch overlay over the game's own `assets/data//` documents.
 *
 * A read goes through every mod in load order: the base document is patched by each mod that
 * targets it, so the result is always "base data + patches" — a mod can add content and adjust
 * existing content, but never replace a document wholesale (that is what keeps mods compatible
 * with each other and with game updates).
 *
 * Directories are listed as the union of the base entries and the documents mods add, which is
 * how a mod's own maps, campaigns or units become visible to the rest of the game.
 */
class ModData(private val mods: List<LoadedMod>) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    /** Every document path (relative to `data/`) some mod patches. */
    val patchedPaths: Set<String> = mods.flatMapTo(linkedSetOf()) { it.patches.keys }

    /** The final text of a data document: base (when present) with every mod patch applied. */
    fun readText(dataDir: String, rel: String): String? {
        val base = Fio.readText("$dataDir/$rel")
        val applicable = mods.mapNotNull { mod -> mod.patches[rel]?.let { mod to it } }
        if (applicable.isEmpty()) return base

        var document = base?.let {
            try {
                json.parseToJsonElement(it)
            } catch (t: Throwable) {
                ModLog.problem(rel, "base document is not valid JSON: ${t.message}")
                null
            }
        } ?: JsonPatch.emptyDocument

        for ((mod, ops) in applicable) {
            document = try {
                JsonPatch.apply(document, ops)
            } catch (t: Throwable) {
                // A failing patch (typically a `test` another mod invalidated) must not take the
                // document down: report it and keep the state from the mods before it.
                ModLog.problem(mod.manifest.id, "patch for $rel failed: ${t.message}")
                document
            }
        }
        return document.toString()
    }

    /**
     * Directory entries: the base ones plus the documents mods add under [rel]. Subdirectories
     * (e.g. the generation folders under `maps/`) are listed by name, like the files without
     * their `.json` suffix.
     */
    fun list(dataDir: String, rel: String): List<String> {
        val dir = "$dataDir/$rel"
        val base = Fio.listDir(dir).orEmpty()
            .filter {
                it.endsWith(".json") || Fio.listDir("$dir/$it") != null
            }
            .map { it.removeSuffix(".json") }
        val prefix = if (rel.isEmpty()) "" else "$rel/"
        val added = patchedPaths
            .filter { it.startsWith(prefix) && it.endsWith(".json") }
            .map { it.removePrefix(prefix).substringBefore('/').removeSuffix(".json") }
            .filter { it.isNotEmpty() }
        return (base + added).distinct().sorted()
    }

    /** Documents of [rel] that came from a mod (not part of the base game). */
    fun modDocuments(dataDir: String, rel: String): List<String> {
        val dir = "$dataDir/$rel"
        val base = Fio.listDir(dir).orEmpty()
            .filter { it.endsWith(".json") || Fio.listDir("$dir/$it") != null }
            .map { it.removeSuffix(".json") }
            .toSet()
        return list(dataDir, rel).filterNot { base.contains(it) }
    }
}

/** Load-time diagnostics: problems are collected for the mod list UI and the console. */
object ModLog {
    private val entries = mutableListOf<ModProblem>()

    val problems: List<ModProblem> get() = entries.toList()

    fun clear() = entries.clear()

    fun problem(mod: String, message: String) {
        entries += ModProblem(mod, message)
        println("Mod: [$mod] $message")
    }
}

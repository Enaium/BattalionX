package cn.enaium.battalion.mod

import cn.enaium.battalion.util.Fio

/**
 * Resolves resource ids to files, with mods on top of the base game.
 *
 * Data never names a file: it names a resource id (a unit's `"sprite": "scorpion"`, a mod icon
 * `"ui/icon"`, a sound `"FireScorpion"`). The id maps onto `<kind>/<id>.<ext>`; mods are searched
 * in reverse load order first (a later mod overrides an earlier one, and both override the base
 * game), then the base game itself. Ids may be namespaced with `mod.id:path` to pin a resource to
 * one mod, which is how two mods can both ship `items/crystal_sword` without colliding.
 */
class ResourceManager(private val mods: List<LoadedMod>, private val assetsDir: String) {

    /** The file backing an image id, or null when nobody provides it. */
    fun imagePath(id: String): String? = resolve(id, images = true)

    /** The file backing a sound id, or null when nobody provides it. */
    fun soundPath(id: String): String? = resolve(id, images = false)

    fun hasImage(id: String): Boolean = imagePath(id) != null

    fun hasSound(id: String): Boolean = soundPath(id) != null

    /** Every image id the base game and the mods provide (used by the editor's icon picker). */
    fun imageIds(): List<String> {
        val ids = linkedSetOf<String>()
        Fio.listDir("$assetsDir/images").orEmpty().forEach { file ->
            val ext = file.substringAfterLast('.', "").lowercase()
            if (ext in ModLoader.imageExtensions) ids += file.removeSuffix(".$ext")
        }
        for (mod in mods) ids += mod.images.keys
        return ids.sorted()
    }

    /** The mod that provides [id], or null for base-game resources. */
    fun providerOf(id: String, images: Boolean = true): LoadedMod? =
        lookup(id, images)?.first

    private fun resolve(id: String, images: Boolean): String? {
        lookup(id, images)?.let { (_, path) -> return path }
        // Base game: the id is a path under images/ or sounds/ (the base set is flat, but a
        // namespaced id may still point into a folder).
        val (name, _) = splitNamespace(id)
        val kind = if (images) "images" else "sounds"
        val extensions = if (images) ModLoader.imageExtensions else ModLoader.soundExtensions
        for (ext in extensions) {
            val path = "$assetsDir/$kind/$name.$ext"
            if (Fio.exists(path)) return path
        }
        return null
    }

    /** The first mod (in reverse load order) providing [id], with the path. */
    private fun lookup(id: String, images: Boolean): Pair<LoadedMod, String>? {
        val (name, namespace) = splitNamespace(id)
        val candidates = if (namespace != null) {
            mods.filter { it.manifest.id == namespace }.reversed()
        } else {
            mods.reversed()
        }
        for (mod in candidates) {
            val table = if (images) mod.images else mod.sounds
            table[name]?.let { return mod to it }
        }
        return null
    }

    /** `mod.id:path` -> (`path`, `mod.id`); a plain id keeps a null namespace. */
    private fun splitNamespace(id: String): Pair<String, String?> {
        val colon = id.indexOf(':')
        if (colon <= 0 || colon == id.length - 1) return id to null
        return id.substring(colon + 1) to id.substring(0, colon)
    }
}

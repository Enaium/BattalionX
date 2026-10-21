package cn.enaium.battalion.data

import cn.enaium.battalion.embedded.Resources
import cn.enaium.battalion.util.Fio

/**
 * The game's assets embedded at build time by `cn.enaium.embedresource` (see build.gradle.kts).
 *
 * Every file below `src/commonMain/resources/` becomes a zlib-compressed Kotlin constant, so a packaged build
 * needs no platform bundle and no copy of the resource directory. The existing loaders
 * (GameLoader, TextureManager, AudioManager) are path based, so [materialize] writes the
 * embedded tree into a writable directory once and the game runs against that directory.
 */
object EmbeddedAssets {

    /** Every embedded resource path, relative to `assets/` (e.g. `data/maps/ep3/Mission_1.json`). */
    val paths: List<String> get() = Resources.paths

    fun readBytes(path: String): ByteArray? = Resources.readBytes(path)

    fun readText(path: String): String? = Resources.readText(path)

    /**
     * Writes every embedded resource under [targetDir], unless [versionTag] is already recorded
     * there. Returns true when the tree is complete; the caller can then use [targetDir] as the
     * assets directory.
     */
    fun materialize(targetDir: String, versionTag: String): Boolean {
        if (Resources.paths.isEmpty()) return false
        val marker = "$targetDir/.embedded-$versionTag"
        if (Fio.exists(marker)) return true
        for (path in Resources.paths) {
            val bytes = Resources.readBytes(path) ?: return false
            if (!Fio.writeBytes("$targetDir/$path", bytes)) return false
        }
        return Fio.writeText(marker, versionTag)
    }
}

/**
 * Resolves the directory the game should read its assets from.
 *
 * A real `assets/` directory (development builds, mod authors) always wins so mods and data
 * edits are live. A packaged build without one materializes the embedded tree into
 * [writableDir]/assets once per [versionTag].
 */
object AssetRoot {
    fun resolve(assetsDir: String, writableDir: String?, versionTag: String): String {
        if (Fio.exists("$assetsDir/data")) return assetsDir
        if (writableDir == null) return assetsDir
        val target = "$writableDir/assets"
        return if (EmbeddedAssets.materialize(target, versionTag)) target else assetsDir
    }
}

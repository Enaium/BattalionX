package cn.enaium.battalion.data

import cn.enaium.battalion.util.Fio
import kotlinx.serialization.json.Json

/**
 * Loads every typed asset document (see [AssetConfigs] for the on-disk contract).
 *
 * All parsing goes through kotlinx.serialization against the @Serializable config models —
 * there is no XML-shaped bridge anywhere in the pipeline. [docs] caches parsed documents so a
 * mission load re-uses the already-read registries.
 */
object AssetLoader {

    @PublishedApi
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    /** Document cache keyed by the path relative to `data/`. */
    @PublishedApi
    internal val docs = mutableMapOf<String, Any>()

    fun clearCache() { docs.clear() }

    @PublishedApi
    internal fun cached(rel: String): Any? = docs[rel]

    inline fun <reified T> read(assetsDir: String, rel: String): T? {
        cached(rel)?.let { return it as? T }
        val text = Fio.readText("${dataDir(assetsDir)}/$rel") ?: return null
        return try {
            val value = decode<T>(text)
            docs[rel] = value as Any
            value
        } catch (t: Throwable) {
            println("AssetLoader: failed to parse $rel: $t")
            null
        }
    }

    @PublishedApi
    internal inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    /** Lists document names in a data subdirectory (e.g. `units`), without extension. */
    fun list(assetsDir: String, rel: String): List<String> =
        Fio.listDir("${dataDir(assetsDir)}/$rel").orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }

    fun dataDir(assetsDir: String): String = "$assetsDir/data"

    // ---- typed convenience readers ----------------------------------------

    fun unit(assetsDir: String, id: String): UnitConfig? =
        read(assetsDir, "units/$id.json")

    fun terrain(assetsDir: String, id: String): TerrainConfig? =
        read(assetsDir, "terrain/$id.json")

    fun modifier(assetsDir: String, kind: String, id: String): ModifierConfig? =
        read(assetsDir, "modifiers/${kind.lowercase()}/$id.json")

    fun ballistics(assetsDir: String): BallisticsConfig? =
        read(assetsDir, "ballistics.json")

    fun sprites(assetsDir: String): SpriteAtlasConfig? =
        read(assetsDir, "sprites.json")

    fun campaign(assetsDir: String, name: String): CampaignConfig? =
        read(assetsDir, "campaigns/$name.json")

    fun map(assetsDir: String, rel: String): MapConfig? =
        read<MapConfig>(assetsDir, if (rel.startsWith("maps/")) rel else "maps/$rel")
}

fun parseArgb(s: String): Int {
    val cleaned = s.removePrefix("0x").removePrefix("0X").trim()
    return cleaned.toLongOrNull(16)?.toInt() ?: 0xFF00FF00.toInt()
}

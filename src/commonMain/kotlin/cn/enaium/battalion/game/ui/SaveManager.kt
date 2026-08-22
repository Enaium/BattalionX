package cn.enaium.battalion.game.ui

import cn.enaium.battalion.util.Fio
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The game's persistent storage: options (sound/music/dialog), the AI difficulty selection,
 * campaign progress (levels + medals) and the single saved game.
 *
 * The original SWF kept everything in one SharedObject (`batvengeance_v1`); a desktop app has
 * no SharedObject, so the same keys are persisted as JSON beside the assets directory (the only
 * writable location guaranteed next to the game data). [load] is idempotent and [flush] writes
 * the whole store back — every setter calls flush, mirroring `Storage.setInt/setBoolean/setXML`.
 */
object SaveManager {

    private const val FILE_NAME = "battalionx_save.json"

    /** The JSON file path (set by [bind] from the assets directory). */
    private var filePath: String = ""

    /**
     * Raw store: option booleans, difficulty index, campaign progress, medals, and the
     * serialized game save (XML string, or null when no saved game exists).
     */
    private val store = mutableMapOf<String, Any?>()

    // ---- lifecycle ---------------------------------------------------------

    /** Binds the store to [assetsDir]/../[FILE_NAME] and loads any existing data. */
    fun bind(assetsDir: String) {
        filePath = "$assetsDir/../$FILE_NAME"
        load()
    }

    /** Re-reads the JSON file; missing/corrupt file falls back to defaults. */
    fun load() {
        store.clear()
        val text = Fio.readText(filePath) ?: return
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject ?: return
        } catch (t: Throwable) {
            return
        }
        for ((k, v) in root) {
            store[k] = when (v) {
                is JsonPrimitive -> if (v.isString) v.content
                else if (v.content == "true") true
                else if (v.content == "false") false
                else v.content.toIntOrNull() ?: v.content
                is JsonArray -> v.map { (it as? JsonPrimitive)?.content }.filterNotNull()
                else -> null
            }
        }
    }

    /** Writes the whole store to disk (best-effort; a read-only dir disables saving). */
    fun flush() {
        if (filePath.isEmpty()) return
        val obj = buildJsonObject {
            for ((k, v) in store) {
                when (v) {
                    is Boolean -> put(k, v)
                    is Int -> put(k, v)
                    is String -> put(k, v)
                    is List<*> -> put(k, JsonArray(v.mapNotNull { e ->
                        if (e is String) JsonPrimitive(e) else null
                    }))
                    else -> Unit
                }
            }
        }
        Fio.writeText(filePath, Json.encodeToString(obj))
    }

    // ---- getters / setters (each setter persists immediately) ---------------

    private fun getBool(key: String, default: Boolean): Boolean = store[key] as? Boolean ?: default
    private fun getInt(key: String, default: Int): Int = store[key] as? Int ?: default
    private fun getString(key: String): String? = store[key] as? String

    private fun setBool(key: String, value: Boolean) { store[key] = value; flush() }
    private fun setInt(key: String, value: Int) { store[key] = value; flush() }
    private fun setString(key: String, value: String?) {
        if (value == null) store.remove(key) else store[key] = value
        flush()
    }

    // ---- options ------------------------------------------------------------

    var sound: Boolean
        get() = getBool("sound", true)
        set(value) = setBool("sound", value)

    var music: Boolean
        get() = getBool("music", true)
        set(value) = setBool("music", value)

    var dialog: Boolean
        get() = getBool("dialog", true)
        set(value) = setBool("dialog", value)

    /** AI difficulty index: 0 = Easy, 1 = Normal, 2 = Hard (matches SWF `aidifficulty`). */
    var aiDifficulty: Int
        get() = getInt("aidifficulty", 1)
        set(value) = setInt("aidifficulty", value)

    // ---- campaign progress ---------------------------------------------------

    /** Highest unlocked campaign level index (SWF `campaign_complete`); beating level N sets N+1. */
    var campaignComplete: Int
        get() = getInt("campaign_complete", 0)
        set(value) = setInt("campaign_complete", value)

    /** Currently selected campaign level (SWF `campaign_level`). */
    var campaignLevel: Int
        get() = getInt("campaign_level", 0)
        set(value) = setInt("campaign_level", value)

    /** Highest unlocked bootcamp level index (SWF `bootcamp_complete`); beating level N sets N+1. */
    var bootcampComplete: Int
        get() = getInt("bootcamp_complete", 0)
        set(value) = setInt("bootcamp_complete", value)

    /** Currently selected bootcamp level (SWF `bootcamp_level`). */
    var bootcampLevel: Int
        get() = getInt("bootcamp_level", 0)
        set(value) = setInt("bootcamp_level", value)

    /** Medal earned for a level, keyed by map URL (SWF `<mapURL>_medal`). */
    fun medalFor(mapUrl: String): String = getString("${mapUrl}_medal") ?: "none"

    /** Upgrades [mapUrl]'s medal to [medal] (gold > silver > bronze > none); never downgrades. */
    fun setMedal(mapUrl: String, medal: String) {
        val rank = { m: String -> when (m) { "gold" -> 3; "silver" -> 2; "bronze" -> 1; else -> 0 } }
        if (rank(medal) > rank(medalFor(mapUrl))) setString("${mapUrl}_medal", medal)
    }

    // ---- the saved game -------------------------------------------------------

    /** The serialized save XML, or null when no game is saved (SWF `saveGame`). */
    var saveGame: String?
        get() = getString("saveGame")
        set(value) = setString("saveGame", value)

    /** Clears every persisted value (SWF `OptionsMenu.clearConfirm` → storage.clear). */
    fun clearAll() {
        store.clear()
        flush()
    }
}

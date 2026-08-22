package cn.enaium.battalion.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the game's static data from JSON documents and rebuilds the same [XmlNode] trees the
 * XML loader produced, so every consumer (TileManager, UnitManager, GameMap, ScriptManager,
 * the factories, Dialogue, ...) keeps working unchanged.
 *
 * The JSON layout is a direct serialization of [XmlNode]: every element is
 * `{"name": "...", "attrs": {...}, "children": [...], "text": "..."}`. [text] carries the raw
 * element text exactly as the original XML parser accumulated it (whitespace included), so a
 * JSON document round-trips to an [XmlNode] identical to the one parsed from the source XML.
 *
 * This is the modding seam: mods ship JSON files in the same shape and the whole data layer
 * (units, terrain, modifiers, ballistics, campaigns, maps) loads from them.
 */
object JsonXml {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses [text] (a single `{"name", ...}` object) into an [XmlNode], or null on failure. */
    fun parseNodeOrNull(text: String): XmlNode? = try {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        toNode(root)
    } catch (t: Throwable) {
        null
    }

    private fun toNode(obj: JsonObject): XmlNode {
        val name = (obj["name"] as? JsonPrimitive)?.content ?: ""
        val attrs = (obj["attrs"] as? JsonObject)?.let { o ->
            o.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }
        } ?: emptyMap()
        val children = (obj["children"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { toNode(it) }
        } ?: emptyList()
        val text = (obj["text"] as? JsonPrimitive)?.content ?: ""
        return XmlNode(name, attrs, children, text)
    }

    /** Serializes an [XmlNode] tree back to the JSON document format (for tooling / saving). */
    @Suppress("unused")
    fun toJsonString(node: XmlNode): String = buildString {
        append('{')
        append("\"name\":").append(Json.encodeToString(node.name))
        append(",\"attrs\":{")
        node.attributes.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append(Json.encodeToString(k)).append(':').append(Json.encodeToString(v))
        }
        append("},\"children\":[")
        node.children.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append(toJsonString(c))
        }
        append("],\"text\":").append(Json.encodeToString(node.text))
        append('}')
    }
}

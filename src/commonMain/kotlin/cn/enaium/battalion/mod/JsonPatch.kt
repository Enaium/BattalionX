package cn.enaium.battalion.mod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON Patch (RFC 6902) over `kotlinx.serialization` [JsonElement]s.
 *
 * Mods never replace a game document: they ship a patch that is applied on top of it, so the
 * result is always "base data + patches" (last loaded mod wins on conflicts, exactly like a
 * `replace` sequence). `test` operations make conflicts visible: a patch that expects a value
 * another mod already changed fails with the failing operation's index and path.
 *
 * One deliberate extension: `add` with the root path (`""` or `"/"`) replaces the whole
 * document, which is how a mod creates a document that did not exist in the base game.
 */
object JsonPatch {

    /** Raised when an operation cannot be applied (missing path, failed `test`, bad pointer). */
    class PatchException(message: String) : Exception(message)

    /** Applies every operation of [patch] in order and returns the patched document. */
    fun apply(document: JsonElement, patch: JsonArray): JsonElement = apply(document, patch.toList())

    /** Applies every operation of [patch] in order and returns the patched document. */
    fun apply(document: JsonElement, patch: List<JsonElement>): JsonElement {
        var current = document
        for ((index, op) in patch.withIndex()) {
            val obj = op as? JsonObject
                ?: throw PatchException("operation $index is not a JSON object")
            current = try {
                applyOp(current, obj)
            } catch (t: PatchException) {
                throw PatchException("operation $index (${obj["op"]?.jsonPrimitive?.content} " +
                    "${obj["path"]?.jsonPrimitive?.content}): ${t.message}")
            }
        }
        return current
    }

    private fun applyOp(document: JsonElement, op: JsonObject): JsonElement {
        val name = op["op"]?.jsonPrimitive?.content
            ?: throw PatchException("missing \"op\"")
        val path = op["path"]?.jsonPrimitive?.content
            ?: throw PatchException("missing \"path\"")

        // Root document operations ("add" also allows creating a document from scratch).
        if (path.isEmpty() || path == "/") {
            return when (name) {
                "add", "replace" -> op["value"] ?: throw PatchException("missing \"value\"")
                "remove" -> throw PatchException("cannot remove the document root")
                "test" -> {
                    val expected = op["value"] ?: throw PatchException("missing \"value\"")
                    if (document != expected) throw PatchException("test failed: root differs")
                    document
                }
                else -> moveOrCopy(document, name, op)
            }
        }

        val tokens = pointerTokens(path)
        return when (name) {
            "add" -> addValue(document, tokens, op["value"] ?: throw PatchException("missing \"value\""))
            "replace" -> replaceValue(document, tokens, op["value"] ?: throw PatchException("missing \"value\""))
            "remove" -> removeValue(document, tokens)
            "test" -> {
                val expected = op["value"] ?: throw PatchException("missing \"value\"")
                val actual = find(document, tokens)
                if (actual != expected) throw PatchException("test failed: ${compact(actual)} != ${compact(expected)}")
                document
            }
            "move", "copy" -> moveOrCopy(document, name, op)
            else -> throw PatchException("unknown op \"$name\"")
        }
    }

    private fun moveOrCopy(document: JsonElement, name: String, op: JsonObject): JsonElement {
        val from = op["from"]?.jsonPrimitive?.content
            ?: throw PatchException("missing \"from\"")
        val path = op["path"]?.jsonPrimitive?.content ?: throw PatchException("missing \"path\"")
        val fromTokens = pointerTokens(from)
        val source = find(document, fromTokens)
        return when (name) {
            "copy" -> addValue(document, pointerTokens(path), source)
            else -> {
                val without = removeValue(document, fromTokens)
                addValue(without, pointerTokens(path), source)
            }
        }
    }

    // ---------------------------------------------------------------------
    // JSON Pointer
    // ---------------------------------------------------------------------

    /** Splits a JSON Pointer into its tokens, undoing the `~0`/`~1` escapes. */
    internal fun pointerTokens(pointer: String): List<String> {
        if (pointer.isEmpty() || pointer == "/") return emptyList()
        if (!pointer.startsWith("/")) throw PatchException("invalid pointer \"$pointer\"")
        return pointer.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
    }

    private fun index(token: String, size: Int, allowEnd: Boolean): Int {
        if (token == "-") {
            if (!allowEnd) throw PatchException("\"-\" is only valid when adding to an array")
            return size
        }
        val i = token.toIntOrNull() ?: throw PatchException("\"$token\" is not an array index")
        val limit = if (allowEnd) size else size - 1
        if (i < 0 || i > limit) throw PatchException("array index $i out of range (0..$limit)")
        return i
    }

    // ---------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------

    private fun find(document: JsonElement, tokens: List<String>): JsonElement {
        var current = document
        for (token in tokens) {
            current = when (current) {
                is JsonObject -> current[token] ?: throw PatchException("no such member \"/$token\"")
                is JsonArray -> current[index(token, current.size, allowEnd = false)]
                else -> throw PatchException("cannot descend into a value at \"/$token\"")
            }
        }
        return current
    }

    /**
     * Rebuilds [node] with [tokens] pointing at the leaf, where [transform] produces the new
     * leaf from its parent ([JsonObject] or [JsonArray]) and the last token. The path from the
     * root down to the leaf is copied, so the returned document is the whole tree.
     */
    private fun mutate(
        node: JsonElement,
        tokens: List<String>,
        transform: (parent: JsonElement, token: String) -> JsonElement,
    ): JsonElement {
        val token = tokens.first()
        if (tokens.size == 1) return transform(node, token)
        val arrayIndex = if (node is JsonArray) index(token, node.size, allowEnd = false) else -1
        val child = when (node) {
            is JsonObject -> node[token] ?: throw PatchException("no such member \"/$token\"")
            is JsonArray -> node[arrayIndex]
            else -> throw PatchException("cannot descend into a value at \"/$token\"")
        }
        val newChild = mutate(child, tokens.drop(1), transform)
        return when (node) {
            is JsonObject -> JsonObject(node.toMutableMap().apply { put(token, newChild) })
            is JsonArray -> buildJsonArray {
                for ((i, element) in node.withIndex()) add(if (i == arrayIndex) newChild else element)
            }
            else -> node
        }
    }

    private fun addValue(document: JsonElement, tokens: List<String>, value: JsonElement): JsonElement {
        if (tokens.isEmpty()) return value
        return mutate(document, tokens) { parent, token ->
            when (parent) {
                is JsonObject -> JsonObject(parent.toMutableMap().apply { put(token, value) })
                is JsonArray -> {
                    val at = index(token, parent.size, allowEnd = true)
                    buildJsonArray {
                        for ((i, element) in parent.withIndex()) {
                            if (i == at) add(value)
                            add(element)
                        }
                        if (at == parent.size) add(value)
                    }
                }
                else -> throw PatchException("cannot add to a value at \"/$token\"")
            }
        }
    }

    private fun replaceValue(document: JsonElement, tokens: List<String>, value: JsonElement): JsonElement {
        if (tokens.isEmpty()) return value
        return mutate(document, tokens) { parent, token ->
            when (parent) {
                is JsonObject -> {
                    if (!parent.containsKey(token)) throw PatchException("no such member \"/$token\"")
                    JsonObject(parent.toMutableMap().apply { put(token, value) })
                }
                is JsonArray -> {
                    val at = index(token, parent.size, allowEnd = false)
                    buildJsonArray {
                        for ((i, element) in parent.withIndex()) add(if (i == at) value else element)
                    }
                }
                else -> throw PatchException("cannot replace a value at \"/$token\"")
            }
        }
    }

    private fun removeValue(document: JsonElement, tokens: List<String>): JsonElement {
        if (tokens.isEmpty()) throw PatchException("cannot remove the document root")
        return mutate(document, tokens) { parent, token ->
            when (parent) {
                is JsonObject -> {
                    if (!parent.containsKey(token)) throw PatchException("no such member \"/$token\"")
                    JsonObject(parent.toMutableMap().apply { remove(token) })
                }
                is JsonArray -> {
                    val at = index(token, parent.size, allowEnd = false)
                    buildJsonArray {
                        for ((i, element) in parent.withIndex()) if (i != at) add(element)
                    }
                }
                else -> throw PatchException("cannot remove a value at \"/$token\"")
            }
        }
    }

    /** A one-line, bounded rendering of [element] for error messages. */
    private fun compact(element: JsonElement): String {
        val text = element.toString()
        return if (text.length <= 60) text else text.take(57) + "..."
    }

    /** Convenience for building a patch in code (tests, generated patches). */
    fun op(name: String, path: String, value: JsonElement? = null, from: String? = null): JsonObject =
        buildJsonObject {
            put("op", JsonPrimitive(name))
            put("path", JsonPrimitive(path))
            if (value != null) put("value", value)
            if (from != null) put("from", JsonPrimitive(from))
        }

    /** Convenience: an empty document a patch can build on. */
    val emptyDocument: JsonElement = JsonObject(emptyMap())

    /** Convenience: a JSON null (used by callers that need an explicit value). */
    val nullValue: JsonElement = JsonNull
}

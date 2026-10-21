package cn.enaium.battalion

import cn.enaium.battalion.mod.JsonPatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards [JsonPatch] — the RFC 6902 engine every mod patch goes through. Mods rely on the
 * last-write-wins ordering and on `test` failing loudly when another mod already changed the
 * value, so both behaviours are pinned here.
 */
class JsonPatchTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun patch(text: String): JsonArray = json(text).jsonArray

    @Test
    fun `add replaces or creates object members and inserts into arrays`() {
        val doc = json("""{"items":{"iron_sword":{"damage":10}},"tags":["a","c"]}""")
        val patched = JsonPatch.apply(
            doc,
            patch(
                """[
                  {"op":"add","path":"/items/iron_sword/damage","value":15},
                  {"op":"add","path":"/items/crystal_sword","value":{"damage":30}},
                  {"op":"add","path":"/tags/1","value":"b"},
                  {"op":"add","path":"/tags/-","value":"d"}
                ]""",
            ),
        )
        assertEquals(15, patched.jsonObject["items"]!!.jsonObject["iron_sword"]!!
            .jsonObject["damage"]!!.jsonPrimitive.content.toInt())
        assertEquals(30, patched.jsonObject["items"]!!.jsonObject["crystal_sword"]!!
            .jsonObject["damage"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("a", "b", "c", "d"), patched.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `remove, replace, move and copy follow the RFC`() {
        val doc = json("""{"a":{"x":1,"y":2},"list":[10,20,30]}""")
        val patched = JsonPatch.apply(
            doc,
            patch(
                """[
                  {"op":"replace","path":"/a/x","value":9},
                  {"op":"remove","path":"/a/y"},
                  {"op":"copy","from":"/a/x","path":"/a/copy"},
                  {"op":"move","from":"/list/0","path":"/a/moved"}
                ]""",
            ),
        )
        assertEquals(9, patched.jsonObject["a"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
        assertTrue("y" !in patched.jsonObject["a"]!!.jsonObject)
        assertEquals(9, patched.jsonObject["a"]!!.jsonObject["copy"]!!.jsonPrimitive.content.toInt())
        // /list/0 held 10, so the moved value is 10 (not the replaced /a/x).
        assertEquals(10, patched.jsonObject["a"]!!.jsonObject["moved"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf(20, 30), patched.jsonObject["list"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
    }

    @Test
    fun `a failing test reports the operation and leaves nothing patched`() {
        val doc = json("""{"items":{"iron_sword":{"damage":15}}}""")
        val error = assertFailsWith<JsonPatch.PatchException> {
            JsonPatch.apply(
                doc,
                patch(
                    """[
                      {"op":"test","path":"/items/iron_sword/damage","value":10},
                      {"op":"replace","path":"/items/iron_sword/damage","value":20}
                    ]""",
                ),
            )
        }
        assertTrue(error.message!!.contains("operation 0"), "message names the op: ${error.message}")
        assertTrue(error.message!!.contains("/items/iron_sword/damage"), "message names the path")
    }

    @Test
    fun `later mods win on the same path`() {
        val doc = json("""{"items":{"iron_sword":{"damage":10}}}""")
        val modA = patch("""[{"op":"replace","path":"/items/iron_sword/damage","value":20}]""")
        val modB = patch("""[{"op":"replace","path":"/items/iron_sword/damage","value":30}]""")
        val patched = JsonPatch.apply(JsonPatch.apply(doc, modA), modB)
        assertEquals(30, patched.jsonObject["items"]!!.jsonObject["iron_sword"]!!
            .jsonObject["damage"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a mod can create a document with a root add`() {
        val created = JsonPatch.apply(
            JsonPatch.emptyDocument,
            patch("""[{"op":"add","path":"/","value":{"name":"Mod Map","rows":2,"cols":2}}]"""),
        )
        assertEquals("Mod Map", created.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid pointers and missing paths fail loudly`() {
        val doc = json("""{"a":1}""")
        assertFailsWith<JsonPatch.PatchException> {
            JsonPatch.apply(doc, patch("""[{"op":"remove","path":"/b"}]"""))
        }
        assertFailsWith<JsonPatch.PatchException> {
            JsonPatch.apply(doc, patch("""[{"op":"add","path":"a","value":1}]"""))
        }
        assertFailsWith<JsonPatch.PatchException> {
            JsonPatch.apply(doc, patch("""[{"op":"nope","path":"/a"}]"""))
        }
    }

    @Test
    fun `pointer escapes are decoded`() {
        val doc = json("""{"a/b":{"c~d":1}}""")
        val patched = JsonPatch.apply(doc, patch("""[{"op":"replace","path":"/a~1b/c~0d","value":2}]"""))
        assertEquals(2, (patched as JsonObject)["a/b"]!!.jsonObject["c~d"]!!.jsonPrimitive.content.toInt())
    }
}

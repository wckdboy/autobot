package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPointerTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    // RFC 6901 section 5 example document.
    private val rfc = j("""{"foo":["bar","baz"],"":0,"a/b":1,"c%d":2,"e^f":3,"g|h":4,"i\\j":5,"k\"l":6," ":7,"m~n":8}""")

    @Test
    fun rfc6901Examples() {
        assertEquals(rfc, JsonPointer.get(rfc, ""))
        assertEquals(j("""["bar","baz"]"""), JsonPointer.get(rfc, "/foo"))
        assertEquals(JsonPrimitive("bar"), JsonPointer.get(rfc, "/foo/0"))
        assertEquals(JsonPrimitive(1), JsonPointer.get(rfc, "/a~1b"))
        assertEquals(JsonPrimitive(2), JsonPointer.get(rfc, "/c%d"))
        assertEquals(JsonPrimitive(5), JsonPointer.get(rfc, "/i\\j"))
        assertEquals(JsonPrimitive(6), JsonPointer.get(rfc, "/k\"l"))
        assertEquals(JsonPrimitive(7), JsonPointer.get(rfc, "/ "))
        assertEquals(JsonPrimitive(8), JsonPointer.get(rfc, "/m~0n"))
    }

    @Test
    fun slashAloneIsTheRootInA2ui() {
        assertEquals(rfc, JsonPointer.get(rfc, "/"))
        assertEquals(emptyList<String>(), JsonPointer.parse("/"))
    }

    @Test
    fun missingValuesAreUndefined() {
        assertNull(JsonPointer.get(rfc, "/nope"))
        assertNull(JsonPointer.get(rfc, "/foo/2"))
        assertNull(JsonPointer.get(rfc, "/foo/01"))
        assertNull(JsonPointer.get(rfc, "/foo/-"))
        assertNull(JsonPointer.get(rfc, "/foo/0/deeper"))
    }

    @Test
    fun badPointersAreRejected() {
        assertThrows(A2uiPointerException::class.java) { JsonPointer.parse("foo") }
        assertThrows(A2uiPointerException::class.java) { JsonPointer.parse("/a~2b") }
        assertThrows(A2uiPointerException::class.java) { JsonPointer.parse("/a~") }
        assertFalse(JsonPointer.isValid("/x~"))
        assertTrue(JsonPointer.isValid("/x~0~1"))
    }

    @Test
    fun formatEscapes() {
        assertEquals("/a~1b/m~0n", JsonPointer.format(listOf("a/b", "m~n")))
        assertEquals("", JsonPointer.format(emptyList()))
        assertEquals(listOf("a/b", "m~n"), JsonPointer.parse("/a~1b/m~0n"))
    }

    @Test
    fun relativePathsResolveAgainstTheScope() {
        assertEquals("/users/0/name", JsonPointer.resolve("/users/0", "name"))
        assertEquals("/users/0/a/b", JsonPointer.resolve("/users/0/", "a/b"))
        assertEquals("/company", JsonPointer.resolve("/users/0", "/company"))
        assertEquals("/name", JsonPointer.resolve("", "name"))
        assertEquals("/users/0", JsonPointer.resolve("/users/0", "."))
        assertEquals("", JsonPointer.resolve("/users/0", "/"))
    }

    @Test
    fun setUpsertsAndCreatesContainers() {
        var doc: JsonElement = j("{}")
        doc = JsonPointer.set(doc, "/user/name", JsonPrimitive("Ada"))
        assertEquals(j("""{"user":{"name":"Ada"}}"""), doc)
        doc = JsonPointer.set(doc, "/user/name", JsonPrimitive("Grace"))
        assertEquals(j("""{"user":{"name":"Grace"}}"""), doc)
        doc = JsonPointer.set(doc, "/items/0/title", JsonPrimitive("first"))
        assertEquals(j("""{"user":{"name":"Grace"},"items":[{"title":"first"}]}"""), doc)
        doc = JsonPointer.set(doc, "/items/-", JsonPrimitive("second"))
        assertEquals(j("""[{"title":"first"},"second"]"""), JsonPointer.get(doc, "/items"))
        doc = JsonPointer.set(doc, "/items/3", JsonPrimitive(true))
        assertEquals(j("""[{"title":"first"},"second",null,true]"""), JsonPointer.get(doc, "/items"))
    }

    @Test
    fun nullDeletes() {
        var doc = j("""{"a":{"b":1,"c":2},"list":[1,2,3]}""")
        doc = JsonPointer.set(doc, "/a/b", null)
        assertEquals(j("""{"a":{"c":2},"list":[1,2,3]}"""), doc)
        doc = JsonPointer.set(doc, "/list/1", null)
        assertEquals(j("[1,null,3]"), JsonPointer.get(doc, "/list"))
        // Deleting what is not there changes nothing and creates nothing.
        assertEquals(doc, JsonPointer.set(doc, "/x/y/z", null))
        assertEquals(doc, JsonPointer.set(doc, "/list/9", null))
    }

    @Test
    fun rootWritesReplaceTheModel() {
        assertEquals(j("""{"x":1}"""), JsonPointer.set(j("""{"a":1}"""), "/", j("""{"x":1}""")))
        assertEquals(j("{}"), JsonPointer.set(j("""{"a":1}"""), "", null))
    }

    @Test
    fun writingThroughAPrimitiveFails() {
        val doc = j("""{"name":"Ada","list":[1]}""")
        assertThrows(A2uiPointerException::class.java) { JsonPointer.set(doc, "/name/first", JsonPrimitive("x")) }
        assertThrows(A2uiPointerException::class.java) { JsonPointer.set(doc, "/list/x", JsonPrimitive(1)) }
        assertThrows(A2uiPointerException::class.java) { JsonPointer.set(doc, "/list/10001", JsonPrimitive(1)) }
        // The input is never modified.
        assertEquals(j("""{"name":"Ada","list":[1]}"""), doc)
    }
}

package dev.wckdboy.autobot.shared.a2ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASIC = A2uiCatalog.BASIC_CATALOG_ID

/** Broken or hostile agent output is rejected with an error, never a crash, hang or unbounded allocation. */
class A2uiHostileInputTest {
    private val host = A2uiHost()

    private fun create(id: String = "s", components: String = "", data: String = "") =
        """{"version":"v1.0","createSurface":{"surfaceId":"$id","catalogId":"$BASIC"${if (components.isNotEmpty()) ",\"components\":[$components]" else ""}${if (data.isNotEmpty()) ",\"dataModel\":$data" else ""}}}"""

    private fun timed(block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test
    fun deepNestingIsRejectedBeforeParsing() = timed {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        val e = host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","value":$deep}}""")
        assertTrue(e.single().message, e.single().message.contains("nesting"))
        val deepObject = "{\"a\":".repeat(5_000) + "1" + "}".repeat(5_000)
        assertTrue(host.receive(deepObject).single().message.contains("nesting"))
        // Deep but within the limit: fine.
        host.receive(create())
        val ok = "{\"a\":".repeat(40) + "1" + "}".repeat(40)
        assertEquals(emptyList<A2uiError>(), host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","value":$ok}}"""))
    }

    @Test
    fun hugeArraysStringsAndStreamsAreRefused() = timed {
        host.receive(create())
        val bigArray = (1..6_000).joinToString(",", "[", "]")
        assertTrue(host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/a","value":$bigArray}}""").single().message.contains("6000 items"))
        val longString = "x".repeat(200_000)
        assertTrue(host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/a","value":"$longString"}}""").single().message.contains("characters"))
        val stream = "x".repeat(3_000_000)
        assertTrue(host.receive(stream).single().message.contains("stream"))
        val many = (1..600).joinToString("\n") { """{"version":"v1.0","deleteSurface":{"surfaceId":"nope$it"}}""" }
        assertTrue(host.receive(many).single().message.contains("600 messages"))
        assertEquals("{}", host.surface("s")!!.dataModelJson())
    }

    @Test
    fun dataModelGrowthIsBounded() = timed {
        val small = A2uiHost(listOf(A2uiCatalog.basic), A2uiLimits(maxDataModelChars = 1_000))
        small.receive(create())
        val chunk = "y".repeat(400)
        assertEquals(emptyList<A2uiError>(), small.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/a","value":"$chunk"}}"""))
        assertEquals(emptyList<A2uiError>(), small.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/b","value":"$chunk"}}"""))
        assertTrue(small.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/c","value":"$chunk"}}""").single().message.contains("limit"))
        assertFalse(small.surface("s")!!.dataModelJson().contains("\"c\""))
        assertFalse(small.setString("s", "/d", chunk))
    }

    @Test
    fun unknownComponentsAndGarbage() {
        assertTrue(host.receive("not json at all").single().message.contains("not valid JSON"))
        assertTrue(host.receive("42").single().message.contains("JSON object"))
        assertTrue(host.receive("""{"version":"v1.0"}""").single().message.contains("needs one of"))
        assertTrue(host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"a"},"deleteSurface":{"surfaceId":"a"}}""").single().message.contains("exactly one"))
        val e = host.receive(create(components = """{"id":"root","component":"<script>alert(1)</script>","text":"x"}"""))
        assertEquals(A2uiErrorCode.VALIDATION_FAILED, e.single().code)
        assertTrue(host.surfaceIds.isEmpty())
        // A component named after the reserved Surface container.
        assertFalse(host.receive(create(components = """{"id":"root","component":"Surface","child":"root"}""")).isEmpty())
    }

    @Test
    fun badPointersAreErrorsNotCrashes() {
        host.receive(create(data = """{"name":"Ada","list":[1,2]}"""))
        assertEquals("/path", host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"name","value":1}}""").single().path)
        assertTrue(host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/name/first","value":1}}""").single().message.contains("string"))
        assertTrue(host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/list/99999","value":1}}""").single().message.contains("limit"))
        assertTrue(host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/a~9","value":1}}""").single().message.contains("~"))
        assertFalse(host.setString("s", "no-slash", "x"))
        assertFalse(host.setString("nope", "/x", "x"))
        assertEquals("""{"name":"Ada","list":[1,2]}""", host.surface("s")!!.dataModelJson())
    }

    @Test
    fun templateExplosionIsCapped() = timed {
        val limited = A2uiHost(listOf(A2uiCatalog.basic), A2uiLimits(maxRenderNodes = 500))
        val items = (1..2_000).joinToString(",", "[", "]") { """{"n":$it}""" }
        val errors = limited.receive(
            create(
                components = """{"id":"root","component":"List","children":{"path":"/items","componentId":"row"}},
                    {"id":"row","component":"Row","children":{"path":"/items","componentId":"cell"}},
                    {"id":"cell","component":"Text","text":{"@path":"n"}}""".replace("\n", ""),
                data = """{"items":$items}""",
            ),
        )
        assertTrue(errors.toString(), errors.any { it.message.contains("more than 500") })
        assertNotNull(limited.render("s"))
    }

    @Test
    fun cyclesRenderAsPlaceholders() {
        host.receive(create(components = """{"id":"root","component":"Card","child":"a"},{"id":"a","component":"Card","child":"root"}"""))
        val root = host.render("s")!!
        assertTrue(root.node("child")!!.node("child")!!.isPlaceholder)
        assertTrue(host.surface("s")!!.renderErrors.any { it.code == A2uiErrorCode.INTEGRITY_ERROR })
    }

    @Test
    fun selfReferencingTemplatesStop() = timed {
        host.receive(
            create(
                components = """{"id":"root","component":"List","children":{"path":"/items","componentId":"root"}}""",
                data = """{"items":[{"items":[{"items":[]}]}]}""",
            ),
        )
        assertTrue(host.render("s")!!.children.single().isPlaceholder)
    }

    @Test
    fun deeplyNestedFunctionCallsAreBounded() = timed {
        var expr = """{"@path":"/flag"}"""
        repeat(20) { expr = """{"@call":"not","args":{"value":$expr}}""" }
        val errors = host.receive(create(components = """{"id":"root","component":"CheckBox","label":"x","value":$expr}""", data = """{"flag":true}"""))
        // Within the JSON depth limit this validates and evaluates without blowing up.
        assertEquals(emptyList<A2uiError>(), errors)
        assertEquals(true, host.render("s")!!.bool("value"))
        var tooDeep = """{"@path":"/flag"}"""
        repeat(30) { tooDeep = """{"@call":"not","args":{"value":$tooDeep}}""" }
        assertTrue(host.receive(create("t", components = """{"id":"root","component":"CheckBox","label":"x","value":$tooDeep}""")).single().message.contains("nesting"))
    }

    @Test
    fun surfaceAndComponentCountsAreBounded() {
        val limited = A2uiHost(listOf(A2uiCatalog.basic), A2uiLimits(maxSurfaces = 2, maxComponentsPerSurface = 3))
        assertEquals(emptyList<A2uiError>(), limited.receive(create("a") + "\n" + create("b")))
        assertTrue(limited.receive(create("c")).single().message.contains("too many surfaces"))
        val four = (1..4).joinToString(",") { """{"id":"t$it","component":"Text","text":"x"}""" }
        assertTrue(limited.receive("""{"version":"v1.0","updateComponents":{"surfaceId":"a","components":[$four]}}""").single().message.contains("more than 3"))
    }

    @Test
    fun formatStringBombsAreBounded() = timed {
        val nested = "${'$'}{".repeat(200) + "/x" + "}".repeat(200)
        host.receive(create(components = """{"id":"root","component":"Text","text":{"@call":"formatString","args":{"value":{"@path":"/t"}}}}""", data = """{"t":"$nested","x":"y"}"""))
        assertEquals("", host.render("s")!!.string("text") ?: "")
        assertTrue(host.surface("s")!!.renderErrors.single().message.contains("nested too deeply"))
        // A template read from the data model can only read data and call pure functions.
        host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/t","value":"${'$'}{openUrl(url: 'https://evil.example')}"}}""")
        assertEquals("", host.render("s")!!.string("text") ?: "")
        assertTrue(host.surface("s")!!.renderErrors.single().message.contains("user action"))
    }

    @Test
    fun regexDenialOfServiceIsRefused() = timed {
        val evil = create(components = """{"id":"root","component":"TextField","label":"x","value":{"@path":"/v"},"checks":[{"condition":{"@call":"regex","args":{"value":{"@path":"/v"},"pattern":"^(a|aa)+$"}}}]}""")
        assertEquals(A2uiErrorCode.EXPRESSION_ERROR, host.receive(evil).single().code)
        // A slow pattern without nested groups is stopped by the read budget.
        val slow = "^\\\\d*\\\\d*\\\\d*\\\\d*\\\\d*\\\\d*\\\\d*\\\\d*$"
        val errors = host.receive(
            create(
                "slow",
                components = """{"id":"root","component":"TextField","label":"x","value":{"@path":"/v"},"checks":[{"condition":{"@call":"regex","args":{"value":{"@path":"/v"},"pattern":"$slow"}}}]}""",
                data = """{"v":"${"1".repeat(60)}a"}""",
            ),
        )
        assertTrue(errors.toString(), errors.single().message.contains("too long"))
        assertFalse(host.render("slow")!!.isValid)
    }
}

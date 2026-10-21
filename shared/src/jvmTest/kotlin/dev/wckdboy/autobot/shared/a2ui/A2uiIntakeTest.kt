package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASIC = A2uiCatalog.BASIC_CATALOG_ID

/** The lenient front door of show_ui ([A2uiIntake]), its feedback ([A2uiFeedback]), and that the validator stays strict. */
class A2uiIntakeTest {
    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    private val components = """[{"id":"root","component":"Column","children":["t","go"]},{"id":"t","component":"Text","text":"Hi"},""" +
        """{"id":"go","component":"Button","child":"go_t","action":{"event":{"name":"ok"}}},{"id":"go_t","component":"Text","text":"OK"}]"""
    private val good = """[{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"$BASIC","components":$components}}]"""

    /** Accepts [input] and validates the result strictly; returns (intake, report). */
    private fun run(input: JsonElement, prior: Map<String, Set<String>> = emptyMap()): Pair<A2uiIntakeResult, A2uiReport?> {
        val intake = A2uiIntake.accept(input, listOf(A2uiCatalog.basic), A2uiLimits.standard, prior)
        return intake to intake.text?.let { A2uiValidator().validate(it) }
    }

    private fun runText(text: String) = run(JsonPrimitive(text))

    private fun components(intake: A2uiIntakeResult, message: Int = 0): List<JsonObject> {
        val m = intake.messages!![message].jsonObject
        val kind = A2uiMessage.kindOf(m)!!
        return m[kind]!!.jsonObject["components"]!!.jsonArray.map { it.jsonObject }
    }

    private fun byId(intake: A2uiIntakeResult, id: String) = components(intake).single { it["id"]!!.jsonPrimitive.content == id }

    @Test
    fun validInputPassesUnchangedWithoutNotes() {
        val (array, r1) = run(json(good))
        assertTrue(r1!!.isValid)
        assertEquals(emptyList<String>(), array.repairs)
        assertEquals(json(good), array.messages)
        val (text, r2) = runText(good)
        assertTrue(r2!!.isValid)
        assertEquals(emptyList<String>(), text.repairs)
        // JSONL and a code fence are read too, without notes (both are forms the spec allows).
        val jsonl = json(good).jsonArray.joinToString("\n") { it.toString() }
        assertTrue(runText("```json\n$jsonl\n```").second!!.isValid)
    }

    @Test
    fun aMissingCloserBeforeTheFinalBracketIsAdded() {
        val broken = good.removeSuffix("}]") + "]" // the outer message's '}' is missing
        assertNull(runCatching { json(broken) }.getOrNull())
        val (intake, report) = runText(broken)
        assertTrue(report!!.forModel(), report.isValid)
        assertEquals(listOf("the JSON was not closed: added 1 '}' missing before the final ']' (close every { and [ you open)"), intake.repairs)
    }

    @Test
    fun bracketsLeftOpenAtTheEndAreClosedAndExtraOnesDropped() {
        val (open, r1) = runText(good.removeSuffix("}}]"))
        assertTrue(r1!!.isValid)
        assertTrue(open.repairs.single(), open.repairs.single().contains("added 2 '}' and 1 ']' missing at the end"))
        val (extra, r2) = runText("$good]")
        assertTrue(r2!!.isValid)
        assertTrue(extra.repairs.single(), extra.repairs.single().contains("removed 1 extra ']' at the end"))
    }

    @Test
    fun bracketsAreNotGuessedInTheMiddle() {
        // A closer missing in the middle: closing at the end does not make it parse, so it is explained instead.
        val broken = good.replace("\"Hi\"}", "\"Hi\"")
        assertNull(A2uiIntake.closeBrackets("[1}, 2]"))
        val (intake, report) = runText(broken)
        assertNull(report)
        val problem = intake.problem!!
        assertTrue(problem, problem.startsWith("messages is not valid JSON. JSON syntax error at char "))
        assertTrue(problem, problem.contains("expected a \"quoted\" key here (is a '}' missing before it?)"))
        assertTrue(problem, problem.contains("⟪here⟫"))
        assertTrue(problem, problem.contains("1 '}' is missing"))
    }

    @Test
    fun anOpenStringIsExplained() {
        val (intake, _) = runText("""[{"version":"v1.0","createSurface":{"surfaceId":"s""")
        assertNull(intake.text)
        assertTrue(intake.problem!!, intake.problem!!.contains("a string is not closed: the one starting at char 48"))
        assertTrue(intake.problem!!.contains("escape quotes inside strings as \\\""))
    }

    @Test
    fun singleQuotesAreExplained() {
        val (intake, _) = runText("[{'version':'v1.0'}]")
        assertTrue(intake.problem!!, intake.problem!!.contains("use double quotes (\") for keys and strings"))
    }

    @Test
    fun jsonInsideAStringIsUnwrappedOnce() {
        val (intake, report) = run(JsonPrimitive(JsonPrimitive(good).toString()))
        assertTrue(report!!.isValid)
        assertEquals(listOf("messages was a string holding JSON text; send the array itself"), intake.repairs)
    }

    @Test
    fun oneMessageWithoutTheArrayIsAccepted() {
        val (intake, report) = run(json(good).jsonArray[0])
        assertTrue(report!!.isValid)
        assertEquals(1, intake.messages!!.size)
    }

    @Test
    fun bareComponentsAreWrappedInACreateSurface() {
        val (intake, report) = run(json(components))
        assertTrue(report!!.forModel(), report.isValid)
        val body = intake.messages!![0].jsonObject["createSurface"]!!.jsonObject
        assertEquals("ui", body["surfaceId"]!!.jsonPrimitive.content)
        assertEquals(BASIC, body["catalogId"]!!.jsonPrimitive.content)
        assertFalse(body.keys.any { it.startsWith("\u0000") })
        assertEquals(1, intake.repairs.size)
        assertTrue(intake.repairs[0].startsWith("the components were not in a message; wrapped them in"))
        // The generated id does not clash with a surface shown before.
        val (again, _) = run(json(components), prior = mapOf("ui" to setOf("root")))
        assertEquals("ui_2", again.messages!![0].jsonObject["createSurface"]!!.jsonObject["surfaceId"]!!.jsonPrimitive.content)
    }

    @Test
    fun oneComponentTreeIsWrappedHoistedAndRooted() {
        val tree = """{"component":"Card","child":{"component":"Column","children":[{"component":"Text","text":"A"},{"id":"b","component":"Text","text":"B"}]}}"""
        val (intake, report) = run(json(tree))
        assertTrue(report!!.forModel(), report.isValid)
        val ids = components(intake).map { it["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("root", "c_child", "c_child_1", "b"), ids)
        assertTrue(intake.repairs.joinToString(), intake.repairs.any { it.startsWith("children are ids, not objects") })
        assertTrue(intake.repairs.any { it.startsWith("no component had id \"root\"") })
    }

    @Test
    fun aCreateSurfaceBodyWithoutItsEnvelopeIsWrapped() {
        val body = """{"surfaceId":"s","catalogId":"$BASIC","components":$components}"""
        val (intake, report) = run(json("[$body]"))
        assertTrue(report!!.isValid)
        assertTrue(intake.repairs.single().startsWith("a createSurface body was sent without its message"))
    }

    @Test
    fun aMissingOrSloppyVersionBecomesV10() {
        for (v in listOf(null, "\"1.0\"", "\"v1\"", "1")) {
            val m = json(good).jsonArray[0].jsonObject.let { if (v == null) JsonObject(it - "version") else JsonObject(it + ("version" to json(v))) }
            val (intake, report) = run(JsonArray(listOf(m, m.let { JsonObject(it + ("createSurface" to JsonObject(it["createSurface"]!!.jsonObject + ("surfaceId" to JsonPrimitive("s2"))))) })))
            assertTrue("$v: ${report!!.forModel()}", report.isValid)
            assertEquals(listOf("set \"version\":\"v1.0\" on every message (×2)"), intake.repairs)
        }
        // Another version is a real mismatch: left for the validator to refuse.
        val (_, report) = run(json(good.replace("\"v1.0\"", "\"v0.9\"")))
        assertFalse(report!!.isValid)
    }

    @Test
    fun aMissingOrGarbledCatalogIdBecomesTheChatsCatalog() {
        val (missing, r1) = run(json(good.replace(""""catalogId":"$BASIC",""", "")))
        assertTrue(r1!!.isValid)
        assertEquals(listOf("createSurface had no catalogId; set it to the chat's catalog"), missing.repairs)
        val (garbled, r2) = run(json(good.replace(BASIC, "https://a-2ui.org/specification/v1_0/catalogs/basic/catalog.json")))
        assertTrue(r2!!.isValid)
        assertTrue(garbled.repairs.single(), garbled.repairs.single().startsWith("catalogId 'https://a-2ui.org/"))
        // With two catalogs there is no single right answer: nothing is changed.
        val other = A2uiCatalog.load(A2uiCatalog.basic.jsonText.replace(BASIC, "https://example.com/other.json")).catalog!!
        val two = A2uiIntake.accept(json(good.replace(BASIC, "x")), listOf(A2uiCatalog.basic, other))
        assertEquals(emptyList<String>(), two.repairs)
    }

    @Test
    fun aButtonLabelBecomesATextChild() {
        val (intake, report) = run(json(good.replace("\"child\":\"go_t\"", "\"child\":\"Book it!\"")))
        assertTrue(report!!.forModel(), report.isValid)
        assertEquals("go_label", byId(intake, "go")["child"]!!.jsonPrimitive.content)
        assertEquals("Book it!", byId(intake, "go_label")["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("a Button's child is the id of a Text: Button 'go''s child \"Book it!\" became a Text component"), intake.repairs)
        // A `label` instead of a child.
        val (labelled, r2) = run(json(good.replace("\"child\":\"go_t\"", "\"label\":\"Send\"")))
        assertTrue(r2!!.forModel(), r2.isValid)
        assertNull(byId(labelled, "go")["label"])
        assertEquals("Send", byId(labelled, "go_label")["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aMissingIdThatLooksLikeAnIdIsNotGuessed() {
        val (intake, report) = run(json(good.replace("\"child\":\"go_t\"", "\"child\":\"send_t\"")))
        assertEquals(emptyList<String>(), intake.repairs)
        assertFalse(report!!.isValid)
        val lines = A2uiFeedback.lines(report, intake.messages)
        assertTrue(lines.joinToString("\n"), lines.any {
            it.endsWith("component 'go' refers to 'send_t', which is not defined. Fix: add {\"id\":\"send_t\",\"component\":\"Text\",\"text\":\"…\"} to components (a Button's child is the id of a Text)")
        })
        assertTrue(A2uiIntake.looksLikeLabel("Confirm"))
        assertTrue(A2uiIntake.looksLikeLabel("ok now"))
        assertFalse(A2uiIntake.looksLikeLabel("go_t"))
        assertFalse(A2uiIntake.looksLikeLabel("submitLabel"))
    }

    @Test
    fun inlinedChildrenAreHoistedWithIds() {
        val inlined = good.replace("\"children\":[\"t\",\"go\"]", "\"children\":[{\"component\":\"Text\",\"text\":\"Inline\"},\"go\"]")
        val (intake, report) = run(json(inlined))
        assertTrue(report!!.forModel(), report.isValid)
        assertEquals(listOf("root_1", "go"), byId(intake, "root")["children"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Inline", byId(intake, "root_1")["text"]!!.jsonPrimitive.content)
        // An inlined child keeps its own id when it is free; Tabs' children are found too.
        val tabs = """[{"id":"root","component":"Tabs","tabs":[{"title":"A","child":{"id":"a","component":"Text","text":"A"}}]}]"""
        val (t, r2) = run(json(tabs))
        assertTrue(r2!!.forModel(), r2.isValid)
        assertEquals("a", components(t)[0]["tabs"]!!.jsonArray[0].jsonObject["child"]!!.jsonPrimitive.content)
    }

    @Test
    fun aChoicePickerStringValueBecomesAList() {
        val picker = """[{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"$BASIC","components":[
            {"id":"root","component":"Column","children":["p","q"]},
            {"id":"p","component":"ChoicePicker","options":[{"label":"A","value":"a"}],"value":"a"},
            {"id":"q","component":"ChoicePicker","options":[{"label":"A","value":"a"}],"value":{"@path":"/q"}}],
            "dataModel":{"q":""}}}]"""
        assertFalse(A2uiValidator().validate(picker).isValid)
        val (intake, report) = run(json(picker))
        assertTrue(report!!.forModel(), report.isValid)
        assertEquals(json("[\"a\"]"), byId(intake, "p")["value"])
        assertEquals(json("[]"), intake.messages!![0].jsonObject["createSurface"]!!.jsonObject["dataModel"]!!.jsonObject["q"])
        assertTrue(intake.repairs.contains("a ChoicePicker's value is a list of values; wrapped the string in [ ]"))
        assertTrue(intake.repairs.contains("a ChoicePicker's value is a list: its dataModel start value became []"))
    }

    @Test
    fun aDataModelInTheWrongPlaceMovesIntoCreateSurface() {
        val beside = json(good).jsonArray[0].jsonObject.let { JsonObject(it + ("dataModel" to json("{\"a\":1}"))) }
        val (m1, r1) = run(JsonArray(listOf(beside)))
        assertTrue(r1!!.forModel(), r1.isValid)
        assertEquals(json("{\"a\":1}"), m1.messages!![0].jsonObject["createSurface"]!!.jsonObject["dataModel"])
        assertEquals(listOf("dataModel belongs inside createSurface; moved it there"), m1.repairs)
        val inComponent = good.replace("\"text\":\"Hi\"}", "\"text\":\"Hi\",\"dataModel\":{\"b\":true}}")
        val (m2, r2) = run(json(inComponent))
        assertTrue(r2!!.forModel(), r2.isValid)
        assertEquals(json("{\"b\":true}"), m2.messages!![0].jsonObject["createSurface"]!!.jsonObject["dataModel"])
        assertNull(byId(m2, "t")["dataModel"])
    }

    @Test
    fun aCardWithChildrenGetsOneChild() {
        val card = """[{"id":"root","component":"Card","children":["a","b"]},{"id":"a","component":"Text","text":"A"},{"id":"b","component":"Text","text":"B"}]"""
        val (intake, report) = run(json(card))
        assertTrue(report!!.forModel(), report.isValid)
        assertEquals("root_column", byId(intake, "root")["child"]!!.jsonPrimitive.content)
        assertEquals(json("[\"a\",\"b\"]"), byId(intake, "root_column")["children"])
        val one = """[{"id":"root","component":"Card","children":[{"component":"Text","text":"A"}]}]"""
        val (i2, r2) = run(json(one))
        assertTrue(r2!!.forModel(), r2.isValid)
        assertEquals("root_child", byId(i2, "root")["child"]!!.jsonPrimitive.content)
    }

    @Test
    fun severalTopComponentsGetAColumnRoot() {
        val tops = good.replace("{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"t\",\"go\"]},", "")
        val (intake, report) = run(json(tops))
        assertTrue(report!!.forModel(), report.isValid)
        assertEquals(json("[\"t\",\"go\"]"), byId(intake, "root")["children"])
        assertEquals(listOf("no component had id \"root\"; added a Column \"root\" holding the top components 't', 'go'"), intake.repairs)
    }

    @Test
    fun updateComponentsKnowsTheIdsOfEarlierSurfaces() {
        val update = """[{"version":"v1.0","updateComponents":{"surfaceId":"s","components":[{"id":"go","component":"Button","child":"go_t","action":{"event":{"name":"ok"}}}]}}]"""
        val intake = A2uiIntake.accept(json(update), prior = mapOf("s" to setOf("root", "go", "go_t")))
        assertEquals(emptyList<String>(), intake.repairs)
        val label = A2uiIntake.accept(json(update.replace("go_t\"", "Go\"")), prior = mapOf("s" to setOf("root", "go", "go_t")))
        assertTrue(label.repairs.single().startsWith("a Button's child is the id of a Text"))
    }

    @Test
    fun inputsThatAreNoMessagesAreRefusedInWords() {
        assertEquals("messages must be a JSON array of A2UI messages, got integer", A2uiIntake.accept(json("3")).problem)
        assertEquals("messages is empty; send a JSON array of A2UI messages", A2uiIntake.accept(JsonPrimitive("  ")).problem)
        val deep = "[".repeat(200) + "]".repeat(200)
        assertTrue(A2uiIntake.accept(JsonPrimitive(deep)).problem!!.contains("nesting is deeper"))
        val small = A2uiLimits.standard.copy(maxStreamChars = 10)
        assertTrue(A2uiIntake.accept(JsonPrimitive(good), limits = small).problem!!.contains("the limit is 10"))
    }

    @Test
    fun hoistingStaysWithinTheComponentLimit() {
        val many = (1..50).joinToString(",") { """{"component":"Text","text":"$it"}""" }
        val tree = """[{"id":"root","component":"Column","children":[$many]}]"""
        val limited = A2uiLimits.standard.copy(maxComponentsPerSurface = 10)
        val intake = A2uiIntake.accept(json(tree), limits = limited)
        assertTrue(components(intake).size <= 10)
    }

    @Test
    fun repeatedErrorsAreSaidOnceAndComponentMessagesExplained() {
        // Two messages with the same problem (an unknown component type at the same path).
        val bad = """{"id":"root","component":"Txt","text":"x"}"""
        val stream = """[{"version":"v1.0","createSurface":{"surfaceId":"a","catalogId":"$BASIC","components":[$bad]}},""" +
            """{"version":"v1.0","createSurface":{"surfaceId":"b","catalogId":"$BASIC","components":[$bad]}}]"""
        val report = A2uiValidator().validate(stream)
        val lines = A2uiFeedback.lines(report, json(stream).jsonArray)
        assertTrue(report.errors.size > lines.size)
        assertTrue(lines.joinToString("\n"), lines.all { it.contains("[every message]") })
        // A component among messages.
        val mixed = """[${json(good).jsonArray[0]},{"id":"x","component":"Text","text":"stray"}]"""
        val (intake, r) = run(json(mixed))
        val fb = A2uiFeedback.lines(r!!, intake.messages)
        assertTrue(fb.joinToString("\n"), fb.any { it.contains("This looks like a component, not a message: put components in {\"version\":\"v1.0\",\"createSurface\"") })
    }

    @Test
    fun missingChildrenOfOneParentAreOneLine() {
        val stream = """[{"version":"v1.0","createSurface":{"surfaceId":"r","catalogId":"$BASIC","components":[{"id":"root","component":"Column","children":["yes","no","maybe"]}]}}]"""
        val report = A2uiValidator().validate(stream)
        assertEquals(3, report.errors.size)
        assertEquals(
            listOf(
                "INTEGRITY_ERROR [message 0] surface 'r' /components/0/children: component 'root' refers to 'yes', 'no', 'maybe', which are not defined. " +
                    "Fix: add a component for each of these ids to the same \"components\" list (naming a child does not create it)",
            ),
            A2uiFeedback.lines(report, json(stream).jsonArray),
        )
    }

    @Test
    fun feedbackIsCappedAtTenLines() {
        val comps = (1..30).joinToString(",") { """{"id":"c$it","component":"Nope$it"}""" }
        val report = A2uiValidator().validate("""[{"version":"v1.0","createSurface":{"surfaceId":"a","catalogId":"$BASIC","components":[$comps]}}]""")
        val lines = A2uiFeedback.lines(report, null)
        assertEquals(A2uiFeedback.MAX_LINES, lines.size)
        assertTrue(lines.last().startsWith("… and "))
    }

    /** Everything the intake repairs is still refused by the validator itself. */
    @Test
    fun theValidatorStaysStrict() {
        val v = A2uiValidator()
        val cases = mapOf(
            "unclosed" to good.removeSuffix("}]") + "]",
            "no version" to good.replace("\"version\":\"v1.0\",", ""),
            "no catalogId" to good.replace(""""catalogId":"$BASIC",""", ""),
            "garbled catalogId" to good.replace(BASIC, "https://a-2ui.org/x.json"),
            "label as child" to good.replace("\"child\":\"go_t\"", "\"child\":\"Book it\""),
            "inlined child" to good.replace("\"children\":[\"t\",\"go\"]", "\"children\":[{\"component\":\"Text\",\"text\":\"I\"},\"go\"]"),
            "bare components" to components,
        )
        for ((name, text) in cases) {
            assertFalse(name, v.validate(text).isValid)
            assertTrue(name, run(JsonPrimitive(text)).second!!.isValid)
        }
        assertTrue(v.validate(good).isValid)
    }

    @Test
    fun theToolTakesAnArrayAndReportsItsRepairs() {
        val tool = ShowUiTool()
        val declared = tool.schema().parameters["properties"]!!.jsonObject["messages"]!!.jsonObject
        assertEquals("array", declared["type"]!!.jsonPrimitive.content)
        val shown = tool.check(json(good.replace("\"child\":\"go_t\"", "\"child\":\"Book it!\"")))
        assertFalse(shown.content, shown.isError)
        assertTrue(shown.content, shown.content.contains("\nRepaired for you (do it this way next time):\n- a Button's child is the id of a Text"))
        assertNotNull(A2uiChat.stream(shown.meta))
        // The stored stream is the repaired one, and it validates on its own.
        assertTrue(A2uiValidator().validate(A2uiChat.stream(shown.meta)!!).isValid)
        val unreadable = tool.check(JsonPrimitive("[{\"version\":\"v1.0\",\"createSurface\":{\"surfaceId\":\"s"))
        assertTrue(unreadable.isError)
        assertTrue(unreadable.content, unreadable.content.startsWith("Error: the UI was not shown. Fix this and call show_ui again:\nmessages is not valid JSON. a string is not closed"))
        assertTrue(tool.check(JsonPrimitive(good)).let { !it.isError && "Repaired" !in it.content })
    }
}

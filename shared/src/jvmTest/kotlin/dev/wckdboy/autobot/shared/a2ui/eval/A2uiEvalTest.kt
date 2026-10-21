package dev.wckdboy.autobot.shared.a2ui.eval

import dev.wckdboy.autobot.shared.a2ui.A2uiCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASIC = A2uiCatalog.BASIC_CATALOG_ID

/** The eval's scoring and retry loop on canned model output (no network). */
class A2uiEvalTest {

    private val form = """
        [{"version":"v1.0","createSurface":{"surfaceId":"signup","catalogId":"$BASIC","components":[
          {"id":"root","component":"Column","children":["name","email","go"]},
          {"id":"name","component":"TextField","label":"Name","value":{"@path":"/form/name"},
           "checks":[{"condition":{"@call":"required","args":{"value":{"@path":"/form/name"}}},"message":"Name is required."}]},
          {"id":"email","component":"TextField","label":"Email","value":{"@path":"/form/email"},
           "checks":[{"condition":{"@call":"email","args":{"value":{"@path":"/form/email"}}},"message":"Enter a valid email."}]},
          {"id":"go","component":"Button","variant":"primary","child":"go_t",
           "action":{"event":{"name":"signup","context":{"form":{"@path":"/form"}}}}},
          {"id":"go_t","component":"Text","text":"Sign up"}],
          "dataModel":{"form":{"name":"","email":""}}}}]
    """.trimIndent()

    /** The same form with the email unbound, no checks and a button that sends nothing. */
    private val sloppy = """
        [{"version":"v1.0","createSurface":{"surfaceId":"signup","catalogId":"$BASIC","components":[
          {"id":"root","component":"Column","children":["name","email","go"]},
          {"id":"name","component":"TextField","label":"Name","value":{"@path":"/name"}},
          {"id":"email","component":"TextField","label":"Email","value":"you@example.com"},
          {"id":"go","component":"Button","child":"go_t","action":{"event":{"name":"signup"}}},
          {"id":"go_t","component":"Text","text":"Sign up"}]}}]
    """.trimIndent()

    private val list = """
        [{"version":"v1.0","createSurface":{"surfaceId":"hotels","catalogId":"$BASIC","components":[
          {"id":"root","component":"List","children":{"path":"/hotels","componentId":"row"}},
          {"id":"row","component":"Row","children":["n","pick"]},
          {"id":"n","component":"Text","text":{"@path":"name"}},
          {"id":"pick","component":"Button","child":"pick_t","action":{"event":{"name":"pick","context":{"hotel":{"@path":"name"}}}}},
          {"id":"pick_t","component":"Text","text":"Pick"}],
          "dataModel":{"hotels":[{"name":"Aster"},{"name":"Linden"}]}}}]
    """.trimIndent()

    private val signupExpect = A2uiExpect(
        components = listOf("TextField", "Button"),
        minInputs = 2,
        inputsBound = true,
        actionUsesInputs = true,
        checks = 2,
        checkFunctions = listOf("email", "required"),
        props = listOf(A2uiPropExpect("TextField", "label", equals = JsonPrimitive("Email"))),
    )

    @Test
    fun bundledSetIsWellFormed() {
        val set = A2uiEvalSet.bundled()
        assertTrue("about 30 tasks", set.tasks.size >= 30)
        assertEquals("ids are unique", set.tasks.size, set.tasks.map { it.id }.toSet().size)
        assertTrue(set.tasks.any { it.expect.noUi })
        for (t in set.tasks.filter { !it.expect.noUi }) {
            assertTrue("${t.id} checks something", A2uiExpectations.check(t.expect, form).isNotEmpty())
            for (c in t.expect.components) for (type in c.split('|')) assertTrue("${t.id}: $type", type in A2uiCatalog.basic.components)
        }
        val categories = set.tasks.map { it.category }.toSet()
        for (c in listOf("choice", "rsvp", "form-validation", "settings", "list-template", "date", "multi-step", "card")) assertTrue(c, c in categories)
    }

    @Test
    fun candidatePromptExampleIsValidAndMeetsAFormsExpectations() {
        val prompt = A2uiEvalTest::class.java.getResource("/a2ui/eval/prompt-candidate.txt")!!.readText()
        val example = prompt.substringAfter("Example: ").trim()
        val report = dev.wckdboy.autobot.shared.a2ui.A2uiValidator().validate(example)
        assertTrue(report.forModel(), report.isValid)
        val expect = A2uiExpect(
            components = listOf("TextField", "ChoicePicker", "Button"), inputsBound = true, actionUsesInputs = true, checks = 1,
            props = listOf(A2uiPropExpect("ChoicePicker", "displayStyle", equals = JsonPrimitive("chips"))),
        )
        assertTrue(A2uiExpectations.check(expect, example).all { it.pass })
        assertTrue("stays small for on-device models", prompt.length < 3_500)
    }

    @Test
    fun goodFormMeetsEveryExpectation() {
        val checks = A2uiExpectations.check(signupExpect, form)
        assertTrue(checks.filter { !it.pass }.toString(), checks.all { it.pass })
        assertEquals(9, checks.size)
    }

    @Test
    fun sloppyFormFailsTheRightChecks() {
        val failed = A2uiExpectations.check(signupExpect, sloppy).filter { !it.pass }.map { it.name }
        assertEquals(listOf("inputs bound", "action carries inputs", "checks >= 2", "check uses email", "check uses required"), failed)
    }

    @Test
    fun actionCoverageAcceptsParentsFormatStringsAndSendDataModel() {
        assertTrue(A2uiExpectations.covers("/form", "/form/name"))
        assertFalse(A2uiExpectations.covers("/form", "/formula"))
        assertEquals(listOf("/a", "/b/c"), A2uiExpectations.referencedPaths(Json.parse("""{"x":{"@path":"/a"},"y":{"@call":"formatString","args":{"value":"at ${'$'}{/b/c}"}}}""")))
        val sends = sloppy.replace("\"catalogId\"", "\"sendDataModel\":true,\"catalogId\"")
        assertTrue(A2uiExpectations.check(A2uiExpect(actionUsesInputs = true), sends).single().pass)
    }

    @Test
    fun templateNeedsAnArrayInTheDataModel() {
        assertTrue(A2uiExpectations.check(A2uiExpect(template = true, components = listOf("List")), list).all { it.pass })
        val empty = list.replace("[{\"name\":\"Aster\"},{\"name\":\"Linden\"}]", "[]")
        assertFalse(A2uiExpectations.check(A2uiExpect(template = true), empty).single().pass)
        assertFalse(A2uiExpectations.check(A2uiExpect(template = true), form).single().pass)
    }

    @Test
    fun propsCompareNumbersAndCountItems() {
        val slider = """[{"version":"v1.0","createSurface":{"surfaceId":"t","catalogId":"$BASIC","components":[
            {"id":"root","component":"Slider","label":"Temp","value":{"@path":"/t"},"min":16,"max":28.0}],"dataModel":{"t":21}}}]"""
        val expect = A2uiExpect(props = listOf(A2uiPropExpect("Slider", "min", equals = JsonPrimitive(16)), A2uiPropExpect("Slider", "max", equals = JsonPrimitive(28))))
        assertTrue(A2uiExpectations.check(expect, slider).all { it.pass })
        val options = A2uiExpect(props = listOf(A2uiPropExpect("ChoicePicker", "options", minItems = 2)))
        assertFalse(A2uiExpectations.check(options, slider).single().pass)
    }

    // The runner, with a scripted endpoint.

    private fun call(messages: String, id: String = "c") =
        A2uiEvalCompletion(toolCalls = listOf(A2uiEvalToolCall(id, "show_ui", buildJsonObject { put("messages", messages) }.toString())), finishReason = "tool_calls", promptTokens = 100, completionTokens = 50, latencyMs = 10)

    private class Script(vararg steps: A2uiEvalCompletion) : A2uiEvalClient {
        val steps = steps.toList()
        val seen = ArrayList<JsonArray>()
        override fun complete(messages: JsonArray, tools: JsonArray): A2uiEvalCompletion {
            seen += messages
            return steps.getOrElse(seen.size - 1) { A2uiEvalCompletion(content = "done") }
        }
    }

    private val task = A2uiEvalTask("signup", "form-validation", "Sign me up", signupExpect)

    @Test
    fun invalidThenValidCountsTwoTries() {
        val broken = form.replace("\"child\":\"go_t\"", "\"child\":\"missing\"")
        val script = Script(call(broken, "a"), call(form, "b"))
        val attempts = ArrayList<A2uiEvalAttempt>()
        val r = A2uiEvalRunner(script, "m").run(task, attempts)
        assertEquals("valid", r.outcome)
        assertEquals(2, r.tries)
        assertEquals(2, r.validAt)
        assertFalse(r.firstTryValid)
        assertEquals(1.0, r.expectScore, 0.0)
        assertEquals(200, r.promptTokens)
        assertEquals(listOf(false, true), attempts.flatMap { it.calls }.map { it.valid })
        assertTrue(r.errors.single(), r.errors.single().startsWith("INTEGRITY_ERROR"))
        // The second request carries the system prompt, the tool schema's call and the validator's errors.
        val second = script.seen[1]
        assertTrue((second[0] as JsonObject)["content"]!!.jsonPrimitive.content.contains("Generative UI: call show_ui"))
        val toolReply = second.last() as JsonObject
        assertEquals("tool", toolReply["role"]!!.jsonPrimitive.content)
        assertEquals("a", toolReply["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(toolReply["content"]!!.jsonPrimitive.content.contains("Fix these and call show_ui again"))
    }

    @Test
    fun threeInvalidCallsEndTheTask() {
        val broken = form.replace("\"Column\"", "\"Colum\"")
        val r = A2uiEvalRunner(Script(call(broken), call(broken), call(broken), call(form)), "m").run(task)
        assertEquals("invalid", r.outcome)
        assertEquals(3, r.tries)
        assertEquals(3, r.requests)
        assertNull(r.validAt)
        assertEquals(0.0, r.expectScore, 0.0)
    }

    @Test
    fun missingMessagesIsRejectedLikeTheExecutorDoes() {
        val empty = A2uiEvalCompletion(toolCalls = listOf(A2uiEvalToolCall("c", "show_ui", """{"ui":$form}""")))
        val r = A2uiEvalRunner(Script(empty, A2uiEvalCompletion(content = "Sorry.")), "m").run(task)
        assertEquals("gave-up", r.outcome)
        assertEquals(listOf("INVALID_ARGS invalid arguments: missing required 'messages'"), r.errors)
    }

    @Test
    fun textCallsAreRecoveredAndMalformedOnesCounted() {
        val args = buildJsonObject { put("name", "show_ui"); put("arguments", buildJsonObject { put("messages", form) }) }
        val r = A2uiEvalRunner(Script(A2uiEvalCompletion(content = "Sure.\n<tool_call>\n$args\n</tool_call>")), "m").run(task)
        assertEquals("valid", r.outcome)
        assertEquals(1, r.salvaged)

        val bare = A2uiEvalRunner(Script(A2uiEvalCompletion(content = "```json\n$form\n```")), "m").run(task)
        assertEquals("valid", bare.outcome)

        val malformed = A2uiEvalRunner(Script(A2uiEvalCompletion(content = "<tool_call>{\"name\":\"show_ui\",\"arguments\":{\"messages\":[{]}}</tool_call>")), "m").run(task)
        assertEquals("malformed-call", malformed.outcome)
        assertEquals(0, malformed.tries)

        val off = A2uiEvalRunner(Script(A2uiEvalCompletion(content = "<tool_call>{\"name\":\"show_ui\",\"arguments\":{}}</tool_call>")), "m", salvage = false).run(task)
        assertEquals("no-call", off.outcome)
    }

    @Test
    fun theToolDeclaresAnArrayAndStillTakesAString() {
        fun declared(r: A2uiEvalRunner) =
            (((((r.tools[0] as JsonObject)["function"] as JsonObject)["parameters"] as JsonObject)["properties"] as JsonObject)["messages"] as JsonObject)["type"]!!.jsonPrimitive.content
        val asArray = A2uiEvalCompletion(toolCalls = listOf(A2uiEvalToolCall("c", "show_ui", """{"messages":$form}""")))
        val runner = A2uiEvalRunner(Script(asArray), "m")
        assertEquals("array", declared(runner))
        val r = runner.run(task)
        assertEquals("valid", r.outcome)
        assertEquals(1.0, r.expectScore, 0.0)
        val legacy = A2uiEvalRunner(Script(call(form)), "m", stringArgs = true)
        assertEquals("string", declared(legacy))
        assertEquals("valid", legacy.run(task).outcome)
    }

    @Test
    fun aServerRefusingAnUnparsableToolCallCountsAsMalformed() {
        val refused = A2uiEvalCompletion(error = "${A2uiEvalRunner.MALFORMED} HTTP 500: Failed to parse tool call arguments as JSON")
        assertEquals("malformed-call", A2uiEvalRunner(Script(refused), "m").run(task).outcome)
        assertEquals("error", A2uiEvalRunner(Script(A2uiEvalCompletion(error = "HTTP 404")), "m").run(task).outcome)
    }

    @Test
    fun noUiTasksScoreRestraint() {
        val noUi = A2uiEvalTask("math", "no-ui", "15% of 80?", A2uiExpect(noUi = true))
        val text = A2uiEvalRunner(Script(A2uiEvalCompletion(content = "12")), "m").run(noUi)
        assertEquals("text", text.outcome)
        assertEquals(1.0, text.expectScore, 0.0)
        val eager = A2uiEvalRunner(Script(call(form)), "m").run(noUi)
        assertEquals(0.0, eager.expectScore, 0.0)
    }

    @Test
    fun summaryAndReport() {
        fun result(id: String, validAt: Int?, tries: Int, checks: List<A2uiCheck> = emptyList(), errors: List<String> = emptyList(), noUi: Boolean = false) =
            A2uiTaskResult("m", id, "c", noUi, if (validAt != null) "valid" else "invalid", tries, validAt, checks, 1, 10, 5, 1000, errors)
        val results = listOf(
            result("a", 1, 1, listOf(A2uiCheck("x", true), A2uiCheck("y", false))),
            result("b", 2, 2, errors = listOf("INTEGRITY_ERROR [message 0] surface 's' /components/3/child: component 'go' refers to 'Confirm', which is not defined")),
            result("c", null, 3, errors = List(3) { "VALIDATION_FAILED [message 0] surface 's' /components/$it/value: expected array or object, got string" }),
            result("d", null, 0),
            result("n", null, 0, noUi = true),
        )
        val s = A2uiModelSummary.of("m", results)
        assertEquals(4, s.uiTasks)
        assertEquals(3, s.called)
        assertEquals(1, s.firstTryValid)
        assertEquals(2, s.validWithin)
        assertEquals(2.0, s.meanTries, 1e-9)
        assertEquals((0.5 + 1.0) / 4, s.expectScore, 1e-9)
        assertEquals(1, s.restraint)
        assertEquals("VALIDATION_FAILED /components/N/value: expected array or object, got string" to 3, s.topErrors.first())
        val md = A2uiEvalReport.markdown("http://x", mapOf("m" to results))
        assertTrue(md, md.contains("| m | 3/4 (75%) | 0/4 (0%) | 1/4 (25%) | 2/4 (50%) | 2.00 | 38% | 1/4 (25%) | 1/1 (100%) |"))
    }

    @Test
    fun parsesAChatCompletionsResponse() {
        val body = """{"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"show_ui","arguments":"{\"messages\":\"[]\"}"}},
                          {"id":"call_2","type":"function","function":{"name":"show_ui","arguments":{"messages":"x"}}}]}}],
            "usage":{"prompt_tokens":900,"completion_tokens":120}}"""
        val c = OpenAiEvalClient.parse(body, 42)
        assertEquals(listOf("call_1", "call_2"), c.toolCalls.map { it.id })
        assertEquals("""{"messages":"[]"}""", c.toolCalls[0].arguments)
        assertEquals("""{"messages":"x"}""", c.toolCalls[1].arguments)
        assertEquals(900, c.promptTokens)
        assertEquals(120, c.completionTokens)
        assertNull(c.content)
        assertTrue(OpenAiEvalClient.parse("""{"error":"x"}""", 0).error!!.startsWith("no choices"))
    }

    private object Json {
        fun parse(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s)
    }
}

package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun a2uiResource(path: String): String =
    checkNotNull(A2uiSpecCasesTest::class.java.getResource("/a2ui/v1_0/$path")) { "missing resource $path" }.readText()

internal fun a2uiJson(path: String): JsonElement = Json.parseToJsonElement(a2uiResource(path))

/**
 * Runs the A2UI v1.0 conformance cases (specification/v1_0/test/cases, the suite the spec runs with
 * ajv) through our JSON Schema interpreter: `valid` cases must validate, the others must fail.
 */
class A2uiSpecCasesTest {
    /** Cases that need JSON Schema features deliberately left out, with the reason. None so far. */
    private val skipped: Map<String, String> = emptyMap()

    private val caseFiles = listOf(
        "button_checks.json", "call_agent_function_message.json", "call_function_message.json",
        "checkable_components.json", "composition_constraints.json", "contact_form_example_test.json",
        "dynamic_value_validation.json", "function_catalog_validation.json", "function_response.json",
        "icon_checks.json", "initial_state_validation.json", "renderer_messages.json", "tabs_checks.json",
        "text_variants.json",
    )

    private fun schemas(catalog: JsonElement): SchemaSet = SchemaSet()
        .add("common_types.json", a2uiJson("json/common_types.json"))
        .add("agent_to_renderer.json", a2uiJson("json/agent_to_renderer.json"))
        .add("agent_to_renderer_list.json", a2uiJson("json/agent_to_renderer_list.json"))
        .add("agent_to_renderer_list_wrapper.json", a2uiJson("json/agent_to_renderer_list_wrapper.json"))
        .add("renderer_to_agent.json", a2uiJson("json/renderer_to_agent.json"))
        .add("renderer_to_agent_list.json", a2uiJson("json/renderer_to_agent_list.json"))
        .add("renderer_to_agent_list_wrapper.json", a2uiJson("json/renderer_to_agent_list_wrapper.json"))
        .add("renderer_capabilities.json", a2uiJson("json/renderer_capabilities.json"))
        .add("agent_capabilities.json", a2uiJson("json/agent_capabilities.json"))
        .add("renderer_data_model.json", a2uiJson("json/renderer_data_model.json"))
        .add("catalog_definition.json", a2uiJson("json/catalog_definition.json"))
        .add("catalog.json", catalog)

    private val basicCatalog = a2uiJson("catalogs/basic/catalog.json")

    @Test
    fun everySpecCaseMatchesItsExpectation() {
        val failures = ArrayList<String>()
        var passed = 0
        var total = 0
        for (file in caseFiles) {
            val suite = a2uiJson("cases/$file").jsonObject
            val schema = suite["schema"]?.jsonPrimitive?.content ?: "agent_to_renderer.json"
            val catalog = suite["catalog"]?.jsonPrimitive?.content?.let { a2uiJson(it) } ?: basicCatalog
            val validator = JsonSchemaValidator(schemas(catalog))
            for (case in suite["tests"]!!.jsonArray) {
                val o = case.jsonObject
                val description = o["description"]!!.jsonPrimitive.content
                if (description in skipped) continue
                total++
                val expected = o["valid"]?.jsonPrimitive?.content != "false"
                val errors = validator.validate(o["data"]!!, schema)
                if (errors.isEmpty() == expected) passed++
                else failures += "$file: $description: expected ${if (expected) "valid" else "invalid"}, got ${if (errors.isEmpty()) "valid" else errors.take(3)}"
            }
        }
        println("A2UI spec cases: $passed/$total passed, ${skipped.size} skipped")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(157, total)
    }

    @Test
    fun contactFormExampleStreamValidatesLineByLine() {
        val validator = JsonSchemaValidator(schemas(basicCatalog))
        val lines = a2uiResource("cases/contact_form_example.jsonl").lines().filter { it.isNotBlank() }
        assertEquals(4, lines.size)
        for (line in lines) assertEquals(line, emptyList<SchemaError>(), validator.validate(Json.parseToJsonElement(line), "agent_to_renderer.json"))
    }

    @Test
    fun catalogsMatchTheCatalogDefinition() {
        val validator = JsonSchemaValidator(schemas(basicCatalog))
        for (path in listOf("catalogs/basic/catalog.json", "testing_catalog.json")) {
            assertEquals(path, emptyList<SchemaError>(), validator.validate(a2uiJson(path), "catalog_definition.json"))
            val load = A2uiCatalog.load(a2uiResource(path))
            assertEquals(path, emptyList<String>(), load.errors)
        }
    }

    @Test
    fun capabilityAndListSchemasAcceptTheirExamples() {
        val validator = JsonSchemaValidator(schemas(basicCatalog))
        fun ok(schema: String, json: String) = assertEquals(schema, emptyList<SchemaError>(), validator.validate(Json.parseToJsonElement(json), schema))
        ok("renderer_capabilities.json", """{"v1.0":{"supportedCatalogIds":["${A2uiCatalog.BASIC_CATALOG_ID}"],"inlineCatalogs":[]}}""")
        ok("agent_capabilities.json", """{"v1.0":{"supportedCatalogIds":["${A2uiCatalog.BASIC_CATALOG_ID}"],"acceptsInlineCatalogs":true}}""")
        ok("renderer_data_model.json", """{"version":"v1.0","surfaces":{"s":{"user":{"name":"Alice"}}}}""")
        ok("agent_to_renderer_list_wrapper.json", """{"messages":[{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"${A2uiCatalog.BASIC_CATALOG_ID}"}}]}""")
        ok("renderer_to_agent_list_wrapper.json", """{"messages":[{"version":"v1.0","action":{"name":"go","surfaceId":"s","sourceComponentId":"b","timestamp":"2026-06-22T17:00:00Z","context":{}}}]}""")
    }

    @Test
    fun embeddedSpecMatchesTheVendoredFiles() {
        assertEquals(a2uiJson("json/common_types.json"), Json.parseToJsonElement(A2uiSpecJson.commonTypes))
        assertEquals(a2uiJson("json/agent_to_renderer.json"), Json.parseToJsonElement(A2uiSpecJson.agentToRenderer))
        assertEquals(a2uiJson("json/catalog_definition.json"), Json.parseToJsonElement(A2uiSpecJson.catalogDefinition))
        assertEquals(basicCatalog, Json.parseToJsonElement(A2uiSpecJson.basicCatalog))
        assertEquals(A2uiCatalog.BASIC_CATALOG_ID, (basicCatalog as JsonObject)["catalogId"]!!.jsonPrimitive.content)
    }
}

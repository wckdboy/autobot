package dev.wckdboy.autobot.shared.a2ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASIC = A2uiCatalog.BASIC_CATALOG_ID

/** The errors an LLM gets back: the right code, a pointer to the field and a message it can act on. */
class A2uiValidatorTest {
    private val validator = A2uiValidator()

    private fun surface(vararg components: String, extra: String = "") =
        """{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"$BASIC","components":[${components.joinToString(",")}]$extra}}"""

    private fun single(text: String): A2uiError {
        val report = validator.validate(text)
        assertEquals(report.forModel(), 1, report.errors.size)
        return report.errors.single()
    }

    @Test
    fun theContactFormIsValid() {
        val report = validator.validate(a2uiResource("cases/contact_form_example.jsonl"))
        assertTrue(report.forModel(), report.isValid)
        assertEquals(4, report.messageCount)
    }

    @Test
    fun theCatalogExamplesAreValid() {
        val catalog = A2uiCatalog.basic.instructions!!
        val examples = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).findAll(catalog).map { it.groupValues[1] }.toList()
        assertEquals(2, examples.size)
        for (e in examples) assertTrue(validator.validate(e).forModel(), validator.validate(e).isValid)
    }

    @Test
    fun unknownComponentListsTheCatalog() {
        val e = single(surface("""{"id":"root","component":"Paragraph","text":"x"}"""))
        assertEquals(A2uiErrorCode.VALIDATION_FAILED, e.code)
        assertEquals("/components/0/component", e.path)
        assertTrue(e.message, e.message.startsWith("must be one of: Text, Image, Icon"))
        assertEquals(0, e.messageIndex)
        assertEquals("s", e.surfaceId)
    }

    @Test
    fun missingAndWrongProperties() {
        val missing = single(surface("""{"id":"root","component":"Text"}"""))
        assertEquals("/components/0", missing.path)
        assertEquals("missing required property 'text'", missing.message)

        val wrongType = single(surface("""{"id":"root","component":"Text","text":42}"""))
        assertEquals("/components/0/text", wrongType.path)
        assertTrue(wrongType.message, wrongType.message.startsWith("expected string or object"))

        val badEnum = single(surface("""{"id":"root","component":"Text","text":"x","variant":"h1"}"""))
        assertEquals("/components/0/variant", badEnum.path)
        assertEquals("must be one of: caption, body", badEnum.message)

        val extra = single(surface("""{"id":"root","component":"Text","text":"x","color":"red"}"""))
        assertEquals("/components/0/color", extra.path)
        assertTrue(extra.message, extra.message.startsWith("property 'color' is not allowed here; allowed: "))
        assertTrue(extra.message, extra.message.contains("variant"))
    }

    @Test
    fun badBindingsAndCalls() {
        val extraKey = single(surface("""{"id":"root","component":"Text","text":{"@path":"/a","default":"x"}}"""))
        assertEquals("/components/0/text/default", extraKey.path)

        val unknownFn = single(surface("""{"id":"root","component":"Text","text":{"@call":"upper","args":{"value":"x"}}}"""))
        assertEquals("/components/0/text/@call", unknownFn.path)
        assertTrue(unknownFn.message, unknownFn.message.startsWith("must be one of: required, regex"))
        assertTrue(unknownFn.message, unknownFn.message.endsWith("@index"))

        val badArgs = single(surface("""{"id":"root","component":"Text","text":{"@call":"formatString","args":{"text":"x"}}}"""))
        assertTrue(badArgs.forModel(), badArgs.path.startsWith("/components/0/text/args"))
    }

    @Test
    fun duplicateIdsAndDirectives() {
        val dup = single(surface("""{"id":"root","component":"Text","text":"a"}""", """{"id":"root","component":"Text","text":"b"}"""))
        assertEquals("/components/1/id", dup.path)
        assertTrue(dup.message.contains("duplicate component id 'root'"))

        val pointer = single(surface("""{"id":"root","component":"Text","text":{"@path":"/a~2b"}}"""))
        assertEquals("/components/0/text/@path", pointer.path)
        assertTrue(pointer.message.contains("JSON Pointer"))
    }

    @Test
    fun functionRules() {
        val passiveOpenUrl = single(surface(
            """{"id":"root","component":"Button","child":"label","action":{"event":{"name":"go","context":{"u":{"@call":"openUrl","args":{"url":"https://x.example"}}}}}}""",
            """{"id":"label","component":"Text","text":"Go"}""",
        ))
        assertEquals(A2uiErrorCode.EXPRESSION_ERROR, passiveOpenUrl.code)
        assertTrue(passiveOpenUrl.message.contains("user gesture"))

        val badPattern = single(surface("""{"id":"root","component":"TextField","label":"x","value":{"@path":"/v"},"checks":[{"condition":{"@call":"regex","args":{"value":{"@path":"/v"},"pattern":"(a+)+"}}}]}"""))
        assertEquals(A2uiErrorCode.EXPRESSION_ERROR, badPattern.code)
        assertEquals("/components/0/checks/0/condition/args/pattern", badPattern.path)

        val badTemplate = single(surface("""{"id":"root","component":"Text","text":{"@call":"formatString","args":{"value":"Hi ${'$'}{/name"}}}"""))
        assertEquals(A2uiErrorCode.EXPRESSION_ERROR, badTemplate.code)

        val stringCheck = single(surface("""{"id":"root","component":"TextField","label":"x","checks":[{"condition":{"@call":"formatString","args":{"value":"x"}}}]}"""))
        assertTrue(stringCheck.message.contains("returns string"))
    }

    @Test
    fun integrityAtTheEndOfTheStream() {
        val missing = single(surface("""{"id":"root","component":"Column","children":["a","ghost"]}""", """{"id":"a","component":"Text","text":"A"}"""))
        assertEquals(A2uiErrorCode.INTEGRITY_ERROR, missing.code)
        assertEquals("/components/0/children/1", missing.path)
        assertTrue(missing.message.contains("'ghost'"))

        val cycle = single(surface("""{"id":"root","component":"Card","child":"a"}""", """{"id":"a","component":"Card","child":"root"}"""))
        assertTrue(cycle.message, cycle.message.contains("cycle: root -> a -> root"))

        val noRoot = single(surface("""{"id":"a","component":"Text","text":"A"}"""))
        assertTrue(noRoot.message.contains("'root'"))

        val index = single(surface("""{"id":"root","component":"Text","text":{"@call":"formatString","args":{"value":"#${'$'}{@index()}"}}}"""))
        assertEquals(A2uiErrorCode.EXPRESSION_ERROR, index.code)

        val missingTemplate = single(surface("""{"id":"root","component":"List","children":{"path":"/items","componentId":"row"}}"""))
        assertEquals("/components/0/children/componentId", missingTemplate.path)
    }

    @Test
    fun expressionsAreEvaluatedAgainstTheData() {
        val e = single(surface(
            """{"id":"root","component":"Text","text":{"@call":"formatString","args":{"value":{"@path":"/greeting"}}}}""",
            extra = ""","dataModel":{"greeting":"Hi ${'$'}{/name"}""",
        ))
        assertEquals(A2uiErrorCode.EXPRESSION_ERROR, e.code)
        assertTrue(e.message, e.message.contains("component 'root' property 'text'"))
    }

    @Test
    fun compositionConstraintsFromACustomCatalog() {
        val catalog = A2uiCatalog.load(
            """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","catalogId":"test:menus","components":{
              "AppLayout":{"type":"object","allowedParents":["Surface"],"properties":{"component":{"const":"AppLayout"},"child":{"${'$'}ref":"common_types.json#/${'$'}defs/ComponentId"}},"required":["component","child"]},
              "Menu":{"type":"object","allowedChildren":["MenuItem"],"properties":{"component":{"const":"Menu"},"children":{"${'$'}ref":"common_types.json#/${'$'}defs/ChildList"}},"required":["component","children"]},
              "MenuItem":{"type":"object","allowedParents":["Menu"],"properties":{"component":{"const":"MenuItem"},"label":{"${'$'}ref":"common_types.json#/${'$'}defs/DynamicString"}},"required":["component","label"]}},
              "${'$'}defs":{"anyComponent":{"oneOf":[{"${'$'}ref":"#/components/AppLayout"},{"${'$'}ref":"#/components/Menu"},{"${'$'}ref":"#/components/MenuItem"}]},"anyFunction":{"oneOf":[]}}}""",
        )
        assertEquals(emptyList<String>(), catalog.errors)
        val v = A2uiValidator(listOf(catalog.catalog!!), A2uiLimits.standard)
        fun run(vararg c: String) = v.validate("""{"version":"v1.0","createSurface":{"surfaceId":"m","catalogId":"test:menus","components":[${c.joinToString(",")}]}}""").errors

        assertEquals(emptyList<A2uiError>(), run(
            """{"id":"root","component":"AppLayout","child":"menu"}""",
            """{"id":"menu","component":"Menu","children":["i1"]}""",
            """{"id":"i1","component":"MenuItem","label":"Open"}""",
        ))
        val nested = run(
            """{"id":"root","component":"Menu","children":["inner"]}""",
            """{"id":"inner","component":"AppLayout","child":"i1"}""",
            """{"id":"i1","component":"MenuItem","label":"Open"}""",
        ).map { it.code }.toSet()
        assertEquals(setOf(A2uiErrorCode.UNALLOWED_PARENT, A2uiErrorCode.UNALLOWED_CHILD), nested)
    }

    @Test
    fun catalogsAreCheckedWhenLoaded() {
        val surface = A2uiCatalog.load("""{"catalogId":"x","components":{"Surface":{"type":"object","properties":{"component":{"const":"Surface"}}}}}""")
        assertTrue(surface.errors.toString(), surface.catalog == null && surface.errors.isNotEmpty())
        val badName = A2uiCatalog.load("""{"catalogId":"x","components":{"my-card":{"type":"object","properties":{"component":{"const":"my-card"}}}},"${'$'}defs":{"anyComponent":{},"anyFunction":{}}}""")
        assertTrue(badName.errors.any { it.contains("UAX #31") })
        val wrongConst = A2uiCatalog.load("""{"catalogId":"x","components":{"Card":{"type":"object","properties":{"component":{"const":"Box"}}}},"${'$'}defs":{"anyComponent":{},"anyFunction":{}}}""")
        assertTrue(wrongConst.errors.any { it.contains("component.const") })
        assertTrue(A2uiCatalog.load("not json").errors.single().startsWith("catalog is not JSON"))
    }

    @Test
    fun basicCatalogShape() {
        val c = A2uiCatalog.basic
        assertEquals(18, c.componentNames.size)
        assertEquals(14, c.functionNames.size)
        assertEquals(listOf("tabs/*/child"), c.component("Tabs")!!.slots.map { it.path.joinToString("/") })
        assertEquals(listOf("trigger", "content"), c.component("Modal")!!.slots.map { it.path.joinToString("/") })
        assertTrue(c.component("Row")!!.slots.single().isList)
        assertEquals(listOf("child"), c.component("Button")!!.slots.map { it.path.joinToString("/") })
        val openUrl = c.function("openUrl")!!
        assertTrue(openUrl.requiresUserActivation)
        assertEquals("rendererOnly", openUrl.allowedCallers)
        assertEquals("validationResult", c.function("email")!!.returnType)
    }

    @Test
    fun reportReadsAsALineList() {
        val report = validator.validate(surface("""{"id":"root","component":"Text","text":"x","variant":"h1"}"""))
        assertEquals("VALIDATION_FAILED [message 0] surface 's' /components/0/variant: must be one of: caption, body", report.forModel())
    }
}

package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A2uiFunctionsTest {
    private val model = Json.parseToJsonElement(
        """{"user":{"first":"Ada","last":"Lovelace","age":36},"items":[{"name":"tea","qty":2},{"name":"cake","qty":1}],
           |"price":1234.5,"when":"2026-02-02T15:17:00Z","flag":true,"empty":"","list":[],"vr":{"valid":false,"message":"from data"}}""".trimMargin(),
    )
    private val errors = ArrayList<A2uiError>()
    private val eval = A2uiEvaluator("s", A2uiCatalog.basic, model, A2uiLimits.standard) { errors += it }

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)
    private fun call(json: String, scope: EvalScope = EvalScope.ROOT, activation: Boolean = false): JsonElement? =
        eval.resolve(j(json), scope, activation)

    private fun str(json: String, scope: EvalScope = EvalScope.ROOT) = coerceToString(call(json, scope))
    private fun valid(json: String) = (call(json) as JsonObject)["valid"]!!.booleanOrNull()!!

    @Test
    fun bindingsReadAbsoluteAndRelativePaths() {
        assertEquals(JsonPrimitive("Ada"), call("""{"@path":"/user/first"}"""))
        val item1 = EvalScope.ROOT.item("/items", 1)
        assertEquals(JsonPrimitive("cake"), call("""{"@path":"name"}""", item1))
        assertEquals(JsonPrimitive("Ada"), call("""{"@path":"/user/first"}""", item1))
        assertNull(call("""{"@path":"/nope"}"""))
    }

    @Test
    fun literalObjectsUnescapeDoubledAtKeysAndResolveNestedBindings() {
        assertEquals(j("""{"@path":"/static","nested":{"x":"Ada"}}"""), call("""{"@@path":"/static","nested":{"x":{"@path":"/user/first"}}}"""))
        assertEquals(j("""{"path":"/a","call":"b"}"""), call("""{"path":"/a","call":"b"}"""))
    }

    @Test
    fun formatStringInterpolates() {
        assertEquals("Hello, Ada Lovelace!", str("""{"@call":"formatString","args":{"value":"Hello, ${'$'}{/user/first} ${'$'}{/user/last}!"}}"""))
        assertEquals("cake x1", str("""{"@call":"formatString","args":{"value":"${'$'}{name} x${'$'}{qty}"}}""", EvalScope.ROOT.item("/items", 1)))
        assertEquals("#2", str("""{"@call":"formatString","args":{"value":"#${'$'}{@index(offset: 1)}"}}""", EvalScope.ROOT.item("/items", 1)))
        assertEquals("on Mon Feb 2", str("""{"@call":"formatString","args":{"value":"on ${'$'}{formatDate(value: ${'$'}{/when}, format: 'E MMM d')}"}}"""))
        assertEquals("price 1,234.50", str("""{"@call":"formatString","args":{"value":"price ${'$'}{formatNumber(value: ${'$'}{/price}, decimals: 2)}"}}"""))
        assertEquals("literal ${'$'}{x} stays", str("""{"@call":"formatString","args":{"value":"literal \\${'$'}{x} stays"}}"""))
        assertEquals("age 36, ok true, none .", str("""{"@call":"formatString","args":{"value":"age ${'$'}{/user/age}, ok ${'$'}{/flag}, none ${'$'}{/missing}."}}"""))
        assertEquals("{\"first\":\"Ada\",\"last\":\"Lovelace\",\"age\":36}", str("""{"@call":"formatString","args":{"value":"${'$'}{/user}"}}"""))
        assertEquals("a 'q' b", str("""{"@call":"formatString","args":{"value":"a ${'$'}{'\\'q\\''} b"}}"""))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun formatStringErrorsAreReportedNotThrown() {
        assertEquals("", str("""{"@call":"formatString","args":{"value":"oops ${'$'}{/a"}}"""))
        assertTrue(errors.single().message.contains("unclosed"))
        assertEquals(A2uiErrorCode.EXPRESSION_ERROR, errors.single().code)
    }

    @Test
    fun indexOnlyInsideTemplates() {
        assertEquals(JsonPrimitive(1), call("""{"@call":"@index"}""", EvalScope.ROOT.item("/items", 1)))
        assertEquals(JsonPrimitive(11), call("""{"@call":"@index","args":{"offset":10}}""", EvalScope.ROOT.item("/items", 1)))
        // Innermost list wins.
        val nested = EvalScope.ROOT.item("/items", 1).item("/items/1/tags", 3)
        assertEquals(JsonPrimitive(3), call("""{"@call":"@index"}""", nested))
        assertNull(call("""{"@call":"@index"}"""))
        assertTrue(errors.single().message.contains("list template"))
    }

    @Test
    fun formatNumber() {
        fun f(v: Double, d: Int? = null, g: Boolean = true) = A2uiFunctions.formatNumber(v, d, g)
        assertEquals("1,234.5", f(1234.5))
        assertEquals("1,234.50", f(1234.5, 2))
        assertEquals("1234.50", f(1234.5, 2, false))
        assertEquals("1,235", f(1234.5, 0))
        assertEquals("0.333", f(1.0 / 3))
        assertEquals("1.01", f(1.005, 2))
        assertEquals("-1,000,000", f(-1e6))
        assertEquals("0.00", f(-0.0001, 2))
        assertEquals("100.00", f(99.9999, 2))
        assertEquals("12,345,678,901,234", f(12345678901234.0))
        assertEquals("0.00001", f(0.00001, 5))
        assertEquals("1,000.0", f(999.96, 1))
        assertEquals("", A2uiFunctions.formatNumber(Double.NaN, null, true))
        assertEquals("1,234.50", str("""{"@call":"formatNumber","args":{"value":{"@path":"/price"},"decimals":2}}"""))
        assertEquals("1234.5", str("""{"@call":"formatNumber","args":{"value":1234.5,"grouping":false}}"""))
    }

    @Test
    fun formatCurrency() {
        assertEquals("$1,234.50", A2uiFunctions.formatCurrency(1234.5, "usd", 2, true))
        assertEquals("€1,234.50", A2uiFunctions.formatCurrency(1234.5, "EUR", 2, true))
        assertEquals("-£3.00", A2uiFunctions.formatCurrency(-3.0, "GBP", 2, true))
        assertEquals("CHF 1,234.50", A2uiFunctions.formatCurrency(1234.5, "CHF", 2, true))
        assertEquals("¥1,235", A2uiFunctions.formatCurrency(1234.5, "JPY", 0, true))
        assertEquals("$1,234.50", str("""{"@call":"formatCurrency","args":{"value":{"@path":"/price"},"currency":"USD"}}"""))
    }

    @Test
    fun formatDate() {
        fun f(v: String, p: String) = A2uiFunctions.formatDate(JsonPrimitive(v), p)
        assertEquals("Jan 16, 2026", f("2026-01-16", "MMM dd, yyyy"))
        assertEquals("14:30", f("2026-01-16T14:30:00Z", "HH:mm"))
        assertEquals("2:30 PM", f("2026-01-16T14:30:00Z", "h:mm a"))
        assertEquals("12:05 AM", f("2026-01-16T00:05:00Z", "h:mm a"))
        assertEquals("Friday, 16 January", f("2026-01-16", "EEEE, d MMMM"))
        assertEquals("26/1/6", f("2026-01-06", "yy/M/d"))
        // The contact form's pattern: YYYY is treated as the calendar year.
        assertEquals("Mon Feb 2, 2026 3:17 PM", f("2026-02-02T15:17:00Z", "E MMM d, YYYY h:mm a"))
        // The wall-clock time is shown as written in its own offset; ISO gives the UTC instant.
        assertEquals("09:00", f("2026-03-01T09:00:00+05:30", "HH:mm"))
        assertEquals("2026-03-01T03:30:00.000Z", f("2026-03-01T09:00:00+05:30", "ISO"))
        assertEquals("at 09:00", f("2026-03-01T09:00:00", "'at' HH:mm"))
        assertEquals("it's 9", f("2026-03-01T09:00:00", "'it''s' H"))
        assertEquals("1970-01-02", A2uiFunctions.formatDate(JsonPrimitive(86_400_000), "yyyy-MM-dd"))
        assertEquals("Sat Feb 29 2020", f("2020-02-29", "EEE MMM d yyyy"))
        assertEquals("", f("2026-02-30", "yyyy"))
        assertEquals("", f("not a date", "yyyy"))
        assertEquals("Mon Feb 2, 2026", str("""{"@call":"formatDate","args":{"value":{"@path":"/when"},"format":"E MMM d, yyyy"}}"""))
    }

    @Test
    fun pluralize() {
        fun p(n: Int, withZero: Boolean = false) = str(
            """{"@call":"pluralize","args":{"value":$n,${if (withZero) "\"zero\":\"none\"," else ""}"one":"1 item","other":{"@call":"formatString","args":{"value":"$n items"}}}}""",
        )
        assertEquals("1 item", p(1))
        assertEquals("2 items", p(2))
        assertEquals("0 items", p(0))
        assertEquals("none", p(0, withZero = true))
    }

    @Test
    fun validationFunctions() {
        assertTrue(valid("""{"@call":"required","args":{"value":{"@path":"/user/first"}}}"""))
        assertTrue(!valid("""{"@call":"required","args":{"value":{"@path":"/empty"}}}"""))
        assertTrue(!valid("""{"@call":"required","args":{"value":{"@path":"/list"}}}"""))
        assertTrue(!valid("""{"@call":"required","args":{"value":{"@path":"/missing"}}}"""))
        assertTrue(valid("""{"@call":"required","args":{"value":false}}"""))

        assertTrue(valid("""{"@call":"regex","args":{"value":"1234567890","pattern":"^\\d{10}$"}}"""))
        assertTrue(!valid("""{"@call":"regex","args":{"value":"12345","pattern":"^\\d{10}$"}}"""))

        assertTrue(valid("""{"@call":"length","args":{"value":"abc","min":2,"max":3}}"""))
        assertTrue(!valid("""{"@call":"length","args":{"value":"a","min":2}}"""))
        assertTrue(!valid("""{"@call":"length","args":{"value":"abcd","max":3}}"""))

        assertTrue(valid("""{"@call":"numeric","args":{"value":5,"min":1,"max":10}}"""))
        assertTrue(valid("""{"@call":"numeric","args":{"value":"7","max":10}}"""))
        assertTrue(!valid("""{"@call":"numeric","args":{"value":11,"max":10}}"""))
        assertTrue(!valid("""{"@call":"numeric","args":{"value":"x","min":1}}"""))

        assertTrue(valid("""{"@call":"email","args":{"value":"john.doe@example.com"}}"""))
        assertTrue(!valid("""{"@call":"email","args":{"value":"john.doe@"}}"""))
        assertTrue(!valid("""{"@call":"email","args":{"value":"a b@example.com"}}"""))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun logicFunctionsCoerceValidationResults() {
        fun b(json: String) = call(json)!!.booleanOrNull()!!
        val req = """{"@call":"required","args":{"value":{"@path":"/user/first"}}}"""
        val reqEmpty = """{"@call":"required","args":{"value":{"@path":"/empty"}}}"""
        assertEquals(true, b("""{"@call":"and","args":{"values":[$req,true]}}"""))
        assertEquals(false, b("""{"@call":"and","args":{"values":[$req,$reqEmpty]}}"""))
        assertEquals(true, b("""{"@call":"or","args":{"values":[$reqEmpty,{"@path":"/flag"}]}}"""))
        assertEquals(false, b("""{"@call":"or","args":{"values":[$reqEmpty,false]}}"""))
        assertEquals(true, b("""{"@call":"not","args":{"value":$reqEmpty}}"""))
        assertEquals(true, b("""{"@call":"not","args":{"value":{"@path":"/vr"}}}"""))
        assertNull(call("""{"@call":"and","args":{"values":[true]}}"""))
        assertTrue(errors.single().message.contains("at least 2"))
    }

    @Test
    fun regexRefusesCatastrophicPatterns() {
        assertNull(call("""{"@call":"regex","args":{"value":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!","pattern":"^(a+)+$"}}"""))
        assertNull(call("""{"@call":"regex","args":{"value":"abab","pattern":"(ab)\\1"}}"""))
        assertNull(call("""{"@call":"regex","args":{"value":"a","pattern":"["}}"""))
        assertEquals(3, errors.size)
    }

    @Test
    fun openUrlNeedsAUserGestureAndAWebUrl() {
        assertNull(call("""{"@call":"openUrl","args":{"url":"https://example.com"}}"""))
        assertTrue(errors.single().message.contains("user action"))
        assertTrue(eval.openedUrls.isEmpty())

        errors.clear()
        call("""{"@call":"openUrl","args":{"url":"https://example.com/a?b=c"}}""", activation = true)
        assertEquals(listOf("https://example.com/a?b=c"), eval.openedUrls)
        for (bad in listOf("javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "/relative", "intent://x", "https://exa mple.com")) {
            call("""{"@call":"openUrl","args":{"url":"$bad"}}""", activation = true)
        }
        assertEquals(1, eval.openedUrls.size)
        assertEquals(6, errors.size)
    }

    @Test
    fun unknownFunctionsAreExpressionErrors() {
        assertNull(call("""{"@call":"eval","args":{"code":"1+1"}}"""))
        assertEquals(A2uiErrorCode.EXPRESSION_ERROR, errors.single().code)
    }

    @Test
    fun templateParserShapes() {
        val p = A2uiTemplateParser()
        assertEquals(listOf(JsonPrimitive("plain")), p.parse("plain"))
        assertEquals(
            listOf(JsonPrimitive("x="), j("""{"@call":"f","args":{"a":1,"b":"s","c":true,"d":null,"e":{"@path":"rel/p"}}}""")),
            p.parse("x=${'$'}{f(a: 1, b: 's', c: true, d: null, e: rel/p)}"),
        )
    }
}

package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An expression that cannot be parsed or evaluated (reported as EXPRESSION_ERROR). */
internal class A2uiExpressionException(message: String) : Exception(message)

/**
 * The `formatString` template language: literal text with `${...}` interpolations. An
 * interpolation is a data path (`/abs/path`, `relative/path`), a literal (`'text'`, `"text"`,
 * numbers, `true`, `false`, `null`), a nested `${...}`, or a function call with named arguments
 * (`formatDate(value: ${/date}, format: 'MMM d')`, `@index(offset: 1)`). `\${` is a literal `${`.
 *
 * Parts come back as dynamic values: a literal string, or a `{"@path"}` / `{"@call"}` object the
 * evaluator resolves like any other binding. Nothing here executes code.
 */
internal class A2uiTemplateParser(private val maxChars: Int = 10_000, private val maxDepth: Int = 32) {
    private val maxParts = 1_000

    fun parse(input: String): List<JsonElement> = parse(input, 0)

    private fun parse(input: String, depth: Int): List<JsonElement> {
        if (depth > maxDepth) throw A2uiExpressionException("formatString expressions are nested too deeply")
        if (input.length > maxChars) throw A2uiExpressionException("formatString template is ${input.length} characters; the limit is $maxChars")
        if (!input.contains("\${")) return if (input.isEmpty()) emptyList() else listOf(JsonPrimitive(input))
        val parts = ArrayList<JsonElement>()
        val text = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (parts.size >= maxParts) throw A2uiExpressionException("formatString template has too many parts")
            when {
                input.startsWith("\\\${", i) -> {
                    text.append("\${"); i += 3
                }
                input.startsWith("\${", i) -> {
                    if (text.isNotEmpty()) {
                        parts += JsonPrimitive(text.toString()); text.clear()
                    }
                    val end = closingBrace(input, i + 2)
                    parts += parseExpression(input.substring(i + 2, end), depth + 1)
                    i = end + 1
                }
                else -> {
                    text.append(input[i]); i++
                }
            }
        }
        if (text.isNotEmpty()) parts += JsonPrimitive(text.toString())
        return parts
    }

    /** Index of the `}` closing an interpolation whose content starts at [start]. */
    private fun closingBrace(s: String, start: Int): Int {
        var balance = 1
        var i = start
        while (i < s.length) {
            when (val c = s[i]) {
                '{' -> balance++
                '}' -> {
                    balance--
                    if (balance == 0) return i
                }
                '\'', '"' -> {
                    i++
                    while (i < s.length && s[i] != c) {
                        if (s[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        throw A2uiExpressionException("unclosed \${ in formatString template")
    }

    fun parseExpression(expr: String, depth: Int): JsonElement {
        val sc = Scanner(expr.trim())
        if (sc.atEnd()) return JsonPrimitive("")
        val v = expression(sc, depth)
        sc.skipSpace()
        if (!sc.atEnd()) throw A2uiExpressionException("unexpected '${sc.rest()}' in expression '${expr.trim()}'")
        return v
    }

    private fun expression(sc: Scanner, depth: Int): JsonElement {
        if (depth > maxDepth) throw A2uiExpressionException("formatString expressions are nested too deeply")
        sc.skipSpace()
        if (sc.atEnd()) throw A2uiExpressionException("empty expression")
        if (sc.startsWith("\${")) {
            sc.advance(2)
            val start = sc.pos
            val end = closingBrace(sc.s, start)
            sc.pos = end + 1
            return parseExpression(sc.s.substring(start, end), depth + 1)
        }
        val c = sc.peek()
        if (c == '\'' || c == '"') return JsonPrimitive(sc.stringLiteral())
        if (c.isDigit() || ((c == '-' || c == '+' || c == '.') && sc.peek(1).let { it.isDigit() || it == '.' })) return sc.number()
        if (sc.keyword("true")) return JsonPrimitive(true)
        if (sc.keyword("false")) return JsonPrimitive(false)
        if (sc.keyword("null")) return JsonNull
        val token = sc.pathOrName()
        sc.skipSpace()
        if (sc.peek() == '(') return call(token, sc, depth)
        if (token.isEmpty()) throw A2uiExpressionException("unexpected '${sc.rest()}' in expression")
        return JsonObject(mapOf("@path" to JsonPrimitive(token)))
    }

    private fun call(name: String, sc: Scanner, depth: Int): JsonElement {
        if (name.isEmpty() || !(name == "@index" || SchemaPatterns.isIdentifier(name))) {
            throw A2uiExpressionException("'$name' is not a function name")
        }
        sc.advance(1)
        val args = LinkedHashMap<String, JsonElement>()
        sc.skipSpace()
        while (!sc.atEnd() && sc.peek() != ')') {
            val arg = sc.name()
            sc.skipSpace()
            if (arg.isEmpty() || sc.peek() != ':') throw A2uiExpressionException("expected 'name: value' arguments in call to '$name'")
            sc.advance(1)
            args[arg] = expression(sc, depth + 1)
            sc.skipSpace()
            if (sc.peek() == ',') {
                sc.advance(1); sc.skipSpace()
            } else if (sc.peek() != ')') {
                throw A2uiExpressionException("expected ',' or ')' in call to '$name'")
            }
        }
        if (sc.peek() != ')') throw A2uiExpressionException("missing ')' in call to '$name'")
        sc.advance(1)
        return JsonObject(mapOf("@call" to JsonPrimitive(name), "args" to JsonObject(args)))
    }

    private class Scanner(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun peek(offset: Int = 0): Char = s.getOrElse(pos + offset) { '\u0000' }
        fun advance(n: Int) {
            pos += n
        }
        fun rest() = s.substring(pos)
        fun startsWith(p: String) = s.startsWith(p, pos)
        fun skipSpace() {
            while (!atEnd() && s[pos].isWhitespace()) pos++
        }

        fun keyword(k: String): Boolean {
            if (!s.startsWith(k, pos)) return false
            val next = peek(k.length)
            if (isNameChar(next) || next == '/' || next == '(') return false
            pos += k.length
            return true
        }

        fun stringLiteral(): String {
            val quote = s[pos++]
            val sb = StringBuilder()
            while (!atEnd()) {
                val c = s[pos++]
                when {
                    c == '\\' && !atEnd() -> when (val n = s[pos++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        else -> sb.append(n)
                    }
                    c == quote -> return sb.toString()
                    else -> sb.append(c)
                }
            }
            throw A2uiExpressionException("unclosed string literal")
        }

        fun number(): JsonElement {
            val start = pos
            if (peek() == '-' || peek() == '+') pos++
            while (!atEnd() && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            val text = s.substring(start, pos)
            val d = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw A2uiExpressionException("invalid number '$text'")
            return jsonNumber(d)
        }

        fun name(): String {
            val start = pos
            while (!atEnd() && isNameChar(s[pos])) pos++
            return s.substring(start, pos)
        }

        /** A data path or a function name: name characters, `/`, `.`, `-`, `~`, and a leading `@`. */
        fun pathOrName(): String {
            val start = pos
            if (peek() == '@') pos++
            while (!atEnd() && (isNameChar(s[pos]) || s[pos] == '/' || s[pos] == '.' || s[pos] == '-' || s[pos] == '~')) pos++
            return s.substring(start, pos)
        }

        private fun isNameChar(c: Char) = c == '_' || c.isLetterOrDigit() || c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.CONNECTOR_PUNCTUATION
    }
}

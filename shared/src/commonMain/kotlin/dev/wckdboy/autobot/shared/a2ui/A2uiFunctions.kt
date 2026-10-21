package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** What a function implementation gets: its resolved arguments and the evaluation it runs in. */
internal class FunctionCall(
    val name: String,
    val args: Map<String, JsonElement?>,
    val evaluator: A2uiEvaluator,
    val scope: EvalScope,
    val activation: Boolean,
    val depth: Int,
) {
    fun arg(name: String): JsonElement? = args[name]?.takeIf { it != JsonNull }
    fun number(name: String): Double? = arg(name)?.let { it.numberOrNull() ?: it.stringOrNull()?.trim()?.toDoubleOrNull() }
    fun string(name: String): String? = arg(name)?.let { coerceToString(it) }
}

internal class RegexBudgetExceeded : RuntimeException("regex budget exceeded")

/** A string that stops a regex engine after [budget] character reads (the engines read through `get`). */
internal class BudgetedChars(private val s: String, private val budget: Int) : CharSequence {
    private var reads = 0
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        if (++reads > budget) throw RegexBudgetExceeded()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = s.subSequence(startIndex, endIndex)
    override fun toString(): String = s
}

internal fun interface FunctionImpl {
    fun invoke(call: FunctionCall): JsonElement?
}

/**
 * The basic catalog's functions. Pure: they read their arguments and return a value; `openUrl`
 * only checks the URL and records it for the renderer to open. Formatting follows en-US.
 */
internal object A2uiFunctions {
    val basic: Map<String, FunctionImpl> = mapOf(
        "required" to FunctionImpl { c -> required(c.arg("value")) },
        "regex" to FunctionImpl { c -> regex(c.string("value") ?: "", c.arg("pattern")?.stringOrNull() ?: throw A2uiExpressionException("regex needs a 'pattern' string"), c.evaluator.limits) },
        "length" to FunctionImpl { c -> length(c.arg("value"), c.number("min"), c.number("max")) },
        "numeric" to FunctionImpl { c -> numeric(c.arg("value"), c.number("min"), c.number("max")) },
        "email" to FunctionImpl { c -> email(c.arg("value")) },
        "formatString" to FunctionImpl { c -> JsonPrimitive(c.evaluator.formatString(c.string("value") ?: "", c.scope, c.activation, c.depth)) },
        "formatNumber" to FunctionImpl { c ->
            val v = c.number("value") ?: return@FunctionImpl JsonPrimitive("")
            JsonPrimitive(formatNumber(v, c.number("decimals")?.toInt(), c.arg("grouping")?.let { truthyOrValid(it) } ?: true))
        },
        "formatCurrency" to FunctionImpl { c ->
            val v = c.number("value") ?: return@FunctionImpl JsonPrimitive("")
            JsonPrimitive(formatCurrency(v, c.string("currency") ?: "", c.number("decimals")?.toInt() ?: 2, c.arg("grouping")?.let { truthyOrValid(it) } ?: true))
        },
        "formatDate" to FunctionImpl { c -> JsonPrimitive(formatDate(c.arg("value"), c.string("format") ?: "yyyy-MM-dd")) },
        "pluralize" to FunctionImpl { c -> JsonPrimitive(pluralize(c.number("value") ?: 0.0) { c.string(it) }) },
        "openUrl" to FunctionImpl { c ->
            val url = c.string("url") ?: ""
            c.evaluator.requestOpenUrl(checkOpenUrl(url))
            JsonNull
        },
        "and" to FunctionImpl { c -> JsonPrimitive(logicalValues(c, "and").all { truthyOrValid(it) }) },
        "or" to FunctionImpl { c -> JsonPrimitive(logicalValues(c, "or").any { truthyOrValid(it) }) },
        "not" to FunctionImpl { c -> JsonPrimitive(!truthyOrValid(c.arg("value"))) },
    )

    private fun logicalValues(c: FunctionCall, name: String): List<JsonElement?> {
        val values = c.arg("values") as? JsonArray ?: throw A2uiExpressionException("$name needs a 'values' list")
        if (values.size < 2) throw A2uiExpressionException("$name needs at least 2 values")
        return values
    }

    /**
     * A ValidationResult. The built-in checks leave `message` out on purpose: the spec lets a
     * function's own message override the agent's `CheckRule.message`, and the agent's wording
     * should win. [why] documents the failure; the renderer falls back to a generic text.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun result(valid: Boolean, why: String?): JsonObject = JsonObject(mapOf("valid" to JsonPrimitive(valid)))

    fun required(v: JsonElement?): JsonObject {
        val valid = when {
            v == null || v == JsonNull -> false
            v.isJsonString() -> (v as JsonPrimitive).content.isNotEmpty()
            v is JsonArray -> v.isNotEmpty()
            else -> true
        }
        return result(valid, "This field is required.")
    }

    // A repeated group that itself contains a quantifier or an alternation: (a+)+, (a|aa)*, (\d{2})+.
    private val riskyRepeat = Regex("""\((?:[^()\\]|\\.)*[+*}|](?:[^()\\]|\\.)*\)\s*(?:[+*]|\{\d*,)""")
    private val backReference = Regex("""\\[1-9]|\\k<""")

    /** Character reads one regex check may make before it is stopped. */
    private const val REGEX_BUDGET = 2_000_000

    /**
     * Rejects patterns that can backtrack catastrophically (repeated groups containing quantifiers
     * or alternations, back-references, very long patterns). What slips through still runs under
     * a read budget ([BudgetedChars]).
     */
    fun checkRegexPattern(pattern: String, limits: A2uiLimits): Regex {
        if (pattern.length > limits.maxRegexPatternChars) {
            throw A2uiExpressionException("regex pattern is ${pattern.length} characters; the limit is ${limits.maxRegexPatternChars}")
        }
        if (riskyRepeat.containsMatchIn(pattern) || backReference.containsMatchIn(pattern)) {
            throw A2uiExpressionException("regex pattern '$pattern' is refused: repeated groups with quantifiers or alternatives, and back-references, can run for a very long time")
        }
        return try {
            Regex(pattern)
        } catch (e: Throwable) {
            throw A2uiExpressionException("invalid regex pattern '$pattern'")
        }
    }

    fun regex(value: String, pattern: String, limits: A2uiLimits): JsonObject {
        val r = checkRegexPattern(pattern, limits)
        if (value.length > limits.maxRegexInputChars) return result(false, "Value is too long to check.")
        val matched = try {
            r.containsMatchIn(BudgetedChars(value, REGEX_BUDGET))
        } catch (_: RegexBudgetExceeded) {
            throw A2uiExpressionException("regex check stopped: the pattern takes too long on this value")
        }
        return result(matched, "Value does not match required pattern.")
    }

    fun length(v: JsonElement?, min: Double?, max: Double?): JsonObject {
        val len = when {
            v != null && v.isJsonString() -> (v as JsonPrimitive).content.length
            v is JsonArray -> v.size
            else -> 0
        }
        return when {
            min != null && len < min -> result(false, "Minimum length is ${formatPlainNumber(min)}.")
            max != null && len > max -> result(false, "Maximum length is ${formatPlainNumber(max)}.")
            else -> result(true, null)
        }
    }

    fun numeric(v: JsonElement?, min: Double?, max: Double?): JsonObject {
        val n = v?.numberOrNull() ?: v?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            ?: return result(false, "Value must be a valid number.")
        return when {
            min != null && n < min -> result(false, "Minimum value is ${formatPlainNumber(min)}.")
            max != null && n > max -> result(false, "Maximum value is ${formatPlainNumber(max)}.")
            else -> result(true, null)
        }
    }

    private val emailRegex = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")

    fun email(v: JsonElement?): JsonObject {
        val s = v?.stringOrNull()
        return result(s != null && s.length <= 320 && emailRegex.matches(s), "Must be a valid email address.")
    }

    /** `openUrl` accepts absolute http and https URLs only. */
    fun checkOpenUrl(url: String): String {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") {
            throw A2uiExpressionException("openUrl only opens http and https URLs, not '${trimmed.take(64)}'")
        }
        val rest = trimmed.substringAfter(':')
        if (!rest.startsWith("//") || rest.length <= 2 || trimmed.any { it.isWhitespace() || it.code < 0x20 }) {
            throw A2uiExpressionException("openUrl needs an absolute URL like https://example.com, not '${trimmed.take(64)}'")
        }
        return trimmed
    }

    // ---- Numbers (en-US) ----

    /** [value] with [decimals] fraction digits (0 to 3 when null), `,` grouping and `.` decimals. */
    fun formatNumber(value: Double, decimals: Int?, grouping: Boolean): String {
        if (value.isNaN()) return ""
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        val minFrac = decimals?.coerceIn(0, 20) ?: 0
        val maxFrac = decimals?.coerceIn(0, 20) ?: 3
        val (negative, intPart, fracPart) = roundDecimal(value, maxFrac)
        var frac = fracPart
        while (frac.length > minFrac && frac.endsWith('0')) frac = frac.dropLast(1)
        val int = if (grouping) group(intPart) else intPart
        val zero = intPart.all { it == '0' } && frac.all { it == '0' }
        return (if (negative && !zero) "-" else "") + int + (if (frac.isNotEmpty()) ".$frac" else "")
    }

    private val currencySymbols = mapOf(
        "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥", "INR" to "₹", "KRW" to "₩", "CNY" to "CN¥",
        "CAD" to "CA$", "AUD" to "A$", "MXN" to "MX$", "BRL" to "R$", "HKD" to "HK$", "NZD" to "NZ$",
        "TWD" to "NT$", "ILS" to "₪", "VND" to "₫", "PHP" to "₱", "XAF" to "FCFA", "XOF" to "F CFA",
    )

    fun formatCurrency(value: Double, currency: String, decimals: Int, grouping: Boolean): String {
        if (value.isNaN()) return ""
        val code = currency.trim().uppercase()
        val number = formatNumber(abs(value), decimals, grouping)
        val symbol = currencySymbols[code] ?: "$code "
        val negative = value < 0 && number.any { it in '1'..'9' }
        return (if (negative) "-" else "") + symbol + number
    }

    private fun group(digits: String): String {
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.toString()
    }

    /** Rounds half away from zero on the shortest decimal form of [value]: (negative, integer digits, [frac] digits). */
    private fun roundDecimal(value: Double, frac: Int): Triple<Boolean, String, String> {
        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
        val (digits, pointPos) = decimalDigits(abs(value))
        // digits d0 d1 ... with the decimal point after pointPos digits (may be <= 0 or > length).
        val keep = pointPos + frac
        var all = digits
        if (keep < 0) return Triple(negative, "0", "0".repeat(frac))
        var roundUp = keep < all.length && all[keep] >= '5'
        all = if (keep < all.length) all.substring(0, keep) else all + "0".repeat(keep - all.length)
        val chars = all.toCharArray()
        var i = chars.size - 1
        while (roundUp && i >= 0) {
            if (chars[i] == '9') {
                chars[i] = '0'; i--
            } else {
                chars[i] = chars[i] + 1; roundUp = false
            }
        }
        var s = chars.concatToString()
        var point = pointPos
        if (roundUp) {
            s = "1$s"; point++
        }
        val intDigits = if (point <= 0) "0" else s.substring(0, point).trimStart('0').ifEmpty { "0" }
        val fracDigits = if (point <= 0) "0".repeat(-point) + s else s.substring(point)
        return Triple(negative, intDigits, fracDigits.take(frac).padEnd(frac, '0'))
    }

    /** The significant digits of [v] (>= 0) and where the decimal point goes. */
    private fun decimalDigits(v: Double): Pair<String, Int> {
        if (v == 0.0) return "0" to 1
        val s = v.toString().lowercase()
        val mantissa = s.substringBefore('e')
        val exp = if ('e' in s) s.substringAfter('e').toInt() else 0
        val intPart = mantissa.substringBefore('.')
        val fracPart = mantissa.substringAfter('.', "")
        var digits = intPart + fracPart
        var point = intPart.length + exp
        val lead = digits.indexOfFirst { it != '0' }
        if (lead > 0) {
            digits = digits.substring(lead); point -= lead
        }
        return digits.trimEnd('0').ifEmpty { "0" } to point
    }

    // ---- Plurals (English CLDR rules) ----

    fun pluralize(value: Double, form: (String) -> String?): String {
        val category = when {
            value == 0.0 && form("zero") != null -> "zero"
            value == 1.0 && form("one") != null -> "one"
            value == 2.0 && form("two") != null -> "two"
            value == 1.0 -> "one"
            else -> "other"
        }
        return form(category) ?: form("other") ?: ""
    }

    // ---- Dates (TR35 subset, en-US names) ----

    private val monthsLong = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val weekdaysLong = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val iso = Regex("""^([+-]?\d{4,6})-(\d{2})-(\d{2})(?:[Tt ](\d{2})(?::(\d{2})(?::(\d{2})(\.\d+)?)?)?)?(Z|z|[+-]\d{2}:?\d{2})?$""")

    /** A wall-clock time as written (in its own offset), with the instant it names. */
    class DateFields(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int, val millis: Int, val epochMillis: Long)

    /** Reads an ISO 8601 date or date-time (no offset means UTC), or epoch milliseconds. */
    fun parseDate(v: JsonElement?): DateFields? {
        v?.numberOrNull()?.let { return fromEpochMillis(it.toLong()) }
        val s = v?.stringOrNull()?.trim() ?: return null
        val m = iso.matchEntire(s) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].ifEmpty { "0" }.toInt()
        val minute = g[5].ifEmpty { "0" }.toInt()
        val second = g[6].ifEmpty { "0" }.toInt()
        val millis = g[7].ifEmpty { ".0" }.drop(1).padEnd(3, '0').take(3).toInt()
        if (month !in 1..12 || day !in 1..SchemaFormats.daysInMonth(year, month) || hour > 23 || minute > 59 || second > 59) return null
        val offsetMinutes = g[8].let { o ->
            if (o.isEmpty() || o == "Z" || o == "z") 0 else {
                val sign = if (o[0] == '-') -1 else 1
                val digits = o.drop(1).replace(":", "")
                sign * (digits.take(2).toInt() * 60 + digits.drop(2).toInt())
            }
        }
        val local = daysFromCivil(year, month, day) * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
        return DateFields(year, month, day, hour, minute, second, millis, local - offsetMinutes * 60_000L)
    }

    private fun fromEpochMillis(ms: Long): DateFields {
        val days = ms.floorDiv(86_400_000L)
        val rem = ms - days * 86_400_000L
        val (y, mo, d) = civilFromDays(days)
        return DateFields(y, mo, d, (rem / 3_600_000).toInt(), (rem / 60_000 % 60).toInt(), (rem / 1000 % 60).toInt(), (rem % 1000).toInt(), ms)
    }

    /** Days since 1970-01-01 (Howard Hinnant's algorithm). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }

    fun toIsoInstant(epochMillis: Long): String {
        val f = fromEpochMillis(epochMillis)
        fun p(n: Int, w: Int = 2) = n.toString().padStart(w, '0')
        return "${p(f.year, 4)}-${p(f.month)}-${p(f.day)}T${p(f.hour)}:${p(f.minute)}:${p(f.second)}.${p(f.millis, 3)}Z"
    }

    /**
     * Formats a date with a TR35 pattern: y/Y (year), M/L (month: 1, 01, Jan, January, J),
     * d, E (Tue, Tuesday, T), a (AM/PM), h/hh, H/HH, k, K, m/mm, s/ss, S (fraction), and quoted
     * literals ('at'). `ISO` gives the UTC instant. Unparseable input gives "".
     */
    fun formatDate(value: JsonElement?, pattern: String): String {
        val f = parseDate(value) ?: return ""
        if (pattern == "ISO") return toIsoInstant(f.epochMillis)
        val weekday = (daysFromCivil(f.year, f.month, f.day) + 3).mod(7L).toInt() // 0 = Monday
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                if (pattern.getOrNull(i + 1) == '\'') {
                    sb.append('\''); i += 2; continue
                }
                i++
                while (i < pattern.length) {
                    if (pattern[i] == '\'') {
                        if (pattern.getOrNull(i + 1) == '\'') {
                            sb.append('\''); i += 2; continue
                        }
                        i++; break
                    }
                    sb.append(pattern[i]); i++
                }
                continue
            }
            if (!c.isLetter() || c.code > 0x7f) {
                sb.append(c); i++; continue
            }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            i += n
            fun num(v: Int) = v.toString().padStart(n.coerceAtMost(9), '0')
            sb.append(
                when (c) {
                    'y', 'Y', 'u' -> if (n == 2) f.year.mod(100).toString().padStart(2, '0') else num(f.year)
                    'M', 'L' -> when (n) {
                        1, 2 -> num(f.month)
                        3 -> monthsLong[f.month - 1].take(3)
                        4 -> monthsLong[f.month - 1]
                        else -> monthsLong[f.month - 1].take(1)
                    }
                    'd' -> num(f.day)
                    'E' -> when {
                        n <= 3 -> weekdaysLong[weekday].take(3)
                        n == 4 -> weekdaysLong[weekday]
                        else -> weekdaysLong[weekday].take(1)
                    }
                    'a' -> if (f.hour < 12) "AM" else "PM"
                    'h' -> num(if (f.hour % 12 == 0) 12 else f.hour % 12)
                    'H' -> num(f.hour)
                    'k' -> num(if (f.hour == 0) 24 else f.hour)
                    'K' -> num(f.hour % 12)
                    'm' -> num(f.minute)
                    's' -> num(f.second)
                    'S' -> f.millis.toString().padStart(3, '0').padEnd(n, '0').take(n)
                    else -> c.toString().repeat(n)
                },
            )
        }
        return sb.toString()
    }
}

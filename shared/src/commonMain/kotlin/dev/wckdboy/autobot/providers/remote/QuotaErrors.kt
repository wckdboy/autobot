package dev.wckdboy.autobot.providers.remote

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The usage window a subscription limit applies to (OpenCode Go: 5 hours, a week, a month). */
enum class QuotaWindow(val label: String) {
    FIVE_HOURS("5-hour"),
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
}

/**
 * Tells a used-up plan or balance (final until it resets: [ErrorKind.QUOTA]) from momentary
 * throttling ([ErrorKind.RATE_LIMITED], worth retrying), and reads when to come back.
 *
 * Subscription plans answer HTTP 429 for both, so the body decides: "Go usage limit exceeded"
 * (OpenCode Go's `GoUsageLimitError`), "Usage limit reached for 5 hours" (Z.ai), "Account budget
 * exceeded" are quota; "Rate limit exceeded" is not. 402 is always quota. The reset time comes
 * from `Retry-After` (seconds or an HTTP date), else from the body (`resets in 2h 13m`, an ISO
 * time with an offset); times without a zone are ignored rather than guessed.
 */
object QuotaErrors {

    /**
     * [error] (as the wire mapped a failed response with [status] and [body]) refined: a usage
     * limit becomes QUOTA with a message naming [provider], the window and the reset; a balance
     * or budget refusal becomes QUOTA with the provider's words; anything else keeps its kind.
     * [retryAfter] is the response's `Retry-After` header.
     */
    @OptIn(ExperimentalTime::class)
    fun refine(
        error: ChatEvent.Error,
        status: Int,
        body: String,
        retryAfter: String?,
        provider: String,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): ChatEvent.Error {
        val retryMs = retryAfterMs(retryAfter, nowMs)
        val candidate = status == 402 || status == 403 || status == 429
        return when {
            candidate && LIMIT.containsMatchIn(body) -> {
                val resetIn = retryMs ?: resetInMs(body, nowMs)
                ChatEvent.Error(ErrorKind.QUOTA, message(provider, window(body), resetIn), httpCode = status, retryAfterMs = resetIn)
            }
            candidate && CREDIT.containsMatchIn(body) -> error.copy(kind = ErrorKind.QUOTA, retryAfterMs = retryMs)
            else -> error.copy(retryAfterMs = retryMs ?: error.retryAfterMs)
        }
    }

    /** `OpenCode Go's 5-hour limit is used up; it resets in ~2 h.` */
    fun message(provider: String, window: QuotaWindow?, resetInMs: Long?): String = buildString {
        append(provider).append("'s ").append(window?.let { "${it.label} limit" } ?: "usage limit").append(" is used up")
        resetInMs?.let { append("; it resets ").append(inWords(it)) }
        append('.')
    }

    /** Whether [message] is one of [message]'s (so the UI does not wrap it again). */
    fun isLimitMessage(message: String): Boolean = LIMIT_MESSAGE.containsMatchIn(message)

    /** `in under a minute`, `in ~25 min`, `in ~2 h`, `in ~3 days`. */
    fun inWords(ms: Long): String {
        val minutes = (ms + 30_000) / 60_000
        return when {
            minutes < 1 -> "in under a minute"
            minutes < 60 -> "in ~$minutes min"
            minutes < 48 * 60 -> "in ~${(minutes + 30) / 60} h"
            else -> "in ~${(minutes + 12 * 60) / (24 * 60)} days"
        }
    }

    /** The window a limit message names, if any. */
    fun window(text: String): QuotaWindow? = when {
        FIVE_HOURS.containsMatchIn(text) -> QuotaWindow.FIVE_HOURS
        WEEK.containsMatchIn(text) -> QuotaWindow.WEEKLY
        MONTH.containsMatchIn(text) -> QuotaWindow.MONTHLY
        DAY.containsMatchIn(text) -> QuotaWindow.DAILY
        else -> null
    }

    /** `Retry-After` in milliseconds from [nowMs]: delta seconds or an HTTP date. */
    fun retryAfterMs(header: String?, nowMs: Long): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
        return httpDate(value)?.let { (it - nowMs).coerceAtLeast(0) }
    }

    /** A reset time in the body: relative (`resets in 2h 13m`) or absolute with a zone. */
    @OptIn(ExperimentalTime::class)
    fun resetInMs(body: String, nowMs: Long): Long? {
        RELATIVE.find(body)?.let { m ->
            val total = UNIT.findAll(m.groupValues[1]).sumOf { u ->
                val n = u.groupValues[1].toLong()
                when (u.groupValues[2].first().lowercaseChar()) {
                    'd' -> n * 86_400_000
                    'h' -> n * 3_600_000
                    'm' -> n * 60_000
                    else -> n * 1000
                }
            }
            if (total > 0) return total
        }
        val iso = ISO.find(body)?.value ?: return null
        val normalized = iso.replace(' ', 'T').replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        val at = runCatching { Instant.parse(normalized).toEpochMilliseconds() }.getOrNull() ?: return null
        return (at - nowMs).takeIf { it > 0 }
    }

    /** RFC 9110 IMF-fixdate (`Wed, 21 Oct 2026 07:28:00 GMT`) in epoch milliseconds. */
    @OptIn(ExperimentalTime::class)
    private fun httpDate(value: String): Long? {
        val m = HTTP_DATE.find(value) ?: return null
        val (day, mon, year, time) = m.destructured
        val month = MONTHS.indexOf(mon.lowercase()).takeIf { it >= 0 } ?: return null
        val iso = "$year-${(month + 1).toString().padStart(2, '0')}-${day.padStart(2, '0')}T${time}Z"
        return runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
    }

    private val I = setOf(RegexOption.IGNORE_CASE)

    /** A plan's usage limit; "rate limit" alone is throttling and deliberately not matched. */
    private val LIMIT = Regex(
        "usagelimit|(usage|spending|subscription|plan|5[- ]?hours?|five[- ]hours?|hourly|daily|weekly|monthly)[ _-]?limits?\\b|limit[ _-]?exhausted",
        I,
    )
    private val CREDIT = Regex("insufficient[ _-]?(quota|balance|funds|credits?)|quota[ _-]?exceeded|budget[ _-]?exceeded|credit[ _-]?limit|out of credits?", I)
    private val LIMIT_MESSAGE = Regex("'s (5-hour|daily|weekly|monthly|usage) limit is used up")
    private val FIVE_HOURS = Regex("\\b(5|five)[ -]?h(ou)?rs?\\b|\\b5h\\b", I)
    private val WEEK = Regex("week", I)
    private val MONTH = Regex("month", I)
    private val DAY = Regex("daily|\\bper day\\b|24[ -]?h(ou)?rs?\\b", I)
    private val RELATIVE = Regex(
        "(?:resets?|try again|retry|available again)\\s+(?:in|after)\\s+((?:\\d+\\s*(?:days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b[\\s,]*(?:and\\s+)?)+)",
        I,
    )
    private val UNIT = Regex("(\\d+)\\s*(days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b", I)
    private val ISO = Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})")
    private val HTTP_DATE = Regex("(\\d{1,2}) ([A-Za-z]{3}) (\\d{4}) (\\d{2}:\\d{2}:\\d{2}) GMT")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
}

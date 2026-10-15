package dev.wckdboy.autobot.providers.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bodies from OpenCode's own provider-error fixtures (Go, Zen, Z.ai) and common throttling replies. */
class QuotaErrorsTest {
    private val now = 1_792_065_600_000L // 2026-10-15T12:00:00Z

    private fun refine(status: Int, body: String, retryAfter: String? = null, provider: String = "OpenCode Go") =
        QuotaErrors.refine(OpenAiCodec.httpError(status, body), status, body, retryAfter, provider, now)

    @Test
    fun goUsageLimitIsQuotaNotThrottling() {
        val e = refine(429, """{"type":"error","error":{"type":"GoUsageLimitError","message":"Go usage limit exceeded"}}""", retryAfter = "7200")
        assertEquals(ErrorKind.QUOTA, e.kind)
        assertEquals("OpenCode Go's usage limit is used up; it resets in ~2 h.", e.message)
        assertEquals(7_200_000L, e.retryAfterMs)
        assertEquals(429, e.httpCode)
    }

    @Test
    fun windowAndResetComeFromTheBodyWhenThereIsNoHeader() {
        val zai = refine(429, """{"error":{"code":"1308","message":"Usage limit reached for 5 hours. Try again in 1h 30m."}}""", provider = "Z.ai (GLM)")
        assertEquals("Z.ai (GLM)'s 5-hour limit is used up; it resets in ~2 h.", zai.message)
        assertEquals(90 * 60_000L, zai.retryAfterMs)

        val weekly = refine(429, """{"error":{"message":"Weekly usage limit exceeded, resets at 2026-10-18T12:00:00Z"}}""")
        assertEquals("OpenCode Go's weekly limit is used up; it resets in ~3 days.", weekly.message)
        val monthly = refine(429, """{"error":{"message":"Monthly limit exhausted. Your limit will reset at 2026-11-01 00:00:00"}}""")
        // A time without a zone is not guessed.
        assertEquals("OpenCode Go's monthly limit is used up.", monthly.message)
        assertNull(monthly.retryAfterMs)
    }

    @Test
    fun budgetsAndBalancesAreQuotaWithTheProvidersWords() {
        val budget = refine(429, """{"error":{"type":"server_error","message":"Upstream request failed: Account budget exceeded"}}""")
        assertEquals(ErrorKind.QUOTA, budget.kind)
        assertEquals("HTTP 429: Upstream request failed: Account budget exceeded", budget.message)
        assertEquals(ErrorKind.QUOTA, refine(402, """{"error":{"message":"Insufficient Balance"}}""").kind)
        assertEquals(ErrorKind.QUOTA, refine(402, "Payment Required").kind)
    }

    @Test
    fun plainRateLimitsStayRetryableAndKeepTheirRetryAfter() {
        val e = refine(429, """{"error":{"message":"Rate limit exceeded. Please try again later."}}""", retryAfter = "12")
        assertEquals(ErrorKind.RATE_LIMITED, e.kind)
        assertEquals(12_000L, e.retryAfterMs)
        assertEquals(ErrorKind.RATE_LIMITED, refine(429, """{"error":{"message":"Rate limit reached for requests"}}""").kind)
        assertEquals(ErrorKind.SERVER, refine(503, "usage limit").kind) // only 402/403/429 are candidates
        assertEquals(ErrorKind.UNAUTHORIZED, refine(401, """{"error":{"message":"Invalid credential"}}""").kind)
    }

    @Test
    fun retryAfterAcceptsSecondsAndHttpDates() {
        assertEquals(5_000L, QuotaErrors.retryAfterMs("5", now))
        assertEquals(3_600_000L, QuotaErrors.retryAfterMs("Thu, 15 Oct 2026 13:00:00 GMT", now))
        assertEquals(0L, QuotaErrors.retryAfterMs("Thu, 15 Oct 2026 11:00:00 GMT", now))
        assertNull(QuotaErrors.retryAfterMs("soon", now))
        assertNull(QuotaErrors.retryAfterMs(null, now))
    }

    @Test
    fun resetTimesInWords() {
        assertEquals("in under a minute", QuotaErrors.inWords(20_000))
        assertEquals("in ~25 min", QuotaErrors.inWords(25 * 60_000L))
        assertEquals("in ~2 h", QuotaErrors.inWords(100 * 60_000L))
        assertEquals("in ~5 days", QuotaErrors.inWords(5 * 86_400_000L))
        assertTrue(QuotaErrors.isLimitMessage(QuotaErrors.message("X", QuotaWindow.FIVE_HOURS, null)))
        assertTrue(!QuotaErrors.isLimitMessage("HTTP 402: Insufficient balance"))
    }
}

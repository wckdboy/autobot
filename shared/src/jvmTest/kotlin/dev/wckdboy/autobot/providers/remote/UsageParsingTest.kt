package dev.wckdboy.autobot.providers.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Token usage as each wire reports it: cached input, cache writes and reasoning broken out. */
class UsageParsingTest {

    private fun data(json: String) = "data: $json"

    private fun openAi(json: String): ChatEvent.Usage = SseDecoder().feed(data(json)).filterIsInstance<ChatEvent.Usage>().single()

    @Test
    fun openAiUsageReadsCachedAndReasoningDetails() {
        val usage = openAi(
            """{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500,
               "prompt_tokens_details":{"cached_tokens":1024,"audio_tokens":0},
               "completion_tokens_details":{"reasoning_tokens":256}}}""",
        )
        assertEquals(ChatEvent.Usage(1200, 300, 1500, reasoningTokens = 256, cacheReadTokens = 1024), usage)
    }

    @Test
    fun openAiUsageWithZeroDetailsLeavesThemUnset() {
        val usage = openAi(
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,
               "prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}}}""",
        )
        assertEquals(ChatEvent.Usage(10, 5, 15), usage)
    }

    @Test
    fun deepSeekCacheHitsCountAsCachedInput() {
        val usage = openAi(
            """{"choices":[],"usage":{"prompt_tokens":900,"completion_tokens":40,"total_tokens":940,
               "prompt_cache_hit_tokens":768,"prompt_cache_miss_tokens":132}}""",
        )
        assertEquals(768, usage.cacheReadTokens)
        assertEquals(900, usage.promptTokens)
    }

    @Test
    fun moonshotTopLevelCachedTokens() {
        val usage = openAi("""{"choices":[],"usage":{"prompt_tokens":500,"completion_tokens":20,"total_tokens":520,"cached_tokens":256}}""")
        assertEquals(256, usage.cacheReadTokens)
    }

    @Test
    fun groqStreamedUsageInXGroq() {
        val usage = openAi("""{"choices":[],"x_groq":{"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}}""")
        assertEquals(ChatEvent.Usage(7, 3, 10), usage)
    }

    @Test
    fun anthropicCacheReadsAndWritesFromMessageStart() {
        val decoder = AnthropicSseDecoder()
        val events = listOf(
            data("""{"type":"message_start","message":{"usage":{"input_tokens":40,"cache_creation_input_tokens":2000,"cache_read_input_tokens":8000,"output_tokens":1}}}"""),
            data("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":120}}"""),
            data("""{"type":"message_stop"}"""),
        ).flatMap(decoder::feed)
        val usage = events.filterIsInstance<ChatEvent.Usage>().single()
        assertEquals(ChatEvent.Usage(10_040, 120, 10_160, cacheReadTokens = 8000, cacheWriteTokens = 2000), usage)
    }

    @Test
    fun anthropicCumulativeMessageDeltaUpdatesTheCounts() {
        val decoder = AnthropicSseDecoder()
        val events = listOf(
            data("""{"type":"message_start","message":{"usage":{"input_tokens":5,"output_tokens":1}}}"""),
            // Newer streams repeat the input counts in message_delta (cumulative), cache fields included.
            data("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":5,"cache_read_input_tokens":300,"output_tokens":9}}"""),
        ).flatMap(decoder::feed)
        assertEquals(ChatEvent.Usage(305, 9, 314, cacheReadTokens = 300), events.filterIsInstance<ChatEvent.Usage>().single())
    }

    @Test
    fun anthropicDeltaWithOnlyOutputKeepsTheStartCounts() {
        val decoder = AnthropicSseDecoder()
        val events = listOf(
            data("""{"type":"message_start","message":{"usage":{"input_tokens":5,"cache_creation_input_tokens":100,"output_tokens":1}}}"""),
            data("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":5,"output_tokens":9}}"""),
        ).flatMap(decoder::feed)
        val usage = events.filterIsInstance<ChatEvent.Usage>().single()
        assertEquals(105, usage.promptTokens)
        assertEquals(100, usage.cacheWriteTokens)
        assertNull(usage.cacheReadTokens)
    }

    @Test
    fun onDeviceEngineUsage() {
        val usage = LocalLlmCodec.decodeResult(
            """{"content":"hi","tool_calls":[],"finish_reason":"stop","usage":{"prompt_tokens":100,"completion_tokens":12,"reasoning_tokens":0}}""",
        ).filterIsInstance<ChatEvent.Usage>().single()
        assertEquals(ChatEvent.Usage(100, 12, 112), usage)
    }

    // ---- rate-limit reset headers --------------------------------------------------------------

    private val throttled = ChatEvent.Error(ErrorKind.RATE_LIMITED, "Rate limit reached", httpCode = 429)

    @Test
    fun goDurations() {
        assertEquals(1_000L, QuotaErrors.goDurationMs("1s"))
        assertEquals(360_000L, QuotaErrors.goDurationMs("6m0s"))
        assertEquals(3_723_500L, QuotaErrors.goDurationMs("1h2m3.5s"))
        assertEquals(250L, QuotaErrors.goDurationMs("250ms"))
        assertNull(QuotaErrors.goDurationMs("soon"))
    }

    @Test
    fun openAiResetHeadersPickTheExhaustedLimit() {
        val headers = mapOf(
            "x-ratelimit-remaining-requests" to "120",
            "x-ratelimit-reset-requests" to "1s",
            "x-ratelimit-remaining-tokens" to "0",
            "x-ratelimit-reset-tokens" to "6m0s",
        )
        val refined = QuotaErrors.refine(throttled, 429, """{"error":{"message":"Rate limit reached"}}""", headers, "OpenAI", nowMs = 0)
        assertEquals(ErrorKind.RATE_LIMITED, refined.kind)
        assertEquals(360_000L, refined.retryAfterMs)
    }

    @Test
    fun anthropicResetHeadersAreTimes() {
        val now = 1_760_000_000_000L
        val headers = mapOf(
            "anthropic-ratelimit-requests-remaining" to "0",
            "anthropic-ratelimit-requests-reset" to "2025-10-09T08:53:40Z", // now + 20 s
        )
        val refined = QuotaErrors.refine(throttled, 429, "rate_limit_error", headers, "Anthropic", nowMs = now)
        assertEquals(20_000L, refined.retryAfterMs)
    }

    @Test
    fun retryAfterWinsOverResetHeaders() {
        val headers = mapOf("retry-after" to "7", "x-ratelimit-reset-requests" to "1m")
        assertEquals(7_000L, QuotaErrors.refine(throttled, 429, "slow down", headers, "Groq", nowMs = 0).retryAfterMs)
    }

    @Test
    fun aPlanLimitIgnoresPerMinuteResetHeaders() {
        val headers = mapOf("x-ratelimit-reset-requests" to "30s")
        val body = """{"type":"error","error":{"type":"GoUsageLimitError","message":"Go usage limit exceeded for 5 hours"}}"""
        val refined = QuotaErrors.refine(throttled, 429, body, headers, "OpenCode Go", nowMs = 0)
        assertEquals(ErrorKind.QUOTA, refined.kind)
        assertNull(refined.retryAfterMs)
        assertEquals("OpenCode Go's 5-hour limit is used up.", refined.message)
    }
}

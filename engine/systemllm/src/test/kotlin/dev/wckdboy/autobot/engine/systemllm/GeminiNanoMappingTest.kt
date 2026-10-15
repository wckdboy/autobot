package dev.wckdboy.autobot.engine.systemllm

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.SystemAvailability
import dev.wckdboy.autobot.providers.remote.SystemModels
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiNanoMappingTest {

    @Test
    fun mapsFeatureStatus() {
        val ready = GeminiNanoMapping.status(FeatureStatus.AVAILABLE, null, null)
        assertEquals(SystemModels.GEMINI_NANO, ready.id)
        assertEquals(true, ready.usable)
        assertEquals("42%", GeminiNanoMapping.status(FeatureStatus.DOWNLOADING, 0.42f, null).detail)
        assertEquals(SystemAvailability.DOWNLOADABLE, GeminiNanoMapping.status(FeatureStatus.DOWNLOADABLE, null, null).availability)
        val none = GeminiNanoMapping.status(FeatureStatus.UNAVAILABLE, null, null)
        assertEquals(SystemAvailability.UNAVAILABLE, none.availability)
        assertEquals("unavailable · this phone does not support Gemini Nano (needs AICore)", none.summary)
    }

    @Test
    fun mapsErrors() {
        val big = GeminiNanoMapping.error(GenAiException.ErrorCode.REQUEST_TOO_LARGE, "too many tokens")
        assertEquals("CONTEXT_WINDOW_EXCEEDED", big.code)
        assertEquals(true, big.message.startsWith("context length exceeded"))
        assertEquals(ErrorKind.RATE_LIMITED, GeminiNanoMapping.error(GenAiException.ErrorCode.BUSY, null).kind)
        assertEquals(
            ChatEvent.Error(ErrorKind.BAD_REQUEST, "Gemini Nano only answers while Autobot is on screen"),
            GeminiNanoMapping.error(GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED, "blocked"),
        )
        assertEquals("length", GeminiNanoMapping.finishReason(1))
        assertEquals("stop", GeminiNanoMapping.finishReason(0))
        assertEquals("stop", GeminiNanoMapping.finishReason(null))
    }
}

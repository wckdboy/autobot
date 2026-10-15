package dev.wckdboy.autobot.feature.chat.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineVoicesTest {

    private fun voice(name: String, tag: String, network: Boolean = false, installed: Boolean = true, quality: Int = 300, latency: Int = 300) =
        VoiceOption(name, Locale.forLanguageTag(tag), network, installed, quality, latency)

    @Test
    fun neverPicksANetworkVoice() {
        val voices = listOf(voice("de-network", "de-DE", network = true, quality = 500), voice("de-local", "de-DE", quality = 300))
        assertEquals("de-local", OfflineVoices.pick(voices, Locale.GERMANY)?.name)
        assertNull(OfflineVoices.pick(voices.take(1), Locale.GERMANY))
    }

    @Test
    fun skipsVoicesWhoseDataIsNotDownloaded() {
        assertNull(OfflineVoices.pick(listOf(voice("fr", "fr-FR", installed = false)), Locale.FRANCE))
    }

    @Test
    fun prefersTheLocaleThenItsLanguageThenEnglish() {
        val voices = listOf(voice("en-us", "en-US"), voice("de-at", "de-AT"), voice("fr-fr", "fr-FR"))
        assertEquals("de-at", OfflineVoices.pick(voices, Locale.GERMANY)?.name)
        assertEquals("en-us", OfflineVoices.pick(voices, Locale.forLanguageTag("ja-JP"))?.name)
        assertEquals("fr-fr", OfflineVoices.pick(voices + voice("fr-ca", "fr-CA", quality = 500), Locale.FRANCE)?.name)
        assertEquals("fr-fr", OfflineVoices.pick(listOf(voice("fr-fr", "fr-FR")), Locale.forLanguageTag("ja-JP"))?.name)
    }

    @Test
    fun thenHigherQualityAndLowerLatency() {
        val voices = listOf(voice("a", "en-US", quality = 300), voice("b", "en-US", quality = 400, latency = 400), voice("c", "en-US", quality = 400, latency = 200))
        assertEquals("c", OfflineVoices.pick(voices, Locale.US)?.name)
    }
}

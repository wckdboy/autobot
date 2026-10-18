package dev.wckdboy.autobot.feature.settings.providers

import dev.wckdboy.autobot.providers.remote.ProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The add-provider editor's validation: every reason Save is disabled is named. */
class ProviderDraftTest {
    private val deepseek = ProviderDraft.fromPreset(ProviderPresets.byId(ProviderPresets.DEEPSEEK)!!)

    @Test
    fun aCompletePresetCanSave() {
        val draft = deepseek.copy(apiKeyInput = "sk-abc123")
        assertNull(draft.saveProblem)
        assertTrue(draft.canSave)
        assertFalse(draft.missingKey)
        assertNull(draft.keyProblem)
    }

    @Test
    fun eachMissingFieldSaysSo() {
        assertEquals("Give the provider a name", deepseek.copy(displayName = " ").saveProblem)
        assertEquals("Enter the base URL", deepseek.copy(baseUrl = "").saveProblem)
        assertEquals("The base URL is not a valid URL", deepseek.copy(baseUrl = "not a url").saveProblem)
        assertEquals("Pick or type a model", deepseek.copy(defaultModel = "").saveProblem)
        assertFalse(deepseek.copy(defaultModel = "").canSave)
    }

    @Test
    fun cleartextToARemoteHostIsRefusedButLoopbackIsFine() {
        val remote = deepseek.copy(baseUrl = "http://192.168.1.20:11434/v1")
        assertTrue(remote.cleartextProblem!!.startsWith("Use https"))
        assertEquals(remote.cleartextProblem, remote.saveProblem)
        assertNull(deepseek.copy(baseUrl = "http://127.0.0.1:11434/v1").cleartextProblem)
        assertNull(deepseek.copy(baseUrl = "http://localhost:8080").cleartextProblem)
    }

    @Test
    fun aPresetThatNeedsAKeyWithoutOneIsFlagged() {
        assertTrue(deepseek.missingKey)
        assertFalse(deepseek.copy(hasStoredKey = true).missingKey)
        assertTrue(deepseek.copy(hasStoredKey = true, clearStoredKey = true).missingKey)
    }

    @Test
    fun aMangledKeyIsNamedWhileTyping() {
        assertEquals("The API key contains a space — paste only the key", deepseek.copy(apiKeyInput = "sk-abc 123").keyProblem)
        assertTrue(deepseek.copy(apiKeyInput = "sk-abc\u00E9123").keyProblem!!.contains("U+00E9"))
        assertNull("invisible characters are cleaned, not reported", deepseek.copy(apiKeyInput = "sk-abc\u200B123").keyProblem)
        assertNull(deepseek.copy(apiKeyInput = "  sk-abc123\n").keyProblem)
    }

    @Test
    fun theKeyIsNeverPrinted() {
        assertFalse(deepseek.copy(apiKeyInput = "sk-secret").toString().contains("sk-secret"))
    }

    @Test
    fun aNodeSavesWithItsNameOnlyUrl() {
        val node = ProviderDraft.fromPreset(ProviderPresets.byId(ProviderPresets.INVINCIBLE)!!).copy(id = "n1", displayName = "studio")
        assertTrue(node.isNode)
        assertNull(node.cleartextProblem)
        assertNull(node.saveProblem)
        assertFalse(node.missingKey)
    }

    @Test
    fun aPairingDraftChecksItsCode() {
        assertEquals("Not an Invincible pairing code", PairingDraft(code = "hello", deviceName = "Pixel").problem)
        val good = PairingDraft(code = "invincible://pair?v=0&k=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc&s=CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk&a=192.168.1.20:7778", deviceName = "Pixel")
        assertNull(good.problem)
        assertEquals("0707-0707-0707-0707", good.fingerprint)
        assertFalse(good.toString().contains("invincible://"))
        assertEquals("Name this phone", good.copy(deviceName = " ").problem)
    }
}

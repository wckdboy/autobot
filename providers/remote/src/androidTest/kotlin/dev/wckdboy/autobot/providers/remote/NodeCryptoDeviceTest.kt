package dev.wckdboy.autobot.providers.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wckdboy.autobot.shared.invincible.JvmNodeCrypto
import dev.wckdboy.autobot.shared.invincible.NodeConformance
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The node crypto on Android's own provider (Conscrypt), not the desktop JDK the JVM tests use:
 * X25519 and Ed25519 keys in their RFC 8410 encodings, ChaCha20-Poly1305, SHA-256 and HMAC, checked
 * against the node's Noise transcripts (snow) and receipt signatures (OpenSSL).
 */
@RunWith(AndroidJUnit4::class)
class NodeCryptoDeviceTest {
    private fun vectors(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("invincible/$name.json").bufferedReader().readText()

    @Test
    fun noiseMatchesTheNodeOnThisDevice() {
        val failures = NodeConformance.noise(JvmNodeCrypto, vectors("noise"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun receiptsVerifyOnThisDevice() {
        val failures = NodeConformance.receipts(JvmNodeCrypto, vectors("receipts"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** RFC 8032 §7.1 test 1 (empty message), straight through the provider, reporting why it fails. */
    @Test
    fun ed25519OnThisDevice() {
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val key = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        val providers = java.security.Security.getProviders().joinToString { it.name }
        val result = runCatching { JvmNodeCrypto.ed25519Verify(key, ByteArray(0), signature) }
        assertTrue("providers: $providers; result: $result; ${result.exceptionOrNull()?.stackTraceToString()?.take(1500)}", result.getOrNull() == true)
    }
}

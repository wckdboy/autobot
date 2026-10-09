package dev.wckdboy.autobot.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretTest {

    @Test
    fun toStringIsRedacted() {
        val secret = Secret("sk-live-1234567890")
        assertEquals(Secret.REDACTED, secret.toString())
        assertFalse("$secret".contains("sk-live"))
        assertEquals("sk-live-1234567890", secret.reveal())
    }

    @Test
    fun rawKeyLiteralFormat() {
        val literal = KeyManager.rawKeyLiteral(byteArrayOf(0x00, 0x0f, 0xff.toByte(), 0x10)).decodeToString()
        assertEquals("x'000fff10'", literal)
    }
}

package dev.wckdboy.autobot.shared.invincible

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PairedNodeTest {
    private val crypto = JvmNodeCrypto

    @Test
    fun theSecretRoundTrips() {
        val device = NodeKeyPair.generate(crypto)
        val node = PairedNode("studio", "Pixel", ByteArray(32) { 7 }, listOf("192.168.1.20:7778", "[fd00::1]:7778"), "receipt-key", device)
        val back = PairedNode.fromSecret(node.toSecret())!!
        assertEquals("studio", back.name)
        assertEquals("Pixel", back.deviceName)
        assertArrayEquals(node.nodeKey, back.nodeKey)
        assertEquals(node.addresses, back.addresses)
        assertEquals("receipt-key", back.receiptKey)
        assertArrayEquals(device.privateKey, back.device.privateKey)
        assertArrayEquals(device.publicKey, back.device.publicKey)
    }

    @Test
    fun anythingElseIsNotASecret() {
        assertNull(PairedNode.fromSecret("sk-not-a-node"))
        assertNull(PairedNode.fromSecret("""{"v":1}"""))
    }

    @Test
    fun printingNeverShowsTheKey() {
        val device = NodeKeyPair.generate(crypto)
        val node = PairedNode("studio", "Pixel", ByteArray(32) { 7 }, listOf("10.0.0.2:7778"), null, device)
        assertFalse(node.toString().contains(device.privateKey.joinToString("")))
        assertFalse(device.toString().contains("privateKey"))
    }
}

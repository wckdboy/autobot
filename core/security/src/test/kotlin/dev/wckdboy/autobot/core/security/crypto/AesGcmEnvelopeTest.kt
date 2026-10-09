package dev.wckdboy.autobot.core.security.crypto

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmEnvelopeTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val plaintext = "sk-very-secret-api-key".encodeToByteArray()

    @Test
    fun roundTrip() {
        val sealed = AesGcmEnvelope.seal(key, plaintext)
        assertArrayEquals(plaintext, AesGcmEnvelope.open(key, sealed))
    }

    @Test
    fun roundTripWithAad() {
        val aad = "ref-1".encodeToByteArray()
        val sealed = AesGcmEnvelope.seal(key, plaintext, aad)
        assertArrayEquals(plaintext, AesGcmEnvelope.open(key, sealed, aad))
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        val sealed = AesGcmEnvelope.seal(key, ByteArray(0))
        assertEquals(0, AesGcmEnvelope.open(key, sealed).size)
    }

    @Test
    fun headerLayout() {
        val sealed = AesGcmEnvelope.seal(key, plaintext)
        assertEquals(AesGcmEnvelope.VERSION, sealed[0])
        assertEquals(AesGcmEnvelope.GCM_IV_LENGTH, sealed[1].toInt())
        // version + ivLen + iv + ciphertext + 16 byte tag
        assertEquals(2 + 12 + plaintext.size + 16, sealed.size)
    }

    @Test
    fun ivIsFreshPerEncryption() {
        val a = AesGcmEnvelope.decode(AesGcmEnvelope.seal(key, plaintext))
        val b = AesGcmEnvelope.decode(AesGcmEnvelope.seal(key, plaintext))
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    fun encodeDecodeAreInverse() {
        val parts = AesGcmEnvelope.Parts(ByteArray(12) { it.toByte() }, ByteArray(20) { (it * 3).toByte() })
        val decoded = AesGcmEnvelope.decode(AesGcmEnvelope.encode(parts))
        assertArrayEquals(parts.iv, decoded.iv)
        assertArrayEquals(parts.ciphertext, decoded.ciphertext)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val sealed = AesGcmEnvelope.seal(key, plaintext)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.open(key, sealed) }
    }

    @Test
    fun tamperedIvIsRejected() {
        val sealed = AesGcmEnvelope.seal(key, plaintext)
        sealed[3] = (sealed[3].toInt() xor 0x01).toByte()
        assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.open(key, sealed) }
    }

    @Test
    fun wrongAadIsRejected() {
        val sealed = AesGcmEnvelope.seal(key, plaintext, "ref-1".encodeToByteArray())
        assertThrows(GeneralSecurityException::class.java) {
            AesGcmEnvelope.open(key, sealed, "ref-2".encodeToByteArray())
        }
    }

    @Test
    fun wrongKeyIsRejected() {
        val sealed = AesGcmEnvelope.seal(key, plaintext)
        assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.open(newKey(), sealed) }
    }

    @Test
    fun unknownVersionIsRejected() {
        val sealed = AesGcmEnvelope.seal(key, plaintext)
        sealed[0] = 9
        assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.decode(sealed) }
    }

    @Test
    fun truncatedEnvelopeIsRejected() {
        val sealed = AesGcmEnvelope.seal(key, plaintext)
        assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.decode(sealed.copyOf(10)) }
        assertThrows(GeneralSecurityException::class.java) { AesGcmEnvelope.decode(ByteArray(1)) }
    }
}

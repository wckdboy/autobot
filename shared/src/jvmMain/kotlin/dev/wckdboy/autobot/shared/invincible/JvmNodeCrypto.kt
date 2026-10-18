package dev.wckdboy.autobot.shared.invincible

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * [NodeCrypto] on javax.crypto and java.security (Android 14+ and JVM tests). Raw 32-byte keys are
 * wrapped in their fixed DER headers (RFC 8410), which every provider accepts, instead of the
 * BigInteger and point specs, whose byte orders are easy to get wrong.
 */
object JvmNodeCrypto : NodeCrypto {
    private val random = SecureRandom()
    private val X25519_PRIVATE = hex("302e020100300506032b656e04220420")
    private val X25519_PUBLIC = hex("302a300506032b656e032100")

    /** RFC 7748 base point u = 9. */
    private val BASE_POINT = ByteArray(32).also { it[0] = 9 }

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun x25519PublicKey(privateKey: ByteArray): ByteArray = x25519(privateKey, BASE_POINT)

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32 && publicKey.size == 32) { "X25519 keys are 32 bytes" }
        val factory = KeyFactory.getInstance("XDH")
        val agreement = KeyAgreement.getInstance("XDH")
        agreement.init(factory.generatePrivate(PKCS8EncodedKeySpec(X25519_PRIVATE + privateKey)))
        agreement.doPhase(factory.generatePublic(X509EncodedKeySpec(X25519_PUBLIC + publicKey)), true)
        return agreement.generateSecret()
    }

    override fun chachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        chacha(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    override fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray =
        chacha(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)

    private fun chacha(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (aad.isNotEmpty()) updateAAD(aad)
        }

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /**
     * BouncyCastle's RFC 8032 verifier, called directly: Android's software provider has no
     * Ed25519 (its only "Ed25519" KeyFactory is the Android Keystore's, for keys made inside it).
     * The same code runs in the JVM tests, against signatures OpenSSL made.
     */
    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519.PUBLIC_KEY_SIZE || signature.size != Ed25519.SIGNATURE_SIZE) return false
        return Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

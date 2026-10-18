package dev.wckdboy.autobot.shared.invincible

/**
 * Crypto primitives for talking to an Invincible node, provided by each platform (javax.crypto on
 * Android and the JVM, CryptoKit on iOS). Everything Noise needs (`25519_ChaChaPoly_SHA256`) and
 * Ed25519 verification for receipts; no primitive is implemented in common code.
 *
 * Keys and outputs are raw bytes: X25519 keys and shared secrets are 32 bytes as RFC 7748 writes
 * them, Ed25519 public keys 32 bytes and signatures 64 (RFC 8032).
 */
interface NodeCrypto {
    fun randomBytes(count: Int): ByteArray

    /** The X25519 public key of [privateKey]. */
    fun x25519PublicKey(privateKey: ByteArray): ByteArray

    /** X25519(privateKey, publicKey). */
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    /** ChaCha20-Poly1305 (RFC 8439) with a 12-byte nonce; returns ciphertext ‖ 16-byte tag. */
    fun chachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** Inverse of [chachaSeal]; throws when authentication fails. */
    fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray

    fun sha256(data: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** Whether [signature] is [publicKey]'s Ed25519 signature of [message]. */
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** An X25519 static keypair. */
class NodeKeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    override fun toString(): String = "NodeKeyPair(${fingerprint(publicKey)})"

    companion object {
        fun generate(crypto: NodeCrypto): NodeKeyPair {
            val private = crypto.randomBytes(32)
            return NodeKeyPair(private, crypto.x25519PublicKey(private))
        }

        fun of(crypto: NodeCrypto, privateKey: ByteArray): NodeKeyPair = NodeKeyPair(privateKey, crypto.x25519PublicKey(privateKey))
    }
}

/** A key's fingerprint for people to compare (spec/pairing.md): the first 8 bytes in hex groups of four. */
fun fingerprint(publicKey: ByteArray): String =
    publicKey.take(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.chunked(4).joinToString("-")

/**
 * [NodeCrypto] as Swift implements it (CryptoKit): failures come back as null, because a Swift
 * implementation cannot throw into Kotlin. [nodeCrypto] turns it into a [NodeCrypto].
 */
interface HostNodeCrypto {
    fun randomBytes(count: Int): ByteArray
    fun x25519PublicKey(privateKey: ByteArray): ByteArray?
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray?
    fun chachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray?
    fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

class NodeCryptoException(message: String) : Exception(message)

fun nodeCrypto(host: HostNodeCrypto): NodeCrypto = object : NodeCrypto {
    override fun randomBytes(count: Int) = host.randomBytes(count)
    override fun x25519PublicKey(privateKey: ByteArray) = host.x25519PublicKey(privateKey) ?: throw NodeCryptoException("bad X25519 key")
    override fun x25519(privateKey: ByteArray, publicKey: ByteArray) = host.x25519(privateKey, publicKey) ?: throw NodeCryptoException("X25519 failed")
    override fun chachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) =
        host.chachaSeal(key, nonce, aad, plaintext) ?: throw NodeCryptoException("encryption failed")
    override fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray) =
        host.chachaOpen(key, nonce, aad, sealed) ?: throw NodeCryptoException("authentication failed")
    override fun sha256(data: ByteArray) = host.sha256(data)
    override fun hmacSha256(key: ByteArray, data: ByteArray) = host.hmacSha256(key, data)
    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray) = host.ed25519Verify(publicKey, message, signature)
}

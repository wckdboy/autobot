package dev.wckdboy.autobot.shared.invincible

/**
 * The Noise Protocol Framework (revision 34), just the two handshakes Invincible uses
 * (spec/pairing.md): `IK` for sessions and `IKpsk1` for pairing, with `25519_ChaChaPoly_SHA256`.
 * Both roles are here so the two sides can be tested against each other; Autobot is the initiator.
 *
 * Checked byte for byte against snow, the node's implementation, with `spec/vectors/noise.json`.
 */
class NoiseHandshake private constructor(
    private val crypto: NodeCrypto,
    private val initiator: Boolean,
    private val local: NodeKeyPair,
    private var remoteStatic: ByteArray?,
    private val psk: ByteArray?,
    private val fixedEphemeral: ByteArray?,
    protocol: String,
    prologue: ByteArray,
) {
    private val symmetric = SymmetricState(crypto, protocol)
    private var ephemeral: NodeKeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var step = 0

    init {
        symmetric.mixHash(prologue)
        // Pre-message `<- s`: both sides start with the responder's static key.
        symmetric.mixHash(if (initiator) remoteStatic!! else local.publicKey)
    }

    /** The other side's static key, once its first message is read (the responder learns it). */
    val remoteStaticKey: ByteArray? get() = remoteStatic

    val finished: Boolean get() = step == 2

    /** The next handshake message, carrying [payload]. */
    fun write(payload: ByteArray): ByteArray {
        check((step == 0 && initiator) || (step == 1 && !initiator)) { "not this side's turn to write" }
        val out = ArrayList<Byte>()
        if (step == 0) {
            writeEphemeral(out)
            symmetric.mixKey(crypto.x25519(ephemeral!!.privateKey, remoteStatic!!)) // es
            out += symmetric.encryptAndHash(local.publicKey).toList() // s
            symmetric.mixKey(crypto.x25519(local.privateKey, remoteStatic!!)) // ss
            if (psk != null) symmetric.mixKeyAndHash(psk) // psk (psk1)
        } else {
            writeEphemeral(out)
            symmetric.mixKey(crypto.x25519(ephemeral!!.privateKey, remoteEphemeral!!)) // ee
            symmetric.mixKey(crypto.x25519(ephemeral!!.privateKey, remoteStatic!!)) // se
        }
        out += symmetric.encryptAndHash(payload).toList()
        step++
        return out.toByteArray()
    }

    /** Reads the other side's handshake message; returns its payload. Throws when it does not authenticate. */
    fun read(message: ByteArray): ByteArray {
        check((step == 0 && !initiator) || (step == 1 && initiator)) { "not this side's turn to read" }
        var at = 0
        fun take(n: Int): ByteArray {
            require(at + n <= message.size) { "handshake message too short" }
            return message.copyOfRange(at, at + n).also { at += n }
        }
        if (step == 0) {
            readEphemeral(take(DH_LEN))
            symmetric.mixKey(crypto.x25519(local.privateKey, remoteEphemeral!!)) // es
            remoteStatic = symmetric.decryptAndHash(take(DH_LEN + TAG_LEN)) // s
            symmetric.mixKey(crypto.x25519(local.privateKey, remoteStatic!!)) // ss
            if (psk != null) symmetric.mixKeyAndHash(psk) // psk
        } else {
            readEphemeral(take(DH_LEN))
            symmetric.mixKey(crypto.x25519(ephemeral!!.privateKey, remoteEphemeral!!)) // ee
            symmetric.mixKey(crypto.x25519(local.privateKey, remoteEphemeral!!)) // se
        }
        val payload = symmetric.decryptAndHash(message.copyOfRange(at, message.size))
        step++
        return payload
    }

    /** After both messages: the transport keys (spec/pairing.md, Transport). */
    fun split(): NoiseTransport {
        check(finished) { "handshake not finished" }
        val (k1, k2) = symmetric.split()
        return if (initiator) NoiseTransport(crypto, send = CipherState(crypto, k1), receive = CipherState(crypto, k2))
        else NoiseTransport(crypto, send = CipherState(crypto, k2), receive = CipherState(crypto, k1))
    }

    private fun writeEphemeral(out: MutableList<Byte>) {
        val e = fixedEphemeral?.let { NodeKeyPair.of(crypto, it) } ?: NodeKeyPair.generate(crypto)
        ephemeral = e
        out += e.publicKey.toList()
        symmetric.mixHash(e.publicKey)
        if (psk != null) symmetric.mixKey(e.publicKey)
    }

    private fun readEphemeral(re: ByteArray) {
        remoteEphemeral = re
        symmetric.mixHash(re)
        if (psk != null) symmetric.mixKey(re)
    }

    companion object {
        const val PAIR = "Noise_IKpsk1_25519_ChaChaPoly_SHA256"
        const val SESSION = "Noise_IK_25519_ChaChaPoly_SHA256"
        internal const val DH_LEN = 32
        internal const val TAG_LEN = 16

        /** The device's side; [psk] for pairing (`IKpsk1`), null for a session (`IK`). */
        fun initiator(
            crypto: NodeCrypto,
            local: NodeKeyPair,
            remoteStatic: ByteArray,
            prologue: ByteArray,
            psk: ByteArray? = null,
            fixedEphemeral: ByteArray? = null,
        ) = NoiseHandshake(crypto, true, local, remoteStatic, psk, fixedEphemeral, if (psk != null) PAIR else SESSION, prologue)

        /** The node's side (tests). */
        fun responder(
            crypto: NodeCrypto,
            local: NodeKeyPair,
            prologue: ByteArray,
            psk: ByteArray? = null,
            fixedEphemeral: ByteArray? = null,
        ) = NoiseHandshake(crypto, false, local, null, psk, fixedEphemeral, if (psk != null) PAIR else SESSION, prologue)
    }
}

/** The two directions of a finished handshake. */
class NoiseTransport internal constructor(private val crypto: NodeCrypto, private val send: CipherState, private val receive: CipherState) {
    fun encrypt(plaintext: ByteArray): ByteArray = send.encryptWithAd(ByteArray(0), plaintext)

    /** Throws when [ciphertext] does not authenticate. */
    fun decrypt(ciphertext: ByteArray): ByteArray = receive.decryptWithAd(ByteArray(0), ciphertext)
}

internal class CipherState(private val crypto: NodeCrypto, private var key: ByteArray? = null) {
    private var nonce = 0L

    val hasKey: Boolean get() = key != null

    fun initializeKey(k: ByteArray) {
        key = k
        nonce = 0
    }

    /** ChaChaPoly's nonce: 32 zero bits, then the counter as 64-bit little-endian. */
    private fun nonceBytes(): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) out[4 + i] = (nonce ushr (8 * i)).toByte()
        return out
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        check(nonce != -1L) { "nonce exhausted" }
        return crypto.chachaSeal(k, nonceBytes(), ad, plaintext).also { nonce++ }
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        check(nonce != -1L) { "nonce exhausted" }
        return crypto.chachaOpen(k, nonceBytes(), ad, ciphertext).also { nonce++ }
    }
}

internal class SymmetricState(private val crypto: NodeCrypto, protocol: String) {
    private val cipher = CipherState(crypto)
    private var h: ByteArray
    private var ck: ByteArray

    init {
        val name = protocol.encodeToByteArray()
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else crypto.sha256(name)
        ck = h
    }

    fun mixHash(data: ByteArray) {
        h = crypto.sha256(h + data)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, k) = hkdf(ck, ikm, 2)
        ck = newCk
        cipher.initializeKey(k)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val (newCk, tempH, k) = hkdf(ck, ikm, 3)
        ck = newCk
        mixHash(tempH)
        cipher.initializeKey(k)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encryptWithAd(h, plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray = cipher.decryptWithAd(h, ciphertext).also { mixHash(ciphertext) }

    fun split(): List<ByteArray> = hkdf(ck, ByteArray(0), 2)

    /** HKDF as Noise defines it (HMAC-SHA256), with [outputs] 2 or 3. */
    private fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        val temp = crypto.hmacSha256(chainingKey, ikm)
        val o1 = crypto.hmacSha256(temp, byteArrayOf(1))
        val o2 = crypto.hmacSha256(temp, o1 + byteArrayOf(2))
        if (outputs == 2) return listOf(o1, o2)
        return listOf(o1, o2, crypto.hmacSha256(temp, o2 + byteArrayOf(3)))
    }

    private companion object {
        const val HASH_LEN = 32
    }
}

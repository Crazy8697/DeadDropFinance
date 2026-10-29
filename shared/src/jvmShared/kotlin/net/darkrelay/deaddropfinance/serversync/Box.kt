package net.darkrelay.deaddropfinance.serversync

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * The encrypted envelopes the Dead Drop server speaks (its docs/PROTOCOL.md), all libsodium through lazysodium:
 * crypto_box (X25519, XSalsa20-Poly1305) for requests and replies, crypto_box_seal for setup, login and pairing,
 * which go before the server knows this device's key. Nothing here is home-made crypto.
 *
 *   request (POST /v1/rpc):                0x01 | device id (16, UUID) | nonce (24) | crypto_box(JSON, nonce, server public, device secret)
 *   reply (200):                           0x01 | nonce (24) | crypto_box(JSON, nonce, device public, server secret)
 *   setup, login, pair (POST /v1/...):     0x01 | crypto_box_seal(JSON, server public)
 */
object Box {
    private val na by lazy { newSodium().also { check(it.sodium_init() >= 0) { "libsodium did not start" } } }

    const val VERSION: Byte = 1
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val MAC_BYTES = 16
    const val SEAL_BYTES = 48

    class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    fun random(n: Int): ByteArray = ByteArray(n).also { na.randombytes_buf(it, n) }

    fun keyPair(): KeyPair {
        val pk = ByteArray(KEY_BYTES); val sk = ByteArray(KEY_BYTES)
        check(na.crypto_box_keypair(pk, sk) == 0)
        return KeyPair(pk, sk)
    }

    fun publicKeyOf(secretKey: ByteArray): ByteArray {
        require(secretKey.size == KEY_BYTES)
        val pk = ByteArray(KEY_BYTES)
        check(na.crypto_scalarmult_base(pk, secretKey) == 0)
        return pk
    }

    fun box(message: ByteArray, nonce: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray {
        val out = ByteArray(message.size + MAC_BYTES)
        check(na.crypto_box_easy(out, message, message.size.toLong(), nonce, theirPublic, mySecret) == 0)
        return out
    }

    fun open(cipher: ByteArray, nonce: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray? {
        if (cipher.size < MAC_BYTES || nonce.size != NONCE_BYTES) return null
        val out = ByteArray(cipher.size - MAC_BYTES)
        return if (na.crypto_box_open_easy(out, cipher, cipher.size.toLong(), nonce, theirPublic, mySecret) == 0) out else null
    }

    fun seal(message: ByteArray, theirPublic: ByteArray): ByteArray {
        val out = ByteArray(message.size + SEAL_BYTES)
        check(na.crypto_box_seal(out, message, message.size.toLong(), theirPublic) == 0)
        return out
    }

    fun sealOpen(cipher: ByteArray, myPublic: ByteArray, mySecret: ByteArray): ByteArray? {
        if (cipher.size < SEAL_BYTES) return null
        val out = ByteArray(cipher.size - SEAL_BYTES)
        return if (na.crypto_box_seal_open(out, cipher, cipher.size.toLong(), myPublic, mySecret) == 0) out else null
    }

    /** The server's short fingerprint: the first 8 bytes of the key's SHA-256, as hex (the server prints the same). */
    fun fingerprint(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(8).hex()

    // ---- envelopes

    /** A request and its nonce (the reply names it). */
    fun request(deviceId: String, plain: ByteArray, serverPublic: ByteArray, deviceSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = random(NONCE_BYTES)
        val cipher = box(plain, nonce, serverPublic, deviceSecret)
        val id = UUID.fromString(deviceId)
        val out = ByteBuffer.allocate(1 + 16 + nonce.size + cipher.size)
            .put(VERSION).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).put(nonce).put(cipher)
        return out.array() to nonce
    }

    /** Null when it doesn't open: a different server key, or tampered with. */
    fun openReply(body: ByteArray, serverPublic: ByteArray, deviceSecret: ByteArray): ByteArray? {
        if (body.size < 1 + NONCE_BYTES + MAC_BYTES || body[0] != VERSION) return null
        val nonce = body.copyOfRange(1, 1 + NONCE_BYTES)
        return open(body.copyOfRange(1 + NONCE_BYTES, body.size), nonce, serverPublic, deviceSecret)
    }

    fun sealed(plain: ByteArray, serverPublic: ByteArray): ByteArray = byteArrayOf(VERSION) + seal(plain, serverPublic)

    fun reply(plain: ByteArray, devicePublic: ByteArray, serverSecret: ByteArray): ByteArray {
        val nonce = random(NONCE_BYTES)
        return byteArrayOf(VERSION) + nonce + box(plain, nonce, devicePublic, serverSecret)
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hex" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

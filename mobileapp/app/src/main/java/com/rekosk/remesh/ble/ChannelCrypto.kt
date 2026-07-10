package com.rekosk.remesh.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Channel keys and the group-message wire format, mirrored from the firmware.
 *
 * The app needs to *reproduce* the exact bytes its own node puts on the air, not
 * merely read what comes back: that byte string is the only handle it has for
 * recognising its own message when a repeater re-transmits it (see
 * `MeshRepository.onHeardPacket`). Every constant below therefore comes from the
 * firmware rather than from the protocol doc.
 *
 * - `Utils::encryptThenMAC`  -- AES-128-ECB, then HMAC-SHA256 truncated to 2 bytes
 * - `Mesh::createGroupDatagram` -- payload is `channel hash || MAC || ciphertext`
 * - `BaseChatMesh::sendGroupMessage` -- plaintext is `timestamp || 0 || "name: text"`
 */
object ChannelCrypto {

    /** `CIPHER_KEY_SIZE`. The firmware's 32-byte secret field is zero-padded. */
    const val SECRET_SIZE = 16

    /** `CIPHER_MAC_SIZE`: the HMAC is truncated to its first two bytes. */
    const val MAC_SIZE = 2

    /** `CIPHER_BLOCK_SIZE`. */
    private const val BLOCK_SIZE = 16

    /** `PUB_KEY_SIZE`: the HMAC key is the secret padded out to this length. */
    private const val HMAC_KEY_SIZE = 32

    /** `BaseChatMesh.h: MAX_TEXT_LEN` -- the cap on `"name: text"`, not on `text`. */
    const val MAX_GROUP_TEXT_LEN = 160

    /** `TXT_TYPE_PLAIN`, the byte that follows the timestamp. */
    private const val TXT_TYPE_PLAIN: Byte = 0

    /** `PUBLIC_GROUP_PSK`, base64 `izOH6cXN6mrJ5e26oRXNcg==`. Known to everyone. */
    val PUBLIC_SECRET: ByteArray = "8b3387e9c5cdea6ac9e5edbaa115cd72".decodeHex()

    /** The name the firmware gives its pre-configured channel 0. */
    const val PUBLIC_CHANNEL_NAME = "Public"

    /**
     * A hashtag channel's secret is public knowledge: the first 16 bytes of
     * `sha256(name)`, hash included. Anyone typing the same name derives the
     * same key, which is the point.
     */
    fun hashtagSecret(name: String): ByteArray =
        sha256(name.toByteArray(Charsets.UTF_8)).copyOf(SECRET_SIZE)

    /** Only these characters may follow the '#', so two people agree on the bytes. */
    private val HASHTAG_BODY = Regex("[a-z0-9-]+")

    /** Returns null when [name] is a usable hashtag channel name, else the reason. */
    fun validateHashtag(name: String): String? = when {
        !name.startsWith("#") -> "A hashtag channel name must start with '#'"
        name.length < 2 -> "Enter a name after the '#'"
        !HASHTAG_BODY.matches(name.substring(1)) ->
            "Only lowercase letters, digits and dashes are allowed"
        else -> null
    }

    fun randomSecret(): ByteArray = ByteArray(SECRET_SIZE).also { SecureRandom().nextBytes(it) }

    /**
     * The single byte a packet carries to say which channel it belongs to:
     * `sha256(secret)[0]`, per `BaseChatMesh::setChannel`. A 16-byte secret is
     * hashed as 16 bytes; the firmware only hashes 32 when the upper half is
     * non-zero, which cannot happen for a secret this app stores.
     */
    fun channelHash(secret: ByteArray): Byte {
        require(secret.size == SECRET_SIZE) { "channel secret must be $SECRET_SIZE bytes" }
        return sha256(secret)[0]
    }

    /**
     * The exact `payload` bytes our node will transmit for this channel message:
     * `hash || MAC || AES(plaintext)`. Deterministic, because the firmware's AES
     * is ECB with no nonce -- which is the only reason matching a heard packet
     * against a sent one works at all.
     */
    fun groupTextPayload(
        secret: ByteArray,
        timestampEpochSec: Long,
        senderName: String,
        text: String,
    ): ByteArray {
        val plaintext = groupTextPlaintext(timestampEpochSec, senderName, text)
        val ciphertext = encrypt(secret, plaintext)
        val mac = mac(secret, ciphertext)
        return byteArrayOf(channelHash(secret)) + mac + ciphertext
    }

    /**
     * `timestamp || TXT_TYPE_PLAIN || "<sender>: <text>"`, with the *joined* string
     * truncated to [MAX_GROUP_TEXT_LEN]. A long node name therefore eats into the
     * message, exactly as `sendGroupMessage` does it.
     */
    internal fun groupTextPlaintext(
        timestampEpochSec: Long,
        senderName: String,
        text: String,
    ): ByteArray {
        val prefix = "$senderName: ".toByteArray(Charsets.UTF_8)
        val body = text.toByteArray(Charsets.UTF_8)
        val bodyLen = body.size.coerceAtMost((MAX_GROUP_TEXT_LEN - prefix.size).coerceAtLeast(0))

        val out = ByteBuffer.allocate(5 + prefix.size + bodyLen).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(timestampEpochSec.toInt())
        out.put(TXT_TYPE_PLAIN)
        out.put(prefix)
        out.put(body, 0, bodyLen)
        return out.array()
    }

    /** AES-128-ECB over a zero-padded plaintext, as `Utils::encrypt` does it. */
    private fun encrypt(secret: ByteArray, plaintext: ByteArray): ByteArray {
        require(secret.size == SECRET_SIZE) { "channel secret must be $SECRET_SIZE bytes" }
        val padded = ByteArray(blocksFor(plaintext.size) * BLOCK_SIZE)
        plaintext.copyInto(padded)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"))
        return cipher.doFinal(padded)
    }

    /**
     * `SHA256::resetHMAC(secret, PUB_KEY_SIZE)` keys the HMAC with 32 bytes, so the
     * 16-byte secret must be zero-padded before use -- not passed as 16.
     */
    private fun mac(secret: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = ByteArray(HMAC_KEY_SIZE)
        secret.copyInto(key)
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(key, "HmacSHA256"))
        return hmac.doFinal(ciphertext).copyOf(MAC_SIZE)
    }

    /** `Utils::encrypt` emits whole blocks only, and nothing at all for no input. */
    private fun blocksFor(length: Int): Int = (length + BLOCK_SIZE - 1) / BLOCK_SIZE

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** Throws on odd length or non-hex characters; callers wrap in runCatching. */
    fun String.decodeHex(): ByteArray {
        val clean = trim()
        require(clean.length % 2 == 0) { "a hex string must have an even number of characters" }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}

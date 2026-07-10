package com.rekosk.remesh.data

import com.rekosk.remesh.ble.ChannelCrypto
import com.rekosk.remesh.ble.ChannelCrypto.decodeHex
import java.net.URLDecoder
import java.net.URLEncoder

/** A channel someone shared as `meshcore://channel/add?...` (see docs/qr_codes.md). */
data class SharedChannel(val name: String, val secret: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SharedChannel && name == other.name && secret.contentEquals(other.secret)

    override fun hashCode(): Int = 31 * name.hashCode() + secret.contentHashCode()
}

private const val CHANNEL_PREFIX = "meshcore://channel/add?"

/**
 * The link behind a channel's QR code, per docs/qr_codes.md:
 * `meshcore://channel/add?name=<urlencoded>&secret=<32 hex>`.
 *
 * Lowercase hex, matching what the reference app prints under its QR code.
 */
fun channelUri(name: String, secret: ByteArray): String {
    require(secret.size == ChannelCrypto.SECRET_SIZE) {
        "a channel secret is ${ChannelCrypto.SECRET_SIZE} bytes"
    }
    val encodedName = URLEncoder.encode(name, "UTF-8")
    val hex = secret.joinToString("") { "%02x".format(it) }
    return "${CHANNEL_PREFIX}name=$encodedName&secret=$hex"
}

/**
 * Reads a scanned QR payload. Returns null for anything that is not a channel
 * invitation this app can act on: a contact link, a web address, or a key of the
 * wrong length.
 *
 * Parsed by hand rather than with `android.net.Uri` so it stays testable on the JVM.
 * `region_scope` is accepted and ignored -- the app has no regions yet, and dropping
 * one parameter beats refusing an otherwise valid channel.
 */
fun parseChannelUri(text: String): SharedChannel? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(CHANNEL_PREFIX, ignoreCase = true)) return null

    val params = trimmed.substring(CHANNEL_PREFIX.length)
        .split('&')
        .mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = pair.substring(0, separator)
            val value = runCatching {
                URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            }.getOrNull() ?: return@mapNotNull null
            key to value
        }
        .toMap()

    val name = params["name"]?.takeIf { it.isNotBlank() } ?: return null
    val secret = runCatching { params["secret"]?.decodeHex() }.getOrNull() ?: return null
    if (secret.size != ChannelCrypto.SECRET_SIZE) return null

    return SharedChannel(name, secret)
}

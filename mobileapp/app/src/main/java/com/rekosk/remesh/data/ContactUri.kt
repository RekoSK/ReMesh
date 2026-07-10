package com.rekosk.remesh.data

import com.rekosk.remesh.ble.ChannelCrypto.decodeHex
import com.rekosk.remesh.ble.MeshCoreProtocol
import java.net.URLDecoder

/** A contact someone shared as `meshcore://contact/add?...` (see docs/qr_codes.md). */
data class SharedContact(val name: String, val publicKey: ByteArray, val type: Int) {
    val publicKeyHex: String get() = publicKey.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean =
        other is SharedContact && name == other.name && type == other.type &&
            publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int =
        31 * (31 * name.hashCode() + publicKey.contentHashCode()) + type
}

private const val CONTACT_PREFIX = "meshcore://contact/add?"

/**
 * Reads a shared-contact link. Returns null for anything else -- a channel link, a
 * web address, or a key of the wrong length.
 *
 * A missing or unrecognised `type` falls back to Chat rather than rejecting the
 * link: the type is a hint for the icon, whereas the key is what actually matters.
 */
fun parseContactUri(text: String): SharedContact? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(CONTACT_PREFIX, ignoreCase = true)) return null

    val params = trimmed.substring(CONTACT_PREFIX.length)
        .split('&')
        .mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val value = runCatching {
                URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            }.getOrNull() ?: return@mapNotNull null
            pair.substring(0, separator) to value
        }
        .toMap()

    val name = params["name"]?.takeIf { it.isNotBlank() } ?: return null
    val key = runCatching { params["public_key"]?.decodeHex() }.getOrNull() ?: return null
    if (key.size != MeshCoreProtocol.PUB_KEY_SIZE) return null

    val type = params["type"]?.toIntOrNull()?.takeIf {
        it in MeshCoreProtocol.AdvType.CHAT..MeshCoreProtocol.AdvType.SENSOR
    } ?: MeshCoreProtocol.AdvType.CHAT

    return SharedContact(name, key, type)
}

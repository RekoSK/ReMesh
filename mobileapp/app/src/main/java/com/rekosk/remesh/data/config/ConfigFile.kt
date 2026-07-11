package com.rekosk.remesh.data.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One tickable row on the export screen. Order matches the reference app. */
enum class ConfigSection(val label: String) {
    NAME("Name"),
    PRIVATE_KEY("Private identity key"),
    RADIO("Radio settings"),
    POSITION("Position settings"),
    OTHER("Other settings"),
    AUTO_ADD("Auto add settings"),
    CHANNELS("Channels"),
    CONTACTS("Contacts"),
}

data class RadioConfig(
    /** Kilohertz, exactly as SELF_INFO reports it (869618 == 869.618 MHz). */
    val frequency: Int,
    /** Hertz, exactly as SELF_INFO reports it (62500 == 62.5 kHz). */
    val bandwidth: Int,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int,
)

data class PositionConfig(val latitude: Double, val longitude: Double)

data class OtherConfig(val manualAddContacts: Int, val advertLocationPolicy: Int)

data class AutoAddConfig(
    val chat: Boolean,
    val repeater: Boolean,
    val roomServer: Boolean,
    val sensor: Boolean,
    val overwriteOldest: Boolean,
    val maxHops: Int,
)

data class ChannelConfig(val name: String, val secret: String)

data class ContactConfig(
    val type: Int,
    val name: String,
    val customName: String?,
    val publicKey: String,
    val flags: Int,
    val latitude: Double,
    val longitude: Double,
    val lastAdvert: Long,
    val lastModified: Long,
    /** Null when the node knows no route; "" for an empty path; hex otherwise. */
    val outPathList: String?,
)

/** Everything a companion config file can carry. Every section is optional. */
data class ConfigFile(
    val name: String? = null,
    val publicKey: String? = null,
    val privateKey: String? = null,
    val radio: RadioConfig? = null,
    val position: PositionConfig? = null,
    val other: OtherConfig? = null,
    val autoAdd: AutoAddConfig? = null,
    val channels: List<ChannelConfig>? = null,
    val contacts: List<ContactConfig>? = null,
) {
    fun sectionsPresent(): Set<ConfigSection> = buildSet {
        if (name != null) add(ConfigSection.NAME)
        if (privateKey != null) add(ConfigSection.PRIVATE_KEY)
        if (radio != null) add(ConfigSection.RADIO)
        if (position != null) add(ConfigSection.POSITION)
        if (other != null) add(ConfigSection.OTHER)
        if (autoAdd != null) add(ConfigSection.AUTO_ADD)
        if (channels != null) add(ConfigSection.CHANNELS)
        if (contacts != null) add(ConfigSection.CONTACTS)
    }
}

/**
 * Reads and writes the same JSON the official MeshCore app produces, so a file
 * exported by either can be imported by the other.
 *
 * Key order, indentation and value shapes are all load-bearing for that
 * compatibility: coordinates are strings, frequency is kilohertz, bandwidth is
 * hertz, and `custom_name` is emitted as an explicit null rather than dropped.
 */
object ConfigCodec {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Coordinates are strings with trailing zeros trimmed but never a bare integer:
     * the reference app writes "48.707708", "48.3" and "0.0".
     */
    internal fun formatCoordinate(value: Double): String {
        val trimmed = "%.6f".format(java.util.Locale.US, value)
            .trimEnd('0')
            .trimEnd('.')
        return if (trimmed.contains('.')) trimmed else "$trimmed.0"
    }

    /**
     * `out_path_len` is a packed path_len byte, not a raw count: top two bits are the
     * hash-size mode, low six the hop count. 0xFF means the node knows no route (exported
     * as null); a zero hop count is a direct route (exported as ""); otherwise the path is
     * `hopCount * hashSize` bytes of hex.
     */
    internal fun formatOutPath(outPathLen: Int, outPath: ByteArray): String? {
        val b = outPathLen and 0xFF
        if (b == 0xFF) return null
        val hopCount = b and 0x3F
        val hashSize = (b shr 6) + 1
        return outPath.take(hopCount * hashSize).joinToString("") { "%02x".format(it) }
    }

    fun encode(config: ConfigFile): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            config.name?.let { put("name", it) }
            config.publicKey?.let { put("public_key", it) }
            config.privateKey?.let { put("private_key", it) }

            config.radio?.let { radio ->
                putJsonObject("radio_settings") {
                    put("frequency", radio.frequency)
                    put("bandwidth", radio.bandwidth)
                    put("spreading_factor", radio.spreadingFactor)
                    put("coding_rate", radio.codingRate)
                    put("tx_power", radio.txPower)
                }
            }
            config.position?.let { position ->
                putJsonObject("position_settings") {
                    put("latitude", formatCoordinate(position.latitude))
                    put("longitude", formatCoordinate(position.longitude))
                }
            }
            config.other?.let { other ->
                putJsonObject("other_settings") {
                    put("manual_add_contacts", other.manualAddContacts)
                    put("advert_location_policy", other.advertLocationPolicy)
                }
            }
            config.autoAdd?.let { autoAdd ->
                putJsonObject("auto_add_settings") {
                    put("auto_add_chat", autoAdd.chat)
                    put("auto_add_repeater", autoAdd.repeater)
                    put("auto_add_room_server", autoAdd.roomServer)
                    put("auto_add_sensor", autoAdd.sensor)
                    put("overwrite_oldest", autoAdd.overwriteOldest)
                    put("auto_add_max_hops", autoAdd.maxHops)
                }
            }
            config.channels?.let { channels ->
                putJsonArray("channels") {
                    channels.forEach { channel ->
                        add(
                            buildJsonObject {
                                put("name", channel.name)
                                put("secret", channel.secret)
                            },
                        )
                    }
                }
            }
            config.contacts?.let { contacts ->
                putJsonArray("contacts") {
                    contacts.forEach { contact ->
                        add(
                            buildJsonObject {
                                put("type", contact.type)
                                put("name", contact.name)
                                // Explicit null, as the reference app emits it.
                                put(
                                    "custom_name",
                                    contact.customName?.let(::JsonPrimitive) ?: JsonNull,
                                )
                                put("public_key", contact.publicKey)
                                put("flags", contact.flags)
                                put("latitude", formatCoordinate(contact.latitude))
                                put("longitude", formatCoordinate(contact.longitude))
                                put("last_advert", contact.lastAdvert)
                                put("last_modified", contact.lastModified)
                                put(
                                    "out_path_list",
                                    contact.outPathList?.let(::JsonPrimitive) ?: JsonNull,
                                )
                            },
                        )
                    }
                }
            }
        },
    )

    fun decode(text: String): ConfigFile {
        val root = json.parseToJsonElement(text).jsonObject

        fun str(key: String): String? = root[key]?.jsonPrimitive?.contentOrNull

        val radio = root["radio_settings"]?.jsonObject?.let { o ->
            RadioConfig(
                frequency = o.getValue("frequency").jsonPrimitive.int,
                bandwidth = o.getValue("bandwidth").jsonPrimitive.int,
                spreadingFactor = o.getValue("spreading_factor").jsonPrimitive.int,
                codingRate = o.getValue("coding_rate").jsonPrimitive.int,
                txPower = o.getValue("tx_power").jsonPrimitive.int,
            )
        }
        val position = root["position_settings"]?.jsonObject?.let { o ->
            PositionConfig(
                latitude = o.getValue("latitude").jsonPrimitive.content.toDouble(),
                longitude = o.getValue("longitude").jsonPrimitive.content.toDouble(),
            )
        }
        val other = root["other_settings"]?.jsonObject?.let { o ->
            OtherConfig(
                manualAddContacts = o.getValue("manual_add_contacts").jsonPrimitive.int,
                advertLocationPolicy = o.getValue("advert_location_policy").jsonPrimitive.int,
            )
        }
        val autoAdd = root["auto_add_settings"]?.jsonObject?.let { o ->
            AutoAddConfig(
                chat = o.getValue("auto_add_chat").jsonPrimitive.boolean,
                repeater = o.getValue("auto_add_repeater").jsonPrimitive.boolean,
                roomServer = o.getValue("auto_add_room_server").jsonPrimitive.boolean,
                sensor = o.getValue("auto_add_sensor").jsonPrimitive.boolean,
                overwriteOldest = o.getValue("overwrite_oldest").jsonPrimitive.boolean,
                maxHops = o.getValue("auto_add_max_hops").jsonPrimitive.int,
            )
        }
        val channels = (root["channels"] as? JsonArray)?.map { element ->
            val o = element.jsonObject
            ChannelConfig(
                name = o.getValue("name").jsonPrimitive.content,
                secret = o.getValue("secret").jsonPrimitive.content,
            )
        }
        val contacts = (root["contacts"] as? JsonArray)?.map { element ->
            val o = element.jsonObject
            ContactConfig(
                type = o.getValue("type").jsonPrimitive.int,
                name = o.getValue("name").jsonPrimitive.content,
                customName = o["custom_name"]?.jsonPrimitive?.contentOrNull,
                publicKey = o.getValue("public_key").jsonPrimitive.content,
                flags = o.getValue("flags").jsonPrimitive.int,
                latitude = o.getValue("latitude").jsonPrimitive.content.toDouble(),
                longitude = o.getValue("longitude").jsonPrimitive.content.toDouble(),
                lastAdvert = o.getValue("last_advert").jsonPrimitive.long,
                lastModified = o.getValue("last_modified").jsonPrimitive.long,
                outPathList = o["out_path_list"]?.jsonPrimitive?.contentOrNull,
            )
        }

        return ConfigFile(
            name = str("name"),
            publicKey = str("public_key"),
            privateKey = str("private_key"),
            radio = radio,
            position = position,
            other = other,
            autoAdd = autoAdd,
            channels = channels,
            contacts = contacts,
        )
    }
}

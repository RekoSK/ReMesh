package com.rekosk.remesh.data.model

import com.rekosk.remesh.ble.MeshCoreProtocol.RadioLimits
import com.rekosk.remesh.ble.MeshFrame

/**
 * The editable subset of a node's configuration. The settings screen edits a
 * copy of this and only the fields that actually changed get written back.
 */
data class NodeSettings(
    val name: String,
    val latE6: Int,
    val lonE6: Int,
    val shareLocation: Boolean,
    val freqKhz: Int,
    val bandwidthHz: Int,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPowerDbm: Int,
) {
    val freqMhz: Double get() = freqKhz / 1000.0
    val bandwidthKhz: Double get() = bandwidthHz / 1000.0

    companion object {
        fun from(self: MeshFrame.SelfInfo) = NodeSettings(
            name = self.name,
            latE6 = self.latE6,
            lonE6 = self.lonE6,
            shareLocation = self.sharesLocation,
            freqKhz = self.radioFreqKhz.toInt(),
            bandwidthHz = self.radioBandwidthHz.toInt(),
            spreadingFactor = self.spreadingFactor,
            codingRate = self.codingRate,
            txPowerDbm = self.txPower,
        )

        fun freqKhzFromMhzText(text: String): Int? =
            text.trim().replace(',', '.').toDoubleOrNull()
                ?.let { Math.round(it * 1000).toInt() }

        fun coordE6FromText(text: String): Int? =
            text.trim().replace(',', '.').toDoubleOrNull()
                ?.let { Math.round(it * 1_000_000).toInt() }
    }

    /** Mirrors the firmware's own validation so we fail before the radio does. */
    fun validate(txPowerCeiling: Int): String? = when {
        name.isBlank() -> "Name must not be empty"
        freqKhz !in RadioLimits.FREQ_KHZ_MIN..RadioLimits.FREQ_KHZ_MAX ->
            "Frequency must be between 150 and 2500 MHz"
        bandwidthHz !in RadioLimits.BW_HZ_MIN..RadioLimits.BW_HZ_MAX ->
            "Bandwidth must be between 7 and 500 kHz"
        spreadingFactor !in RadioLimits.SF_MIN..RadioLimits.SF_MAX ->
            "Spreading factor must be ${RadioLimits.SF_MIN}..${RadioLimits.SF_MAX}"
        codingRate !in RadioLimits.CR_MIN..RadioLimits.CR_MAX ->
            "Coding rate must be ${RadioLimits.CR_MIN}..${RadioLimits.CR_MAX}"
        txPowerDbm !in RadioLimits.TX_POWER_MIN..txPowerCeiling ->
            "TX power must be ${RadioLimits.TX_POWER_MIN}..$txPowerCeiling dBm"
        latE6 !in -90_000_000..90_000_000 -> "Latitude must be between -90 and 90"
        lonE6 !in -180_000_000..180_000_000 -> "Longitude must be between -180 and 180"
        else -> null
    }

    fun radioDiffers(other: NodeSettings): Boolean =
        freqKhz != other.freqKhz ||
            bandwidthHz != other.bandwidthHz ||
            spreadingFactor != other.spreadingFactor ||
            codingRate != other.codingRate
}

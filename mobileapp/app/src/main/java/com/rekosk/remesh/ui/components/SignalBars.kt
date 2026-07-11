package com.rekosk.remesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Meshtastic-style three-bar signal glyph shown next to every signal-strength
 * reading in the app. The number of lit bars, and their colour, tells the strength
 * at a glance; the exact figure sits beside it as text.
 *
 * Two entry points because the app reports strength two ways: SNR in dB (LoRa link
 * quality) and RSSI in dBm (raw received power, incl. the BLE link to the phone).
 */
@Composable
fun SignalBars(snr: Float, modifier: Modifier = Modifier) =
    SignalBarsCore(snrLevel(snr), modifier)

@Composable
fun SignalBarsForRssi(rssiDbm: Int, modifier: Modifier = Modifier) =
    SignalBarsCore(rssiLevel(rssiDbm), modifier)

/**
 * Thresholds match the node's own display: LoRa decodes well below 0 dB SNR, so
 * anything above about 5 dB is a strong link, and below 0 dB is weak but usable.
 */
internal fun snrLevel(snr: Float): Int = when {
    snr >= 5f -> 3
    snr >= 0f -> 2
    else -> 1
}

/** A rough RSSI mapping that reads sensibly for both the LoRa mesh and the BLE link. */
internal fun rssiLevel(rssiDbm: Int): Int = when {
    rssiDbm >= -80 -> 3
    rssiDbm >= -100 -> 2
    else -> 1
}

@Composable
private fun SignalBarsCore(filled: Int, modifier: Modifier) {
    val color = when (filled) {
        3 -> Color(0xFF4CAF50)
        2 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((6 + index * 5).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (index < filled) color else empty),
            )
        }
    }
}

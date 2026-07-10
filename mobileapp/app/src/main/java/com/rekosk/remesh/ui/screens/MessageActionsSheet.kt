package com.rekosk.remesh.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.ui.components.formatMessageTime

/**
 * The sheet a long press opens. What it offers depends on who wrote the message:
 * our own messages can be inspected for which repeaters carried them, everybody
 * else's can be replied to.
 *
 * "Show message routes" is deliberately inert for now -- the node reports the path
 * a packet took, but nothing renders it yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: MeshMessage,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onHeardRepeats: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            SheetHeader(onDismiss)

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.size(16.dp))
                MessagePreview(message)
                Spacer(Modifier.size(16.dp))

                ActionRow(Icons.Filled.ContentCopy, "Copy text") {
                    copyToClipboard(context, message.text)
                    onDismiss()
                }

                if (message.isOutgoing) {
                    ActionRow(Icons.Filled.CellTower, "Heard repeats", onClick = onHeardRepeats)
                } else {
                    ActionRow(Icons.AutoMirrored.Filled.Reply, "Reply", onClick = onReply)
                    ActionRow(
                        icon = Icons.Filled.Route,
                        label = "Show message routes",
                        enabled = false,
                        onClick = {},
                    )
                }

                ActionRow(Icons.Filled.Delete, "Delete", onClick = onDelete)
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SheetHeader(onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
            Spacer(Modifier.width(8.dp))
            Text("Message actions", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * The message itself, plus what the radio knows about it. An incoming message gets
 * the full arrival report; one of ours reports how many repeaters carried it --
 * without a tick, because the count already says everything the tick would.
 */
@Composable
private fun MessagePreview(message: MeshMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        if (!message.isOutgoing) {
            Text(
                text = message.author,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(4.dp))
        }

        Surface(
            color = if (message.isOutgoing) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (message.isOutgoing) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.size(8.dp))

        if (message.isOutgoing) {
            Text(
                text = heardRepeatsSummary(message.heardRepeats.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column {
                DetailLine("Sent", formatMessageTime(message.timestampEpochMs))
                message.receivedEpochMs?.let { DetailLine("Received", formatMessageTime(it)) }
                message.pathHashSizeBytes?.let { DetailLine("Path Hash Size", "$it-byte") }
                message.snr?.let { DetailLine("SNR", formatSnr(it)) }
                DetailLine(
                    label = "Hops",
                    value = if (message.isDirectRoute) "Direct" else "${message.hopCount}",
                )
            }
        }
    }
}

/** "Heard 3 repeats", the phrase the row and the screen title share. */
internal fun heardRepeatsSummary(count: Int): String = when (count) {
    0 -> "No repeats heard"
    1 -> "Heard 1 repeat"
    else -> "Heard $count repeats"
}

/**
 * "-8.5 dB", "7.75 dB", "0 dB". The radio reports SNR in quarter-decibel steps, so
 * two decimals is exact and any trailing zeros are noise.
 */
internal fun formatSnr(snr: Float): String {
    val digits = "%.2f".format(java.util.Locale.US, snr).trimEnd('0').trimEnd('.')
    return "$digits dB"
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Box(modifier = Modifier.padding(vertical = 6.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(icon, contentDescription = null, tint = contentColor)
                Spacer(Modifier.width(20.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        }
    }
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
}

package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.RepeaterName
import com.rekosk.remesh.data.ResolvedRepeat
import com.rekosk.remesh.ui.MeshViewModel

/**
 * The repeaters our own node overheard forwarding one of our messages.
 *
 * A row's hash is only a key prefix, so it does not always name one node. When it
 * matches several known contacts the row says "Duplicated" and tapping it lists them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeardRepeatsScreen(
    viewModel: MeshViewModel,
    conversationId: String,
    messageId: String,
    onBack: () -> Unit,
) {
    // Recomputed as messages change: a repeater can be heard while this screen is open,
    // and a row appearing under the user is the whole point of the feature.
    val messages by viewModel.messagesFor(conversationId).collectAsStateWithLifecycle()
    val repeats = remember(messages, messageId) {
        viewModel.heardRepeats(conversationId, messageId)
    }
    var expanded by remember { mutableStateOf<ResolvedRepeat?>(null) }

    expanded?.let { repeat ->
        DuplicateRepeatersDialog(repeat = repeat, onDismiss = { expanded = null })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(heardRepeatsSummary(repeats.size)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            InfoBanner("Your companion overheard these repeaters forwarding your message.")

            if (repeats.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No repeater has been heard forwarding this message.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(repeats, key = { it.hashHex }) { repeat ->
                        RepeatRow(
                            repeat = repeat,
                            // Only an ambiguous hash has anything more to show.
                            onClick = if (repeat.isAmbiguous) {
                                { expanded = repeat }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun RepeatRow(repeat: ResolvedRepeat, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HashAvatar(repeat.hashHex)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(repeat.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = repeat.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SignalBars(repeat.snr)
            Spacer(Modifier.size(4.dp))
            Text(
                text = formatSnr(repeat.snr),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HashAvatar(hashHex: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(hashHex, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Three bars, filled according to how well we heard the repeater. The thresholds
 * are the ones the node's own display uses: LoRa decodes well below 0 dB SNR, so
 * anything above about 5 dB is a strong link.
 */
@Composable
private fun SignalBars(snr: Float) {
    val filled = when {
        snr >= 5f -> 3
        snr >= 0f -> 2
        else -> 1
    }
    val color = when (filled) {
        3 -> Color(0xFF4CAF50)
        2 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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

/** Which of the known nodes sharing this hash actually repeated the message is unknowable. */
@Composable
private fun DuplicateRepeatersDialog(repeat: ResolvedRepeat, onDismiss: () -> Unit) {
    val names = (repeat.name as? RepeaterName.Duplicated)?.names.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duplicated (${repeat.hashHex})") },
        text = {
            Column {
                Text(
                    text = "${names.size} known repeaters share this hash. Any one of them " +
                        "could have forwarded the message.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                names.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HashAvatar(repeat.hashHex)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "1 known repeater",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

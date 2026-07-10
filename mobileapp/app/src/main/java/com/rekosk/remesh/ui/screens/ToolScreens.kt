package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.model.LoggedPacket
import com.rekosk.remesh.data.model.NearbyNode
import com.rekosk.remesh.data.model.TraceHop
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.formatClock
import com.rekosk.remesh.ui.screens.settings.InfoBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- path trace

/**
 * Traces a route through repeaters the user names by hash, reporting the SNR each
 * one heard the trace at. The trace is a real transmission along a chosen path.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PathTraceScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    var hashes by remember { mutableStateOf("") }
    val hops by viewModel.trace.collectAsStateWithLifecycle()
    val isTracing by viewModel.isTracing.collectAsStateWithLifecycle()
    val hashSize = remember { viewModel.pathHashSizeBytes() }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { ToolBar("Path trace", onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            InfoBanner(
                "Enter the repeaters to trace through, as their path hashes joined " +
                    "together. This mesh uses ${hashSize * 2}-character hashes, so a " +
                    "two-hop trace is ${hashSize * 4} characters.",
            )

            OutlinedTextField(
                value = hashes,
                onValueChange = { hashes = it },
                label = { Text("Repeater hashes (hex)") },
                singleLine = true,
                enabled = !isTracing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Button(
                onClick = {
                    viewModel.traceRoute(hashes.trim(), hashSize) { error ->
                        scope.launch { snackbar.showSnackbar(error) }
                    }
                },
                enabled = !isTracing && hashes.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("Start trace")
            }

            if (isTracing) {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.size(8.dp))
            hops?.let { result ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(result.size) { index -> TraceHopRow(index + 1, result[index]) }
                }
            }
        }
    }
}

@Composable
private fun TraceHopRow(number: Int, hop: TraceHop) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hop.name ?: "Unknown (${hop.hashHex})",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = hop.hashHex,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatSnr(hop.snr),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- packet log

/** Everything the radio hears, newest first. Nothing is transmitted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketLogScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val packets by viewModel.packetLog.collectAsStateWithLifecycle()
    val isConnected by viewModel.isRadioConnected.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("Packet log", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Showing ${packets.size} " +
                                if (packets.size == 1) "packet" else "packets",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearPacketLog, enabled = packets.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear log")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (packets.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isConnected) {
                            "No packets yet. The mesh may simply be quiet."
                        } else {
                            "Connect to a node to receive packets."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(packets) { PacketRow(it) }
                }
                Text(
                    text = "Only the last 500 packets are kept, and the log clears when " +
                        "the app restarts.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PacketRow(packet: LoggedPacket) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = payloadTypeName(packet.payloadType),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = buildList {
                    add(formatClock(packet.receivedEpochMs))
                    add("${packet.sizeBytes} B")
                    add(if (packet.isFlood) "Flood" else "Direct")
                    if (packet.hops > 0) add("${packet.hops} hops")
                    packet.lastPathHash?.let { add("via $it") }
                }.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatSnr(packet.snr), style = MaterialTheme.typography.labelMedium)
            Text(
                text = "${packet.rssi} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `PAYLOAD_TYPE_*` from Packet.h. An unparseable packet has no type at all. */
internal fun payloadTypeName(type: Int?): String = when (type) {
    null -> "Unparsed"
    0 -> "Request"
    1 -> "Response"
    2 -> "Text message"
    3 -> "Ack"
    4 -> "Advert"
    5 -> "Channel message"
    6 -> "Channel datagram"
    7 -> "Anonymous request"
    8 -> "Path"
    9 -> "Trace"
    10 -> "Multipart"
    11 -> "Control"
    15 -> "Raw custom"
    else -> "Type $type"
}

// ---------------------------------------------------------------- nearby nodes

/**
 * Transmits a zero-hop discovery request and lists whoever answers, so everything
 * here is in direct radio range.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DiscoverNearbyScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val nodes by viewModel.nearbyNodes.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { ToolBar("Discover nearby nodes", onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            InfoBanner(
                "Sends a zero-hop request. Only nodes in direct radio range answer, and " +
                    "they rate-limit their replies, so a repeat scan can come back empty.",
            )

            Button(
                onClick = {
                    viewModel.scanNearbyNodes { error ->
                        scope.launch { snackbar.showSnackbar(error) }
                    }
                },
                enabled = !isScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (isScanning) "Scanning..." else "Start scan")
            }

            if (isScanning) {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            if (nodes.isEmpty() && !isScanning) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No nodes answered.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(nodes, key = { it.publicKey.contentHashCode() }) { NearbyRow(it) }
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(node: NearbyNode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(node.shortKey, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name ?: "Unknown node",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formatShortKey(node.publicKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            // The two directions of the link, which are often not the same.
            Text("↓ ${formatSnr(node.inboundSnr)}", style = MaterialTheme.typography.labelMedium)
            Text(
                text = "↑ ${formatSnr(node.outboundSnr)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- noise floor

private const val NOISE_POLL_MS = 1_000L

/** Polls the radio's noise floor once a second and plots it. Read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoiseFloorScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val samples by viewModel.noiseFloor.collectAsStateWithLifecycle()
    val stats by viewModel.radioStats.collectAsStateWithLifecycle()
    val isConnected by viewModel.isRadioConnected.collectAsStateWithLifecycle()

    // Sampling stops as soon as this screen leaves the composition.
    LaunchedEffect(isConnected) {
        viewModel.clearNoiseFloor()
        while (isConnected) {
            viewModel.sampleNoiseFloor()
            delay(NOISE_POLL_MS)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { ToolBar("Noise floor", onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Text(
                text = stats?.let { "${it.noiseFloorDbm} dBm" } ?: "—",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = "Radio noise floor, sampled every second",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(24.dp))
            NoiseChart(samples)

            Spacer(Modifier.size(24.dp))
            stats?.let {
                StatLine("Last RSSI", "${it.lastRssi} dBm")
                StatLine("Last SNR", formatSnr(it.lastSnr))
                StatLine("Transmit air time", formatDuration(it.txAirTimeSec))
                StatLine("Receive air time", formatDuration(it.rxAirTimeSec))
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A plain line chart. The vertical range is fixed at -120..-60 dBm, the window a
 * LoRa radio's noise floor actually lives in, so the line does not rescale itself
 * into meaninglessness when the noise is steady.
 */
@Composable
private fun NoiseChart(samples: List<Int>) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val top = -60f
            val bottom = -120f
            fun y(dbm: Int): Float =
                size.height * (top - dbm.coerceIn(bottom.toInt(), top.toInt())) / (top - bottom)

            for (dbm in -120..-60 step 10) {
                val yy = y(dbm)
                drawLine(grid, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f)
            }
            if (samples.size < 2) return@Canvas

            val step = size.width / (samples.size - 1)
            for (i in 0 until samples.size - 1) {
                drawLine(
                    color = line,
                    start = Offset(i * step, y(samples[i])),
                    end = Offset((i + 1) * step, y(samples[i + 1])),
                    strokeWidth = 4f,
                )
            }
        }
    }
}

/** "3h 12m", "48s" -- cumulative air time, which grows slowly. */
internal fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        title = { Text(title) },
    )
}

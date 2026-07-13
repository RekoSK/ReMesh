package com.rekosk.remesh.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryUnknown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.ConnectionState
import com.rekosk.remesh.ble.DiscoveredDevice
import com.rekosk.remesh.ble.MeshFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.rekosk.remesh.data.SavedNodeSummary
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.components.NodeAvatar
import com.rekosk.remesh.ui.components.SignalBarsForRssi

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectScreen(
    viewModel: MeshViewModel,
    onOpenSettings: () -> Unit,
    // Null when shown as the "Me" bottom-bar tab: no back arrow, and the tab title.
    onBack: (() -> Unit)? = null,
    // Where to go once an offline node is opened. Defaults to the back action.
    onNodeOpened: () -> Unit = { onBack?.invoke() },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val savedNodes by viewModel.savedNodes.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val selfName by viewModel.selfName.collectAsStateWithLifecycle()
    val connectionRssi by viewModel.connectionRssi.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val pendingSettings by viewModel.pendingSettings.collectAsStateWithLifecycle()

    // Once any node has ever been connected, the tab defaults to that node's panel
    // (its offline values + live status); the Bluetooth picker moves behind "Switch".
    var showPicker by remember { mutableStateOf(false) }
    val hasNode = savedNodes.isNotEmpty()
    LaunchedEffect(state) { if (state is ConnectionState.Ready) showPicker = false }

    // While connected, keep the link RSSI and battery fresh: RSSI ticks every few
    // seconds, battery far less often since it barely moves.
    val isConnected = state is ConnectionState.Ready
    LaunchedEffect(isConnected) {
        if (isConnected) {
            var tick = 0
            while (true) {
                viewModel.readConnectionRssi()
                if (tick % 5 == 0) viewModel.refreshStorage()
                tick++
                delay(3000)
            }
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            BLE_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasPermissions = granted.values.all { it }
        if (hasPermissions) viewModel.startScan()
    }

    // Scanning is expensive; run it only while the picker is actually on screen (the
    // auto-reconnect loop does its own duty-cycled scanning behind the node panel).
    DisposableEffect(hasPermissions, state, showPicker, hasNode) {
        if (hasPermissions && state !is ConnectionState.Ready && (showPicker || !hasNode)) {
            viewModel.startScan()
        }
        onDispose { viewModel.stopScan() }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (onBack != null || (showPicker && hasNode)) {
                            IconButton(onClick = { if (showPicker) showPicker = false else onBack?.invoke() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                    title = {
                        Text(
                            when {
                                showPicker || onBack != null -> "Connect to node"
                                else -> "Me"
                            },
                        )
                    },
                )
                AnimatedVisibility(visible = state is ConnectionState.Scanning) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            StatusBanner(state = state, selfName = selfName, error = error)

            val connected = state is ConnectionState.Ready
            when {
                !hasPermissions -> PermissionPrompt { permissionLauncher.launch(BLE_PERMISSIONS) }

                // The default "Me" view once a node has ever been connected: its saved
                // (offline) values with a live status line; the picker is behind Switch.
                // Pulling down starts a search for the node right now.
                hasNode && !showPicker -> {
                    var pullRefreshing by remember { mutableStateOf(false) }
                    LaunchedEffect(pullRefreshing) {
                        if (pullRefreshing) {
                            // Spin until the scan window this pull started is over.
                            withTimeoutOrNull(3_000) {
                                snapshotFlow { state }.first { it is ConnectionState.Scanning }
                            }
                            snapshotFlow { state }.first { it !is ConnectionState.Scanning }
                            pullRefreshing = false
                        }
                    }
                    val pullState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = pullRefreshing,
                        onRefresh = {
                            if (!connected) {
                                pullRefreshing = true
                                viewModel.refreshNodeSearch()
                            }
                        },
                        state = pullState,
                        // The themed (expressive) loading shape, like every other menu.
                        indicator = {
                            PullToRefreshDefaults.LoadingIndicator(
                                state = pullState,
                                isRefreshing = pullRefreshing,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        NodePanel(
                            deviceName = selfName
                                ?: (state as? ConnectionState.Ready)?.deviceName
                                ?: savedNodes.maxByOrNull { it.lastConnectedEpochMs }?.name
                                ?: "(node)",
                            status = nodeStatus(state, isSyncing),
                            connected = connected,
                            rssi = connectionRssi,
                            battery = storage,
                            hasPendingSettings = pendingSettings != null,
                            onDisconnect = viewModel::disconnect,
                            onOpenSettings = onOpenSettings,
                            onSwitch = { showPicker = true },
                        )
                    }
                }

                state is ConnectionState.Bonding || state is ConnectionState.Connecting ||
                    state is ConnectionState.Discovering -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator() }

                else -> NodeList(
                    devices = devices,
                    savedNodes = savedNodes,
                    onConnect = {
                        showPicker = false
                        viewModel.connect(it)
                    },
                    onOpenOffline = { key ->
                        viewModel.openSavedNode(key)
                        showPicker = false
                        onNodeOpened()
                    },
                )
            }
        }
    }
}

/** The one-line node status shown in the panel: offline → searching → … → online. */
private fun nodeStatus(state: ConnectionState, isSyncing: Boolean): String = when {
    state is ConnectionState.Ready && isSyncing -> "Updating…"
    state is ConnectionState.Ready -> "Online"
    state is ConnectionState.Connecting || state is ConnectionState.Bonding ||
        state is ConnectionState.Discovering -> "Connecting…"
    state is ConnectionState.Scanning -> "Searching…"
    else -> "Offline"
}

@Composable
private fun StatusBanner(state: ConnectionState, selfName: String?, error: String?) {
    val text = when (state) {
        is ConnectionState.Disconnected -> error ?: "Not connected"
        is ConnectionState.Scanning -> "Scanning..."
        is ConnectionState.Connecting -> "Connecting..."
        is ConnectionState.Bonding -> "Pairing — enter the PIN shown on your node"
        is ConnectionState.Discovering -> "Reading node services..."
        is ConnectionState.Ready -> "Connected to ${selfName ?: state.deviceName}"
        is ConnectionState.Failed -> state.reason
    }
    val isProblem = state is ConnectionState.Failed || (error != null && state is ConnectionState.Disconnected)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isProblem) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = "ReMesh needs Bluetooth permission to find your node.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onRequest) { Text("Grant permission") }
    }
}

@Composable
private fun NodePanel(
    deviceName: String,
    status: String,
    connected: Boolean,
    rssi: Int?,
    battery: MeshFrame.Battery?,
    hasPendingSettings: Boolean,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Scrollable so the pull-to-refresh gesture above has something to grab.
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // My own node: a companion avatar in the system accent colour.
        NodeAvatar(
            type = NodeType.CHAT,
            isBlocked = false,
            size = 72,
            name = deviceName,
            isSelf = true,
        )
        Text(
            text = deviceName,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelLarge,
            color = if (connected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (connected) {
            Column(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Signal strength of the BLE link between this phone and the node.
                StatRow(
                    leading = { if (rssi != null) SignalBarsForRssi(rssi) },
                    label = "Signal to node",
                    value = rssi?.let { "$it dBm" } ?: "…",
                )
                // Battery reported by the node itself (CMD_GET_BATT_AND_STORAGE).
                StatRow(
                    leading = {
                        Icon(
                            imageVector = batteryIcon(battery?.batteryPercent(), battery?.charging == true),
                            contentDescription = null,
                            tint = batteryTint(battery?.batteryPercent()),
                        )
                    },
                    label = "Battery",
                    value = battery?.let {
                        val pct = "${it.batteryPercent()}% · ${"%.2f".format(it.volts())} V"
                        when (it.charging) {
                            true -> "$pct · Charging"
                            false -> "$pct · On battery"
                            null -> pct
                        }
                    } ?: "…",
                )
            }
        }

        if (connected) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.padding(top = 24.dp)) {
                Icon(Icons.Filled.LinkOff, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Disconnect")
            }
        }
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            // Offline edits queue up; the clock says some are still waiting to upload.
            Text(if (hasPendingSettings) "Configuration  🕖" else "Configuration")
        }
        OutlinedButton(onClick = onSwitch, modifier = Modifier.padding(top = 12.dp)) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Switch node")
        }
    }
}

/** One "glyph — label — value" line in the connected panel's status block. */
@Composable
private fun StatRow(
    leading: @Composable () -> Unit,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Green / amber / red by charge, matching the signal glyph's own colour scale. */
@Composable
private fun batteryTint(percent: Int?): androidx.compose.ui.graphics.Color = when {
    percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
    percent >= 50 -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
    percent >= 20 -> androidx.compose.ui.graphics.Color(0xFFFF9800)
    else -> androidx.compose.ui.graphics.Color(0xFFF44336)
}

/**
 * A battery glyph whose fill steps with the charge level, using Material's stepped
 * battery icons. While charging we show the charging-bolt battery (level is still
 * conveyed by the tint and the "Charging" text). Unknown level falls back to the
 * question-mark battery.
 */
private fun batteryIcon(percent: Int?, charging: Boolean): ImageVector {
    if (percent == null) return Icons.Filled.BatteryUnknown
    if (charging) return Icons.Filled.BatteryChargingFull
    return when {
        percent >= 95 -> Icons.Filled.BatteryFull
        percent >= 80 -> Icons.Filled.Battery6Bar
        percent >= 65 -> Icons.Filled.Battery5Bar
        percent >= 50 -> Icons.Filled.Battery4Bar
        percent >= 35 -> Icons.Filled.Battery3Bar
        percent >= 20 -> Icons.Filled.Battery2Bar
        percent >= 8 -> Icons.Filled.Battery1Bar
        else -> Icons.Filled.Battery0Bar
    }
}

/**
 * The scan list, split into an "Online" section (nodes in radio range now) and an
 * "Offline" section (nodes with saved data that are not in range). Tapping an online
 * node connects; tapping an offline one opens its saved history read-only.
 */
@Composable
private fun NodeList(
    devices: List<DiscoveredDevice>,
    savedNodes: List<SavedNodeSummary>,
    onConnect: (String) -> Unit,
    onOpenOffline: (String) -> Unit,
) {
    val scannedAddresses = devices.map { it.address }.toSet()
    val savedByAddress = savedNodes.mapNotNull { node -> node.address?.let { it to node } }.toMap()
    val offline = savedNodes.filter { it.address == null || it.address !in scannedAddresses }

    if (devices.isEmpty() && offline.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Scanning for MeshCore nodes...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        if (devices.isNotEmpty()) {
            item { SectionHeader("Online") }
            items(devices, key = { it.address }) { device ->
                DeviceCard(
                    name = savedByAddress[device.address]?.name ?: device.name ?: "(unnamed)",
                    address = device.address,
                    rssi = device.rssi,
                    onClick = { onConnect(device.address) },
                )
                Spacer(Modifier.size(8.dp))
            }
        }
        if (offline.isNotEmpty()) {
            item { SectionHeader("Offline") }
            items(offline, key = { it.key }) { node ->
                OfflineNodeCard(node = node, onClick = { onOpenOffline(node.key) })
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
    )
}

/** A saved node that is not in range: opens its stored data instead of connecting. */
@Composable
private fun OfflineNodeCard(node: SavedNodeSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Router,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = node.address ?: "Saved data",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "offline",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(name: String, address: String, rssi: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Router,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBarsForRssi(rssi)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$rssi dBm",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

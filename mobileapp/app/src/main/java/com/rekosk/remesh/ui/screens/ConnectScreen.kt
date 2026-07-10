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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LinkOff
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.ConnectionState
import com.rekosk.remesh.ui.MeshViewModel

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectScreen(
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val selfName by viewModel.selfName.collectAsStateWithLifecycle()

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

    // Scanning is expensive; run it only while this screen is on top.
    DisposableEffect(hasPermissions, state) {
        if (hasPermissions && state !is ConnectionState.Ready) viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text("Connect to node") },
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

            when {
                !hasPermissions -> PermissionPrompt { permissionLauncher.launch(BLE_PERMISSIONS) }

                state is ConnectionState.Ready -> ConnectedPanel(
                    deviceName = (state as ConnectionState.Ready).deviceName,
                    onDisconnect = viewModel::disconnect,
                    onOpenSettings = onOpenSettings,
                )

                state is ConnectionState.Bonding || state is ConnectionState.Connecting ||
                    state is ConnectionState.Discovering -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator() }

                devices.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Scanning for MeshCore nodes...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    items(devices, key = { it.address }) { device ->
                        DeviceCard(
                            name = device.name ?: "(unnamed)",
                            address = device.address,
                            rssi = device.rssi,
                            onClick = { viewModel.connect(device.address) },
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                }
            }
        }
    }
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
private fun ConnectedPanel(
    deviceName: String,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Router,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = deviceName,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.padding(top = 24.dp)) {
            Icon(Icons.Filled.LinkOff, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Disconnect")
        }
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Configuration")
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
            Text(
                text = "$rssi dBm",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

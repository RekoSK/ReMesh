package com.rekosk.remesh.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

/**
 * The node's own telemetry: battery from `CMD_GET_BATT_AND_STORAGE`, position
 * from `SELF_INFO`. Refreshed by pulling down rather than a floating button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TelemetryScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val selfInfo by viewModel.selfInfo.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isTelemetryRefreshing.collectAsStateWithLifecycle()
    val isConnected by viewModel.isRadioConnected.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pullState = rememberPullToRefreshState()

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    LaunchedEffect(isConnected) {
        if (isConnected) viewModel.refreshTelemetry { message(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("My telemetry") },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshTelemetry { message(it) } },
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // The scroll is what makes the pull gesture reachable on a short page.
                    .verticalScroll(rememberScrollState()),
            ) {
                // Only channel 1 exists: this node reports base telemetry and nothing else.
                FlatDropdown(
                    label = "Telemetry channel",
                    selected = "Channel 1",
                    options = listOf("Channel 1"),
                    onSelectIndex = {},
                )

                SettingsDivider()

                TelemetryRow(
                    icon = { Icon(Icons.Filled.BatteryFull, contentDescription = null) },
                    title = "Battery",
                    value = storage?.let { formatBattery(it.batteryPercent(), it.volts()) } ?: "—",
                )

                val position = selfInfo?.let { formatPosition(it.latE6, it.lonE6) }
                TelemetryRow(
                    icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                    title = "Location",
                    value = position ?: "—",
                    trailing = {
                        IconButton(
                            onClick = {
                                if (position == null) message("Not connected to a node")
                                else {
                                    copyToClipboard(context, position)
                                    message("Location copied")
                                }
                            },
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy location")
                        }
                    },
                )

                if (!isConnected) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Connect to a node to read its telemetry.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Location", text))
}

/**
 * "48% / 3.58v". The literal percent sign must be escaped as `%%`: a bare `%`
 * followed by a space makes String.format throw UnknownFormatConversionException.
 */
internal fun formatBattery(percent: Int, volts: Double): String =
    "%d%% / %.2fv".format(java.util.Locale.US, percent, volts)

/** "48.7077, 21.2163" -- four decimals, as the reference app shows it. */
internal fun formatPosition(latE6: Int, lonE6: Int): String =
    "%.4f, %.4f".format(java.util.Locale.US, latE6 / 1e6, lonE6 / 1e6)

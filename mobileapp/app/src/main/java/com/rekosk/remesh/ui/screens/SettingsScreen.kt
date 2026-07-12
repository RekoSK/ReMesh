package com.rekosk.remesh.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.MeshCoreProtocol.RadioLimits
import com.rekosk.remesh.ble.MeshFrame
import com.rekosk.remesh.data.model.NodeSettings
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.avatarGlyph
import com.rekosk.remesh.ui.screens.settings.BackGuard
import com.rekosk.remesh.ui.screens.settings.DiscardChangesDialog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Public information and Radio settings are editable; nothing is written to the
 * node until the check mark in the app bar is pressed. Everything below those two
 * sections is presentational, except the storage meter, which is a live read.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    onOpenShareQr: () -> Unit,
    onEditSelfLocation: () -> Unit,
    nav: SettingsNav,
    modifier: Modifier = Modifier,
) {
    val selfInfo by viewModel.selfInfo.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val contactCount by viewModel.totalContactCount.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshStorage() }

    // Baseline from the node. Re-keyed on selfInfo so a successful save (which
    // re-reads SELF_INFO) resets the form to what the node actually accepted.
    val original = selfInfo?.let { NodeSettings.from(it) }

    var nameText by remember(original) { mutableStateOf(original?.name.orEmpty()) }
    var latText by remember(original) { mutableStateOf(original?.latE6.asCoordinateText()) }
    var lonText by remember(original) { mutableStateOf(original?.lonE6.asCoordinateText()) }
    var shareLocation by remember(original) { mutableStateOf(original?.shareLocation ?: false) }
    var freqText by remember(original) {
        mutableStateOf(original?.let { "%.3f".format(it.freqMhz) }.orEmpty())
    }
    var bandwidthKhz by remember(original) { mutableStateOf(original?.bandwidthKhz ?: 250.0) }
    var spreadingFactor by remember(original) { mutableStateOf(original?.spreadingFactor ?: 10) }
    var codingRate by remember(original) { mutableStateOf(original?.codingRate ?: 5) }
    var txPowerText by remember(original) { mutableStateOf(original?.txPowerDbm?.toString().orEmpty()) }

    val txCeiling = selfInfo?.txPowerCeiling ?: RadioLimits.TX_POWER_MAX_FALLBACK
    var showDiscard by remember { mutableStateOf(false) }

    // Compared as text against the same formatting used to seed each field, so an
    // unparseable entry still shows up as a pending change rather than vanishing.
    val changes = original?.let { base ->
        buildList {
            if (nameText != base.name) add("Name: ${base.name} → $nameText")
            if (latText != base.latE6.asCoordinateText()) {
                add("Latitude: ${base.latE6.asCoordinateText()} → $latText")
            }
            if (lonText != base.lonE6.asCoordinateText()) {
                add("Longitude: ${base.lonE6.asCoordinateText()} → $lonText")
            }
            if (shareLocation != base.shareLocation) {
                add("Share location in advert: ${if (shareLocation) "off → on" else "on → off"}")
            }
            if (freqText != "%.3f".format(base.freqMhz)) {
                add("Frequency: ${"%.3f".format(base.freqMhz)} → $freqText MHz")
            }
            if (bandwidthKhz != base.bandwidthKhz) {
                add("Bandwidth: ${base.bandwidthKhz} → $bandwidthKhz kHz")
            }
            if (spreadingFactor != base.spreadingFactor) {
                add("Spreading factor: ${base.spreadingFactor} → $spreadingFactor")
            }
            if (codingRate != base.codingRate) add("Coding rate: ${base.codingRate} → $codingRate")
            if (txPowerText != base.txPowerDbm.toString()) {
                add("TX power: ${base.txPowerDbm} → $txPowerText dBm")
            }
        }
    }.orEmpty()

    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun onSave(thenBack: Boolean = false) {
        val base = original ?: run { showMessage("Not connected to a node"); return }
        val latE6 = NodeSettings.coordE6FromText(latText)
            ?: run { showMessage("Latitude is not a number"); return }
        val lonE6 = NodeSettings.coordE6FromText(lonText)
            ?: run { showMessage("Longitude is not a number"); return }
        val freqKhz = NodeSettings.freqKhzFromMhzText(freqText)
            ?: run { showMessage("Frequency is not a number"); return }
        val txPower = txPowerText.trim().toIntOrNull()
            ?: run { showMessage("TX power is not a number"); return }

        val edited = base.copy(
            name = nameText.trim(),
            latE6 = latE6,
            lonE6 = lonE6,
            shareLocation = shareLocation,
            freqKhz = freqKhz,
            bandwidthHz = (bandwidthKhz * 1000).roundToInt(),
            spreadingFactor = spreadingFactor,
            codingRate = codingRate,
            txPowerDbm = txPower,
        )
        if (edited == base) {
            onBack()
            return
        }
        viewModel.saveSettings(base, edited) { error ->
            when {
                error != null -> showMessage(error)
                thenBack -> onBack()
                else -> showMessage("Settings saved to node")
            }
        }
    }

    fun attemptBack() {
        if (changes.isEmpty()) onBack() else showDiscard = true
    }

    BackGuard(changes.isNotEmpty()) { showDiscard = true }

    if (showDiscard) {
        DiscardChangesDialog(
            changes = changes,
            isSaving = isSaving,
            onStay = { showDiscard = false },
            onDiscard = {
                showDiscard = false
                onBack()
            },
            onUpload = {
                showDiscard = false
                onSave(thenBack = true)
            },
        )
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = ::attemptBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text("Settings") },
                    actions = {
                        IconButton(
                            onClick = { onSave(thenBack = false) },
                            enabled = !isSaving && original != null,
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "Save to node")
                        }
                    },
                )
                AnimatedVisibility(visible = isSaving) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { IdentityHeader(selfInfo) }

            item {
                PublicInfoSection(
                    selfInfo = selfInfo,
                    name = nameText,
                    onNameChange = { nameText = it },
                    lat = latText,
                    onLatChange = { latText = it },
                    lon = lonText,
                    onLonChange = { lonText = it },
                    shareLocation = shareLocation,
                    onShareLocationChange = { shareLocation = it },
                    onClearLocation = {
                        latText = "0.000000"
                        lonText = "0.000000"
                    },
                    onShareQr = onOpenShareQr,
                    onPickLocation = onEditSelfLocation,
                    onCopyKey = {
                        val hex = selfInfo?.publicKey?.toHex()
                        if (hex == null) showMessage("Not connected to a node")
                        else copyToClipboard(context, "Public key", hex)
                    },
                )
            }

            item {
                RadioSection(
                    freq = freqText,
                    onFreqChange = { freqText = it },
                    bandwidthKhz = bandwidthKhz,
                    onBandwidthChange = { bandwidthKhz = it },
                    spreadingFactor = spreadingFactor,
                    onSpreadingFactorChange = { spreadingFactor = it },
                    codingRate = codingRate,
                    onCodingRateChange = { codingRate = it },
                    txPower = txPowerText,
                    onTxPowerChange = { txPowerText = it },
                    txCeiling = txCeiling,
                )
            }

            item { OtherSettingsSection(nav) }
            item { OtherToolsSection(nav) }
            item {
                DeviceInfoSection(
                    deviceInfo = deviceInfo,
                    storage = storage,
                    channelCount = channels.size,
                    contactCount = contactCount,
                    onShowTelemetry = nav.onShowTelemetry,
                )
            }
        }
    }
}

// ---------------- header ----------------

@Composable
private fun IdentityHeader(selfInfo: MeshFrame.SelfInfo?) {
    val nodeName = selfInfo?.name.orEmpty().ifBlank { null }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            // With no node there is no initial to show; an "N" for "Not connected"
            // would read as a node name.
            if (nodeName == null) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Text(
                    text = avatarGlyph(nodeName),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            text = nodeName ?: "Not connected",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = selfInfo?.let { "<${shortKey(it.publicKey)}>" } ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------- public information ----------------

@Composable
private fun PublicInfoSection(
    selfInfo: MeshFrame.SelfInfo?,
    name: String,
    onNameChange: (String) -> Unit,
    lat: String,
    onLatChange: (String) -> Unit,
    lon: String,
    onLonChange: (String) -> Unit,
    shareLocation: Boolean,
    onShareLocationChange: (Boolean) -> Unit,
    onClearLocation: () -> Unit,
    onShareQr: () -> Unit,
    onPickLocation: () -> Unit,
    onCopyKey: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Public information",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Public info options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                    text = { Text("Clear location") },
                    onClick = {
                        menuOpen = false
                        onClearLocation()
                    },
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.QrCode, contentDescription = null) },
                    text = { Text("Share via QR code") },
                    onClick = {
                        menuOpen = false
                        onShareQr()
                    },
                )
            }
        }
    }

    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlatTextField(
                label = "Name",
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier.weight(1f),
            )
        }
        RowDivider()

        // Read-only: the identity key is not something an app should let you retype.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            LabelledValue("Public key", selfInfo?.publicKey?.toHex() ?: "—", Modifier.weight(1f))
            IconButton(onClick = onCopyKey) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy public key")
            }
        }
        RowDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlatTextField(
                label = "Latitude",
                value = lat,
                onValueChange = onLatChange,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            FlatTextField(
                label = "Longitude",
                value = lon,
                onValueChange = onLonChange,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPickLocation) {
                Icon(Icons.Filled.Map, contentDescription = "Set location on map")
            }
        }
        RowDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShareLocationChange(!shareLocation) }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = shareLocation, onCheckedChange = onShareLocationChange)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Share location in advert",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------- radio ----------------

@Composable
private fun RadioSection(
    freq: String,
    onFreqChange: (String) -> Unit,
    bandwidthKhz: Double,
    onBandwidthChange: (Double) -> Unit,
    spreadingFactor: Int,
    onSpreadingFactorChange: (Int) -> Unit,
    codingRate: Int,
    onCodingRateChange: (Int) -> Unit,
    txPower: String,
    onTxPowerChange: (String) -> Unit,
    txCeiling: Int,
) {
    SectionHeader("Radio settings")

    SettingsCard {
        FlatTextField(
            label = "Frequency (MHz)",
            value = freq,
            onValueChange = onFreqChange,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth(),
        )
        RowDivider()
        FlatDropdown(
            label = "Bandwidth",
            selected = formatBandwidth(bandwidthKhz),
            options = RadioLimits.BANDWIDTHS_KHZ.map { formatBandwidth(it) },
            onSelectIndex = { onBandwidthChange(RadioLimits.BANDWIDTHS_KHZ[it]) },
        )
        RowDivider()
        FlatDropdown(
            label = "Spreading factor",
            selected = spreadingFactor.toString(),
            options = RadioLimits.SPREADING_FACTORS.map { it.toString() },
            onSelectIndex = { onSpreadingFactorChange(RadioLimits.SPREADING_FACTORS[it]) },
        )
        RowDivider()
        FlatDropdown(
            label = "Coding rate",
            selected = codingRate.toString(),
            options = RadioLimits.CODING_RATES.map { it.toString() },
            onSelectIndex = { onCodingRateChange(RadioLimits.CODING_RATES[it]) },
        )
        RowDivider()
        FlatTextField(
            label = "TX power (dBm)  •  max $txCeiling",
            value = txPower,
            onValueChange = onTxPowerChange,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatBandwidth(khz: Double): String =
    if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else "$khz kHz"

// ---------------- other settings ----------------

@Composable
private fun OtherSettingsSection(nav: SettingsNav) {
    SectionHeader("Other settings")
    NavRow(Icons.Filled.VpnKey, "Manage identity key", onClick = nav.onIdentityKey)
    NavRow(Icons.Filled.Bluetooth, "Bluetooth settings", onClick = nav.onBluetooth)
    NavRow(Icons.Filled.Person, "Contact settings", onClick = nav.onContacts)
    NavRow(Icons.AutoMirrored.Filled.Send, "Message settings", onClick = nav.onMessages)
    NavRow(Icons.Filled.Notifications, "Notification settings", onClick = nav.onNotifications)
    NavRow(Icons.Filled.LocationOn, "Location settings", onClick = nav.onLocation)
    NavRow(Icons.Filled.BarChart, "Telemetry settings", onClick = nav.onTelemetry)
    NavRow(Icons.Filled.LocalFireDepartment, "Experimental settings", onClick = nav.onExperimental)
}

/** Navigation callbacks for the eight sub-screens. */
data class SettingsNav(
    val onIdentityKey: () -> Unit,
    val onBluetooth: () -> Unit,
    val onContacts: () -> Unit,
    val onMessages: () -> Unit,
    val onNotifications: () -> Unit,
    val onLocation: () -> Unit,
    val onTelemetry: () -> Unit,
    val onExperimental: () -> Unit,
    val onImportConfig: () -> Unit,
    val onExportConfig: () -> Unit,
    val onShowTelemetry: () -> Unit,
)

// ---------------- other tools ----------------

@Composable
private fun OtherToolsSection(nav: SettingsNav) {
    SectionHeader("Other tools")
    NavRow(Icons.Filled.FileUpload, "Import configuration", onClick = nav.onImportConfig)
    NavRow(Icons.Filled.Save, "Export configuration", onClick = nav.onExportConfig)
    NavRow(Icons.Filled.Download, "Export app database")
}

// ---------------- device information ----------------

@Composable
private fun DeviceInfoSection(
    deviceInfo: MeshFrame.DeviceInfo?,
    storage: MeshFrame.Battery?,
    channelCount: Int,
    contactCount: Int,
    onShowTelemetry: () -> Unit,
) {
    SectionHeader("Device information")

    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = "Channels: $channelCount/${deviceInfo?.maxChannels ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Contacts: $contactCount/${deviceInfo?.maxContacts ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    StorageCard(storage)

    NavRow(Icons.Filled.BarChart, "Show telemetry", onClick = onShowTelemetry)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FooterLine("Device model: ${deviceInfo?.model.orEmpty().ifBlank { "—" }}")
        FooterLine("Firmware date: ${deviceInfo?.firmwareBuild.orEmpty().ifBlank { "—" }}")
        FooterLine("Firmware version: ${deviceInfo?.version.orEmpty().ifBlank { "—" }}")
    }
}

@Composable
private fun StorageCard(storage: MeshFrame.Battery?) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SdStorage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Storage", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = storage?.let { "${it.usedPercent()}% used  •  ${it.usedKb}kb / ${it.totalKb}kb" }
                        ?: "Not connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { storage?.usedFraction() ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------- shared pieces ----------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    )
}

/** A text field styled like the flat label-over-value rows of the reference app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlatTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlatDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelectIndex: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelectIndex(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    label: String,
    trailingText: String? = null,
    showChevron: Boolean = true,
    onClick: () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            trailingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FooterLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------- formatting ----------------

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** "2b627718...13e2d490", as the official app abbreviates a 32-byte key. */
private fun shortKey(key: ByteArray): String {
    val hex = key.toHex()
    return if (hex.length <= 16) hex else "${hex.take(8)}...${hex.takeLast(8)}"
}

private fun Int?.asCoordinateText(): String =
    if (this == null) "" else "%.6f".format(this / 1e6)

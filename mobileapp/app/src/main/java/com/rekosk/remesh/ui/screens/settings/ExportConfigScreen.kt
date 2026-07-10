package com.rekosk.remesh.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.config.ConfigSection
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

@Composable
fun ExportConfigScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val selfInfo by viewModel.selfInfo.collectAsStateWithLifecycle()
    val autoAdd by viewModel.autoAdd.collectAsStateWithLifecycle()
    val channels by viewModel.rawChannels.collectAsStateWithLifecycle()
    val contacts by viewModel.rawContacts.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selected by remember { mutableStateOf(emptySet<ConfigSection>()) }
    var pendingJson by remember { mutableStateOf<String?>(null) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    // The system file picker owns the destination; we only hand it the bytes.
    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingJson
        pendingJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: error("Could not open the file for writing")
        }.fold(
            onSuccess = { message("Configuration exported") },
            onFailure = { message(it.message ?: "Export failed") },
        )
    }

    fun onSave() {
        if (selected.isEmpty()) {
            message("Select at least one section to export")
            return
        }
        viewModel.buildConfigJson(selected) { result ->
            result.fold(
                onSuccess = { json ->
                    pendingJson = json
                    val name = selfInfo?.name?.replace(Regex("[^A-Za-z0-9_-]"), "_").orEmpty()
                    createFile.launch(if (name.isBlank()) "meshcore.json" else "$name.json")
                },
                onFailure = { message(it.message ?: "Export failed") },
            )
        }
    }

    fun toggle(section: ConfigSection, on: Boolean) {
        selected = if (on) selected + section else selected - section
    }

    SettingsSubScreen(
        title = "Export configuration",
        onBack = onBack,
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = ::onSave,
    ) {
        InfoBanner("Select data to export.")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = { selected = ConfigSection.entries.toSet() }) { Text("Select all") }
            TextButton(onClick = { selected = emptySet() }) { Text("Deselect") }
        }

        val self = selfInfo
        CheckRow(
            title = ConfigSection.NAME.label,
            subtitle = self?.name ?: "—",
            checked = ConfigSection.NAME in selected,
            onCheckedChange = { toggle(ConfigSection.NAME, it) },
        )
        CheckRow(
            title = ConfigSection.PRIVATE_KEY.label,
            subtitle = "Your private key must stay secret. It should only be exported as a " +
                "backup.\nPublic key: <${self?.publicKey?.shortHex() ?: "—"}>\nPrivate key: <hidden>",
            checked = ConfigSection.PRIVATE_KEY in selected,
            onCheckedChange = { toggle(ConfigSection.PRIVATE_KEY, it) },
        )
        CheckRow(
            title = ConfigSection.RADIO.label,
            subtitle = self?.let {
                "Frequency: %.3f MHz\nBandwidth: %.1f kHz\nSpreading factor: %d\nCoding rate: %d\nTX power: %d"
                    .format(
                        it.radioFreqKhz / 1000.0,
                        it.radioBandwidthHz / 1000.0,
                        it.spreadingFactor,
                        it.codingRate,
                        it.txPower,
                    )
            } ?: "—",
            checked = ConfigSection.RADIO in selected,
            onCheckedChange = { toggle(ConfigSection.RADIO, it) },
        )
        CheckRow(
            title = ConfigSection.POSITION.label,
            subtitle = self?.let { "%.6f, %.6f".format(it.latE6 / 1e6, it.lonE6 / 1e6) } ?: "—",
            checked = ConfigSection.POSITION in selected,
            onCheckedChange = { toggle(ConfigSection.POSITION, it) },
        )
        CheckRow(
            title = ConfigSection.OTHER.label,
            subtitle = self?.let {
                "Manual add contacts: ${if (it.manualAddContacts) 1 else 0}\n" +
                    "Share location in advert: ${it.advertLocPolicy}"
            } ?: "—",
            checked = ConfigSection.OTHER in selected,
            onCheckedChange = { toggle(ConfigSection.OTHER, it) },
        )
        CheckRow(
            title = ConfigSection.AUTO_ADD.label,
            subtitle = autoAdd?.let { cfg ->
                buildString {
                    append("Auto add users: ${cfg.has(0x02)}\n")
                    append("Auto add repeaters: ${cfg.has(0x04)}\n")
                    append("Auto add room servers: ${cfg.has(0x08)}\n")
                    append("Auto add sensors: ${cfg.has(0x10)}\n")
                    append("Auto add at max hops: ${if (cfg.maxHops == 0) "(no limit)" else cfg.maxHops}\n")
                    append("Overwrite oldest: ${cfg.has(0x01)}")
                }
            } ?: "—",
            checked = ConfigSection.AUTO_ADD in selected,
            onCheckedChange = { toggle(ConfigSection.AUTO_ADD, it) },
        )
        CheckRow(
            title = "${ConfigSection.CHANNELS.label} (${channels.size})",
            subtitle = "All channels will be exported.",
            checked = ConfigSection.CHANNELS in selected,
            onCheckedChange = { toggle(ConfigSection.CHANNELS, it) },
        )
        CheckRow(
            title = "${ConfigSection.CONTACTS.label} (${contacts.size})",
            subtitle = "All contacts will be exported.",
            checked = ConfigSection.CONTACTS in selected,
            onCheckedChange = { toggle(ConfigSection.CONTACTS, it) },
        )
    }
}

private fun ByteArray.shortHex(): String {
    val hex = joinToString("") { "%02x".format(it) }
    return if (hex.length <= 12) hex else "${hex.take(6)}...${hex.takeLast(6)}"
}

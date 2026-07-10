package com.rekosk.remesh.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.MeshCoreProtocol.BlePin
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

private val PIN_TYPES = listOf("Random (requires a screen)", "Fixed PIN")

@Composable
fun BluetoothSettingsScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The node reports its current PIN in DEVICE_INFO; 0 means "generate one per pairing".
    val currentPin = deviceInfo?.blePin?.toInt() ?: BlePin.RANDOM
    var useRandom by remember(currentPin) { mutableStateOf(currentPin == BlePin.RANDOM) }
    var pinText by remember(currentPin) {
        mutableStateOf(if (currentPin == BlePin.RANDOM) "" else currentPin.toString())
    }
    var showDiscard by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val draftPin = if (useRandom) BlePin.RANDOM else pinText.trim().toIntOrNull() ?: -1
    val changes = buildList {
        if (useRandom != (currentPin == BlePin.RANDOM)) {
            add("Bluetooth PIN type: ${if (useRandom) "Fixed → Random" else "Random → Fixed"}")
        } else if (!useRandom && draftPin != currentPin) {
            add("Bluetooth PIN: changed")
        }
    }

    fun upload(thenBack: Boolean) {
        val pin = if (useRandom) {
            BlePin.RANDOM
        } else {
            pinText.trim().toIntOrNull()?.takeIf { it in BlePin.MIN_FIXED..BlePin.MAX_FIXED }
                ?: run { message("A fixed PIN must be exactly 6 digits"); return }
        }
        viewModel.uploadSettings(nodeWrite = { viewModel.writeBlePin(pin) }) { error ->
            when {
                error != null -> message(error)
                thenBack -> onBack()
                else -> message("Bluetooth PIN saved. Re-pair the node for it to take effect.")
            }
        }
    }

    BackGuard(changes.isNotEmpty()) { showDiscard = true }

    SettingsSubScreen(
        title = "Bluetooth settings",
        onBack = { if (changes.isEmpty()) onBack() else showDiscard = true },
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = { if (changes.isEmpty()) onBack() else upload(thenBack = false) },
    ) {
        InfoBanner(
            "If you forget your Bluetooth PIN you will have to flash the USB firmware and " +
                "connect from https://app.meshcore.nz to reset it.",
        )

        FlatDropdown(
            label = "Bluetooth PIN type",
            selected = if (useRandom) PIN_TYPES[0] else PIN_TYPES[1],
            options = PIN_TYPES,
            onSelectIndex = { useRandom = it == 0 },
        )

        if (!useRandom) {
            FlatField(
                label = "Bluetooth PIN (6 digits)",
                value = pinText,
                onValueChange = { text -> pinText = text.filter { it.isDigit() }.take(6) },
                keyboardType = KeyboardType.NumberPassword,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

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
                upload(thenBack = true)
            },
        )
    }
}

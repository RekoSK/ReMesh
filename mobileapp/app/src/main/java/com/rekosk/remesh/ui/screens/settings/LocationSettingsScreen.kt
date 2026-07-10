package com.rekosk.remesh.ui.screens.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

@Composable
fun LocationSettingsScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val customVars by viewModel.customVars.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A board built without ENV_INCLUDE_GPS never reports a "gps" custom var.
    val reportedGps = customVars?.gpsEnabled
    val hasGps = reportedGps != null

    var gpsOn by remember(reportedGps) { mutableStateOf(reportedGps ?: false) }
    var showDiscard by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val changes = buildList {
        if (hasGps && gpsOn != reportedGps) {
            add("GPS mode: ${if (gpsOn) "Off → On" else "On → Off"}")
        }
    }

    fun upload(thenBack: Boolean) {
        viewModel.uploadSettings(nodeWrite = { viewModel.writeGpsEnabled(gpsOn) }) { error ->
            when {
                error != null -> message(error)
                thenBack -> onBack()
                else -> message("GPS mode saved to node")
            }
        }
    }

    BackGuard(changes.isNotEmpty()) { showDiscard = true }

    SettingsSubScreen(
        title = "Location settings",
        onBack = { if (changes.isEmpty()) onBack() else showDiscard = true },
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = if (!hasGps) null else {
            { if (changes.isEmpty()) onBack() else upload(thenBack = false) }
        },
    ) {
        if (!hasGps) {
            InfoBanner("This node reports no GPS, so GPS mode cannot be changed from here.")
        }

        SettingsSectionTitle("GPS mode")
        RadioRow(
            title = "Off",
            subtitle = "The GPS on your MeshCore device will be turned off.",
            selected = !gpsOn,
            onClick = { gpsOn = false },
            enabled = hasGps,
        )
        RadioRow(
            title = "On",
            subtitle = "The GPS on your MeshCore device will be turned on and your location " +
                "will be updated automatically.",
            selected = gpsOn,
            onClick = { gpsOn = true },
            enabled = hasGps,
        )
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

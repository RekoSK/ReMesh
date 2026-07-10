package com.rekosk.remesh.ui.screens.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.MeshCoreProtocol.Telemetry
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

private fun label(value: Int) = when (value) {
    Telemetry.NO -> "No"
    Telemetry.YES -> "Yes"
    else -> "Specific contacts"
}

@Composable
fun TelemetrySettingsScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val selfInfo by viewModel.selfInfo.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val mode = selfInfo?.telemetryMode ?: 0
    val savedBase = Telemetry.base(mode)
    val savedLocation = Telemetry.location(mode)
    val savedEnvironment = Telemetry.environment(mode)

    var base by remember(mode) { mutableIntStateOf(savedBase) }
    var location by remember(mode) { mutableIntStateOf(savedLocation) }
    var environment by remember(mode) { mutableIntStateOf(savedEnvironment) }
    var showDiscard by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val changes = buildList {
        if (base != savedBase) add("Allow telemetry requests: ${label(savedBase)} → ${label(base)}")
        if (location != savedLocation) {
            add("Include location: ${label(savedLocation)} → ${label(location)}")
        }
        if (environment != savedEnvironment) {
            add("Include environment sensors: ${label(savedEnvironment)} → ${label(environment)}")
        }
    }

    fun upload(thenBack: Boolean) {
        viewModel.uploadSettings(
            nodeWrite = { viewModel.writeTelemetry(base, location, environment) },
        ) { error ->
            when {
                error != null -> message(error)
                thenBack -> onBack()
                else -> message("Telemetry settings saved to node")
            }
        }
    }

    BackGuard(changes.isNotEmpty()) { showDiscard = true }

    SettingsSubScreen(
        title = "Telemetry settings",
        onBack = { if (changes.isEmpty()) onBack() else showDiscard = true },
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = { if (changes.isEmpty()) onBack() else upload(thenBack = false) },
    ) {
        TelemetryGroup(
            question = "Allow telemetry requests?",
            noText = "Telemetry requests will be ignored.",
            yesText = "Telemetry requests will be allowed from everyone.",
            permittedText = "Telemetry requests will be allowed from permitted contacts.",
            value = base,
            onChange = { base = it },
        )
        TelemetryGroup(
            question = "Include location in telemetry?",
            noText = "Location will be excluded from your telemetry.",
            yesText = "Location will be included in your telemetry.",
            permittedText = "Location will be included for permitted contacts.",
            value = location,
            onChange = { location = it },
        )
        TelemetryGroup(
            question = "Include environment sensors in telemetry?",
            noText = "Environment sensors will be excluded from your telemetry.",
            yesText = "Environment sensors will be included in your telemetry.",
            permittedText = "Environment sensors will be included for permitted contacts.",
            value = environment,
            onChange = { environment = it },
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

/**
 * The firmware's third mode (2) restricts telemetry to contacts holding a
 * permission flag. The app has no per-contact permission list yet, so the option
 * is shown but disabled rather than silently missing.
 */
@Composable
private fun TelemetryGroup(
    question: String,
    noText: String,
    yesText: String,
    permittedText: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    SettingsSectionTitle(question)
    RadioRow(
        title = "No",
        subtitle = noText,
        selected = value == Telemetry.NO,
        onClick = { onChange(Telemetry.NO) },
    )
    RadioRow(
        title = "Yes",
        subtitle = yesText,
        selected = value == Telemetry.YES,
        onClick = { onChange(Telemetry.YES) },
    )
    RadioRow(
        title = "For specific contacts (coming soon)",
        subtitle = permittedText,
        selected = value == Telemetry.PERMITTED_CONTACTS,
        onClick = {},
        enabled = false,
    )
}

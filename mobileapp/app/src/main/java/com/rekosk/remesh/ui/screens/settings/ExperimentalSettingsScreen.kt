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
import com.rekosk.remesh.ble.MeshCoreProtocol.PathHashMode
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

private fun pathHashLabel(mode: Int): String {
    val size = PathHashMode.hashSizeBytes(mode)
    return "$size-byte (max ${PathHashMode.maxHops(mode)} hops)"
}

@Composable
fun ExperimentalSettingsScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val savedPrefs by viewModel.experimentalPrefs.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val savedMode = deviceInfo?.pathHashMode ?: PathHashMode.MIN
    var mode by remember(savedMode) { mutableIntStateOf(savedMode) }
    var draftPrefs by remember(savedPrefs) { mutableStateOf(savedPrefs) }
    var showDiscard by remember { mutableStateOf(false) }

    val options = (PathHashMode.MIN..PathHashMode.MAX).map(::pathHashLabel)

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val changes = buildList {
        if (mode != savedMode) {
            add("Path hash size: ${pathHashLabel(savedMode)} → ${pathHashLabel(mode)}")
        }
        val original = savedPrefs
        val edited = draftPrefs
        if (original != null && edited != null) addAll(edited.changesFrom(original))
    }

    fun upload(thenBack: Boolean) {
        val prefs = draftPrefs
        viewModel.uploadSettings(
            savePrefs = prefs?.let { { viewModel.persist(it) } },
            nodeWrite = if (mode == savedMode) null else {
                { viewModel.writePathHashMode(mode) }
            },
            onResult = { error ->
                when {
                    error != null -> message(error)
                    thenBack -> onBack()
                    else -> message("Experimental settings saved")
                }
            },
        )
    }

    BackGuard(changes.isNotEmpty()) { showDiscard = true }

    SettingsSubScreen(
        title = "Experimental settings",
        onBack = { if (changes.isEmpty()) onBack() else showDiscard = true },
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = { if (changes.isEmpty()) onBack() else upload(thenBack = false) },
    ) {
        InfoBanner(
            "These settings are experimental and may cause problems.",
            tone = BannerTone.Warning,
        )

        SettingsSectionTitle("Path hash size")
        InfoBanner(
            "MeshCore firmware v1.14.0+ supports multi-byte path hashes. Longer repeater IDs " +
                "per hop reduce the maximum hop count. Nodes on older firmware only support " +
                "1-byte path hashes and will not receive your messages if you change this. " +
                "Every repeater and companion on the route must be updated to use this.",
        )
        FlatDropdown(
            label = "Default path hash size",
            selected = pathHashLabel(mode),
            options = options,
            onSelectIndex = { mode = it },
        )

        SettingsDivider()

        val prefs = draftPrefs ?: return@SettingsSubScreen
        CheckRow(
            title = "Faster channel syncing",
            subtitle = "When enabled, channels will sync much faster when connected via " +
                "Bluetooth or Android USB.",
            checked = prefs.fasterChannelSyncing,
            onCheckedChange = { draftPrefs = prefs.copy(fasterChannelSyncing = it) },
        )
        CheckRow(
            title = "Use companion clock for DMs",
            subtitle = "When enabled, DMs will use the companion clock instead of the app clock " +
                "for timestamps.",
            checked = prefs.companionClockForDms,
            onCheckedChange = { draftPrefs = prefs.copy(companionClockForDms = it) },
        )
        CheckRow(
            title = "Use companion clock for CLI",
            subtitle = "When enabled, CLI commands will use the companion clock instead of the " +
                "app clock for timestamps.",
            checked = prefs.companionClockForCli,
            onCheckedChange = { draftPrefs = prefs.copy(companionClockForCli = it) },
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

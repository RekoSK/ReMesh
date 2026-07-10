package com.rekosk.remesh.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.rekosk.remesh.ble.MeshCoreProtocol.AutoAdd
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

@Composable
fun ContactSettingsScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val autoAdd by viewModel.autoAdd.collectAsStateWithLifecycle()
    val savedPrefs by viewModel.contactAppPrefs.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val savedConfig = autoAdd?.config ?: AutoAdd.ALL_TYPES
    val savedHops = autoAdd?.maxHops ?: AutoAdd.MAX_HOPS_UNLIMITED

    // With every type bit set there is nothing to distinguish "add all" from
    // "add selected, all four ticked", so we render that state as "add all".
    var addAll by remember(savedConfig) {
        mutableStateOf((savedConfig and AutoAdd.ALL_TYPES) == AutoAdd.ALL_TYPES)
    }
    var chat by remember(savedConfig) { mutableStateOf(savedConfig and AutoAdd.CHAT != 0) }
    var repeater by remember(savedConfig) { mutableStateOf(savedConfig and AutoAdd.REPEATER != 0) }
    var room by remember(savedConfig) { mutableStateOf(savedConfig and AutoAdd.ROOM != 0) }
    var sensor by remember(savedConfig) { mutableStateOf(savedConfig and AutoAdd.SENSOR != 0) }
    var overwriteOldest by remember(savedConfig) {
        mutableStateOf(savedConfig and AutoAdd.OVERWRITE_OLDEST != 0)
    }
    var maxHopsText by remember(savedHops) {
        mutableStateOf(if (savedHops == AutoAdd.MAX_HOPS_UNLIMITED) "" else savedHops.toString())
    }
    var draftPrefs by remember(savedPrefs) { mutableStateOf(savedPrefs) }
    var showDiscard by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val draftConfig = buildAutoAddConfig(addAll, chat, repeater, room, sensor, overwriteOldest)
    val savedHopsText =
        if (savedHops == AutoAdd.MAX_HOPS_UNLIMITED) "" else savedHops.toString()

    val changes = buildList {
        if (draftConfig != savedConfig) add("Auto add rules changed")
        if (maxHopsText != savedHopsText) {
            add("Auto add max hops: ${savedHopsText.ifBlank { "no limit" }} → " +
                maxHopsText.ifBlank { "no limit" })
        }
        val original = savedPrefs
        val edited = draftPrefs
        if (original != null && edited != null) addAll(edited.changesFrom(original))
    }

    fun upload(thenBack: Boolean) {
        val maxHops = if (maxHopsText.isBlank()) {
            AutoAdd.MAX_HOPS_UNLIMITED
        } else {
            maxHopsText.trim().toIntOrNull()?.takeIf { it in 0..AutoAdd.MAX_HOPS_LIMIT }
                ?: run { message("Max hops must be 0-${AutoAdd.MAX_HOPS_LIMIT}, or blank"); return }
        }
        val prefs = draftPrefs
        val nodeChanged = draftConfig != savedConfig || maxHopsText != savedHopsText

        viewModel.uploadSettings(
            savePrefs = prefs?.let { { viewModel.persist(it) } },
            nodeWrite = if (!nodeChanged) null else {
                { viewModel.writeAutoAddConfig(draftConfig, maxHops) }
            },
            onResult = { error ->
                when {
                    error != null -> message(error)
                    thenBack -> onBack()
                    else -> message("Contact settings saved")
                }
            },
        )
    }

    BackGuard(changes.isNotEmpty()) { showDiscard = true }

    SettingsSubScreen(
        title = "Contact settings",
        onBack = { if (changes.isEmpty()) onBack() else showDiscard = true },
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = { if (changes.isEmpty()) onBack() else upload(thenBack = false) },
    ) {
        RadioRow(
            title = "Auto add all",
            subtitle = "When enabled, all received adverts will be added to contacts.",
            selected = addAll,
            onClick = { addAll = true },
        )
        RadioRow(
            title = "Auto add selected",
            subtitle = "When enabled, only the contact types selected below are added automatically.",
            selected = !addAll,
            onClick = { addAll = false },
        )

        CheckRow("Auto add users", chat, { chat = it }, enabled = !addAll, indent = true)
        CheckRow("Auto add repeaters", repeater, { repeater = it }, enabled = !addAll, indent = true)
        CheckRow("Auto add room servers", room, { room = it }, enabled = !addAll, indent = true)
        CheckRow("Auto add sensors", sensor, { sensor = it }, enabled = !addAll, indent = true)

        SettingsDivider()

        CheckRow(
            title = "Overwrite oldest",
            subtitle = "When enabled, the oldest non-favourite contacts are overwritten by new " +
                "contacts once the contact list is full.",
            checked = overwriteOldest,
            onCheckedChange = { overwriteOldest = it },
        )

        SettingsSectionTitle("Auto add at max hop count")
        Text(
            text = "Contacts are added automatically only if their advert route has the same or " +
                "fewer hops than the configured limit. Leave blank for no limit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        FlatField(
            label = "Auto add max hops (0-${AutoAdd.MAX_HOPS_LIMIT})",
            value = maxHopsText,
            onValueChange = { text -> maxHopsText = text.filter { it.isDigit() }.take(2) },
            keyboardType = KeyboardType.Number,
        )

        SettingsDivider()

        val prefs = draftPrefs ?: return@SettingsSubScreen
        CheckRow(
            title = "Pull to refresh",
            subtitle = "When enabled you can pull down to refresh the contact list.",
            checked = prefs.pullToRefresh,
            onCheckedChange = { draftPrefs = prefs.copy(pullToRefresh = it) },
        )
        CheckRow(
            title = "Show public keys",
            subtitle = "When enabled, public keys are shown in the contact list.",
            checked = prefs.showPublicKeys,
            onCheckedChange = { draftPrefs = prefs.copy(showPublicKeys = it) },
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

private fun buildAutoAddConfig(
    addAll: Boolean,
    chat: Boolean,
    repeater: Boolean,
    room: Boolean,
    sensor: Boolean,
    overwriteOldest: Boolean,
): Int {
    var bits = if (addAll) {
        AutoAdd.ALL_TYPES
    } else {
        var selected = 0
        if (chat) selected = selected or AutoAdd.CHAT
        if (repeater) selected = selected or AutoAdd.REPEATER
        if (room) selected = selected or AutoAdd.ROOM
        if (sensor) selected = selected or AutoAdd.SENSOR
        selected
    }
    if (overwriteOldest) bits = bits or AutoAdd.OVERWRITE_OLDEST
    return bits
}

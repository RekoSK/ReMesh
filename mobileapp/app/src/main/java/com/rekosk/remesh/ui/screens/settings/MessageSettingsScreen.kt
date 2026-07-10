package com.rekosk.remesh.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.MeshCoreProtocol
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

/**
 * The node stores the number of *extra* acks, clamped to 0..1, so the total it
 * transmits is 1 or 2. The user picks the total.
 */
private val ACK_OPTIONS = MeshCoreProtocol.MultiAcks.TOTAL_OPTIONS

@Composable
fun MessageSettingsScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val selfInfo by viewModel.selfInfo.collectAsStateWithLifecycle()
    val saved by viewModel.messagePrefs.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val savedAcks = selfInfo?.totalDirectAcks ?: 1
    var draftAcks by remember(savedAcks) { mutableIntStateOf(savedAcks) }
    var draft by remember(saved) { mutableStateOf(saved) }
    var showDiscard by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val original = saved
    val edited = draft
    val changes = buildList {
        if (original != null && edited != null) addAll(edited.changesFrom(original))
        if (draftAcks != savedAcks) add("Direct message acks: $savedAcks → $draftAcks")
    }

    fun upload(thenBack: Boolean) {
        val prefs = edited
        viewModel.uploadSettings(
            savePrefs = prefs?.let { { viewModel.persist(it) } },
            nodeWrite = if (draftAcks == savedAcks) null else {
                { viewModel.writeDirectMessageAcks(draftAcks) }
            },
            onResult = { error ->
                when {
                    error != null -> message(error)
                    thenBack -> onBack()
                    else -> message("Message settings saved")
                }
            },
        )
    }

    BackGuard(changes.isNotEmpty()) { showDiscard = true }

    SettingsSubScreen(
        title = "Message settings",
        onBack = { if (changes.isEmpty()) onBack() else showDiscard = true },
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = { if (changes.isEmpty()) onBack() else upload(thenBack = false) },
    ) {
        if (edited == null) return@SettingsSubScreen

        CheckRow(
            title = "Auto retry",
            subtitle = "If a direct message fails to send, it will retry up to 5 times for a " +
                "direct route, or 3 times when flooding.",
            checked = edited.autoRetry,
            onCheckedChange = { draft = edited.copy(autoRetry = it) },
        )
        CheckRow(
            title = "Auto reset path",
            subtitle = "If auto retry is on and a direct message keeps failing, the last attempt " +
                "resets the route and sends the message as a flood.",
            checked = edited.autoResetPath,
            onCheckedChange = { draft = edited.copy(autoResetPath = it) },
        )

        SettingsSectionTitle("Direct message acks")
        Text(
            text = "When you receive a direct message, your companion sends an acknowledgement to " +
                "the sender. If it is lost, the sender may see the message as failed. Sending " +
                "several acks improves delivery reporting but increases network traffic.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        FlatDropdown(
            label = "Direct message acks",
            selected = draftAcks.toString(),
            options = ACK_OPTIONS.map { it.toString() },
            onSelectIndex = { draftAcks = ACK_OPTIONS[it] },
        )

        SettingsDivider()

        CheckRow(
            title = "Keep screen on",
            subtitle = "When enabled the screen stays on while viewing contact or channel messages.",
            checked = edited.keepScreenOn,
            onCheckedChange = { draft = edited.copy(keepScreenOn = it) },
        )
        CheckRow(
            title = "Auto focus message field",
            subtitle = "When enabled, opening a conversation focuses the message input field.",
            checked = edited.autoFocusMessageField,
            onCheckedChange = { draft = edited.copy(autoFocusMessageField = it) },
        )
        CheckRow(
            title = "Jump to oldest message",
            subtitle = "When enabled, conversations open at the oldest unread message.",
            checked = edited.jumpToOldestUnread,
            onCheckedChange = { draft = edited.copy(jumpToOldestUnread = it) },
        )
        CheckRow(
            title = "Mark delivery faster",
            subtitle = "When enabled, outgoing messages show as delivered as soon as an ack is " +
                "seen, rather than waiting for it to travel back through every repeater.",
            checked = edited.markDeliveryFaster,
            onCheckedChange = { draft = edited.copy(markDeliveryFaster = it) },
        )
        CheckRow(
            title = "Save message drafts",
            subtitle = "When enabled, unsent messages are saved on leaving a conversation and " +
                "restored on return.",
            checked = edited.saveDrafts,
            onCheckedChange = { draft = edited.copy(saveDrafts = it) },
        )
        CheckRow(
            title = "Show channel message hops",
            subtitle = "When enabled, the hop count is shown under channel messages.",
            checked = edited.showChannelMessageHops,
            onCheckedChange = { draft = edited.copy(showChannelMessageHops = it) },
        )
        CheckRow(
            title = "Show channel message path hash sizes",
            subtitle = "When enabled, path hash sizes are shown under channel messages.",
            checked = edited.showChannelPathHashSizes,
            onCheckedChange = { draft = edited.copy(showChannelPathHashSizes = it) },
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

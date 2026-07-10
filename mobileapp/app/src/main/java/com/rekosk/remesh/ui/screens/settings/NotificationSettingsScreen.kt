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
fun NotificationSettingsScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val saved by viewModel.notificationPrefs.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var draft by remember(saved) { mutableStateOf(saved) }
    var showDiscard by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val original = saved
    val edited = draft
    val changes = if (original == null || edited == null) emptyList()
    else edited.changesFrom(original)

    fun upload(thenBack: Boolean) {
        val prefs = edited ?: return
        // Nothing here reaches the node, so there is no node write to sequence.
        viewModel.uploadSettings(savePrefs = { viewModel.persist(prefs) }) { error ->
            when {
                error != null -> message(error)
                thenBack -> onBack()
                else -> message("Notification settings saved")
            }
        }
    }

    BackGuard(changes.isNotEmpty()) { showDiscard = true }

    SettingsSubScreen(
        title = "Notification settings",
        onBack = { if (changes.isEmpty()) onBack() else showDiscard = true },
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = { if (changes.isEmpty()) onBack() else upload(thenBack = false) },
    ) {
        InfoBanner(
            "ReMesh does not post notifications yet. These choices are saved and will take " +
                "effect once notifications are implemented.",
        )

        if (edited == null) return@SettingsSubScreen

        CheckRow(
            title = "Contact messages",
            subtitle = "When a contact sends you a message.",
            checked = edited.contactMessages,
            onCheckedChange = { draft = edited.copy(contactMessages = it) },
        )
        CheckRow(
            title = "Channel messages",
            subtitle = "When a channel message is received.",
            checked = edited.channelMessages,
            onCheckedChange = { draft = edited.copy(channelMessages = it) },
        )
        CheckRow(
            title = "Room server messages",
            subtitle = "When a room message is received.",
            checked = edited.roomMessages,
            onCheckedChange = { draft = edited.copy(roomMessages = it) },
        )
        CheckRow(
            title = "New messages while disconnected",
            subtitle = "When the app reconnects and syncs several unread messages.",
            checked = edited.missedWhileOffline,
            onCheckedChange = { draft = edited.copy(missedWhileOffline = it) },
        )
        CheckRow(
            title = "New contact discovered",
            subtitle = "When a new contact is discovered.",
            checked = edited.newContact,
            onCheckedChange = { draft = edited.copy(newContact = it) },
        )
        CheckRow(
            title = "Contacts full",
            subtitle = "When your contact list is full.",
            checked = edited.contactsFull,
            onCheckedChange = { draft = edited.copy(contactsFull = it) },
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

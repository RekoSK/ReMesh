package com.rekosk.remesh.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.MeshCoreProtocol
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.screens.settings.FlatDropdown
import com.rekosk.remesh.ui.screens.settings.FlatField
import com.rekosk.remesh.ui.screens.settings.InfoBanner
import com.rekosk.remesh.ui.screens.settings.SettingsDivider
import kotlinx.coroutines.launch

/** The advert types a contact can have, in the order the reference app lists them. */
private val CONTACT_TYPES = listOf(
    MeshCoreProtocol.AdvType.CHAT to "Chat",
    MeshCoreProtocol.AdvType.REPEATER to "Repeater",
    MeshCoreProtocol.AdvType.ROOM to "Room Server",
    MeshCoreProtocol.AdvType.SENSOR to "Sensor",
)

/**
 * Adds a contact by hand, without waiting for it to advertise. The key can be typed,
 * pasted from a shared link, or scanned; the ✅ writes it to the node.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(viewModel: MeshViewModel, onBack: () -> Unit, onScanQr: () -> Unit) {
    var typeIndex by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    fun say(message: String) = scope.launch { snackbar.showSnackbar(message) }

    fun save() {
        viewModel.addContact(name, CONTACT_TYPES[typeIndex].first, publicKey.trim()) { error ->
            if (error == null) onBack() else say(error)
        }
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
                title = { Text("Add contact") },
                actions = {
                    IconButton(
                        onClick = ::save,
                        enabled = !isBusy && name.isNotBlank() && publicKey.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save contact")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            InfoBanner(
                "If you know the public key of somebody you want to contact, you can add " +
                    "them manually without waiting for their advert.",
            )

            FlatDropdown(
                label = "Contact type",
                selected = CONTACT_TYPES[typeIndex].second,
                options = CONTACT_TYPES.map { it.second },
                onSelectIndex = { typeIndex = it },
            )
            SettingsDivider()

            FlatField(label = "Name", value = name, onValueChange = { name = it })
            SettingsDivider()

            FlatField(
                label = "Public key (hex)",
                value = publicKey,
                onValueChange = { publicKey = it },
            )

            Spacer(Modifier.size(16.dp))
            ActionCard(Icons.Filled.ContentPaste, "Import from link in clipboard") {
                when (val shared = viewModel.parseContactLink(clipboardText(context).orEmpty())) {
                    null -> say("No MeshCore contact link in the clipboard")
                    else -> {
                        name = shared.name
                        publicKey = shared.publicKeyHex
                        typeIndex = CONTACT_TYPES.indexOfFirst { it.first == shared.type }
                            .coerceAtLeast(0)
                    }
                }
            }
            ActionCard(Icons.Filled.PhotoCamera, "Scan QR code", onClick = onScanQr)
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(20.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()
}

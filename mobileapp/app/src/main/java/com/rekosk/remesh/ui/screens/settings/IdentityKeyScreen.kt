package com.rekosk.remesh.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

@Composable
fun IdentityKeyScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val selfName by viewModel.selfName.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var keyHex by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    SettingsSubScreen(
        title = "Manage identity key",
        subtitle = selfName,
        onBack = onBack,
        snackbar = snackbar,
        isSaving = isSaving,
    ) {
        InfoBanner("Importing and exporting the identity key requires companion firmware v1.7.0+.")

        InfoBanner(
            "WARNING: your private identity key must stay secret. It is used to encrypt and " +
                "decrypt the messages you send and receive. If you have a new device you can " +
                "export the private key from this device and import it into the new one to keep " +
                "your existing identity.",
            tone = BannerTone.Warning,
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyHex,
                onValueChange = {},
                readOnly = true,
                label = { Text("Private key") },
                singleLine = true,
                visualTransformation = if (revealed) VisualTransformation.None
                else PasswordVisualTransformation(),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            IconButton(onClick = { revealed = !revealed }, enabled = keyHex.isNotEmpty()) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff
                    else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide private key" else "Reveal private key",
                )
            }
            IconButton(
                onClick = {
                    copySecret(context, keyHex)
                    message("Private key copied. Clear your clipboard when you are done.")
                },
                enabled = keyHex.isNotEmpty(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy private key")
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.exportPrivateKey { result ->
                        result.fold(
                            onSuccess = {
                                keyHex = it.toHex()
                                message("Private key exported from node")
                            },
                            onFailure = { message(it.message ?: "Export failed") },
                        )
                    }
                },
            ) { Text("Export private key") }

            OutlinedButton(onClick = { showImport = true }) { Text("Import private key") }
        }
    }

    if (showImport) {
        ImportKeyDialog(
            onDismiss = { showImport = false },
            onImport = { hex ->
                showImport = false
                val bytes = hex.hexToBytesOrNull()
                if (bytes == null || bytes.size != 64) {
                    message("A private key must be 128 hex characters")
                } else {
                    viewModel.importPrivateKey(bytes) { error ->
                        message(error ?: "Identity imported. The node now has a new public key.")
                    }
                }
            },
        )
    }
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import private key") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("128 hex characters") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onImport(text) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun copySecret(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("MeshCore private key", text).apply {
        // Keeps the value out of clipboard previews and history where supported.
        description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytesOrNull(): ByteArray? {
    val clean = trim().removePrefix("0x")
    if (clean.length % 2 != 0 || clean.any { it !in "0123456789abcdefABCDEF" }) return null
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

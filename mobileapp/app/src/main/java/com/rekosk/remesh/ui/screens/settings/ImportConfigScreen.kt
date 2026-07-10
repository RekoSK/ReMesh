package com.rekosk.remesh.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.config.ConfigCodec
import com.rekosk.remesh.data.config.ConfigFile
import com.rekosk.remesh.data.config.ConfigSection
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

@Composable
fun ImportConfigScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val isSaving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    val progress by viewModel.importProgress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var loaded by remember { mutableStateOf<ConfigFile?>(null) }
    var selected by remember { mutableStateOf(emptySet<ConfigSection>()) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            } ?: error("Could not read the file")
            ConfigCodec.decode(text)
        }.fold(
            onSuccess = { config ->
                loaded = config
                // Default to everything the file actually contains.
                selected = config.sectionsPresent()
            },
            onFailure = { message("Not a valid MeshCore configuration file") },
        )
    }

    val config = loaded

    SettingsSubScreen(
        title = "Import configuration",
        onBack = onBack,
        snackbar = snackbar,
        isSaving = isSaving,
        onSave = if (config == null) null else {
            {
                if (selected.isEmpty()) {
                    message("Select at least one section to import")
                } else {
                    viewModel.applyConfig(config, selected) { errors ->
                        if (errors.isEmpty()) {
                            message("Configuration imported")
                            onBack()
                        } else {
                            message(errors.first())
                        }
                    }
                }
            }
        },
    ) {
        if (config == null) {
            EmptyPicker(onPick = { pickFile.launch(arrayOf("application/json", "*/*")) })
            return@SettingsSubScreen
        }

        progress?.let {
            InfoBanner("Importing: $it")
        }

        InfoBanner("Select the data to write to your node.")

        val present = config.sectionsPresent()
        ConfigSection.entries.forEach { section ->
            val count = when (section) {
                ConfigSection.CHANNELS -> config.channels?.size
                ConfigSection.CONTACTS -> config.contacts?.size
                else -> null
            }
            CheckRow(
                title = if (count == null) section.label else "${section.label} ($count)",
                subtitle = if (section in present) null else "Not present in this file",
                checked = section in selected,
                enabled = section in present,
                onCheckedChange = { on ->
                    selected = if (on) selected + section else selected - section
                },
            )
        }
    }
}

@Composable
private fun EmptyPicker(onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(160.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.FileUpload,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Import configuration",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Select a companion configuration file",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onPick, modifier = Modifier.padding(top = 16.dp)) { Text("Select file") }
    }
}

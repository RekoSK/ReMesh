package com.rekosk.remesh.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Colour of an [InfoBanner]. */
enum class BannerTone { Info, Warning }

/**
 * Shown when the user leaves a settings screen with unsaved edits. Lists exactly
 * what is queued, so "Discard" is never a guess.
 *
 * "Back" returns to the screen untouched, "Discard" throws the edits away and
 * leaves, "Upload" writes them and leaves.
 */
@Composable
fun DiscardChangesDialog(
    changes: List<String>,
    onStay: () -> Unit,
    onDiscard: () -> Unit,
    onUpload: () -> Unit,
    isSaving: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("Discard changes?") },
        text = {
            Column {
                Text(
                    text = "These changes are queued and have not been sent yet:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                changes.forEach { change ->
                    Row(modifier = Modifier.padding(bottom = 4.dp)) {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium)
                        Text(text = change, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onStay, enabled = !isSaving) { Text("Back") }
                TextButton(onClick = onDiscard, enabled = !isSaving) { Text("Discard") }
                TextButton(onClick = onUpload, enabled = !isSaving) { Text("Upload") }
            }
        },
    )
}

/**
 * Wires the hardware/gesture back button to the same guard as the app bar arrow.
 * Disabled when there is nothing queued, so back behaves normally.
 */
@Composable
fun BackGuard(hasChanges: Boolean, onIntercept: () -> Unit) {
    BackHandler(enabled = hasChanges, onBack = onIntercept)
}

/**
 * The common shell for every settings sub-screen: back arrow, title, and an
 * optional check mark. Screens that write to the node on the check mark pass
 * [onSave]; screens whose controls apply immediately leave it null, matching the
 * reference app (its Messages and Notifications screens have no check mark).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isSaving: Boolean = false,
    onSave: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(title)
                            subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (onSave != null) {
                            IconButton(onClick = onSave, enabled = !isSaving) {
                                Icon(Icons.Filled.Check, contentDescription = "Save to node")
                            }
                        }
                    },
                )
                AnimatedVisibility(visible = isSaving) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            content()
        }
    }
}

@Composable
fun InfoBanner(text: String, tone: BannerTone = BannerTone.Info) {
    val container = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.surfaceContainerHigh
        BannerTone.Warning -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.onSurface
        BannerTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = onContainer)
            Spacer(Modifier.width(16.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = onContainer)
        }
    }
}

@Composable
fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun CheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    indent: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(start = if (indent) 40.dp else 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun RadioRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlatField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlatDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelectIndex: (Int) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelectIndex(index)
                    },
                )
            }
        }
    }
}

@Composable
fun Spacer8() = Spacer(Modifier.size(8.dp))

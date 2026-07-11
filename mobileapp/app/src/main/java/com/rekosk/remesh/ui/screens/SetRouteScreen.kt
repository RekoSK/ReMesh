package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.PathInputCard
import com.rekosk.remesh.ui.components.RepeaterPickerDialog
import com.rekosk.remesh.ui.components.appendHash
import com.rekosk.remesh.ui.components.repeatersOnly
import com.rekosk.remesh.ui.screens.settings.InfoBanner
import kotlinx.coroutines.launch

/**
 * Sets a contact's outgoing route, using the same path editor as Tools -> Path trace:
 * a byte-size selector, the `aa,bb,cc` field, and a `+` to pick a saved repeater.
 * An empty path pins a direct, zero-hop route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetRouteScreen(viewModel: MeshViewModel, contactId: String, onBack: () -> Unit) {
    val contacts by viewModel.allContacts.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val extras = remember(contactId) { viewModel.contactExtras(contactId) }

    var path by remember { mutableStateOf(extras?.outPathHex.orEmpty()) }
    var byteSize by remember { mutableStateOf((extras?.pathHashSizeBytes ?: 1).coerceIn(1, 3)) }
    var showPicker by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                title = { Text("Outgoing route") },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showPicker = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add repeater to path")
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            InfoBanner(
                "Choose the repeaters your messages should route through, last hop first. " +
                    "Leave it empty for a direct zero-hop route.",
            )
            PathInputCard(
                byteSize = byteSize,
                onByteSize = { byteSize = it },
                path = path,
                onPath = { path = it },
                enabled = !isBusy,
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = {
                    viewModel.setContactRoute(contactId, path) { error ->
                        if (error == null) onBack() else scope.launch { snackbar.showSnackbar(error) }
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text("Save route")
            }
        }
    }

    if (showPicker) {
        RepeaterPickerDialog(
            repeaters = contacts.repeatersOnly(),
            byteSize = byteSize,
            onDismiss = { showPicker = false },
            onPick = { prefix ->
                showPicker = false
                path = appendHash(path, prefix)
            },
        )
    }
}

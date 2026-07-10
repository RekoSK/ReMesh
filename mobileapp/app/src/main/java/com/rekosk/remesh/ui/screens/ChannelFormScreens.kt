package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.ChannelCrypto
import com.rekosk.remesh.ui.MeshViewModel
import kotlinx.coroutines.launch

/** Invents a fresh 16-byte key. Nobody else can join until the key is shared. */
@Composable
fun CreatePrivateChannelScreen(viewModel: MeshViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    ChannelForm(
        viewModel = viewModel,
        onBack = onBack,
        onDone = onDone,
        icon = Icons.Filled.Lock,
        title = "Create private channel",
        subtitle = "Enter a channel name.",
        buttonLabel = "Create channel",
        footnote = "Once created you can share the channel so that others can join it.",
    ) { name, _, done ->
        viewModel.createPrivateChannel(name, done)
    }
}

/** Joins a channel somebody else created, by typing the key they shared. */
@Composable
fun JoinPrivateChannelScreen(viewModel: MeshViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    ChannelForm(
        viewModel = viewModel,
        onBack = onBack,
        onDone = onDone,
        icon = Icons.Filled.Lock,
        title = "Join private channel",
        subtitle = "Enter the existing channel details.",
        buttonLabel = "Join channel",
        footnote = "The channel name can be anything. Only the secret key has to match.",
        secretLabel = "Secret key (hex)",
    ) { name, secret, done ->
        viewModel.joinPrivateChannel(name, secret, done)
    }
}

/** The one channel whose key ships in the firmware; everyone is already on it. */
@Composable
fun JoinPublicChannelScreen(viewModel: MeshViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    ChannelForm(
        viewModel = viewModel,
        onBack = onBack,
        onDone = onDone,
        icon = Icons.Filled.Public,
        title = "Join public channel",
        subtitle = "Enter a channel name.",
        buttonLabel = "Join channel",
        footnote = "The public channel uses a static secret key. Anyone can join this channel.",
        initialName = ChannelCrypto.PUBLIC_CHANNEL_NAME,
    ) { name, _, done ->
        viewModel.joinPublicChannel(name, done)
    }
}

/** The key is `sha256(name)`, so the name alone decides who ends up together. */
@Composable
fun JoinHashtagChannelScreen(viewModel: MeshViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    ChannelForm(
        viewModel = viewModel,
        onBack = onBack,
        onDone = onDone,
        icon = Icons.Filled.Tag,
        title = "Join hashtag channel",
        subtitle = "Enter a channel name. For example: #meshcore",
        buttonLabel = "Join channel",
        footnote = "Hashtag channels are public. Anyone can join by entering the same name. " +
            "Only a-z, 0-9 and dashes are allowed.",
        initialName = "#",
    ) { name, _, done ->
        viewModel.joinHashtagChannel(name, done)
    }
}

/**
 * All four forms are the same page: an icon, a name, sometimes a key, and a button.
 * [onSubmit] gets the name and the key text and reports back null or an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelForm(
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonLabel: String,
    footnote: String,
    initialName: String = "",
    secretLabel: String? = null,
    onSubmit: (name: String, secret: String, onResult: (String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var secret by remember { mutableStateOf("") }
    val isBusy by viewModel.isAddingChannel.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val canSubmit = name.isNotBlank() &&
        (secretLabel == null || secret.isNotBlank()) &&
        !isBusy

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
                title = { Text("Add channel") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.size(72.dp))
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            }

            Spacer(Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.size(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Channel name") },
                singleLine = true,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            )

            if (secretLabel != null) {
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(secretLabel) },
                    singleLine = true,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.size(16.dp))
            Button(
                onClick = {
                    onSubmit(name, secret) { error ->
                        if (error == null) onDone()
                        else scope.launch { snackbar.showSnackbar(error) }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(buttonLabel)
            }

            Spacer(Modifier.size(16.dp))
            Text(
                text = footnote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(24.dp))
        }
    }
}

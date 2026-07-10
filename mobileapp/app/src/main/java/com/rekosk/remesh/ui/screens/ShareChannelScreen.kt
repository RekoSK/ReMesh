package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.encodeQr
import kotlinx.coroutines.launch

/**
 * A channel as a QR code plus its raw key. Both carry the same secret: the code is
 * for someone standing next to you, the hex for someone who is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareChannelScreen(viewModel: MeshViewModel, channelIndex: Int, onBack: () -> Unit) {
    // Re-derived when the channel list changes, so deleting the channel elsewhere
    // does not leave a stale key on screen.
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val channel = remember(channels, channelIndex) { viewModel.channelByIndex(channelIndex) }
    val uri = remember(channels, channelIndex) { viewModel.channelShareUri(channelIndex) }
    val secretHex = remember(channels, channelIndex) { viewModel.channelSecretHex(channelIndex) }
    val qr = remember(uri) { uri?.let { encodeQr(it) } }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                title = { Text("Share channel") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (channel == null || qr == null || secretHex == null) {
                Text(
                    text = "Connect to a node to share this channel.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }

            // A QR code has to stay high-contrast regardless of the app theme,
            // so this square keeps its own white background in dark mode.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(12.dp),
            ) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Channel QR code",
                    modifier = Modifier.size(280.dp),
                )
            }

            Spacer(Modifier.size(16.dp))
            Text(channel.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Scan this QR code to add the channel.\n" +
                    "Menu → Add channel → Scan QR code",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.size(24.dp))
            SecretKeyField(
                secretHex = secretHex,
                onCopy = {
                    copyToClipboard(context, secretHex)
                    scope.launch { snackbar.showSnackbar("Secret key copied") }
                },
            )

            Spacer(Modifier.size(16.dp))
            Text(
                text = "Anyone with the secret key can send and receive messages on this channel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SecretKeyField(secretHex: String, onCopy: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Secret key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = secretHex, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy secret key")
            }
        }
    }
}

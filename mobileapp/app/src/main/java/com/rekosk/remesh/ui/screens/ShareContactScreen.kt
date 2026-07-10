package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.encodeQr

/** Renders the node's `meshcore://contact/add` link as a scannable QR code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareContactScreen(
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selfInfo by viewModel.selfInfo.collectAsStateWithLifecycle()
    val uri = remember(selfInfo) { viewModel.selfContactUri() }
    val qr = remember(uri) { uri?.let { encodeQr(it) } }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Share contact") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val info = selfInfo
            if (info == null || qr == null) {
                Text(
                    text = "Connect to a node to share its contact.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = info.name.take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "<${shortKeyOf(info.publicKey)}>",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // A QR code has to stay high-contrast regardless of the app theme,
            // so this square keeps its own white background in dark mode.
            Box(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(12.dp),
            ) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Contact QR code",
                    modifier = Modifier.size(280.dp),
                )
            }

            Text(
                text = "Scan this QR code to add this contact.\nMenu → Add contact → Scan QR code",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            )
        }
    }
}

private fun shortKeyOf(key: ByteArray): String {
    val hex = key.joinToString("") { "%02x".format(it) }
    return if (hex.length <= 16) hex else "${hex.take(8)}...${hex.takeLast(8)}"
}

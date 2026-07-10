package com.rekosk.remesh.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The four ways to end up in a channel, plus the QR shortcut. What separates them
 * is only where the 16-byte secret comes from: invented, typed, well-known, or
 * derived from the name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChannelScreen(
    onBack: () -> Unit,
    onCreatePrivate: () -> Unit,
    onJoinPrivate: () -> Unit,
    onJoinPublic: () -> Unit,
    onJoinHashtag: () -> Unit,
    onScanQr: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.size(8.dp))
            ChannelInfoCard(
                "Channels allow secure group conversations across the whole mesh. They are " +
                    "protected by a secret encryption key. You must know the key to send and " +
                    "receive messages on a channel.",
            )

            OptionRow(
                icon = Icons.Filled.Add,
                title = "Create private channel",
                subtitle = "Protected by a secret key.",
                onClick = onCreatePrivate,
            )
            OptionRow(
                icon = Icons.Filled.Lock,
                title = "Join private channel",
                subtitle = "Enter the secret key manually.",
                onClick = onJoinPrivate,
            )
            OptionRow(
                icon = Icons.Filled.Public,
                title = "Join public channel",
                subtitle = "Anyone can join this channel.",
                onClick = onJoinPublic,
            )
            OptionRow(
                icon = Icons.Filled.Tag,
                title = "Join hashtag channel",
                subtitle = "Anyone can join a hashtag channel.",
                onClick = onJoinHashtag,
            )
            OptionRow(
                icon = Icons.Filled.PhotoCamera,
                title = "Scan QR code",
                subtitle = null,
                onClick = onScanQr,
            )
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun ChannelInfoCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

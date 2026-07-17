package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.ChannelKind
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.ui.components.NodeAvatar
import com.rekosk.remesh.ui.theme.NodeColors

/**
 * The chat composer's "+" menu: ways to put shareable things (contact cards, channel
 * links, coordinates) into the message textbox. Items grey out while the data they
 * need is not available (node never connected, nothing to share).
 */
@Composable
internal fun ChatShareMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    myContactInfoEnabled: Boolean,
    myLocationEnabled: Boolean,
    shareChannelEnabled: Boolean,
    shareContactEnabled: Boolean,
    onMyContactInfo: () -> Unit,
    onMyLocation: () -> Unit,
    onShareChannel: () -> Unit,
    onShareContact: () -> Unit,
    onShareLocationFromMap: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("My contact info") },
            leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null) },
            enabled = myContactInfoEnabled,
            onClick = onMyContactInfo,
        )
        DropdownMenuItem(
            text = { Text("My location") },
            leadingIcon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
            enabled = myLocationEnabled,
            onClick = onMyLocation,
        )
        DropdownMenuItem(
            text = { Text("Share channel") },
            leadingIcon = { Icon(Icons.Filled.Tag, contentDescription = null) },
            enabled = shareChannelEnabled,
            onClick = onShareChannel,
        )
        DropdownMenuItem(
            text = { Text("Share contact") },
            leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
            enabled = shareContactEnabled,
            onClick = onShareContact,
        )
        DropdownMenuItem(
            text = { Text("Share location from map") },
            leadingIcon = { Icon(Icons.Outlined.Map, contentDescription = null) },
            onClick = onShareLocationFromMap,
        )
    }
}

/** Full-screen "Select channel" picker; picking hands the channel back to the chat. */
@Composable
internal fun ChannelPickerDialog(
    channels: List<Channel>,
    onDismiss: () -> Unit,
    onPick: (Channel) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(channels, query) {
        channels
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }
    FullScreenPickerDialog(title = "Select channel", query = query, onQueryChange = { query = it }, onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { channel ->
                PickerRow(
                    title = channel.name,
                    subtitle = channel.kind.pickerLabel(),
                    onClick = { onPick(channel) },
                ) {
                    // Same tonal kind-glyph disc as the channel list, so the two match.
                    val tint = channel.kind.pickerTint()
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = channel.kind.pickerIcon(),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Full-screen "Select contact" picker; picking hands the contact back to the chat. */
@Composable
internal fun ContactPickerDialog(
    contacts: List<Contact>,
    publicKeyHexFor: (String) -> String?,
    onDismiss: () -> Unit,
    onPick: (Contact) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(contacts, query) {
        contacts
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }
    FullScreenPickerDialog(title = "Select contact", query = query, onQueryChange = { query = it }, onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { contact ->
                val keyHex = remember(contact.id) { publicKeyHexFor(contact.id) }
                PickerRow(
                    title = contact.name,
                    subtitle = keyHex?.let { "<${shortenKeyHex(it)}>" } ?: "",
                    onClick = { onPick(contact) },
                ) {
                    NodeAvatar(
                        type = contact.type,
                        isBlocked = contact.isBlocked,
                        isFavorite = contact.isFavorite,
                        size = 44,
                        name = contact.name,
                        colorSeed = contact.id,
                    )
                }
            }
        }
    }
}

/** Shared scaffold for the share pickers: X-to-close top bar, search box, then the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenPickerDialog(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    },
                    title = { Text(title) },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                )
                content()
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "7f39b1d0...b7fb2365" — enough of a 64-hex identity key to recognise it by. */
private fun shortenKeyHex(hex: String): String =
    if (hex.length <= 16) hex else "${hex.take(8)}...${hex.takeLast(8)}"

private fun ChannelKind.pickerIcon() = when (this) {
    ChannelKind.PUBLIC -> Icons.Filled.Public
    ChannelKind.PRIVATE -> Icons.Filled.Lock
    ChannelKind.HASHTAG -> Icons.Filled.Tag
}

private fun ChannelKind.pickerLabel() = when (this) {
    ChannelKind.PUBLIC -> "Public channel"
    ChannelKind.PRIVATE -> "Private channel"
    ChannelKind.HASHTAG -> "Hashtag channel"
}

private fun ChannelKind.pickerTint(): Color = when (this) {
    ChannelKind.PUBLIC -> NodeColors.Public
    ChannelKind.PRIVATE -> NodeColors.Chat
    ChannelKind.HASHTAG -> NodeColors.Chat
}

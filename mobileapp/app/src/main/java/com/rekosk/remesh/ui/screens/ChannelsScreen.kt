package com.rekosk.remesh.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.ChannelKind
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.MeshTopBarActions
import com.rekosk.remesh.ui.components.OverflowNav
import com.rekosk.remesh.ui.theme.NodeColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelsScreen(
    viewModel: MeshViewModel,
    onChannelClick: (Channel) -> Unit,
    onOpenConnect: () -> Unit,
    onAddChannel: () -> Unit,
    onShareChannel: (Channel) -> Unit,
    overflow: OverflowNav,
    modifier: Modifier = Modifier,
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isConnected by viewModel.isRadioConnected.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()

    var pendingDelete by remember { mutableStateOf<Channel?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    pendingDelete?.let { channel ->
        DeleteChannelDialog(
            channel = channel,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                viewModel.deleteChannel(channel.index) { error ->
                    error?.let { scope.launch { snackbar.showSnackbar(it) } }
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("Channels") },
                    actions = {
                        MeshTopBarActions(
                            isConnected = isConnected,
                            onOpenConnect = onOpenConnect,
                            onAdvert = viewModel::sendAdvert,
                            selfContactUri = viewModel::selfContactUri,
                            overflow = overflow,
                        )
                    },
                )
                AnimatedVisibility(
                    visible = isSyncing,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddChannel) {
                Icon(Icons.Filled.Add, contentDescription = "Add channel")
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = viewModel::sync,
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = isSyncing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            // Always a LazyColumn, even when empty, so the pull gesture still works.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (channels.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No channels on this node",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(channels, key = { it.id }) { channel ->
                        ChannelCard(
                            channel = channel,
                            onClick = { onChannelClick(channel) },
                            onShare = { onShareChannel(channel) },
                            onDelete = { pendingDelete = channel },
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * A long press opens the row's actions, anchored to the row the way the reference
 * app anchors them to its overflow button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // The menu is a sibling of the card inside this Box, so it anchors to the row.
        ChannelActionsMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onShare = {
                menuOpen = false
                onShare()
            },
            onDelete = {
                menuOpen = false
                onDelete()
            },
        )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(channel.kind.tint().copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = channel.kind.icon(),
                    contentDescription = channel.kind.label(),
                    tint = channel.kind.tint(),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = channel.kind.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
}

@Composable
private fun ChannelActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            onClick = onShare,
        )
        DropdownMenuItem(
            text = { Text("Delete channel") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = onDelete,
        )
    }
}

/**
 * Deleting frees the node's slot. A private channel is gone for good unless its key
 * was written down, which is worth saying out loud before it happens.
 */
@Composable
private fun DeleteChannelDialog(channel: Channel, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${channel.name}?") },
        text = {
            Text(
                text = if (channel.kind == ChannelKind.PRIVATE) {
                    "This frees the channel slot on your node and forgets its messages. " +
                        "You cannot rejoin without the secret key, so share it first if you " +
                        "want to keep the channel."
                } else {
                    "This frees the channel slot on your node and forgets its messages. " +
                        "You can rejoin it at any time."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun ChannelKind.icon() = when (this) {
    ChannelKind.PUBLIC -> Icons.Filled.Public
    ChannelKind.PRIVATE -> Icons.Filled.Lock
    ChannelKind.HASHTAG -> Icons.Filled.Tag
}

private fun ChannelKind.label() = when (this) {
    ChannelKind.PUBLIC -> "Public channel"
    ChannelKind.PRIVATE -> "Private channel"
    ChannelKind.HASHTAG -> "Hashtag channel"
}

private fun ChannelKind.tint(): Color = when (this) {
    ChannelKind.PUBLIC -> NodeColors.Public
    ChannelKind.PRIVATE -> NodeColors.Chat
    ChannelKind.HASHTAG -> NodeColors.Chat
}

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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.ChannelKind
import com.rekosk.remesh.data.model.ConversationSummary
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.MeshTopBarActions
import com.rekosk.remesh.ui.components.NodeAvatar
import com.rekosk.remesh.ui.components.OverflowNav
import com.rekosk.remesh.ui.components.color
import com.rekosk.remesh.ui.components.icon
import com.rekosk.remesh.ui.theme.NodeColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelsScreen(
    viewModel: MeshViewModel,
    onOpenConversation: (String) -> Unit,
    onAddChannel: () -> Unit,
    onShareChannel: (Int) -> Unit,
    overflow: OverflowNav,
    modifier: Modifier = Modifier,
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
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
                if (conversations.isEmpty()) {
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
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onClick = { onOpenConversation(conversation.id) },
                            onShare = {
                                channels.firstOrNull { it.id == conversation.id }
                                    ?.let { onShareChannel(it.index) }
                            },
                            onDelete = {
                                pendingDelete = channels.firstOrNull { it.id == conversation.id }
                            },
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * A channel or a direct-message thread. Long-pressing a channel opens its actions;
 * a DM has none. The right edge shows the unread count, or nothing when caught up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    conversation: ConversationSummary,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (conversation.isChannel) {
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
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (conversation.isChannel) menuOpen = true },
                ),
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
                ConversationIcon(conversation)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = conversation.subtitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (conversation.unreadCount > 0) {
                    Badge { Text("${conversation.unreadCount}") }
                }
            }
        }
    }
}

@Composable
private fun ConversationIcon(conversation: ConversationSummary) {
    // A direct message uses the same companion avatar as the contacts list — the
    // node's first letter/emoji over its per-node colour — so the two screens match.
    if (!conversation.isChannel) {
        NodeAvatar(
            type = conversation.contactType ?: NodeType.CHAT,
            isBlocked = false,
            size = 44,
            name = conversation.title,
            colorSeed = conversation.id,
        )
        return
    }
    // Channels keep their kind glyph (public / private / hashtag) in the tonal circle.
    val tint = conversation.channelKind?.tint() ?: NodeColors.Chat
    val icon: ImageVector = conversation.channelKind?.icon() ?: Icons.Filled.Public
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun ConversationSummary.subtitle(): String = when {
    !isChannel -> "Direct message"
    else -> channelKind?.label() ?: "Channel"
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

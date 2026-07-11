package com.rekosk.remesh.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.Route
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.MeshTopBarActions
import com.rekosk.remesh.ui.components.OverflowNav
import com.rekosk.remesh.ui.components.NodeAvatar
import com.rekosk.remesh.ui.components.formatLastSeen
import com.rekosk.remesh.ui.components.label

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContactsScreen(
    viewModel: MeshViewModel,
    onContactClick: (Contact) -> Unit,
    overflow: OverflowNav,
    modifier: Modifier = Modifier,
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val total by viewModel.totalContactCount.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()

    // Long-press multi-select: while any row is selected the top bar becomes a
    // contextual "Selected: N" bar with a batch-delete action.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selectionMode = selected.isNotEmpty()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = selectionMode) { selected = emptySet() }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                if (selectionMode) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { selected = emptySet() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                            }
                        },
                        title = { Text("Selected: ${selected.size}") },
                        actions = {
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Contacts", style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    text = "$total contacts",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            MeshTopBarActions(
                                onAdvert = viewModel::sendAdvert,
                                selfContactUri = viewModel::selfContactUri,
                                overflow = overflow,
                            )
                        },
                    )
                }
                // The "snake" bar covers syncs the pull gesture didn't start:
                // the post-connect handshake, and drains triggered by MSG_WAITING.
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!selectionMode) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search contacts...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = isSyncing,
                onRefresh = viewModel::sync,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
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
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    if (contacts.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (query.isBlank()) "No contacts discovered yet"
                                    else "No contacts match \"$query\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(contacts, key = { it.id }) { contact ->
                            ContactRow(
                                contact = contact,
                                selected = contact.id in selected,
                                onClick = {
                                    if (selectionMode) {
                                        selected = if (contact.id in selected) {
                                            selected - contact.id
                                        } else {
                                            selected + contact.id
                                        }
                                    } else {
                                        onContactClick(contact)
                                    }
                                },
                                onLongClick = { selected = selected + contact.id },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete $count ${if (count == 1) "contact" else "contacts"}?") },
            text = { Text("This removes them from your node. Their messages are forgotten too.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.removeContacts(selected) {}
                    selected = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    contact: Contact,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NodeAvatar(
            type = contact.type,
            isBlocked = contact.isBlocked,
            isFavorite = contact.isFavorite,
            name = contact.name,
            colorSeed = contact.id,
        )
        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (contact.isBlocked) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = "Blocked",
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = contact.subtitle(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (contact.type == NodeType.GROUP) {
                if (contact.unreadCount > 0) Badge { Text("${contact.unreadCount}") }
            } else {
                Text(
                    text = formatLastSeen(contact.lastSeenEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (contact.hasLocation) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = "Has known position",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** "Chat • Flood" / "Repeater • 4 hops" / for groups, the member list. */
private fun Contact.subtitle(): String {
    memberSummary?.let { return it }
    val routeText = when (val r = route) {
        is Route.Flood -> "Flood"
        is Route.Hops -> "${r.count} ${if (r.count == 1) "hop" else "hops"}"
    }
    return "${type.label()} • $routeText"
}

package com.rekosk.remesh.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    onOpenConnect: () -> Unit,
    overflow: OverflowNav,
    modifier: Modifier = Modifier,
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val total by viewModel.totalContactCount.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isConnected by viewModel.isRadioConnected.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
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
                            isConnected = isConnected,
                            onOpenConnect = onOpenConnect,
                            onAdvert = viewModel::sendAdvert,
                            selfContactUri = viewModel::selfContactUri,
                            overflow = overflow,
                        )
                    },
                )
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
                            ContactRow(contact = contact, onClick = { onContactClick(contact) })
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NodeAvatar(type = contact.type, isBlocked = contact.isBlocked)
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

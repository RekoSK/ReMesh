package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.model.RecentAdvert
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.NodeAvatar
import com.rekosk.remesh.ui.components.formatMessageTime

/**
 * The nodes whose adverts this node heard most recently, newest first.
 *
 * The firmware keeps only the last 16 in a circular table, so this is a window on
 * what is on the air right now rather than a full contact list. Read-only: it asks
 * the node about each contact it already knows and transmits nothing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DiscoverContactsScreen(
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    onOpenNode: (RecentAdvert) -> Unit,
) {
    val adverts by viewModel.recentAdverts.collectAsStateWithLifecycle()
    val isLoading by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val isConnected by viewModel.isRadioConnected.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()
    // True only while a refresh the user started by swiping is in flight, so the
    // circular spinner is reserved for that and background refreshes show the bar.
    var swipeRefresh by remember { mutableStateOf(false) }

    LaunchedEffect(isConnected) {
        if (isConnected) viewModel.refreshRecentAdverts()
    }
    LaunchedEffect(isLoading) {
        if (!isLoading) swipeRefresh = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("Discover", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Recent node adverts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = {
                swipeRefresh = true
                viewModel.refreshRecentAdverts()
            },
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            indicator = {
                // The wavy "snake" bar shows for any active refresh — including the
                // first, automatic one, where it is the *only* indicator.
                if (isLoading) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    )
                }
                // The circular spinner is added only for a manual swipe (shown on top
                // of the bar), and while idle it doubles as the pull-to-refresh affordance.
                if (swipeRefresh || !isLoading) {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullState,
                        isRefreshing = swipeRefresh && isLoading,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            },
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (adverts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isConnected) {
                                    "No adverts heard yet."
                                } else {
                                    "Connect to a node to see recent adverts."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(adverts, key = { it.publicKey.contentHashCode() }) {
                        AdvertRow(it, onClick = { onOpenNode(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvertRow(advert: RecentAdvert, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NodeAvatar(
            type = advert.type,
            isBlocked = false,
            name = advert.name,
            colorSeed = advert.publicKey.joinToString("") { "%02x".format(it) },
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(advert.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = formatShortKey(advert.publicKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatMessageTime(advert.receivedEpochMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = hopLabel(advert),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Direct", "1 hop", "5 hops" -- an advert that arrived unrepeated came straight to us. */
internal fun hopLabel(advert: RecentAdvert): String = when {
    advert.isDirect -> "Direct"
    advert.hops == 1 -> "1 hop"
    else -> "${advert.hops} hops"
}

internal fun formatShortKey(key: ByteArray): String {
    val hex = key.joinToString("") { "%02x".format(it) }
    return "<${hex.take(8)}...${hex.takeLast(8)}>"
}

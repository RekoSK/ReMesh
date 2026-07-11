package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.ResolvedRepeat
import com.rekosk.remesh.data.ResolvedRoute
import com.rekosk.remesh.data.model.RepeaterContactRef
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.SignalBars
import com.rekosk.remesh.ui.components.avatarColor
import com.rekosk.remesh.ui.components.avatarGlyph

/**
 * "Show message routes": every route an incoming message reached us by. Each row is
 * one overheard copy, headlined by the repeater that handed it to us. Tapping a row
 * opens the full hop-by-hop path in [MessageRouteScreen].
 *
 * The data is reconstructed from overheard raw packets while connected, so a message
 * received while the app was away simply has no routes -- hence the empty state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageRoutesScreen(
    viewModel: MeshViewModel,
    conversationId: String,
    messageId: String,
    onOpenRoute: (routeIndex: Int) -> Unit,
    onBack: () -> Unit,
) {
    // Recomputed as messages change: a route can land while this screen is open.
    val messages by viewModel.messagesFor(conversationId).collectAsStateWithLifecycle()
    val routes = remember(messages, messageId) {
        viewModel.messageRoutes(conversationId, messageId)
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
                title = { Text(messageRoutesTitle(routes.size)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (routes.isEmpty()) {
                RouteEmptyState()
            } else {
                RouteInfoBanner("Your companion received this message over the following routes.")
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(routes) { index, route ->
                        RouteSummaryRow(route = route, onClick = { onOpenRoute(index) })
                    }
                }
            }
        }
    }
}

/**
 * The hop-by-hop path of one route: the sender, each repeater in turn, then us.
 * Tapping a repeater opens its contact detail when it is a saved contact, offers a
 * picker when the hash matches several, and does nothing when it matches none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageRouteScreen(
    viewModel: MeshViewModel,
    conversationId: String,
    messageId: String,
    routeIndex: Int,
    onOpenContact: (contactId: String) -> Unit,
    onBack: () -> Unit,
) {
    val messages by viewModel.messagesFor(conversationId).collectAsStateWithLifecycle()
    val selfName by viewModel.selfName.collectAsStateWithLifecycle()
    val route = remember(messages, messageId, routeIndex) {
        viewModel.messageRoutes(conversationId, messageId).getOrNull(routeIndex)
    }
    val senderName = remember(messages, messageId) {
        messages.firstOrNull { it.id == messageId }?.author ?: "Unknown"
    }

    // When a hash resolves to several contacts, let the user pick which one to open.
    var picker by remember { mutableStateOf<List<RepeaterContactRef>?>(null) }
    picker?.let { options ->
        RepeaterPickerDialog(
            options = options,
            onPick = { picker = null; onOpenContact(it.contactId) },
            onDismiss = { picker = null },
        )
    }

    val onHopTap: (ResolvedRepeat) -> Unit = { hop ->
        val matches = viewModel.repeaterContactsForHash(hop.hashHex)
        when {
            matches.isEmpty() -> Unit
            matches.size == 1 -> onOpenContact(matches.first().contactId)
            else -> picker = matches
        }
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
                        Text("Show route", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = hopsLabel(route?.hopCount ?: 0),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item { EndpointRow(name = senderName, subtitle = "Message sent") }
            itemsIndexed(route?.hops.orEmpty()) { index, hop ->
                HopRow(
                    hop = hop,
                    hopNumber = index + 1,
                    onClick = if (viewModel.repeaterContactsForHash(hop.hashHex).isNotEmpty()) {
                        { onHopTap(hop) }
                    } else {
                        null
                    },
                )
            }
            item { EndpointRow(name = selfName ?: "You", subtitle = "You received the message") }
        }
    }
}

/* --------------------------------- pieces --------------------------------- */

/** "Heard once", "Heard 3 times" -- the routes screen's title. */
private fun messageRoutesTitle(count: Int): String = when (count) {
    0 -> "No routes"
    1 -> "Heard once"
    else -> "Heard $count times"
}

private fun hopsLabel(hops: Int): String = when (hops) {
    0 -> "Direct"
    1 -> "1 hop"
    else -> "$hops hops"
}

@Composable
private fun RouteInfoBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun RouteEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AltRoute,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.size(20.dp))
            Text(
                text = "No route information",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "There are no records of how this message was received. The app has to be " +
                    "connected to the companion as the message arrives.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RouteSummaryRow(route: ResolvedRoute, onClick: () -> Unit) {
    val hop = route.finalHop
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hop != null) HashAvatar(hop.hashHex) else EndpointAvatar("~")
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(hop?.title ?: "Direct", style = MaterialTheme.typography.titleMedium)
            Text(
                text = hop?.subtitle ?: "Heard directly",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = hopsLabel(route.hopCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SignalBars(route.snr)
            Spacer(Modifier.size(4.dp))
            Text(
                text = formatSnr(route.snr),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EndpointRow(name: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EndpointAvatar(name)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HopRow(hop: ResolvedRepeat, hopNumber: Int, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HashAvatar(hop.hashHex)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(hop.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Hop $hopNumber · Message repeated",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = hop.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The sender/self endpoints use the companion avatar scheme: letter or emoji glyph. */
@Composable
private fun EndpointAvatar(name: String) {
    val color = avatarColor(name, isSystemInDarkTheme())
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(avatarGlyph(name), color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** A repeater hop shows its raw hash prefix, as the reference app does. */
@Composable
private fun HashAvatar(hashHex: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(hashHex, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RepeaterPickerDialog(
    options: List<RepeaterContactRef>,
    onPick: (RepeaterContactRef) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which repeater?") },
        text = {
            Column {
                Text(
                    text = "Several known repeaters share this hash. Open which one?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EndpointAvatar(option.name)
                        Spacer(Modifier.width(16.dp))
                        Text(option.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

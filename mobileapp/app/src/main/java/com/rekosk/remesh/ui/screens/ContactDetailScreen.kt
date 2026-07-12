package com.rekosk.remesh.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.ContactExtras
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.Route
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.PingResult
import com.rekosk.remesh.ui.components.MenuCard
import com.rekosk.remesh.ui.components.MenuRowDivider
import com.rekosk.remesh.ui.components.NodeAvatar
import com.rekosk.remesh.ui.components.formatLastSeen
import com.rekosk.remesh.ui.components.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The detail screen for a contact, styled like the app's other menus (grouped cards
 * on the OLED-black surface). It serves both a repeater -- where the tools are ping,
 * share and delete -- and a chat contact opened from its conversation, where "Ping"
 * is replaced by "Send message".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    viewModel: MeshViewModel,
    contactId: String,
    onBack: () -> Unit,
    onEditRoute: () -> Unit,
    onEditLocation: () -> Unit,
    onSendMessage: () -> Unit,
    unknownName: String? = null,
    unknownPublicKeyHex: String? = null,
    unknownAdvType: Int = 0,
) {
    val contact by viewModel.contactFlow(contactId).collectAsStateWithLifecycle()
    val isConnected by viewModel.isRadioConnected.collectAsStateWithLifecycle()
    val pendingLocations by viewModel.pendingLocations.collectAsStateWithLifecycle()
    val extras = remember(contact, pendingLocations) { viewModel.contactExtras(contactId) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var inboundPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(contactId, isConnected) {
        if (isConnected) viewModel.inboundPath(contactId) { inboundPath = it }
    }

    var showRename by remember { mutableStateOf(false) }
    var showPing by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    fun toast(message: String?) {
        message?.let { scope.launch { snackbar.showSnackbar(it) } }
    }

    // Identity of a discovered node that is not (yet) a contact, passed through the nav
    // args. Present only when this screen was opened from a Discover list for a node the
    // node's own contact table does not hold.
    val unknownNodeType = when (unknownAdvType) {
        2 -> NodeType.REPEATER
        3 -> NodeType.ROOM
        4 -> NodeType.SENSOR
        else -> NodeType.CHAT
    }
    val unknownHex4 = unknownPublicKeyHex?.take(4)?.uppercase().orEmpty()
    val unknownAddType = if (unknownAdvType in 1..4) unknownAdvType else 1
    val discovered = remember(unknownPublicKeyHex) {
        unknownPublicKeyHex?.let { viewModel.discoveredNode(it) }
    }
    val unknownDisplayName = (discovered?.name ?: unknownName)?.ifBlank { null }

    fun addUnknownContact(name: String) {
        val hex = unknownPublicKeyHex ?: return
        viewModel.addContact(name, unknownAddType, hex) { error -> toast(error ?: "Added $name") }
    }

    val current = contact
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
                title = { Text("Contact") },
            )
        },
    ) { padding ->
        if (current == null) {
            if (unknownPublicKeyHex == null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(padding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Contact not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Scaffold
            }
            // Unknown node opened from Discover: identity + whatever the advert/scan already
            // told us, and an "Add to contacts" action in place of the Delete row. Once added,
            // contactFlow emits the real contact and the full menu below takes over.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                UnknownNodeHeader(unknownNodeType, unknownDisplayName, unknownPublicKeyHex)

                MenuCard {
                    InfoRow("Public key", unknownPublicKeyHex, monospace = true, trailing = {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(unknownPublicKeyHex))
                            toast("Public key copied")
                        }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
                    })
                    MenuRowDivider()
                    InfoRow("Contact type", unknownNodeType.label())
                }

                if (discovered != null &&
                    (discovered.hops != null || discovered.inboundSnr != null ||
                        discovered.lastHeardEpochMs != null)
                ) {
                    SectionHeader("Signal")
                    MenuCard {
                        var needDivider = false
                        discovered.hops?.let {
                            InfoRow(
                                "Hop count",
                                if (discovered.isDirect == true) "Direct"
                                else "$it ${if (it == 1) "hop" else "hops"}",
                            )
                            needDivider = true
                        }
                        discovered.inboundSnr?.let { inb ->
                            if (needDivider) MenuRowDivider()
                            InfoRow(
                                "Signal (in / out)",
                                "%.1f / %.1f dB".format(inb, discovered.outboundSnr ?: 0f),
                            )
                            needDivider = true
                        }
                        discovered.lastHeardEpochMs?.let {
                            if (needDivider) MenuRowDivider()
                            InfoRow("Last heard", formatLastSeen(it))
                        }
                    }
                }

                SectionHeader("Other tools")
                MenuCard {
                    ToolRow(
                        Icons.Filled.PersonAdd,
                        "Add to contacts",
                        enabled = isConnected,
                        onClick = {
                            if (unknownNodeType == NodeType.CHAT) {
                                addUnknownContact(unknownDisplayName ?: "Node $unknownHex4")
                            } else {
                                showAdd = true
                            }
                        },
                    )
                }
                Spacer(Modifier.size(16.dp))
            }
            return@Scaffold
        }
        val isChat = current.type == NodeType.CHAT

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Header(current, extras)

            MenuCard {
                InfoRow("Name", current.name, trailing = {
                    IconButton(onClick = { showRename = true }, enabled = isConnected) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename")
                    }
                })
                extras?.let { ex ->
                    MenuRowDivider()
                    InfoRow("Public key", ex.publicKeyHex, monospace = true, trailing = {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(ex.publicKeyHex))
                            toast("Public key copied")
                        }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
                    })
                }
                MenuRowDivider()
                InfoRow("Contact type", current.type.label())
                extras?.lastAdvertEpochMs?.let {
                    MenuRowDivider()
                    InfoRow("Last captured advert", formatLastSeen(it))
                }
            }

            SectionHeader("Location")
            MenuCard {
                val lat = extras?.latitude
                val lon = extras?.longitude
                val positionText = if (lat != null && lon != null) {
                    "%.4f, %.4f".format(lat, lon)
                } else {
                    "Unknown"
                }
                InfoRow("Position", positionText, trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (contactId in pendingLocations) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = "Queued — will sync when the node connects",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = onEditLocation) {
                            Icon(Icons.Filled.Map, contentDescription = "Set location on map")
                        }
                    }
                })
                MenuRowDivider()
                InfoRow(
                    "Distance",
                    extras?.distanceKm?.let { "%.2f km / %.2f mi".format(it, it * 0.621371) } ?: "Unknown",
                )
            }

            SectionHeader("Route")
            MenuCard {
                InfoRow("Hop count", routeLabel(current.route), trailing = {
                    IconButton(
                        onClick = { viewModel.resetContactRoute(contactId) { toast(it) } },
                        enabled = isConnected,
                    ) { Icon(Icons.Filled.Close, contentDescription = "Reset route") }
                })
                MenuRowDivider()
                InfoRow("Outgoing route", outgoingRouteLabel(extras), trailing = {
                    IconButton(onClick = onEditRoute, enabled = isConnected) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit route")
                    }
                })
                MenuRowDivider()
                InfoRow("Last inbound path", inboundPath ?: if (isConnected) "Unknown" else "—")
            }

            SectionHeader("Other tools")
            MenuCard {
                ToolRow(Icons.Filled.Star, "Favourite", onClick = {
                    viewModel.setFavorite(contactId, !current.isFavorite)
                }, trailing = {
                    Checkbox(
                        checked = current.isFavorite,
                        onCheckedChange = { viewModel.setFavorite(contactId, it) },
                    )
                })
                MenuRowDivider()
                if (isChat) {
                    ToolRow(Icons.AutoMirrored.Filled.Chat, "Send message", onClick = onSendMessage)
                } else {
                    ToolRow(
                        Icons.Filled.Bolt,
                        "Ping (Zero hop)",
                        enabled = isConnected,
                        onClick = { showPing = true },
                    )
                }
                MenuRowDivider()
                ToolRow(Icons.Filled.Share, "Share", onClick = {
                    val uri = viewModel.contactShareUri(contactId)
                    if (uri == null) {
                        toast("Nothing to share yet")
                    } else {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, uri)
                                },
                                "Share contact",
                            ),
                        )
                    }
                })
                MenuRowDivider()
                ToolRow(
                    Icons.Filled.Delete,
                    "Delete contact",
                    enabled = isConnected,
                    onClick = { showDelete = true },
                )
            }
            Spacer(Modifier.size(16.dp))
        }
    }

    if (showRename) {
        TextEntryDialog(
            title = "Rename contact",
            initial = current?.name.orEmpty(),
            label = "Name",
            onDismiss = { showRename = false },
            onConfirm = { name ->
                showRename = false
                viewModel.renameContact(contactId, name) { toast(it) }
            },
        )
    }

    if (showPing) {
        PingDialog(
            onDismiss = { showPing = false },
            startPing = { onResult -> viewModel.pingZeroHop(contactId, onResult) },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete ${current?.name ?: "contact"}?") },
            text = { Text("This removes the contact from your node. Its messages are forgotten too.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.removeContact(contactId) { error ->
                        if (error == null) onBack() else toast(error)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }

    if (showAdd) {
        TextEntryDialog(
            title = "Add to contacts",
            initial = "",
            label = "Custom name (optional)",
            confirmLabel = "Add",
            onDismiss = { showAdd = false },
            onConfirm = { entered ->
                showAdd = false
                val fallback = when (unknownNodeType) {
                    NodeType.ROOM -> "Room "
                    NodeType.SENSOR -> "Sensor "
                    else -> "Repeater "
                } + unknownHex4
                addUnknownContact(entered.ifBlank { fallback })
            },
        )
    }
}

@Composable
private fun UnknownNodeHeader(type: NodeType, name: String?, publicKeyHex: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NodeAvatar(
            type = type,
            isBlocked = false,
            size = 96,
            name = name,
            colorSeed = publicKeyHex,
        )
        Spacer(Modifier.size(12.dp))
        Text(name ?: "Unknown node", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "<${publicKeyHex.take(8)}...${publicKeyHex.takeLast(8)}>",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(contact: Contact, extras: ContactExtras?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NodeAvatar(
            type = contact.type,
            isBlocked = contact.isBlocked,
            isFavorite = contact.isFavorite,
            size = 96,
            name = contact.name,
            colorSeed = contact.id,
        )
        Spacer(Modifier.size(12.dp))
        Text(contact.name, style = MaterialTheme.typography.headlineSmall)
        extras?.let {
            Text(
                text = "<${it.publicKeyHex.take(8)}...${it.publicKeyHex.takeLast(8)}>",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        trailing?.invoke()
    }
}

private fun routeLabel(route: Route): String = when (route) {
    is Route.Flood -> "Flood"
    is Route.Hops -> if (route.count == 0) "Direct" else "${route.count} hops"
}

private fun outgoingRouteLabel(extras: ContactExtras?): String = when (val hex = extras?.outPathHex) {
    null -> "Not set (floods)"
    "" -> "Direct (zero hop)"
    else -> hex.chunked(2).joinToString(" ").uppercase()
}

@Composable
private fun TextEntryDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    confirmLabel: String = "Save",
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Pinging..." with a snake bar that fills over the timeout window. When the result
 * arrives (or the bar reaches the end) it replaces the bar with the outcome. Back
 * always closes and never waits for the ping to finish.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PingDialog(onDismiss: () -> Unit, startPing: ((PingResult) -> Unit) -> Unit) {
    var result by remember { mutableStateOf<PingResult?>(null) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) { startPing { result = it } }
    LaunchedEffect(Unit) {
        val steps = 100
        // Matches MeshRepository.PING_TIMEOUT_MS: a direct neighbour replies in well
        // under a second, so a 5s window is enough without a long "no response" wait.
        val totalMs = 5_000L
        for (i in 1..steps) {
            if (result != null) break
            progress = i.toFloat() / steps
            delay(totalMs / steps)
        }
        if (result == null) result = PingResult(false, "No response (timed out)")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ping (Zero hop)") },
        text = {
            val outcome = result
            if (outcome == null) {
                Column {
                    Text("Pinging...")
                    Spacer(Modifier.size(16.dp))
                    LinearWavyProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text((if (outcome.success) "Success\n" else "Failed\n") + outcome.message)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

package com.rekosk.remesh.ui.screens

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ble.ChannelCrypto
import com.rekosk.remesh.ble.MeshCoreProtocol
import com.rekosk.remesh.data.MessagePrefs
import com.rekosk.remesh.data.model.DeliveryState
import androidx.compose.foundation.clickable
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.MeshViewModel
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import com.rekosk.remesh.ui.components.MentionOutlineAmber
import com.rekosk.remesh.ui.components.MentionOutlineDark
import com.rekosk.remesh.ui.components.MentionText
import com.rekosk.remesh.ui.components.MentionVisualTransformation
import com.rekosk.remesh.ui.components.NodeAvatar
import com.rekosk.remesh.ui.components.avatarColor
import com.rekosk.remesh.ui.components.avatarGlyph
import com.rekosk.remesh.ui.components.findActiveMention
import com.rekosk.remesh.ui.components.formatMessageTime
import com.rekosk.remesh.ui.components.insertMention
import com.rekosk.remesh.ui.components.mentionsName
import com.rekosk.remesh.ui.components.normalizeMentionTyping
import com.rekosk.remesh.ui.components.rememberWavePhase
import com.rekosk.remesh.ui.components.wavyRoundRectPath

/**
 * Stable per-author colour for sender names and their message avatars, from the shared
 * companion palette ([avatarColor]). [seed] is the author's contact id (public key) when
 * they are a saved contact, else their name — so a known sender's chat pfp colour matches
 * their avatar in the contacts and chat lists exactly.
 */
@Composable
private fun authorColor(seed: String): Color =
    avatarColor(seed, isSystemInDarkTheme())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MeshViewModel,
    conversationId: String,
    onBack: () -> Unit,
    onOpenHeardRepeats: (messageId: String) -> Unit,
    onShowMessageRoutes: (messageId: String) -> Unit = {},
    onOpenContact: () -> Unit = {},
    onPickLocationFromMap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val messages by viewModel.messagesFor(conversationId).collectAsStateWithLifecycle()
    val (title, subtitle) = remember(conversationId) { viewModel.conversationTitle(conversationId) }
    val isChannel = remember(conversationId) { viewModel.isChannel(conversationId) }
    // For a DM, the other node -- drives the top-bar avatar.
    val dmContact by remember(conversationId) { viewModel.contactFlow(conversationId) }
        .collectAsStateWithLifecycle()
    // Null until DataStore answers; the compiled-in defaults show everything.
    val prefs = viewModel.messagePrefs.collectAsStateWithLifecycle().value ?: MessagePrefs()
    val selfName by viewModel.selfName.collectAsStateWithLifecycle()
    // Map a message author's display name to its contact id (public key) so a sender's
    // chat avatar colour matches their avatar in the contacts/chat lists exactly. Unknown
    // authors (not a saved contact) fall back to seeding on the name.
    val contacts by viewModel.allContacts.collectAsStateWithLifecycle()
    val seedByName = remember(contacts) { contacts.associate { it.name to it.id } }
    val colorSeedFor: (String) -> String = { seedByName[it] ?: it }
    // A name's "PFP colour", used for @-mention chips and the dropdown avatars.
    val dark = isSystemInDarkTheme()
    val colorForName: (String) -> Color = { avatarColor(colorSeedFor(it), dark) }
    // Everyone who has posted in this chat (not me), for the @-mention dropdown.
    val participants = remember(messages, selfName) {
        messages.map { it.author }
            .filter { it.isNotBlank() && !it.equals(selfName, ignoreCase = true) }
            .distinct()
    }
    // Firmware byte cap on what actually goes out: a DM sends the text alone
    // (MAX_TEXT_LEN); a channel sends "myName: text" capped at MAX_GROUP_TEXT_LEN,
    // so my node name eats into the room's budget.
    val maxMessageBytes = remember(isChannel, selfName) {
        if (isChannel) {
            val prefix = "${selfName.orEmpty()}: ".toByteArray(Charsets.UTF_8).size
            (ChannelCrypto.MAX_GROUP_TEXT_LEN - prefix).coerceAtLeast(0)
        } else {
            MeshCoreProtocol.MAX_TEXT_LEN
        }
    }

    var draft by remember { mutableStateOf(TextFieldValue()) }
    var selected by remember { mutableStateOf<MeshMessage?>(null) }
    var confirmDelete by remember { mutableStateOf<MeshMessage?>(null) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }

    // The composer's "+" share menu and the two full-screen pickers it opens.
    var shareMenuOpen by remember { mutableStateOf(false) }
    var showChannelPicker by remember { mutableStateOf(false) }
    var showContactPicker by remember { mutableStateOf(false) }
    val selfInfo by viewModel.selfInfo.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    // Refreshed when SELF_INFO changes; the offline fallback position rarely moves under us.
    val selfLocationText = remember(selfInfo) { viewModel.selfLocationText() }
    fun insertIntoDraft(text: String) {
        draft = insertAtCursor(draft, text)
    }

    // Text handed back by the map share picker (it lives on another nav destination,
    // so it can't touch the draft directly).
    val composerInsert by viewModel.composerInsert.collectAsStateWithLifecycle()
    LaunchedEffect(composerInsert) {
        composerInsert?.let { text ->
            insertIntoDraft(text)
            viewModel.consumeComposerInsert()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        // Opening the chat, and any message arriving while it is open, counts as read.
        viewModel.markRead(conversationId)
    }

    if (showChannelPicker) {
        ChannelPickerDialog(
            channels = channels,
            onDismiss = { showChannelPicker = false },
            onPick = { channel ->
                showChannelPicker = false
                viewModel.channelShareUri(channel.index)?.let(::insertIntoDraft)
            },
        )
    }
    if (showContactPicker) {
        ContactPickerDialog(
            contacts = contacts,
            publicKeyHexFor = viewModel::contactPublicKeyHex,
            onDismiss = { showContactPicker = false },
            onPick = { contact ->
                showContactPicker = false
                viewModel.contactCard(contact.id)?.let(::insertIntoDraft)
            },
        )
    }

    selected?.let { message ->
        MessageActionsSheet(
            message = message,
            onDismiss = { selected = null },
            onReply = {
                // MeshCore has no reply metadata on the wire; a mention is the whole
                // convention, and it is what the reference app inserts too.
                val mention = "@[${message.author}] "
                draft = TextFieldValue(mention, TextRange(mention.length))
                selected = null
                inputFocus.requestFocus()
            },
            onHeardRepeats = {
                selected = null
                onOpenHeardRepeats(message.id)
            },
            onShowMessageRoutes = {
                selected = null
                onShowMessageRoutes(message.id)
            },
            onDelete = {
                confirmDelete = message
                selected = null
            },
        )
    }

    confirmDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete message?") },
            text = {
                Text(
                    if (message.deliveryState == DeliveryState.QUEUED) {
                        "This queued message hasn't been sent yet — deleting it cancels it."
                    } else {
                        "This removes the message from this device."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessage(conversationId, message.id)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }

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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // Tapping a DM's avatar or name opens the contact detail screen.
                        modifier = Modifier.clickable(enabled = !isChannel, onClick = onOpenContact),
                    ) {
                        if (isChannel) {
                            Icon(
                                imageVector = when {
                                    title.startsWith("#") -> Icons.Filled.Tag
                                    title == "Public" -> Icons.Filled.Public
                                    else -> Icons.Filled.Lock
                                },
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                        } else {
                            NodeAvatar(
                                type = dmContact?.type ?: NodeType.CHAT,
                                isBlocked = dmContact?.isBlocked ?: false,
                                isFavorite = dmContact?.isFavorite ?: false,
                                size = 36,
                                name = dmContact?.name ?: title,
                                colorSeed = dmContact?.id,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            MessageInput(
                value = draft,
                onValueChange = { nv ->
                    // Apply the space-confirm / backspace-revert tagging rules on every edit.
                    val n = normalizeMentionTyping(nv.text, nv.selection.start)
                    draft = if (n.text == nv.text) nv else TextFieldValue(n.text, TextRange(n.cursor))
                },
                focusRequester = inputFocus,
                maxBytes = maxMessageBytes,
                participants = participants,
                colorForName = colorForName,
                leadingIcon = {
                    Box {
                        IconButton(onClick = { shareMenuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.AddCircleOutline,
                                contentDescription = "Share into the chat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ChatShareMenu(
                            expanded = shareMenuOpen,
                            onDismiss = { shareMenuOpen = false },
                            myContactInfoEnabled = selfInfo != null,
                            myLocationEnabled = selfLocationText != null,
                            shareChannelEnabled = channels.isNotEmpty(),
                            shareContactEnabled = contacts.isNotEmpty(),
                            onMyContactInfo = {
                                shareMenuOpen = false
                                viewModel.selfContactCard()?.let(::insertIntoDraft)
                            },
                            onMyLocation = {
                                shareMenuOpen = false
                                selfLocationText?.let(::insertIntoDraft)
                            },
                            onShareChannel = {
                                shareMenuOpen = false
                                showChannelPicker = true
                            },
                            onShareContact = {
                                shareMenuOpen = false
                                showContactPicker = true
                            },
                            onShareLocationFromMap = {
                                shareMenuOpen = false
                                onPickLocationFromMap()
                            },
                        )
                    }
                },
                onSend = {
                    viewModel.send(conversationId, draft.text)
                    draft = TextFieldValue()
                },
            )
        },
    ) { innerPadding ->
        if (messages.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        showHops = prefs.showChannelMessageHops,
                        showHashSize = prefs.showChannelPathHashSizes,
                        colorSeed = colorSeedFor(message.author),
                        selfName = selfName,
                        colorForName = colorForName,
                        onLongPress = { selected = message },
                    )
                }
            }
        }
    }
}

/** Splices [text] into the draft at the cursor, replacing any selection. */
private fun insertAtCursor(draft: TextFieldValue, text: String): TextFieldValue {
    val start = draft.selection.min.coerceIn(0, draft.text.length)
    val end = draft.selection.max.coerceIn(0, draft.text.length)
    val newText = draft.text.replaceRange(start, end, text)
    return TextFieldValue(newText, TextRange(start + text.length))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: MeshMessage,
    showHops: Boolean,
    showHashSize: Boolean,
    colorSeed: String,
    selfName: String?,
    colorForName: (String) -> Color,
    onLongPress: () -> Unit,
) {
    val outgoing = message.isOutgoing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (!outgoing) {
                AuthorAvatar(author = message.author, colorSeed = colorSeed)
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }

            Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
                if (!outgoing) {
                    Text(
                        text = message.author,
                        style = MaterialTheme.typography.titleSmall,
                        color = authorColor(colorSeed),
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Bubble(message, selfName = selfName, colorForName = colorForName)
                Spacer(Modifier.size(4.dp))
                MessageFooter(message, showHops = showHops, showHashSize = showHashSize)
            }
        }
    }
}

@Composable
private fun AuthorAvatar(author: String, colorSeed: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(authorColor(colorSeed).copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarGlyph(author),
            style = MaterialTheme.typography.titleSmall,
            color = authorColor(colorSeed),
        )
    }
}

@Composable
private fun Bubble(message: MeshMessage, selfName: String?, colorForName: (String) -> Color) {
    val outgoing = message.isOutgoing
    // An incoming bubble that tags me gets the animated "cloudy" amber/dark outline.
    val taggedMe = !outgoing && mentionsName(message.text, selfName)
    val phase = rememberWavePhase(taggedMe)
    Surface(
        color = if (outgoing) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = if (outgoing) 18.dp else 4.dp,
            bottomEnd = if (outgoing) 4.dp else 18.dp,
        ),
        modifier = Modifier
            .widthIn(max = 300.dp)
            .then(
                if (!taggedMe) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val path = wavyRoundRectPath(
                        w = size.width,
                        h = size.height,
                        cornerRadius = 16.dp.toPx(),
                        amplitude = 2.5.dp.toPx(),
                        waveLength = 12.dp.toPx(),
                        phase = phase,
                    )
                    drawPath(path, MentionOutlineDark, style = Stroke(width = 4.dp.toPx()))
                    drawPath(path, MentionOutlineAmber, style = Stroke(width = 2.dp.toPx()))
                },
            ),
    ) {
        MentionText(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            colorForName = colorForName,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * Under an incoming bubble: "20:30 - 7 Hops - 2-byte". Under one of ours: the
 * delivery ticks. One means the node put it on the air, two that a repeater was
 * overheard passing it on.
 */
@Composable
private fun MessageFooter(message: MeshMessage, showHops: Boolean, showHashSize: Boolean) {
    if (message.isOutgoing) {
        DeliveryTicks(message.deliveryState, message.heardRepeats.size)
        return
    }
    Text(
        text = incomingFooterText(message, showHops, showHashSize),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The two flags come from the message settings screen; the time is never hidden. */
internal fun incomingFooterText(
    message: MeshMessage,
    showHops: Boolean = true,
    showHashSize: Boolean = true,
): String = buildList {
    add(formatMessageTime(message.timestampEpochMs))
    if (showHops) {
        if (message.isDirectRoute) {
            add("Direct")
        } else if (message.hopCount > 0) {
            add("${message.hopCount} ${if (message.hopCount == 1) "Hop" else "Hops"}")
        }
    }
    if (showHashSize) message.pathHashSizeBytes?.let { add("$it-byte") }
}.joinToString(" • ")

@Composable
private fun DeliveryTicks(state: DeliveryState, repeaterCount: Int) {
    if (state == DeliveryState.PENDING) return
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    // Queued while offline: a clock until the node connects and it is transmitted.
    if (state == DeliveryState.QUEUED) {
        Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = "Queued — will send when the node connects",
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = if (state == DeliveryState.CONFIRMED) {
                "Heard being repeated"
            } else {
                "Sent"
            },
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        if (state == DeliveryState.CONFIRMED) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = tint,
                // Overlapped, the way a double tick is normally drawn.
                modifier = Modifier
                    .size(14.dp)
                    .offset(x = (-6).dp),
            )
            // "✅✅ • 3": how many distinct repeaters were overheard forwarding it.
            if (repeaterCount > 0) {
                Text(
                    text = "• $repeaterCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    maxBytes: Int,
    participants: List<String>,
    colorForName: (String) -> Color,
    leadingIcon: (@Composable () -> Unit)? = null,
    onSend: () -> Unit,
) {
    // The firmware truncates by UTF-8 bytes, so count bytes, not characters.
    val usedBytes = value.text.toByteArray(Charsets.UTF_8).size
    val over = usedBytes > maxBytes
    // The @-token being typed, and the participants that match it.
    val active = findActiveMention(value.text, value.selection.start)
    val suggestions = if (active == null) emptyList() else {
        participants.filter { it.contains(active.second, ignoreCase = true) }.take(6)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (active != null && suggestions.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Column {
                        suggestions.forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val (nt, nc) = insertMention(
                                            value.text, active.first, value.selection.start, name,
                                        )
                                        onValueChange(TextFieldValue(nt, TextRange(nc)))
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(colorForName(name).copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = avatarGlyph(name),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorForName(name),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text("Type a message...") },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 4,
                    isError = over,
                    leadingIcon = leadingIcon,
                    visualTransformation = MentionVisualTransformation(colorForName),
                )
                // Sending an over-length message would silently lose the tail, so block it.
                IconButton(onClick = onSend, enabled = value.text.isNotBlank() && !over) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (value.text.isNotBlank() && !over) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Character (byte) budget, shown once you start typing; turns red if exceeded.
            if (value.text.isNotEmpty()) {
                Text(
                    text = "$usedBytes/$maxBytes",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (over) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 12.dp, top = 2.dp),
                )
            }
        }
    }
}

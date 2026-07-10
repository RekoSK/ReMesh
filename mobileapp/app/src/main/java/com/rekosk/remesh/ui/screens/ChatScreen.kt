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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.rekosk.remesh.data.MessagePrefs
import com.rekosk.remesh.data.model.DeliveryState
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.formatMessageTime

/** Stable per-author colour for sender names, as the reference app does. */
private val authorPalette = listOf(
    Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFBA68C8), Color(0xFF4DD0E1), Color(0xFFF06292),
)

private fun authorColor(author: String): Color =
    authorPalette[(author.hashCode().mod(authorPalette.size))]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MeshViewModel,
    conversationId: String,
    onBack: () -> Unit,
    onOpenHeardRepeats: (messageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages by viewModel.messagesFor(conversationId).collectAsStateWithLifecycle()
    val (title, subtitle) = remember(conversationId) { viewModel.conversationTitle(conversationId) }
    val isChannel = remember(conversationId) { viewModel.isChannel(conversationId) }
    // Null until DataStore answers; the compiled-in defaults show everything.
    val prefs = viewModel.messagePrefs.collectAsStateWithLifecycle().value ?: MessagePrefs()

    var draft by remember { mutableStateOf(TextFieldValue()) }
    var selected by remember { mutableStateOf<MeshMessage?>(null) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    selected?.let { message ->
        MessageActionsSheet(
            message = message,
            onDismiss = { selected = null },
            onReply = {
                // MeshCore has no reply metadata on the wire; a mention is the whole
                // convention, and it is what the reference app inserts too.
                val mention = "@${message.author} "
                draft = TextFieldValue(mention, TextRange(mention.length))
                selected = null
                inputFocus.requestFocus()
            },
            onHeardRepeats = {
                selected = null
                onOpenHeardRepeats(message.id)
            },
            onDelete = {
                viewModel.deleteMessage(conversationId, message.id)
                selected = null
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                onValueChange = { draft = it },
                focusRequester = inputFocus,
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
                        onLongPress = { selected = message },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: MeshMessage,
    showHops: Boolean,
    showHashSize: Boolean,
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
                AuthorAvatar(message.author)
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }

            Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
                if (!outgoing) {
                    Text(
                        text = message.author,
                        style = MaterialTheme.typography.titleSmall,
                        color = authorColor(message.author),
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Bubble(message)
                Spacer(Modifier.size(4.dp))
                MessageFooter(message, showHops = showHops, showHashSize = showHashSize)
            }
        }
    }
}

@Composable
private fun AuthorAvatar(author: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(authorColor(author).copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = author.take(1).uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = authorColor(author),
        )
    }
}

@Composable
private fun Bubble(message: MeshMessage) {
    val outgoing = message.isOutgoing
    Surface(
        color = if (outgoing) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = if (outgoing) 18.dp else 4.dp,
            bottomEnd = if (outgoing) 4.dp else 18.dp,
        ),
        modifier = Modifier.widthIn(max = 300.dp),
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
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
        DeliveryTicks(message.deliveryState)
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
private fun DeliveryTicks(state: DeliveryState) {
    if (state == DeliveryState.PENDING) return
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            )
            IconButton(onClick = onSend, enabled = value.text.isNotBlank()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (value.text.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

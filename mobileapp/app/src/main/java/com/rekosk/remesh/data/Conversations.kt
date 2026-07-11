package com.rekosk.remesh.data

import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.ConversationSummary
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.data.model.NodeType

/**
 * The messaging list shown on the Channels screen: every channel, followed by any
 * direct-message thread that has messages, ordered by most recent activity. Pure so
 * it can be unit tested without a live repository.
 */
fun buildConversations(
    channels: List<Channel>,
    messages: Map<String, List<MeshMessage>>,
    readMarks: Map<String, Long>,
    contacts: List<Contact>,
): List<ConversationSummary> {
    val channelRows = channels.map { channel ->
        ConversationSummary(
            id = channel.id,
            title = channel.name,
            isChannel = true,
            channelKind = channel.kind,
            unreadCount = unreadCount(messages[channel.id], readMarks[channel.id]),
            lastActivityEpochMs = messages[channel.id]?.maxOfOrNull { it.timestampEpochMs } ?: 0L,
        )
    }
    val contactsById = contacts.associateBy { it.id }
    val dmRows = messages.entries
        .filter { it.key.startsWith(MeshRepository.CONTACT_PREFIX) && it.value.isNotEmpty() }
        .map { (id, list) ->
            val contact = contactsById[id]
            ConversationSummary(
                id = id,
                title = contact?.name ?: id.removePrefix(MeshRepository.CONTACT_PREFIX).take(8),
                isChannel = false,
                contactType = contact?.type ?: NodeType.CHAT,
                unreadCount = unreadCount(list, readMarks[id]),
                lastActivityEpochMs = list.maxOfOrNull { it.timestampEpochMs } ?: 0L,
            )
        }
        .sortedByDescending { it.lastActivityEpochMs }
    return channelRows + dmRows
}

/**
 * Incoming messages that arrived after the conversation was last read. Our own
 * messages never count, and a never-read conversation counts everything.
 */
fun unreadCount(messages: List<MeshMessage>?, readMark: Long?): Int {
    val since = readMark ?: 0L
    return messages.orEmpty().count {
        !it.isOutgoing && (it.receivedEpochMs ?: it.timestampEpochMs) > since
    }
}

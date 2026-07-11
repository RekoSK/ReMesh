package com.rekosk.remesh.data

import com.rekosk.remesh.data.model.Channel
import com.rekosk.remesh.data.model.ChannelKind
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.MeshMessage
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsTest {

    private fun incoming(text: String, at: Long) = MeshMessage(
        id = "in-$at", author = "Them", text = text, timestampEpochMs = at,
        isOutgoing = false, receivedEpochMs = at,
    )

    private fun outgoing(text: String, at: Long) = MeshMessage(
        id = "out-$at", author = "Me", text = text, timestampEpochMs = at, isOutgoing = true,
    )

    @Test
    fun `unread counts only incoming messages newer than the read mark`() {
        val messages = listOf(
            incoming("a", 100),
            incoming("b", 200),
            outgoing("mine", 300), // ours never counts
            incoming("c", 400),
        )
        // Read at 200: only the messages after it, and only incoming ones.
        assertEquals(1, unreadCount(messages, 200))
        // Never read: every incoming message is unread.
        assertEquals(3, unreadCount(messages, null))
        // Read past the newest: nothing unread.
        assertEquals(0, unreadCount(messages, 500))
    }

    @Test
    fun `an outgoing-only thread has no unread messages`() {
        val messages = listOf(outgoing("hello", 10), outgoing("there", 20))
        assertEquals(0, unreadCount(messages, null))
    }

    @Test
    fun `the list is channels first, then dm threads by recent activity`() {
        val channels = listOf(
            Channel(id = "ch:0", index = 0, name = "Public", kind = ChannelKind.PUBLIC),
        )
        val dmOld = MeshRepository.CONTACT_PREFIX + "aa"
        val dmNew = MeshRepository.CONTACT_PREFIX + "bb"
        val messages = mapOf(
            "ch:0" to listOf(incoming("chan", 50)),
            dmOld to listOf(incoming("old", 100)),
            dmNew to listOf(incoming("new", 900)),
        )
        val contacts = listOf(
            Contact(dmNew, "Bob", NodeType.CHAT, Route.Flood, 900),
        )

        val list = buildConversations(channels, messages, readMarks = emptyMap(), contacts = contacts)

        assertEquals(listOf("ch:0", dmNew, dmOld), list.map { it.id })
        assertTrue(list[0].isChannel)
        assertFalse(list[1].isChannel)
        // A DM with a known contact takes its name; an unknown one falls back to the hash.
        assertEquals("Bob", list[1].title)
        assertEquals("aa", list[2].title)
    }

    @Test
    fun `a channel with no messages still appears, a dm with none does not`() {
        val channels = listOf(Channel("ch:1", 1, "Empty", ChannelKind.PRIVATE))
        val messages = mapOf(MeshRepository.CONTACT_PREFIX + "cc" to emptyList<MeshMessage>())

        val list = buildConversations(channels, messages, emptyMap(), emptyList())

        assertEquals(1, list.size)
        assertEquals("ch:1", list.single().id)
    }

    @Test
    fun `unread badge count surfaces on the conversation row`() {
        val channels = listOf(Channel("ch:0", 0, "Public", ChannelKind.PUBLIC))
        val messages = mapOf("ch:0" to listOf(incoming("a", 100), incoming("b", 200)))

        val read = buildConversations(channels, messages, mapOf("ch:0" to 100L), emptyList())
        assertEquals(1, read.single().unreadCount)

        val unread = buildConversations(channels, messages, emptyMap(), emptyList())
        assertEquals(2, unread.single().unreadCount)
    }
}

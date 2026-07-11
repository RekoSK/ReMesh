package com.rekosk.remesh.ui.components

import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.data.model.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class PathInputTest {

    private fun contact(id: String, type: NodeType) =
        Contact(id = id, name = id, type = type, route = Route.Flood, lastSeenEpochMs = 0)

    @Test
    fun `appendHash joins with a comma and never leaves a stray one`() {
        assertEquals("aa", appendHash("", "aa"))
        assertEquals("aa,bb", appendHash("aa", "bb"))
        assertEquals("aa,bb", appendHash("aa,", "bb"))
        assertEquals("aa,bb", appendHash("aa, ", "bb"))
        assertEquals("aa,bb,cc", appendHash("aa,bb", "cc"))
    }

    @Test
    fun `hashPrefix takes the chosen number of bytes from the contact id`() {
        val c = contact("c:292c019bdda9", NodeType.REPEATER)
        assertEquals("29", c.hashPrefix(1))
        assertEquals("292c", c.hashPrefix(2))
        assertEquals("292c01", c.hashPrefix(3))
    }

    @Test
    fun `the hop ceiling shrinks as the per-hop hash grows`() {
        // MAX_PATH_SIZE is 64 bytes, so hops = 64 / hash size -- not a flat 64.
        assertEquals("1-byte (max 64 hops)", pathSizeLabel(1))
        assertEquals("2-byte (max 32 hops)", pathSizeLabel(2))
        assertEquals("3-byte (max 21 hops)", pathSizeLabel(3))
    }

    @Test
    fun `repeatersOnly keeps only repeater contacts`() {
        val list = listOf(
            contact("c:01", NodeType.CHAT),
            contact("c:02", NodeType.REPEATER),
            contact("c:03", NodeType.SENSOR),
            contact("c:04", NodeType.REPEATER),
        )
        assertEquals(listOf("c:02", "c:04"), list.repeatersOnly().map { it.id })
    }
}

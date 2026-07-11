package com.rekosk.remesh.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire formats for the contact-detail commands, pinned against `MyMesh.cpp`. */
class ContactCommandsTest {

    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `reset path is opcode 13 followed by the whole key`() {
        val frame = MeshCoreProtocol.encodeResetPath(key)
        assertEquals(33, frame.size)
        assertEquals(MeshCoreProtocol.Cmd.RESET_PATH.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 33))
    }

    @Test
    fun `remove contact is opcode 15 followed by the whole key`() {
        val frame = MeshCoreProtocol.encodeRemoveContact(key)
        assertEquals(33, frame.size)
        assertEquals(MeshCoreProtocol.Cmd.REMOVE_CONTACT.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 33))
    }

    @Test
    fun `both reject a key that is not 32 bytes`() {
        assertTrue(runCatching { MeshCoreProtocol.encodeResetPath(ByteArray(6)) }.isFailure)
        assertTrue(runCatching { MeshCoreProtocol.encodeRemoveContact(ByteArray(31)) }.isFailure)
    }
}

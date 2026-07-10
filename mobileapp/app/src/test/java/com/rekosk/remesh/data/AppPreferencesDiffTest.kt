package com.rekosk.remesh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The discard dialog lists exactly what `changesFrom` returns, so an empty list
 * must mean "nothing queued" -- otherwise the dialog appears with no content, or
 * worse, a real edit is silently dropped on back.
 */
class AppPreferencesDiffTest {

    @Test
    fun `an untouched group reports no changes`() {
        assertTrue(MessagePrefs().changesFrom(MessagePrefs()).isEmpty())
        assertTrue(NotificationPrefs().changesFrom(NotificationPrefs()).isEmpty())
        assertTrue(ContactAppPrefs().changesFrom(ContactAppPrefs()).isEmpty())
        assertTrue(ExperimentalPrefs().changesFrom(ExperimentalPrefs()).isEmpty())
    }

    @Test
    fun `a single toggle reports one change with both states`() {
        val original = MessagePrefs()
        val edited = original.copy(autoRetry = false)
        assertEquals(listOf("Auto retry: on → off"), edited.changesFrom(original))
    }

    @Test
    fun `turning a setting back on reads the other way round`() {
        val original = MessagePrefs(autoFocusMessageField = false)
        val edited = original.copy(autoFocusMessageField = true)
        assertEquals(listOf("Auto focus message field: off → on"), edited.changesFrom(original))
    }

    @Test
    fun `every changed field is listed`() {
        val original = MessagePrefs()
        val edited = original.copy(
            autoRetry = false,
            keepScreenOn = false,
            saveDrafts = false,
        )
        assertEquals(3, edited.changesFrom(original).size)
    }

    @Test
    fun `toggling a value and back again queues nothing`() {
        val original = NotificationPrefs()
        val edited = original.copy(contactMessages = false).copy(contactMessages = true)
        assertTrue(edited.changesFrom(original).isEmpty())
    }

    @Test
    fun `notification changes carry their labels`() {
        val original = NotificationPrefs()
        val edited = original.copy(contactsFull = false, newContact = false)
        assertEquals(
            listOf("New contact discovered: on → off", "Contacts full: on → off"),
            edited.changesFrom(original),
        )
    }

    @Test
    fun `contact app prefs diff independently of the node settings`() {
        val original = ContactAppPrefs()
        assertEquals(
            listOf("Show public keys: off → on"),
            original.copy(showPublicKeys = true).changesFrom(original),
        )
    }

    @Test
    fun `experimental prefs diff`() {
        val original = ExperimentalPrefs()
        val edited = original.copy(companionClockForCli = true)
        assertEquals(listOf("Use companion clock for CLI: off → on"), edited.changesFrom(original))
    }
}

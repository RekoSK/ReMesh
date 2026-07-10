package com.rekosk.remesh.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("remesh_settings")

/** "Auto retry: on → off", the way the discard dialog lists a pending change. */
private fun toggle(label: String, from: Boolean, to: Boolean): String? =
    if (from == to) null else "$label: ${onOff(from)} → ${onOff(to)}"

private fun onOff(value: Boolean) = if (value) "on" else "off"

/**
 * Settings that live in the app rather than on the node. Each screen edits a copy
 * of one of these groups and writes it back only when the user confirms.
 *
 * Several describe behaviour the app does not implement yet; those are marked.
 */
data class MessagePrefs(
    /** Not yet wired: the app does not retry failed sends. */
    val autoRetry: Boolean = true,
    /** Not yet wired: depends on autoRetry. */
    val autoResetPath: Boolean = true,
    /** Not yet wired into ChatScreen. */
    val keepScreenOn: Boolean = true,
    /** Not yet wired into ChatScreen. */
    val autoFocusMessageField: Boolean = false,
    /** Not yet wired: needs per-conversation unread tracking. */
    val jumpToOldestUnread: Boolean = false,
    /** Not yet wired: the app has no ack-path tracking. */
    val markDeliveryFaster: Boolean = true,
    /** Not yet wired into ChatScreen. */
    val saveDrafts: Boolean = true,
    /** Hides the "6 Hops" part of a received message's footer. */
    val showChannelMessageHops: Boolean = true,
    /** Hides the "2-byte" part of a received message's footer. */
    val showChannelPathHashSizes: Boolean = true,
) {
    fun changesFrom(original: MessagePrefs): List<String> = listOfNotNull(
        toggle("Auto retry", original.autoRetry, autoRetry),
        toggle("Auto reset path", original.autoResetPath, autoResetPath),
        toggle("Keep screen on", original.keepScreenOn, keepScreenOn),
        toggle("Auto focus message field", original.autoFocusMessageField, autoFocusMessageField),
        toggle("Jump to oldest message", original.jumpToOldestUnread, jumpToOldestUnread),
        toggle("Mark delivery faster", original.markDeliveryFaster, markDeliveryFaster),
        toggle("Save message drafts", original.saveDrafts, saveDrafts),
        toggle("Show channel message hops", original.showChannelMessageHops, showChannelMessageHops),
        toggle(
            "Show channel message path hash sizes",
            original.showChannelPathHashSizes,
            showChannelPathHashSizes,
        ),
    )
}

/** Read by MeshConnectionService, which posts the notifications these describe. */
data class NotificationPrefs(
    val contactMessages: Boolean = true,
    val channelMessages: Boolean = true,
    val roomMessages: Boolean = true,
    val missedWhileOffline: Boolean = true,
    val newContact: Boolean = true,
    val contactsFull: Boolean = true,
) {
    fun changesFrom(original: NotificationPrefs): List<String> = listOfNotNull(
        toggle("Contact messages", original.contactMessages, contactMessages),
        toggle("Channel messages", original.channelMessages, channelMessages),
        toggle("Room server messages", original.roomMessages, roomMessages),
        toggle("New messages while disconnected", original.missedWhileOffline, missedWhileOffline),
        toggle("New contact discovered", original.newContact, newContact),
        toggle("Contacts full", original.contactsFull, contactsFull),
    )
}

data class ContactAppPrefs(
    /** Not yet wired into ContactsScreen. */
    val pullToRefresh: Boolean = true,
    /** Not yet wired into ContactsScreen. */
    val showPublicKeys: Boolean = false,
) {
    fun changesFrom(original: ContactAppPrefs): List<String> = listOfNotNull(
        toggle("Pull to refresh", original.pullToRefresh, pullToRefresh),
        toggle("Show public keys", original.showPublicKeys, showPublicKeys),
    )
}

data class ExperimentalPrefs(
    /** Not yet wired. */
    val fasterChannelSyncing: Boolean = false,
    /** Not yet wired: DM timestamps come from the phone clock. */
    val companionClockForDms: Boolean = false,
    /** Not yet wired: no CLI screen exists. */
    val companionClockForCli: Boolean = false,
) {
    fun changesFrom(original: ExperimentalPrefs): List<String> = listOfNotNull(
        toggle("Faster channel syncing", original.fasterChannelSyncing, fasterChannelSyncing),
        toggle("Use companion clock for DMs", original.companionClockForDms, companionClockForDms),
        toggle("Use companion clock for CLI", original.companionClockForCli, companionClockForCli),
    )
}

class AppPreferences(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val autoRetry = booleanPreferencesKey("auto_retry")
        val autoResetPath = booleanPreferencesKey("auto_reset_path")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val autoFocusMessageField = booleanPreferencesKey("auto_focus_message")
        val jumpToOldestUnread = booleanPreferencesKey("jump_to_oldest")
        val markDeliveryFaster = booleanPreferencesKey("mark_delivery_faster")
        val saveDrafts = booleanPreferencesKey("save_drafts")
        val showChannelMessageHops = booleanPreferencesKey("show_channel_hops")
        val showChannelPathHashSizes = booleanPreferencesKey("show_path_hash_sizes")

        val notifyContact = booleanPreferencesKey("notify_contact_msgs")
        val notifyChannel = booleanPreferencesKey("notify_channel_msgs")
        val notifyRoom = booleanPreferencesKey("notify_room_msgs")
        val notifyMissed = booleanPreferencesKey("notify_missed")
        val notifyNewContact = booleanPreferencesKey("notify_new_contact")
        val notifyContactsFull = booleanPreferencesKey("notify_contacts_full")

        val pullToRefresh = booleanPreferencesKey("pull_to_refresh")
        val showPublicKeys = booleanPreferencesKey("show_public_keys")

        val fasterChannelSyncing = booleanPreferencesKey("faster_channel_sync")
        val companionClockForDms = booleanPreferencesKey("companion_clock_dms")
        val companionClockForCli = booleanPreferencesKey("companion_clock_cli")
    }

    val messages: Flow<MessagePrefs> = store.data.map { p ->
        val d = MessagePrefs()
        MessagePrefs(
            autoRetry = p[Keys.autoRetry] ?: d.autoRetry,
            autoResetPath = p[Keys.autoResetPath] ?: d.autoResetPath,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            autoFocusMessageField = p[Keys.autoFocusMessageField] ?: d.autoFocusMessageField,
            jumpToOldestUnread = p[Keys.jumpToOldestUnread] ?: d.jumpToOldestUnread,
            markDeliveryFaster = p[Keys.markDeliveryFaster] ?: d.markDeliveryFaster,
            saveDrafts = p[Keys.saveDrafts] ?: d.saveDrafts,
            showChannelMessageHops = p[Keys.showChannelMessageHops] ?: d.showChannelMessageHops,
            showChannelPathHashSizes = p[Keys.showChannelPathHashSizes] ?: d.showChannelPathHashSizes,
        )
    }

    suspend fun save(prefs: MessagePrefs) {
        store.edit { p ->
            p[Keys.autoRetry] = prefs.autoRetry
            p[Keys.autoResetPath] = prefs.autoResetPath
            p[Keys.keepScreenOn] = prefs.keepScreenOn
            p[Keys.autoFocusMessageField] = prefs.autoFocusMessageField
            p[Keys.jumpToOldestUnread] = prefs.jumpToOldestUnread
            p[Keys.markDeliveryFaster] = prefs.markDeliveryFaster
            p[Keys.saveDrafts] = prefs.saveDrafts
            p[Keys.showChannelMessageHops] = prefs.showChannelMessageHops
            p[Keys.showChannelPathHashSizes] = prefs.showChannelPathHashSizes
        }
    }

    val notifications: Flow<NotificationPrefs> = store.data.map { p ->
        val d = NotificationPrefs()
        NotificationPrefs(
            contactMessages = p[Keys.notifyContact] ?: d.contactMessages,
            channelMessages = p[Keys.notifyChannel] ?: d.channelMessages,
            roomMessages = p[Keys.notifyRoom] ?: d.roomMessages,
            missedWhileOffline = p[Keys.notifyMissed] ?: d.missedWhileOffline,
            newContact = p[Keys.notifyNewContact] ?: d.newContact,
            contactsFull = p[Keys.notifyContactsFull] ?: d.contactsFull,
        )
    }

    suspend fun save(prefs: NotificationPrefs) {
        store.edit { p ->
            p[Keys.notifyContact] = prefs.contactMessages
            p[Keys.notifyChannel] = prefs.channelMessages
            p[Keys.notifyRoom] = prefs.roomMessages
            p[Keys.notifyMissed] = prefs.missedWhileOffline
            p[Keys.notifyNewContact] = prefs.newContact
            p[Keys.notifyContactsFull] = prefs.contactsFull
        }
    }

    val contacts: Flow<ContactAppPrefs> = store.data.map { p ->
        val d = ContactAppPrefs()
        ContactAppPrefs(
            pullToRefresh = p[Keys.pullToRefresh] ?: d.pullToRefresh,
            showPublicKeys = p[Keys.showPublicKeys] ?: d.showPublicKeys,
        )
    }

    suspend fun save(prefs: ContactAppPrefs) {
        store.edit { p ->
            p[Keys.pullToRefresh] = prefs.pullToRefresh
            p[Keys.showPublicKeys] = prefs.showPublicKeys
        }
    }

    val experimental: Flow<ExperimentalPrefs> = store.data.map { p ->
        val d = ExperimentalPrefs()
        ExperimentalPrefs(
            fasterChannelSyncing = p[Keys.fasterChannelSyncing] ?: d.fasterChannelSyncing,
            companionClockForDms = p[Keys.companionClockForDms] ?: d.companionClockForDms,
            companionClockForCli = p[Keys.companionClockForCli] ?: d.companionClockForCli,
        )
    }

    suspend fun save(prefs: ExperimentalPrefs) {
        store.edit { p ->
            p[Keys.fasterChannelSyncing] = prefs.fasterChannelSyncing
            p[Keys.companionClockForDms] = prefs.companionClockForDms
            p[Keys.companionClockForCli] = prefs.companionClockForCli
        }
    }
}

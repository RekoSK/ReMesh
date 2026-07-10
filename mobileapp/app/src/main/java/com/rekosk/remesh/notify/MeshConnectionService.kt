package com.rekosk.remesh.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.rekosk.remesh.data.MeshContainer
import com.rekosk.remesh.data.MeshEvent
import com.rekosk.remesh.data.NotificationPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "MeshConnectionService"

/** How often to re-read the battery while connected, for the expanded notification. */
private const val BATTERY_POLL_MS = 60_000L

/**
 * Keeps a notification up for as long as the app is alive, saying whether the node
 * is connected, and posts the alerts the notification settings screen offers.
 *
 * It is a foreground service so that the BLE link survives the Activity going away:
 * a mesh radio the user has walked away from should still be receiving.
 */
class MeshConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MeshNotifications.createChannels(this)

        val repository = MeshContainer.repository(this)
        val preferences = MeshContainer.preferences(this)

        // Post something immediately: startForeground must be called within seconds
        // of the service starting, or the system kills us.
        startForeground()

        scope.launch {
            combine(
                repository.isRadioConnected,
                repository.selfName,
                repository.storage,
            ) { connected, name, battery -> Triple(connected, name, battery) }
                .collect { (connected, name, battery) ->
                    notifyConnection(connected, name, battery)
                }
        }

        // The battery only changes when we ask for it, and the notification is the
        // only thing looking while the app is in the background.
        scope.launch {
            while (true) {
                if (repository.isRadioConnected.value) {
                    runCatching { repository.refreshStorage() }
                        .onFailure { Log.w(TAG, "battery poll failed", it) }
                }
                delay(BATTERY_POLL_MS)
            }
        }

        scope.launch {
            repository.events.collect { event ->
                runCatching { handle(event, preferences.notifications.first()) }
                    .onFailure { Log.w(TAG, "could not post notification", it) }
            }
        }
    }

    private fun handle(event: MeshEvent, prefs: NotificationPrefs) {
        when (event) {
            is MeshEvent.MessageReceived -> {
                val allowed = when {
                    // Anything the node had queued up arrives at handshake time; the
                    // user controls that burst with its own switch.
                    event.whileDisconnected -> prefs.missedWhileOffline
                    event.isChannel -> prefs.channelMessages
                    else -> prefs.contactMessages
                }
                if (allowed) {
                    MeshNotifications.postMessage(
                        context = this,
                        conversationId = event.conversationId,
                        title = event.conversationTitle,
                        author = event.author,
                        text = event.text,
                    )
                }
            }

            is MeshEvent.NewContact -> if (prefs.newContact) {
                MeshNotifications.postSimpleAlert(
                    context = this,
                    id = MeshNotifications.ID_NEW_CONTACT,
                    title = "New contact discovered",
                    text = "${event.name} advertised itself and was added to your contacts.",
                )
            }

            MeshEvent.ContactsFull -> if (prefs.contactsFull) {
                MeshNotifications.postSimpleAlert(
                    context = this,
                    id = MeshNotifications.ID_CONTACTS_FULL,
                    title = "Contacts full",
                    text = "Your node cannot store any more contacts. Remove some to keep discovering nodes.",
                )
            }
        }
    }

    private fun notifyConnection(
        connected: Boolean,
        nodeName: String?,
        battery: com.rekosk.remesh.ble.MeshFrame.Battery?,
    ) {
        if (!MeshNotifications.canPost(this)) return
        val notification =
            MeshNotifications.connectionNotification(this, connected, nodeName, battery)
        androidx.core.app.NotificationManagerCompat.from(this)
            .notify(MeshNotifications.STATUS_NOTIFICATION_ID, notification)
    }

    /**
     * A `connectedDevice` foreground service needs a Bluetooth permission, which the
     * user has not granted on a fresh install. Refusing to start is survivable -- the
     * app works, it just has no status row until the next launch -- whereas letting
     * the SecurityException escape would kill the process on first run.
     */
    private fun startForeground() {
        val repository = MeshContainer.repository(this)
        val notification = MeshNotifications.connectionNotification(
            context = this,
            isConnected = repository.isRadioConnected.value,
            nodeName = repository.selfName.value,
            battery = repository.storage.value,
        )
        runCatching {
            startForeground(
                MeshNotifications.STATUS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure {
            Log.w(TAG, "cannot run in the foreground yet; Bluetooth is not granted", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        // The link has no other owner once we go: leaving it open would keep the
        // radio bonded to a process that can no longer read from it.
        MeshContainer.repository(this).disconnect()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MeshConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshConnectionService::class.java))
        }
    }
}

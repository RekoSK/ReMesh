package com.rekosk.remesh.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rekosk.remesh.MainActivity
import com.rekosk.remesh.R
import com.rekosk.remesh.ble.MeshFrame

/**
 * Every notification the app posts, in one place.
 *
 * Two channels, because they want opposite treatment: the connection status is a
 * silent, permanent, un-dismissable row, while an incoming message should buzz.
 */
object MeshNotifications {

    /** Silent and ongoing: the foreground service's own notification. */
    const val STATUS_CHANNEL_ID = "connection_status"

    /** Incoming messages, new contacts, a full contact table. */
    const val ALERTS_CHANNEL_ID = "mesh_alerts"

    /** Fixed, because the foreground service replaces rather than stacks it. */
    const val STATUS_NOTIFICATION_ID = 1

    /** Alert ids start above the status one and are derived from the conversation. */
    private const val ALERT_ID_BASE = 1000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL_ID,
                "Node connection",
                // LOW: visible in the shade and on the lock screen, but never a sound.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows whether ReMesh is connected to your node."
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Messages and alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Incoming messages, new contacts, and node warnings."
            },
        )
    }

    /**
     * The ongoing row. Collapsed it says connected or not; expanded it names the
     * node and reports its battery, which is the pair of facts worth glancing at.
     */
    fun connectionNotification(
        context: Context,
        isConnected: Boolean,
        nodeName: String?,
        battery: MeshFrame.Battery?,
    ): Notification {
        val headline = if (isConnected) "Connected" else "Not connected"
        val detail = buildString {
            append("Node: ")
            append(if (isConnected) nodeName?.ifBlank { null } ?: "Unknown node" else "None")
            append('\n')
            append("Battery: ")
            append(
                if (isConnected && battery != null) formatBattery(battery) else "Unknown",
            )
        }

        return NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(if (isConnected) R.drawable.ic_stat_connected else R.drawable.ic_stat_disconnected)
            .setContentTitle("ReMesh")
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail).setSummaryText(headline))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** "48% - 3.58 V", the same numbers the telemetry screen shows. */
    private fun formatBattery(battery: MeshFrame.Battery): String =
        "%d%% - %.2f V".format(java.util.Locale.US, battery.batteryPercent(), battery.volts())

    /**
     * Posts [text] from [author] under [title]. Notifications for the same
     * conversation replace each other rather than stacking, so a busy channel
     * cannot bury the shade.
     */
    fun postMessage(
        context: Context,
        conversationId: String,
        title: String,
        author: String,
        text: String,
    ) {
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(if (title == author) text else "$author: $text")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        post(context, alertIdFor(conversationId), notification)
    }

    fun postSimpleAlert(context: Context, id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        post(context, id, notification)
    }

    const val ID_NEW_CONTACT = 2
    const val ID_CONTACTS_FULL = 3

    /**
     * A stable non-negative id per conversation. Collisions merely merge two
     * conversations' notifications, which is why nothing important rides on it.
     */
    private fun alertIdFor(conversationId: String): Int =
        ALERT_ID_BASE + (conversationId.hashCode().mod(10_000))

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Silently does nothing without POST_NOTIFICATIONS, which the user may deny. */
    private fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

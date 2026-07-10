package com.rekosk.remesh.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// "09/Jul" -- pinned to English so the month abbreviation matches the rest of the app.
private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MMM", Locale.ENGLISH)

/** "19:42" -- the timestamp inside a message bubble. */
fun formatClock(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFormatter)

/**
 * "19:42" for today, "09/Jul 19:42" for any earlier day -- the timestamp under a
 * message bubble, where the day matters once a conversation is more than a session old.
 */
fun formatMessageTime(epochMs: Long, today: LocalDate = LocalDate.now()): String {
    val moment = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    val clock = moment.format(clockFormatter)
    return if (moment.toLocalDate() == today) clock else "${moment.format(dateFormatter)} $clock"
}

/** "Last seen 7 mins ago" -- the trailing text on a contact row. */
fun formatLastSeen(epochMs: Long?): String {
    if (epochMs == null) return ""
    val elapsed = System.currentTimeMillis() - epochMs
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        minutes < 1L -> "Last seen just now"
        minutes < 60L -> "Last seen $minutes ${plural(minutes, "min", "mins")} ago"
        hours < 24L -> "Last seen $hours ${plural(hours, "hour", "hours")} ago"
        else -> "Last seen $days ${plural(days, "day", "days")} ago"
    }
}

private fun plural(value: Long, singular: String, plural: String) =
    if (value == 1L) singular else plural

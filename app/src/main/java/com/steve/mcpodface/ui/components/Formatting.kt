package com.steve.mcpodface.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

// Pure Kotlin display formatting for list rows (PLAN.md §8.2). No Android imports, unit-tested.

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

private const val KB = 1024L
private const val MB = KB * 1024L
private const val GB = MB * 1024L

/** "" for null or non-positive, "45 sec", "45 min", "1 h", "1 h 5 min". */
fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return ""
    if (seconds < 60) return "$seconds sec"
    return formatMinutes((seconds / 60).toLong())
}

/** "12 min left", "1 h 5 min left". "" when the duration is unknown; never negative. */
fun formatTimeLeft(durationSec: Int?, positionMs: Long): String {
    if (durationSec == null || durationSec <= 0) return ""
    val remainingSec = max(0L, durationSec * 1000L - positionMs) / 1000L
    if (remainingSec < 60L) return "1 min left"
    return formatMinutes(remainingSec / 60L) + " left"
}

private fun formatMinutes(totalMinutes: Long): String {
    if (totalMinutes < 60L) return "$totalMinutes min"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (minutes == 0L) "$hours h" else "$hours h $minutes min"
}

/**
 * "Just now" within an hour either side (tolerates clock skew), "3h ago" under a day,
 * "Yesterday", "3d ago" under a week, then "12 Oct" (same year) or "12 Oct 2024".
 */
fun formatRelativeDate(epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val diff = now - epochMs
    if (diff >= -HOUR_MS && diff < HOUR_MS) return "Just now"
    if (diff >= HOUR_MS && diff < DAY_MS) return "${diff / HOUR_MS}h ago"

    val pubDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    if (diff >= DAY_MS) {
        if (ChronoUnit.DAYS.between(pubDate, today) == 1L) return "Yesterday"
        if (diff < 7 * DAY_MS) return "${diff / DAY_MS}d ago"
    }
    return formatCalendarDate(pubDate, today)
}

private fun formatCalendarDate(date: LocalDate, today: LocalDate): String {
    val pattern = if (date.year == today.year) "d MMM" else "d MMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}

/** "0 MB", "850 KB", "412 MB", "1.2 GB" (1024 base; only GB gets a decimal). */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes < MB -> "${max(1L, (bytes.toDouble() / KB).roundToLong())} KB"
    bytes < GB -> "${(bytes.toDouble() / MB).roundToLong()} MB"
    else -> String.format(Locale.ENGLISH, "%.1f GB", bytes.toDouble() / GB)
}

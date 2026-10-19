package com.steve.mcpodface.ui.stats

import com.steve.mcpodface.data.db.SessionTimeRow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

// Pure Kotlin calculations and formatting for the Stats screen (PLAN.md §13). No Android imports.

/** A day counts toward the streak once it has at least this much listening (5 minutes). */
const val STREAK_MIN_MS = 5 * 60_000L

/** Length of the daily bar chart, in days, ending today. */
const val CHART_DAYS = 30

private const val MINUTE_MS = 60_000L

/** One bar of the daily chart: a local day and the listening time on it. */
data class DayBar(val date: LocalDate, val listenedMs: Long)

/** Consecutive qualifying days: [current] runs up to today (or yesterday, while today is still young). */
data class StreakStats(val current: Int, val longest: Int)

/** Listening time this week (Monday to today) against the same span last week. */
data class WeekComparison(val thisWeekMs: Long, val lastWeekMs: Long)

/**
 * Sums listened ms per local day. Each session counts toward the local day it started on, so a
 * session that runs past midnight is not split. Times are read in [zone].
 */
fun dailyListenedMs(rows: List<SessionTimeRow>, zone: ZoneId): Map<LocalDate, Long> {
    val totals = HashMap<LocalDate, Long>()
    for (row in rows) {
        val day = Instant.ofEpochMilli(row.startedAt).atZone(zone).toLocalDate()
        totals[day] = (totals[day] ?: 0L) + row.listenedMs
    }
    return totals
}

/** The [days] local days ending on [today], oldest first. Days with no listening are zero. */
fun dailyBars(totals: Map<LocalDate, Long>, today: LocalDate, days: Int = CHART_DAYS): List<DayBar> =
    (days - 1 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        DayBar(day, totals[day] ?: 0L)
    }

/** Epoch ms at the start (in [zone]) of the first day of the [days]-day window ending on [today]. */
fun windowStartMillis(today: LocalDate, zone: ZoneId, days: Int = CHART_DAYS): Long =
    today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Daily streaks over days with at least [minMs] of listening. The current streak counts back from
 * today; if today has not qualified yet it counts back from yesterday, so an unfinished day does
 * not break a streak. Runs cross month and year boundaries, since they step by [LocalDate.plusDays].
 */
fun streaks(totals: Map<LocalDate, Long>, today: LocalDate, minMs: Long = STREAK_MIN_MS): StreakStats {
    val qualifying = totals.filterValues { it >= minMs }.keys

    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in qualifying.sorted()) {
        run = if (previous != null && day == previous.plusDays(1)) run + 1 else 1
        longest = max(longest, run)
        previous = day
    }

    var cursor = if (today in qualifying) today else today.minusDays(1)
    var current = 0
    while (cursor in qualifying) {
        current++
        cursor = cursor.minusDays(1)
    }
    return StreakStats(current = current, longest = longest)
}

/**
 * This week (Monday up to and including [today]) against last week over the same weekdays, so the
 * two numbers cover like-for-like spans.
 */
fun weekComparison(totals: Map<LocalDate, Long>, today: LocalDate): WeekComparison {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val lastMonday = monday.minusWeeks(1)
    return WeekComparison(
        thisWeekMs = sumBetween(totals, monday, today),
        lastWeekMs = sumBetween(totals, lastMonday, today.minusWeeks(1)),
    )
}

/** Sum of the day totals from [from] to [to], both inclusive. */
fun sumBetween(totals: Map<LocalDate, Long>, from: LocalDate, to: LocalDate): Long =
    totals.filterKeys { it >= from && it <= to }.values.sum()

/**
 * "Up 25%", "Down 10%" or "Same as last week" comparing this week with last week. Null when last
 * week has no listening to compare against.
 */
fun changeLabel(thisWeekMs: Long, lastWeekMs: Long): String? {
    if (lastWeekMs <= 0L) return null
    val percent = Math.round((thisWeekMs - lastWeekMs) * 100.0 / lastWeekMs).toInt()
    return when {
        percent > 0 -> "Up $percent%"
        percent < 0 -> "Down ${-percent}%"
        else -> "Same as last week"
    }
}

/** Share of [total] that [part] makes up, 0..1. Zero when there is no total. */
fun shareOf(part: Long, total: Long): Float {
    if (total <= 0L) return 0f
    return (part.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Time saved by speed, the reference for the DAO's SUM(listenedMs × (speed − 1)) over sessions
 * faster than 1x. Each pair is (listenedMs, speed). Sessions at 1x or slower save nothing.
 */
fun timeSavedMs(sessions: List<Pair<Long, Float>>): Double =
    sessions.sumOf { (listenedMs, speed) -> if (speed > 1f) listenedMs * (speed - 1.0) else 0.0 }

/**
 * "142 h 10 min", "1 h", "45 min", "0 min". Minutes are rounded down. Used for hero numbers, the
 * weekly comparison and the top-podcast rows.
 */
fun formatListeningTime(ms: Long): String {
    val totalMinutes = max(0L, ms) / MINUTE_MS
    if (totalMinutes < 60L) return "$totalMinutes min"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (minutes == 0L) "$hours h" else "$hours h $minutes min"
}

/** "0 days", "1 day", "12 days". */
fun formatDays(count: Int): String = if (count == 1) "1 day" else "$count days"

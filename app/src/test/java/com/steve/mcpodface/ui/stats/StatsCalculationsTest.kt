package com.steve.mcpodface.ui.stats

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.SessionTimeRow
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StatsCalculationsTest {
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    private val utc: ZoneId = ZoneId.of("UTC")
    private val minute = 60_000L

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    // ---- day bucketing ------------------------------------------------------------------------

    @Test fun sessionsBucketIntoTheLocalDayOfTheSystemZone() {
        // 00:30 on 20 Oct in Tokyo is 15:30 on 19 Oct UTC. It belongs to the 20th in Tokyo.
        val rows = listOf(SessionTimeRow(startedAt = at(tokyo, 2026, 10, 20, h = 0, min = 30), listenedMs = 7 * minute))

        val totals = dailyListenedMs(rows, tokyo)

        assertThat(totals).isEqualTo(mapOf(LocalDate.of(2026, 10, 20) to 7 * minute))
    }

    @Test fun sameInstantBucketsToTheEarlierUtcDayWhenZoneIsUtc() {
        val rows = listOf(SessionTimeRow(startedAt = at(tokyo, 2026, 10, 20, h = 0, min = 30), listenedMs = 7 * minute))

        val totals = dailyListenedMs(rows, utc)

        assertThat(totals).isEqualTo(mapOf(LocalDate.of(2026, 10, 19) to 7 * minute))
    }

    @Test fun sessionsOnTheSameDayAreSummed() {
        val rows = listOf(
            SessionTimeRow(at(tokyo, 2026, 10, 19, h = 8), 4 * minute),
            SessionTimeRow(at(tokyo, 2026, 10, 19, h = 22), 6 * minute),
        )

        assertThat(dailyListenedMs(rows, tokyo)).isEqualTo(mapOf(LocalDate.of(2026, 10, 19) to 10 * minute))
    }

    @Test fun sessionRunningPastMidnightCountsOnlyTowardItsStartDay() {
        // Starts 23:50 on 19 Oct in Tokyo and runs 20 minutes into the 20th. All of it is the 19th.
        val rows = listOf(SessionTimeRow(at(tokyo, 2026, 10, 19, h = 23, min = 50), 20 * minute))

        assertThat(dailyListenedMs(rows, tokyo)).isEqualTo(mapOf(LocalDate.of(2026, 10, 19) to 20 * minute))
    }

    @Test fun dailyBarsAreThirtyDaysOldestFirstEndingToday() {
        val today = LocalDate.of(2026, 10, 19)
        val totals = mapOf(today to 5 * minute, LocalDate.of(2026, 9, 20) to 3 * minute)

        val bars = dailyBars(totals, today)

        assertThat(bars).hasSize(30)
        assertThat(bars.first()).isEqualTo(DayBar(LocalDate.of(2026, 9, 20), 3 * minute))
        assertThat(bars.last()).isEqualTo(DayBar(today, 5 * minute))
        assertThat(bars.map { it.date }).isInOrder()
        assertThat(bars.count { it.listenedMs == 0L }).isEqualTo(28)
    }

    @Test fun windowStartIsMidnightTwentyNineDaysBackInTheZone() {
        val today = LocalDate.of(2026, 10, 19)

        assertThat(windowStartMillis(today, tokyo)).isEqualTo(at(tokyo, 2026, 9, 20, h = 0))
        assertThat(windowStartMillis(today, utc)).isEqualTo(at(utc, 2026, 9, 20, h = 0))
    }

    // ---- streaks ------------------------------------------------------------------------------

    private fun days(vararg dates: LocalDate, minutes: Long = 10): Map<LocalDate, Long> =
        dates.associateWith { minutes * minute }

    @Test fun streakRunsAcrossAMonthBoundary() {
        val totals = days(
            LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30),
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2),
        )

        val stats = streaks(totals, today = LocalDate.of(2026, 10, 2))

        assertThat(stats).isEqualTo(StreakStats(current = 4, longest = 4))
    }

    @Test fun streakRunsAcrossANewYear() {
        val totals = days(LocalDate.of(2025, 12, 30), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1))

        assertThat(streaks(totals, today = LocalDate.of(2026, 1, 1))).isEqualTo(StreakStats(current = 3, longest = 3))
    }

    @Test fun dayBelowFiveMinutesBreaksTheStreakAndDoesNotCount() {
        val today = LocalDate.of(2026, 10, 19)
        val totals = mapOf(
            today.minusDays(2) to 10 * minute,
            today.minusDays(1) to 4 * minute, // 4 min: not a streak day
            today to 10 * minute,
        )

        assertThat(streaks(totals, today)).isEqualTo(StreakStats(current = 1, longest = 1))
    }

    @Test fun fiveMinutesExactlyCountsAsAStreakDay() {
        val today = LocalDate.of(2026, 10, 19)
        val totals = mapOf(today.minusDays(1) to 5 * minute, today to 5 * minute)

        assertThat(streaks(totals, today)).isEqualTo(StreakStats(current = 2, longest = 2))
    }

    @Test fun unfinishedTodayDoesNotBreakTheCurrentStreak() {
        val today = LocalDate.of(2026, 10, 19)
        // Yesterday and the day before qualify. Today has only 1 minute so far.
        val totals = mapOf(
            today.minusDays(2) to 10 * minute,
            today.minusDays(1) to 10 * minute,
            today to 1 * minute,
        )

        assertThat(streaks(totals, today)).isEqualTo(StreakStats(current = 2, longest = 2))
    }

    @Test fun longestStreakCanBeOlderThanTheCurrentOne() {
        val today = LocalDate.of(2026, 10, 19)
        val old = (1L..10L).map { today.minusDays(40 + it) }
        val recent = listOf(today.minusDays(1), today)
        val totals = (old + recent).associateWith { 10 * minute }

        assertThat(streaks(totals, today)).isEqualTo(StreakStats(current = 2, longest = 10))
    }

    @Test fun noQualifyingDaysMeansNoStreak() {
        val today = LocalDate.of(2026, 10, 19)

        assertThat(streaks(emptyMap(), today)).isEqualTo(StreakStats(current = 0, longest = 0))
        assertThat(streaks(mapOf(today to 2 * minute), today)).isEqualTo(StreakStats(current = 0, longest = 0))
    }

    // ---- week comparison ----------------------------------------------------------------------

    @Test fun weekComparisonUsesMondayToToday_againstTheSameWeekdaysLastWeek() {
        // Today is Wednesday 21 Oct 2026. This week is Mon 19 to Wed 21; last week is Mon 12 to Wed 14.
        val today = LocalDate.of(2026, 10, 21)
        val totals = mapOf(
            LocalDate.of(2026, 10, 12) to 10 * minute,
            LocalDate.of(2026, 10, 13) to 20 * minute,
            LocalDate.of(2026, 10, 14) to 5 * minute,
            LocalDate.of(2026, 10, 15) to 999 * minute, // Thursday last week: outside the span
            LocalDate.of(2026, 10, 19) to 30 * minute,
            LocalDate.of(2026, 10, 21) to 15 * minute,
            LocalDate.of(2026, 10, 22) to 999 * minute, // tomorrow: outside the span
        )

        assertThat(weekComparison(totals, today)).isEqualTo(
            WeekComparison(thisWeekMs = 45 * minute, lastWeekMs = 35 * minute),
        )
    }

    @Test fun weekComparisonOnMondayCountsOnlyToday() {
        val today = LocalDate.of(2026, 10, 19)
        val totals = mapOf(today to 9 * minute, LocalDate.of(2026, 10, 12) to 4 * minute, LocalDate.of(2026, 10, 13) to 50 * minute)

        assertThat(weekComparison(totals, today)).isEqualTo(
            WeekComparison(thisWeekMs = 9 * minute, lastWeekMs = 4 * minute),
        )
    }

    @Test fun changeLabelDescribesTheDifference() {
        assertThat(changeLabel(thisWeekMs = 120 * minute, lastWeekMs = 100 * minute)).isEqualTo("Up 20%")
        assertThat(changeLabel(thisWeekMs = 80 * minute, lastWeekMs = 100 * minute)).isEqualTo("Down 20%")
        assertThat(changeLabel(thisWeekMs = 100 * minute, lastWeekMs = 100 * minute)).isEqualTo("Same as last week")
    }

    @Test fun changeLabelIsNullWhenLastWeekHasNothingToCompare() {
        assertThat(changeLabel(thisWeekMs = 30 * minute, lastWeekMs = 0L)).isNull()
    }

    // ---- time saved by speed ------------------------------------------------------------------

    @Test fun timeSavedIsListenedTimesSpeedMinusOneForSessionsFasterThanOne() {
        val sessions = listOf(
            60_000L to 1.5f, // saves 30 s
            60_000L to 1f, // 1x saves nothing
            120_000L to 2f, // saves 2 min
            30_000L to 0.8f, // slower than 1x saves nothing
        )

        assertThat(timeSavedMs(sessions)).isWithin(1e-6).of(150_000.0)
    }

    @Test fun timeSavedIsZeroWithNoSessions() {
        assertThat(timeSavedMs(emptyList())).isEqualTo(0.0)
    }

    @Test fun shareOfIsAFractionClampedToOneAndZeroWhenNoTotal() {
        assertThat(shareOf(part = 25L, total = 100L)).isWithin(1e-6f).of(0.25f)
        assertThat(shareOf(part = 150L, total = 100L)).isWithin(1e-6f).of(1f)
        assertThat(shareOf(part = 5L, total = 0L)).isEqualTo(0f)
    }

    // ---- formatting ---------------------------------------------------------------------------

    @Test fun listeningTimeFormatsHoursAndMinutes() {
        assertThat(formatListeningTime((142L * 60 + 10) * minute)).isEqualTo("142 h 10 min")
        assertThat(formatListeningTime(3L * 3_600_000L)).isEqualTo("3 h")
    }

    @Test fun listeningTimeFormatsMinutesUnderAnHour() {
        assertThat(formatListeningTime(45 * minute)).isEqualTo("45 min")
        assertThat(formatListeningTime(60 * minute)).isEqualTo("1 h")
    }

    @Test fun listeningTimeRoundsDownAndNeverGoesNegative() {
        assertThat(formatListeningTime(59_999L)).isEqualTo("0 min")
        assertThat(formatListeningTime(0L)).isEqualTo("0 min")
        assertThat(formatListeningTime(-5 * minute)).isEqualTo("0 min")
    }

    @Test fun daysAreSingularOnlyForOne() {
        assertThat(formatDays(0)).isEqualTo("0 days")
        assertThat(formatDays(1)).isEqualTo("1 day")
        assertThat(formatDays(12)).isEqualTo("12 days")
    }
}

package com.steve.mcpodface.data.feed

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class FeedDatesTest {

    private fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun rfc822WithDayNameAndGmt() {
        assertThat(FeedDates.parse("Tue, 03 Jun 2025 08:00:00 GMT")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun missingDayName() {
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 GMT")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun oneDigitDayAndTwoDigitYear() {
        assertThat(FeedDates.parse("Tue, 3 Jun 25 08:00:00 GMT")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun twoDigitYearFollowsRfc2822Pivot() {
        assertThat(FeedDates.parse("3 Jun 99 08:00 GMT")).isEqualTo(utc("1999-06-03T08:00:00Z"))
    }

    @Test
    fun numericNegativeOffset() {
        assertThat(FeedDates.parse("Tue, 03 Jun 2025 08:00:00 -0500")).isEqualTo(utc("2025-06-03T13:00:00Z"))
    }

    @Test
    fun numericPositiveOffsetWithColon() {
        assertThat(FeedDates.parse("Tue, 03 Jun 2025 08:00:00 +05:30")).isEqualTo(utc("2025-06-03T02:30:00Z"))
    }

    @Test
    fun numericZeroOffset() {
        assertThat(FeedDates.parse("Tue, 03 Jun 2025 08:00:00 +0000")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun namedUsZones() {
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 EST")).isEqualTo(utc("2025-06-03T13:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 EDT")).isEqualTo(utc("2025-06-03T12:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 CST")).isEqualTo(utc("2025-06-03T14:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 CDT")).isEqualTo(utc("2025-06-03T13:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 MST")).isEqualTo(utc("2025-06-03T15:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 MDT")).isEqualTo(utc("2025-06-03T14:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 PST")).isEqualTo(utc("2025-06-03T16:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 PDT")).isEqualTo(utc("2025-06-03T15:00:00Z"))
    }

    @Test
    fun namedUtcAndBritishZones() {
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 UT")).isEqualTo(utc("2025-06-03T08:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 UTC")).isEqualTo(utc("2025-06-03T08:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 Z")).isEqualTo(utc("2025-06-03T08:00:00Z"))
        assertThat(FeedDates.parse("3 Jun 2025 08:00:00 BST")).isEqualTo(utc("2025-06-03T07:00:00Z"))
    }

    @Test
    fun missingSeconds() {
        assertThat(FeedDates.parse("Tue, 03 Jun 2025 08:00 GMT")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun extraWhitespaceIsTolerated() {
        assertThat(FeedDates.parse("  Tue,   3  Jun   2025   08:00:00    GMT  "))
            .isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun missingTimeMeansMidnightUtc() {
        assertThat(FeedDates.parse("Tue, 03 Jun 2025")).isEqualTo(utc("2025-06-03T00:00:00Z"))
    }

    @Test
    fun fullMonthName() {
        assertThat(FeedDates.parse("Tuesday, 3 June 2025 08:00:00 GMT")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun isoWithZ() {
        assertThat(FeedDates.parse("2025-06-03T08:00:00Z")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun isoWithOffset() {
        assertThat(FeedDates.parse("2025-06-03T10:00:00+02:00")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun isoWithoutOffsetIsUtc() {
        assertThat(FeedDates.parse("2025-06-03T08:00:00")).isEqualTo(utc("2025-06-03T08:00:00Z"))
    }

    @Test
    fun isoDateOnlyIsUtcMidnight() {
        assertThat(FeedDates.parse("2025-06-03")).isEqualTo(utc("2025-06-03T00:00:00Z"))
    }

    @Test
    fun blankAndNullAreNull() {
        assertThat(FeedDates.parse(null)).isNull()
        assertThat(FeedDates.parse("")).isNull()
        assertThat(FeedDates.parse("   ")).isNull()
    }

    @Test
    fun unparseableIsNull() {
        assertThat(FeedDates.parse("sometime last tuesday-ish")).isNull()
        assertThat(FeedDates.parse("32 Jun 2025 08:00:00 GMT")).isNull()
        assertThat(FeedDates.parse("3 Foo 2025 08:00:00 GMT")).isNull()
        assertThat(FeedDates.parse("3 Jun 2025 25:00:00 GMT")).isNull()
    }
}

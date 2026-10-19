package com.steve.mcpodface.data.feed

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedDurationsTest {

    @Test
    fun plainSeconds() {
        assertThat(FeedDurations.parse("90")).isEqualTo(90)
        assertThat(FeedDurations.parse("0")).isEqualTo(0)
    }

    @Test
    fun decimalSecondsAreRounded() {
        assertThat(FeedDurations.parse("1234.5")).isEqualTo(1235)
        assertThat(FeedDurations.parse("59.4")).isEqualTo(59)
    }

    @Test
    fun minutesColonSeconds() {
        assertThat(FeedDurations.parse("1:30")).isEqualTo(90)
        assertThat(FeedDurations.parse("45:07")).isEqualTo(2707)
    }

    @Test
    fun hoursMinutesSeconds() {
        assertThat(FeedDurations.parse("01:02:03")).isEqualTo(3723)
        assertThat(FeedDurations.parse("1:02:03")).isEqualTo(3723)
        assertThat(FeedDurations.parse("12:00:00")).isEqualTo(43200)
    }

    @Test
    fun colonFormsWithDecimalSeconds() {
        assertThat(FeedDurations.parse("1:02:03.4")).isEqualTo(3723)
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertThat(FeedDurations.parse("  42  ")).isEqualTo(42)
        assertThat(FeedDurations.parse(" 01:00 ")).isEqualTo(60)
    }

    @Test
    fun blankAndNullAreNull() {
        assertThat(FeedDurations.parse(null)).isNull()
        assertThat(FeedDurations.parse("")).isNull()
        assertThat(FeedDurations.parse("   ")).isNull()
    }

    @Test
    fun junkIsNull() {
        assertThat(FeedDurations.parse("junk")).isNull()
        assertThat(FeedDurations.parse("1h2m3s")).isNull()
        assertThat(FeedDurations.parse("-5")).isNull()
        assertThat(FeedDurations.parse("1e3")).isNull()
        assertThat(FeedDurations.parse("1:2:3:4")).isNull()
        assertThat(FeedDurations.parse("1:99")).isNull()
        assertThat(FeedDurations.parse("1:02:99")).isNull()
    }
}

package com.steve.mcpodface.ui.components

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

class FormattingTest {

    private val utc = ZoneId.of("UTC")
    // 2026-10-19T12:00:00Z
    private val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ---- formatDuration --------------------------------------------------------------------

    @Test fun `duration null is empty`() {
        assertThat(formatDuration(null)).isEqualTo("")
    }

    @Test fun `duration zero or negative is empty`() {
        assertThat(formatDuration(0)).isEqualTo("")
        assertThat(formatDuration(-5)).isEqualTo("")
    }

    @Test fun `duration under a minute is seconds`() {
        assertThat(formatDuration(30)).isEqualTo("30 sec")
        assertThat(formatDuration(59)).isEqualTo("59 sec")
    }

    @Test fun `duration of exactly a minute`() {
        assertThat(formatDuration(60)).isEqualTo("1 min")
    }

    @Test fun `duration in minutes`() {
        assertThat(formatDuration(2700)).isEqualTo("45 min")
    }

    @Test fun `duration of whole hours drops minutes`() {
        assertThat(formatDuration(3600)).isEqualTo("1 h")
        assertThat(formatDuration(7200)).isEqualTo("2 h")
    }

    @Test fun `duration with hours and minutes`() {
        assertThat(formatDuration(3900)).isEqualTo("1 h 5 min")
        assertThat(formatDuration(5400)).isEqualTo("1 h 30 min")
    }

    // ---- formatTimeLeft --------------------------------------------------------------------

    @Test fun `time left is empty when duration unknown`() {
        assertThat(formatTimeLeft(null, 1_000L)).isEqualTo("")
        assertThat(formatTimeLeft(0, 1_000L)).isEqualTo("")
    }

    @Test fun `time left at the start of an episode`() {
        assertThat(formatTimeLeft(1500, 0L)).isEqualTo("25 min left")
    }

    @Test fun `time left over an hour`() {
        assertThat(formatTimeLeft(3900, 0L)).isEqualTo("1 h 5 min left")
    }

    @Test fun `time left mid episode`() {
        // 1800s episode, 18 minutes in: 12 minutes remain.
        assertThat(formatTimeLeft(1800, 18 * 60_000L)).isEqualTo("12 min left")
    }

    @Test fun `time left under a minute is one minute`() {
        // 720s episode, position 719.5s: 500ms remain.
        assertThat(formatTimeLeft(720, 719_500L)).isEqualTo("1 min left")
    }

    @Test fun `time left at the very end is one minute not negative`() {
        assertThat(formatTimeLeft(720, 720_000L)).isEqualTo("1 min left")
    }

    @Test fun `position past the end is never negative`() {
        assertThat(formatTimeLeft(600, 900_000L)).isEqualTo("1 min left")
    }

    @Test fun `negative position counts from zero`() {
        assertThat(formatTimeLeft(600, -5_000L)).isEqualTo("10 min left")
    }

    // ---- formatRelativeDate ----------------------------------------------------------------

    @Test fun `ten minutes ago is just now`() {
        assertThat(formatRelativeDate(now - 10 * 60_000L, now, utc)).isEqualTo("Just now")
    }

    @Test fun `slightly in the future is just now`() {
        assertThat(formatRelativeDate(now + 30 * 60_000L, now, utc)).isEqualTo("Just now")
    }

    @Test fun `exactly one hour ago is shown in hours`() {
        assertThat(formatRelativeDate(now - 3_600_000L, now, utc)).isEqualTo("1h ago")
    }

    @Test fun `three hours ago`() {
        assertThat(formatRelativeDate(now - 3 * 3_600_000L, now, utc)).isEqualTo("3h ago")
    }

    @Test fun `twenty three hours ago`() {
        assertThat(formatRelativeDate(now - 23 * 3_600_000L, now, utc)).isEqualTo("23h ago")
    }

    @Test fun `previous calendar day is yesterday`() {
        assertThat(formatRelativeDate(at("2026-10-18T08:00:00Z"), now, utc)).isEqualTo("Yesterday")
    }

    @Test fun `three days ago`() {
        assertThat(formatRelativeDate(at("2026-10-16T12:00:00Z"), now, utc)).isEqualTo("3d ago")
    }

    @Test fun `six days ago`() {
        assertThat(formatRelativeDate(at("2026-10-13T12:00:00Z"), now, utc)).isEqualTo("6d ago")
    }

    @Test fun `seven days ago shows the date`() {
        assertThat(formatRelativeDate(at("2026-10-12T12:00:00Z"), now, utc)).isEqualTo("12 Oct")
    }

    @Test fun `single digit day has no leading zero`() {
        assertThat(formatRelativeDate(at("2026-01-05T12:00:00Z"), now, utc)).isEqualTo("5 Jan")
    }

    @Test fun `other year includes the year`() {
        assertThat(formatRelativeDate(at("2024-10-12T12:00:00Z"), now, utc)).isEqualTo("12 Oct 2024")
    }

    @Test fun `far future date shows the date`() {
        assertThat(formatRelativeDate(at("2026-10-21T12:00:00Z"), now, utc)).isEqualTo("21 Oct")
    }

    @Test fun `calendar day uses the given zone`() {
        // 03:00Z on the 18th is 23:00 on the 17th in New York: 33h ago, two calendar days back.
        val ny = ZoneId.of("America/New_York")
        assertThat(formatRelativeDate(at("2026-10-18T03:00:00Z"), now, ny)).isEqualTo("1d ago")
    }

    // ---- formatBytes -----------------------------------------------------------------------

    private val kb = 1024L
    private val mb = 1024L * kb
    private val gb = 1024L * mb

    @Test fun `zero bytes is zero MB`() {
        assertThat(formatBytes(0L)).isEqualTo("0 MB")
    }

    @Test fun `kilobytes`() {
        assertThat(formatBytes(850 * kb)).isEqualTo("850 KB")
    }

    @Test fun `megabytes have no decimal`() {
        assertThat(formatBytes(412 * mb)).isEqualTo("412 MB")
    }

    @Test fun `exactly one megabyte`() {
        assertThat(formatBytes(mb)).isEqualTo("1 MB")
    }

    @Test fun `gigabytes have one decimal`() {
        assertThat(formatBytes((1.2 * gb).toLong())).isEqualTo("1.2 GB")
    }

    @Test fun `exactly one gigabyte keeps the decimal`() {
        assertThat(formatBytes(gb)).isEqualTo("1.0 GB")
    }

    @Test fun `large gigabyte values`() {
        assertThat(formatBytes(2 * gb)).isEqualTo("2.0 GB")
        assertThat(formatBytes((1.5 * gb).toLong())).isEqualTo("1.5 GB")
    }
}

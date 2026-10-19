package com.steve.mcpodface.ui.library

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.HistoryItem
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryGroupingTest {
    // Wednesday 2026-10-21 10:00 UTC. The week starts on Monday 2026-10-19.
    private val now = at(LocalDateTime.of(2026, 10, 21, 10, 0), ZoneOffset.UTC)

    private fun at(time: LocalDateTime, zone: ZoneId): Long = time.atZone(zone).toInstant().toEpochMilli()

    private fun item(id: Long, lastListenedAt: Long) = HistoryItem(
        episode = EpisodeEntity(
            id = id, podcastId = 1, guid = "g$id", title = "E$id", pubDate = 0,
            enclosureUrl = "https://x/$id.mp3", firstSeenAt = 0,
        ),
        podcastTitle = "Show",
        podcastArtworkUrl = null,
        downloadState = null,
        downloadBytes = null,
        inQueue = false,
        lastListenedAt = lastListenedAt,
        totalListenedMs = 1_000L,
    )

    private fun dayAt(day: Int, hour: Int = 12, zone: ZoneId = ZoneOffset.UTC) =
        at(LocalDateTime.of(LocalDate.of(2026, 10, day), java.time.LocalTime.of(hour, 0)), zone)

    private fun List<HistorySection>.titles() = map { it.title }

    @Test fun emptyInputHasNoSections() {
        assertThat(groupHistory(emptyList(), now, ZoneOffset.UTC)).isEmpty()
    }

    @Test fun bucketsByDayRelativeToNow() {
        val items = listOf(
            item(1, dayAt(21, 8)),   // today
            item(2, dayAt(20, 23)),  // yesterday
            item(3, dayAt(19, 9)),   // Monday: this week
            item(4, dayAt(18, 9)),   // Sunday before the week started: earlier
            item(5, dayAt(1, 9)),    // earlier
        )

        val sections = groupHistory(items, now, ZoneOffset.UTC)

        assertThat(sections.titles()).containsExactly("Today", "Yesterday", "This week", "Earlier").inOrder()
        assertThat(sections.map { s -> s.items.map { it.episode.id } }).containsExactly(
            listOf(1L), listOf(2L), listOf(3L), listOf(4L, 5L),
        ).inOrder()
    }

    @Test fun futureTimestampsCountAsToday() {
        val sections = groupHistory(listOf(item(1, now + 60_000L)), now, ZoneOffset.UTC)

        assertThat(sections.titles()).containsExactly("Today")
    }

    @Test fun emptySectionsAreDropped() {
        val sections = groupHistory(listOf(item(1, dayAt(1, 9))), now, ZoneOffset.UTC)

        assertThat(sections.titles()).containsExactly("Earlier")
    }

    @Test fun keepsInputOrderInsideASection() {
        val items = listOf(item(7, dayAt(21, 11)), item(8, dayAt(21, 9)), item(9, dayAt(21, 1)))

        val sections = groupHistory(items, now, ZoneOffset.UTC)

        assertThat(sections.single().items.map { it.episode.id }).containsExactly(7L, 8L, 9L).inOrder()
    }

    @Test fun usesTheGivenTimeZoneForDayBoundaries() {
        // 2026-10-20 02:00 UTC is still 19 Oct in New York (EDT, UTC-4), a Monday in "This week".
        val lateNight = at(LocalDateTime.of(2026, 10, 20, 2, 0), ZoneOffset.UTC)
        val newYork = ZoneId.of("America/New_York")

        val inUtc = groupHistory(listOf(item(1, lateNight)), now, ZoneOffset.UTC)
        val inNewYork = groupHistory(listOf(item(1, lateNight)), now, newYork)

        assertThat(inUtc.titles()).containsExactly("Yesterday")
        assertThat(inNewYork.titles()).containsExactly("This week")
    }

    @Test fun onMondayTheWeekSectionIsEmptyAndSundayIsYesterday() {
        val monday = at(LocalDateTime.of(2026, 10, 19, 10, 0), ZoneOffset.UTC)
        val items = listOf(item(1, dayAt(18, 9)), item(2, dayAt(17, 9)))

        val sections = groupHistory(items, monday, ZoneOffset.UTC)

        assertThat(sections.titles()).containsExactly("Yesterday", "Earlier").inOrder()
    }
}

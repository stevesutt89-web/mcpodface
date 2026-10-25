package com.steve.mcpodface.playback.auto

import androidx.media3.session.MediaConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.PlayState
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class BrowseItemsTest {

    private val london = ZoneId.of("Europe/London")

    /** 2026-10-25 12:00 in London. */
    private val now = at(2026, 10, 25, 12, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, london).toInstant().toEpochMilli()

    private fun episode(
        playState: PlayState = PlayState.UNPLAYED,
        positionMs: Long = 0,
        durationSec: Int? = 3900,
        downloaded: Boolean = false,
        pubDate: Long = now,
        podcast: String = "Show",
    ) = CarEpisode(
        id = 7,
        title = "Ep",
        podcastTitle = podcast,
        artworkUrl = null,
        pubDate = pubDate,
        durationSec = durationSec,
        positionMs = positionMs,
        playState = playState,
        downloaded = downloaded,
        isStarred = false,
        inQueue = false,
    )

    private fun extras(item: androidx.media3.common.MediaItem) = item.mediaMetadata.extras!!

    // Completion badges

    @Test fun unplayedEpisode_isNotPlayed_andNotDownloaded() {
        val extras = extras(BrowseItems.episodeItem(episode(), now))
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
        assertThat(extras.containsKey(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE)).isFalse()
        assertThat(extras.getLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_STATUS_NOT_DOWNLOADED)
    }

    @Test fun partlyPlayed_isPartiallyPlayed_withPercentage() {
        // 300 s of 1200 s is a quarter.
        val extras = extras(BrowseItems.episodeItem(episode(PlayState.IN_PROGRESS, 300_000, 1200), now))
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
        assertThat(extras.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE)).isWithin(1e-9).of(0.25)
    }

    @Test fun partlyPlayed_withUnknownDuration_hasNoPercentage() {
        val extras = extras(BrowseItems.episodeItem(episode(PlayState.IN_PROGRESS, 300_000, null), now))
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
        assertThat(extras.containsKey(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE)).isFalse()
    }

    @Test fun playedEpisode_isFullyPlayed_evenWithAPosition_andHasNoPercentage() {
        val extras = extras(BrowseItems.episodeItem(episode(PlayState.PLAYED, 3_000_000, 1200), now))
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
        assertThat(extras.containsKey(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE)).isFalse()
    }

    @Test fun playedAtZeroPosition_isFullyPlayed() {
        val extras = extras(BrowseItems.episodeItem(episode(PlayState.PLAYED, 0), now))
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
    }

    @Test fun percentageIsClampedToOne_whenPositionPastDuration() {
        val extras = extras(BrowseItems.episodeItem(episode(PlayState.IN_PROGRESS, 2_000_000, 1200), now))
        assertThat(extras.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE)).isWithin(1e-9).of(1.0)
    }

    @Test fun downloadedEpisode_hasDownloadedBadge() {
        val extras = extras(BrowseItems.episodeItem(episode(downloaded = true), now))
        assertThat(extras.getLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
    }

    // Subtitles and duration

    @Test fun unplayed_subtitleShowsTotalDuration() {
        assertThat(BrowseItems.subtitle(episode(durationSec = 3900))).isEqualTo("Show · 1 h 05 min")
    }

    @Test fun partlyPlayed_subtitleShowsRemainingTime() {
        // 30 min episode, 7 min in: exactly 23 min left.
        assertThat(BrowseItems.subtitle(episode(PlayState.IN_PROGRESS, 420_000, 1800)))
            .isEqualTo("Show · 23 min left")
    }

    @Test fun remainingTime_roundsUpToTheNextMinute() {
        // 3600 s with 2999 s in: 601 s (10.02 min) left, so 11 min.
        assertThat(BrowseItems.subtitle(episode(PlayState.IN_PROGRESS, 2_999_000, 3600)))
            .isEqualTo("Show · 11 min left")
    }

    @Test fun remainingTime_isAtLeastOneMinute() {
        assertThat(BrowseItems.subtitle(episode(PlayState.IN_PROGRESS, 3_599_500, 3600)))
            .isEqualTo("Show · 1 min left")
    }

    @Test fun subtitle_fiftyNineMinutes() {
        assertThat(BrowseItems.subtitle(episode(durationSec = 59 * 60))).isEqualTo("Show · 59 min")
    }

    @Test fun subtitle_sixtyMinutes() {
        assertThat(BrowseItems.subtitle(episode(durationSec = 3600))).isEqualTo("Show · 1 h 00 min")
    }

    @Test fun subtitle_zeroDuration() {
        assertThat(BrowseItems.subtitle(episode(durationSec = 0))).isEqualTo("Show · 0 min")
    }

    @Test fun subtitle_nullDuration_isJustThePodcastTitle() {
        assertThat(BrowseItems.subtitle(episode(durationSec = null))).isEqualTo("Show")
    }

    @Test fun episodeItem_setsSubtitleAndDuration() {
        val item = BrowseItems.episodeItem(episode(durationSec = 3900), now)
        assertThat(item.mediaMetadata.subtitle.toString()).isEqualTo("Show · 1 h 05 min")
        assertThat(item.mediaMetadata.durationMs).isEqualTo(3_900_000L)
    }

    @Test fun episodeItem_withoutDuration_hasNoDuration() {
        val item = BrowseItems.episodeItem(episode(durationSec = null), now)
        assertThat(item.mediaMetadata.durationMs).isNull()
    }

    // Group titles

    @Test fun groupTitle_sameDay_isToday() {
        assertThat(BrowseItems.groupTitle(at(2026, 10, 25, 0, 0, 10), now, london)).isEqualTo("Today")
    }

    @Test fun groupTitle_justBeforeMidnight_isYesterday() {
        assertThat(BrowseItems.groupTitle(at(2026, 10, 24, 23, 59, 59), now, london)).isEqualTo("Yesterday")
    }

    @Test fun groupTitle_justAfterMidnight_isToday() {
        val justAfterMidnight = at(2026, 10, 25, 0, 0, 1)
        assertThat(BrowseItems.groupTitle(justAfterMidnight, justAfterMidnight + 60_000, london)).isEqualTo("Today")
    }

    @Test fun groupTitle_yesterday() {
        assertThat(BrowseItems.groupTitle(at(2026, 10, 24, 12, 0), now, london)).isEqualTo("Yesterday")
    }

    @Test fun groupTitle_twoDaysAgo_isThisWeek() {
        assertThat(BrowseItems.groupTitle(at(2026, 10, 23, 12, 0), now, london)).isEqualTo("This week")
    }

    @Test fun groupTitle_sixDaysAgo_isThisWeek() {
        assertThat(BrowseItems.groupTitle(at(2026, 10, 19, 12, 0), now, london)).isEqualTo("This week")
    }

    @Test fun groupTitle_sevenDaysAgo_isEarlier() {
        assertThat(BrowseItems.groupTitle(at(2026, 10, 18, 23, 59), now, london)).isEqualTo("Earlier")
    }

    @Test fun groupTitle_longAgo_isEarlier() {
        assertThat(BrowseItems.groupTitle(at(2025, 1, 1, 9, 0), now, london)).isEqualTo("Earlier")
    }

    @Test fun groupTitle_futureDate_isToday() {
        assertThat(BrowseItems.groupTitle(at(2026, 10, 27, 9, 0), now, london)).isEqualTo("Today")
    }

    @Test fun groupTitle_usesTheGivenZone() {
        // 23:30 UTC on 24 Oct is already 00:30 on 25 Oct in London (BST).
        val pub = java.time.Instant.parse("2026-10-24T23:30:00Z").toEpochMilli()
        assertThat(BrowseItems.groupTitle(pub, now, london)).isEqualTo("Today")
        assertThat(BrowseItems.groupTitle(pub, now, ZoneId.of("UTC"))).isEqualTo("Yesterday")
    }

    @Test fun episodeItem_groupTitleExtra_presentWhenGiven() {
        val item = BrowseItems.episodeItem(episode(), now, groupTitle = "This week")
        assertThat(extras(item).getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)).isEqualTo("This week")
    }

    @Test fun episodeItem_groupTitleExtra_absentWhenNull() {
        val item = BrowseItems.episodeItem(episode(), now, groupTitle = null)
        assertThat(extras(item).containsKey(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)).isFalse()
    }

    // Row actions and folder styles

    @Test fun episodeItem_hasTheRowActions() {
        val item = BrowseItems.episodeItem(episode(), now)
        assertThat(item.mediaMetadata.supportedCommands).containsExactlyElementsIn(AutoCommands.ROW_ACTIONS).inOrder()
    }

    @Test fun episodeItem_isPlayable_notBrowsable() {
        val item = BrowseItems.episodeItem(episode(), now)
        assertThat(item.mediaMetadata.isPlayable).isTrue()
        assertThat(item.mediaMetadata.isBrowsable).isFalse()
    }

    @Test fun folder_childrenAsGrid_setsGridBrowsable_andListPlayable() {
        val extras = extras(BrowseItems.folder("podcasts", "Podcasts", childrenAsGrid = true))
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    @Test fun folder_default_setsListBrowsable() {
        val extras = extras(BrowseItems.folder("library", "Library"))
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }
}

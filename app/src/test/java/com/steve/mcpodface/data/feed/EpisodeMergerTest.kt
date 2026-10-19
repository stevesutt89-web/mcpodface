package com.steve.mcpodface.data.feed

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeOrigin
import com.steve.mcpodface.data.db.PlayState
import org.junit.Test

class EpisodeMergerTest {
    private val merger = EpisodeMerger()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun parsed(
        guid: String,
        title: String = "Episode $guid",
        pubDate: Long? = now - day,
        url: String = "https://cdn.example.com/$guid.mp3",
        html: String? = "<p>Notes for $guid</p>",
    ) = ParsedEpisode(guid, title, html, pubDate, 1800, url, "audio/mpeg", 1000, null, null, null, null, null)

    private fun stored(
        id: Long,
        guid: String,
        title: String = "Episode $guid",
        pubDate: Long = now - day,
        url: String = "https://cdn.example.com/$guid.mp3",
        origin: EpisodeOrigin = EpisodeOrigin.FEED,
        inCurrentFeed: Boolean = true,
    ) = EpisodeEntity(
        id = id, podcastId = 1, guid = guid, title = title, descriptionHtml = "<p>Notes for $guid</p>",
        descriptionText = "Notes for $guid", summaryText = "Notes for $guid", pubDate = pubDate,
        durationSec = 1800, enclosureUrl = url, enclosureType = "audio/mpeg", enclosureLength = 1000, origin = origin, inCurrentFeed = inCurrentFeed, firstSeenAt = 0,
    )

    private fun live(existing: List<EpisodeEntity>, incoming: List<ParsedEpisode>) =
        merger.merge(1, existing, incoming, EpisodeOrigin.FEED, now)

    @Test fun newEpisodesAreInsertedWithPlainTextAndSummary() {
        val r = live(emptyList(), listOf(parsed("a", html = "<p>Hello &amp; <b>welcome</b></p>")))
        val e = r.toInsert.single()
        assertThat(e.descriptionText).isEqualTo("Hello & welcome")
        assertThat(e.summaryText).isEqualTo("Hello & welcome")
        assertThat(e.origin).isEqualTo(EpisodeOrigin.FEED)
        assertThat(e.inCurrentFeed).isTrue()
        assertThat(e.firstSeenAt).isEqualTo(now)
        assertThat(e.podcastId).isEqualTo(1)
    }

    @Test fun missingPubDateFallsBackToNow() {
        assertThat(live(emptyList(), listOf(parsed("a", pubDate = null))).toInsert.single().pubDate).isEqualTo(now)
    }

    @Test fun matchesByGuid() {
        val r = live(listOf(stored(1, "a")), listOf(parsed("a")))
        assertThat(r.toInsert).isEmpty()
        assertThat(r.toUpdate).isEmpty() // nothing changed
    }

    @Test fun matchesByNormalizedEnclosureWhenGuidChanges() {
        val existing = stored(1, "old-guid", url = "https://cdn.example.com/ep1.mp3?source=rss")
        val incoming = parsed("new-guid", url = "https://dts.podtrac.com/redirect.mp3/cdn.example.com/ep1.mp3")
        val r = live(listOf(existing), listOf(incoming))
        assertThat(r.toInsert).isEmpty()
        assertThat(r.toUpdate.single().guid).isEqualTo("old-guid") // stored guid is kept
    }

    @Test fun matchesByTitleAndSameDay() {
        val existing = stored(1, "g1", title = "The  Big Story", pubDate = now - day, url = "https://a.com/1.mp3")
        val incoming = parsed("g2", title = "the big story", pubDate = now - day + 3_600_000, url = "https://b.com/x.mp3")
        assertThat(live(listOf(existing), listOf(incoming)).toInsert).isEmpty()
    }

    @Test fun sameTitleOnDifferentDayIsANewEpisode() {
        val existing = stored(1, "g1", title = "Mailbag", pubDate = now - 8 * day, url = "https://a.com/1.mp3")
        val incoming = parsed("g2", title = "Mailbag", pubDate = now - day, url = "https://a.com/2.mp3")
        assertThat(live(listOf(existing), listOf(incoming)).toInsert).hasSize(1)
    }

    @Test fun liveFeedUpdatesFeedFieldsButNeverUserState() {
        val existing = stored(1, "a").copy(
            playState = PlayState.IN_PROGRESS, positionMs = 12_345, playedAt = 99, lastPlayedAt = 98,
            isStarred = true, autoDownloadBlocked = true,
        )
        val r = live(listOf(existing), listOf(parsed("a", title = "Renamed", html = "<p>New notes</p>")))
        val u = r.toUpdate.single()
        assertThat(u.title).isEqualTo("Renamed")
        assertThat(u.descriptionText).isEqualTo("New notes")
        assertThat(u.playState).isEqualTo(PlayState.IN_PROGRESS)
        assertThat(u.positionMs).isEqualTo(12_345)
        assertThat(u.playedAt).isEqualTo(99)
        assertThat(u.lastPlayedAt).isEqualTo(98)
        assertThat(u.isStarred).isTrue()
        assertThat(u.autoDownloadBlocked).isTrue()
        assertThat(u.firstSeenAt).isEqualTo(0)
    }

    @Test fun episodesMissingFromLiveFeedAreKeptButMarkedArchived() {
        val r = live(listOf(stored(1, "a"), stored(2, "b")), listOf(parsed("a")))
        assertThat(r.toUpdate.single()).isEqualTo(stored(2, "b").copy(inCurrentFeed = false))
        assertThat(r.toInsert).isEmpty()
    }

    @Test fun archivedEpisodeReturningToFeedIsMarkedCurrentAgain() {
        val r = live(listOf(stored(1, "a", inCurrentFeed = false)), listOf(parsed("a")))
        assertThat(r.toUpdate.single().inCurrentFeed).isTrue()
    }

    @Test fun duplicateItemsInOneFeedInsertOnce() {
        assertThat(live(emptyList(), listOf(parsed("a"), parsed("a"))).toInsert).hasSize(1)
    }

    @Test fun backfillInsertsOnlyUnknownEpisodesAsArchived() {
        val existing = listOf(stored(1, "a"))
        val r = merger.merge(1, existing, listOf(parsed("a", title = "Changed"), parsed("old")), EpisodeOrigin.PODCAST_INDEX, now)
        assertThat(r.toUpdate).isEmpty() // never overwrite live-feed data with backfill data
        val e = r.toInsert.single()
        assertThat(e.guid).isEqualTo("old")
        assertThat(e.origin).isEqualTo(EpisodeOrigin.PODCAST_INDEX)
        assertThat(e.inCurrentFeed).isFalse()
    }

    @Test fun backfillDoesNotArchiveEpisodesItDidNotSee() {
        val r = merger.merge(1, listOf(stored(1, "a")), listOf(parsed("old")), EpisodeOrigin.FEED_PAGED, now)
        assertThat(r.toUpdate).isEmpty()
    }

    @Test fun nullFeedFieldsDoNotEraseStoredValues() {
        val existing = stored(1, "a").copy(durationSec = 1234, artworkUrl = "https://img/1.jpg")
        val incoming = parsed("a").copy(durationSec = null, artworkUrl = null)
        assertThat(live(listOf(existing), listOf(incoming)).toUpdate).isEmpty()
    }
}

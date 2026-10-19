package com.steve.mcpodface.data.feed

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.steve.mcpodface.data.db.FeedType
import java.io.InputStream
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith

/** Real-world feeds (trimmed to channel + 5 items) and hand-written edge cases in resources/feeds. */
@RunWith(AndroidJUnit4::class)
class RssParserFixturesTest {

    private val feedUrl = "https://example.com/feed.xml"

    private fun fixture(name: String): InputStream =
        javaClass.classLoader!!.getResourceAsStream("feeds/$name")!!

    private fun parse(name: String): ParsedFeed = RssParser().parse(fixture(name), feedUrl)

    private fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val realFixtures = listOf(
        "planet_money.xml",
        "changelog.xml",
        "hardcore_history.xml",
        "the_daily.xml",
        "darknet.xml",
        "s_town.xml",
        "dr_death.xml",
        "podcastindex_pc20.xml",
        "truefans_serial_p20.xml",
        "megaphone_42.xml",
    )

    @Test
    fun everyRealFeedParsesWithCompleteEpisodes() {
        for (name in realFixtures) {
            val feed = parse(name)
            assertThat(feed.title).isNotEmpty()
            assertThat(feed.episodes).isNotEmpty()
            assertThat(feed.episodes.size).isAtMost(5)
            feed.episodes.forEach { episode ->
                assertThat(episode.guid).isNotEmpty()
                assertThat(episode.enclosureUrl).startsWith("http")
                assertWithMessage("pubDate of ${episode.guid} in $name").that(episode.pubDate).isNotNull()
            }
        }
    }

    @Test
    fun planetMoneyEpisodicFeed() {
        val feed = parse("planet_money.xml")
        assertThat(feed.title).isEqualTo("Planet Money")
        assertThat(feed.author).isEqualTo("NPR")
        assertThat(feed.feedType).isEqualTo(FeedType.EPISODIC)
        assertThat(feed.websiteUrl).startsWith("https://www.npr.org/podcasts/510289")
        assertThat(feed.nextPageUrl).isNull()
        val first = feed.episodes.first()
        assertThat(first.guid).isEqualTo("515a052e-a76b-4127-a5f4-9c6a9d70c1ac")
        assertThat(first.durationSec).isEqualTo(1929)
        assertThat(first.enclosureType).isEqualTo("audio/mpeg")
        assertThat(first.enclosureLength).isEqualTo(30878911)
    }

    @Test
    fun changelogHasTimestampsAndTranscripts() {
        val feed = parse("changelog.xml")
        assertThat(feed.author).isEqualTo("Changelog Media")
        assertThat(feed.episodes.first().guid).isEqualTo("changelog.com/1/2834")
        assertThat(feed.episodes.first().durationSec).isEqualTo(9771)
        assertThat(feed.episodes.first().chaptersUrl).isEqualTo("https://changelog.com/podcast/686/chapters")
    }

    @Test
    fun hardcoreHistoryUsesUrlGuidsAndZoneName() {
        val feed = parse("hardcore_history.xml")
        assertThat(feed.title).isEqualTo("Dan Carlin's Hardcore History")
        val first = feed.episodes.first()
        // Guid is itself a URL here, so it must survive untouched.
        assertThat(first.guid).isEqualTo("http://traffic.libsyn.com/dancarlinhh/dchha74_Mania_for_Subjugation_IV.mp3")
        assertThat(first.durationSec).isEqualTo(14480)
        // "PST" in pubDate: 10:02:23 PST is 18:02:23 UTC.
        assertThat(first.pubDate).isEqualTo(utc("2026-07-31T18:02:23Z"))
    }

    @Test
    fun darknetDiariesHasVttTranscriptAndDoubleZeroOffset() {
        val feed = parse("darknet.xml")
        assertThat(feed.author).isEqualTo("Jack Rhysider")
        val first = feed.episodes.first()
        assertThat(first.transcriptUrl).endsWith(".vtt")
        assertThat(first.durationSec).isEqualTo(59 * 60 + 17)
        assertThat(first.pubDate).isEqualTo(utc("2026-10-06T07:00:00Z"))
    }

    @Test
    fun theDailyIsEpisodic() {
        val feed = parse("the_daily.xml")
        assertThat(feed.title).isEqualTo("The Daily")
        assertThat(feed.feedType).isEqualTo(FeedType.EPISODIC)
        assertThat(feed.episodes.first().durationSec).isEqualTo(43 * 60 + 39)
    }

    @Test
    fun serialShowsAreSerial() {
        assertThat(parse("s_town.xml").feedType).isEqualTo(FeedType.SERIAL)
        val drDeath = parse("dr_death.xml")
        assertThat(drDeath.feedType).isEqualTo(FeedType.SERIAL)
        assertThat(drDeath.episodes.first().enclosureLength).isEqualTo(8935967)
        assertThat(parse("truefans_serial_p20.xml").feedType).isEqualTo(FeedType.SERIAL)
    }

    @Test
    fun podcastIndexNamespaceChaptersAndTranscripts() {
        val feed = parse("podcastindex_pc20.xml")
        assertThat(feed.title).isEqualTo("Podcasting 2.0")
        assertThat(feed.author).isEqualTo("Podcast Index LLC")
        assertThat(feed.episodes.any { it.chaptersUrl != null }).isTrue()
        assertThat(feed.episodes.any { it.transcriptUrl?.endsWith(".srt") == true }).isTrue()
    }

    @Test
    fun truefansSerialHasVttTranscripts() {
        val feed = parse("truefans_serial_p20.xml")
        assertThat(feed.title).isEqualTo("Podcasting 2.0 in Practice")
        assertThat(feed.episodes.any { it.transcriptUrl?.endsWith(".vtt") == true }).isTrue()
    }

    @Test
    fun megaphoneZeroLengthEnclosureBecomesNull() {
        val first = parse("megaphone_42.xml").episodes.first()
        assertThat(first.enclosureLength).isNull()
        assertThat(first.enclosureUrl).startsWith("https://")
    }

    @Test
    fun missingGuidFallsBackToEnclosureUrl() {
        val episodes = parse("edge_missing_guid.xml").episodes
        assertThat(episodes.map { it.guid })
            .containsExactly("https://cdn.example.com/audio/a.mp3", "https://cdn.example.com/audio/b.mp3")
            .inOrder()
    }

    @Test
    fun mediaContentOnlyFeedUsesMediaUrls() {
        val episodes = parse("edge_media_content_only.xml").episodes
        assertThat(episodes).hasSize(2)
        val first = episodes[0]
        assertThat(first.guid).isEqualTo("media-ep-1")
        assertThat(first.enclosureUrl).isEqualTo("https://cdn.example.com/media/ep1.m4a")
        assertThat(first.enclosureType).isEqualTo("audio/x-m4a")
        assertThat(first.enclosureLength).isEqualTo(5555)
        // Enclosure beats media:content when both are present.
        assertThat(episodes[1].enclosureUrl).isEqualTo("https://cdn.example.com/enclosure/ep2.mp3")
        assertThat(episodes[1].enclosureLength).isEqualTo(777)
    }

    @Test
    fun sloppyDatesParseOrBecomeNull() {
        val dates = parse("edge_sloppy_dates.xml").episodes.map { it.pubDate }
        assertThat(dates).containsExactly(
            utc("2025-06-03T13:00:00Z"), // 3 Jun 25 08:00:00 EST
            utc("2025-06-03T13:00:00Z"), // Tue, 03 Jun 2025 08:00 -0500
            utc("2025-06-03T08:00:00Z"), // ... UT
            utc("2025-06-03T08:00:00Z"), // ISO-8601 with Z
            null, // unparseable
            null, // missing
            utc("2025-06-07T06:15:00Z"), // Fri, 06 Jun 2025 23:15:00 PDT
        ).inOrder()
    }

    @Test
    fun cdataAndEntitiesDecodeCorrectly() {
        val feed = parse("edge_cdata_entities.xml")
        assertThat(feed.title).isEqualTo("CDATA & Entities")
        // CDATA is raw HTML, so its entities are left for the renderer.
        assertThat(feed.descriptionHtml).isEqualTo("<p>Channel <b>HTML</b> description &amp; more</p>")

        val (first, second, third) = feed.episodes
        // CDATA is literal, so &amp; stays as written; content:encoded beats description and summary.
        assertThat(first.title).isEqualTo("Tom &amp; Jerry &lt;3 &#8217;s")
        assertThat(first.descriptionHtml).isEqualTo("<p>Full <strong>content</strong> wins &amp; stays raw</p>")

        assertThat(second.title).isEqualTo("Café & crème — “quoted”")
        assertThat(second.descriptionHtml).isEqualTo("Café <b>bold</b> &amp; 'apostrophe'")

        // Blank title and summary: title becomes empty, description null, episode kept.
        assertThat(third.title).isEmpty()
        assertThat(third.descriptionHtml).isNull()
    }

    @Test
    fun atomNextLinkIsReadAndSelfLinkIgnored() {
        val feed = parse("edge_atom_next.xml")
        assertThat(feed.nextPageUrl).isEqualTo("https://paged.example.com/feed.xml?page=2")
        assertThat(feed.episodes.map { it.guid }).containsExactly("paged-1")
    }

    @Test
    fun itemsWithoutEnclosureAreSkippedAndChannelFallbacksApply() {
        val feed = parse("edge_no_enclosure_and_channel_fallbacks.xml")
        assertThat(feed.title).isEqualTo("Fallback Show")
        assertThat(feed.author).isEqualTo("Managing Editor Name")
        assertThat(feed.descriptionHtml).isEqualTo("Channel summary used when description is missing")
        assertThat(feed.artworkUrl).isEqualTo("https://fallback.example.com/rss-image.jpg")
        assertThat(feed.feedType).isEqualTo(FeedType.SERIAL)

        val episode = feed.episodes.single()
        assertThat(episode.guid).isEqualTo("fb-2")
        assertThat(episode.descriptionHtml).isEqualTo("Item summary fallback")
        assertThat(episode.artworkUrl).isEqualTo("https://fallback.example.com/ep2.jpg")
        assertThat(episode.season).isEqualTo(2)
        assertThat(episode.episodeNumber).isEqualTo(14)
        assertThat(episode.durationSec).isEqualTo(3723)
    }
}

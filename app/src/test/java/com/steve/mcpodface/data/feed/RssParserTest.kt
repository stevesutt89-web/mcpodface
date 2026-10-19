package com.steve.mcpodface.data.feed

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.FeedType
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RssParserTest {

    private val feedUrl = "https://example.com/feed.xml"

    private fun parse(xml: String): ParsedFeed =
        RssParser().parse(xml.byteInputStream(Charsets.UTF_8), feedUrl)

    /** A feed with every namespace this app cares about declared, so tests only write the elements they need. */
    // No indentation inside the template: an XML declaration must be the very first thing in the document.
    private fun feed(channelBody: String = "", items: String = ""): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:media="http://search.yahoo.com/mrss/">
<channel>
<title>Show</title>
$channelBody
$items
</channel>
</rss>"""

    private fun item(body: String): String = "<item>$body</item>"

    private fun enclosure(url: String = "https://cdn.example.com/a.mp3"): String =
        """<enclosure url="$url" length="100" type="audio/mpeg"/>"""

    private fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // Channel

    @Test
    fun channelFieldsAreRead() {
        val result = parse(
            feed(
                channelBody = """
                    <link>https://show.example.com/</link>
                    <description>Plain description</description>
                    <itunes:author>Itunes Author</itunes:author>
                    <managingEditor>ed@example.com (Editor Name)</managingEditor>
                    <itunes:image href="https://show.example.com/art.jpg"/>
                    <itunes:type>serial</itunes:type>
                """.trimIndent(),
            ),
        )
        assertThat(result.title).isEqualTo("Show")
        assertThat(result.author).isEqualTo("Itunes Author")
        assertThat(result.descriptionHtml).isEqualTo("Plain description")
        assertThat(result.artworkUrl).isEqualTo("https://show.example.com/art.jpg")
        assertThat(result.websiteUrl).isEqualTo("https://show.example.com/")
        assertThat(result.feedType).isEqualTo(FeedType.SERIAL)
        assertThat(result.episodes).isEmpty()
    }

    @Test
    fun authorFallsBackToManagingEditorThenAuthor() {
        val managing = parse(feed(channelBody = "<managingEditor>ed@example.com (Ed Name)</managingEditor>"))
        assertThat(managing.author).isEqualTo("Ed Name")

        val managingNoName = parse(feed(channelBody = "<managingEditor>ed@example.com</managingEditor>"))
        assertThat(managingNoName.author).isEqualTo("ed@example.com")

        val author = parse(feed(channelBody = "<author>Plain Author</author>"))
        assertThat(author.author).isEqualTo("Plain Author")

        assertThat(parse(feed()).author).isNull()
    }

    @Test
    fun descriptionFallsBackToItunesSummary() {
        val result = parse(feed(channelBody = "<itunes:summary>From summary</itunes:summary>"))
        assertThat(result.descriptionHtml).isEqualTo("From summary")
    }

    @Test
    fun blankChannelDescriptionBecomesNull() {
        val result = parse(feed(channelBody = "<description>   </description>"))
        assertThat(result.descriptionHtml).isNull()
    }

    @Test
    fun artworkFallsBackToImageUrl() {
        val result = parse(feed(channelBody = "<image><url>https://show.example.com/rss.jpg</url></image>"))
        assertThat(result.artworkUrl).isEqualTo("https://show.example.com/rss.jpg")
    }

    @Test
    fun itunesTypeDecidesFeedType() {
        assertThat(parse(feed(channelBody = "<itunes:type>episodic</itunes:type>")).feedType)
            .isEqualTo(FeedType.EPISODIC)
        assertThat(parse(feed(channelBody = "<itunes:type> SERIAL </itunes:type>")).feedType)
            .isEqualTo(FeedType.SERIAL)
        assertThat(parse(feed()).feedType).isEqualTo(FeedType.EPISODIC)
    }

    @Test
    fun atomNextLinkBecomesNextPageUrlAndSelfLinkIsIgnored() {
        val result = parse(
            feed(
                channelBody = """
                    <atom:link href="https://show.example.com/feed.xml" rel="self"/>
                    <atom:link href="https://show.example.com/feed.xml?page=2" rel="next"/>
                """.trimIndent(),
            ),
        )
        assertThat(result.nextPageUrl).isEqualTo("https://show.example.com/feed.xml?page=2")
    }

    @Test
    fun atomSelfLinkAloneGivesNoNextPage() {
        val result = parse(feed(channelBody = """<atom:link href="https://show.example.com/feed.xml" rel="self"/>"""))
        assertThat(result.nextPageUrl).isNull()
    }

    @Test
    fun missingChannelTitleFallsBackToFeedUrl() {
        val xml = """<rss version="2.0"><channel><description>x</description></channel></rss>"""
        assertThat(parse(xml).title).isEqualTo(feedUrl)
    }

    // Items

    @Test
    fun itemFieldsAreRead() {
        val result = parse(
            feed(
                items = item(
                    """
                    <guid isPermaLink="false">  ep-1  </guid>
                    <title>  Episode One  </title>
                    <pubDate>Tue, 03 Jun 2025 08:00:00 GMT</pubDate>
                    <itunes:duration>1:02:03</itunes:duration>
                    <itunes:season>2</itunes:season>
                    <itunes:episode>14</itunes:episode>
                    <itunes:image href="https://show.example.com/ep1.jpg"/>
                    <enclosure url="https://cdn.example.com/ep1.mp3" length="2048" type="audio/mpeg"/>
                    """.trimIndent(),
                ),
            ),
        )
        val episode = result.episodes.single()
        assertThat(episode.guid).isEqualTo("ep-1")
        assertThat(episode.title).isEqualTo("Episode One")
        assertThat(episode.pubDate).isEqualTo(utc("2025-06-03T08:00:00Z"))
        assertThat(episode.durationSec).isEqualTo(3723)
        assertThat(episode.season).isEqualTo(2)
        assertThat(episode.episodeNumber).isEqualTo(14)
        assertThat(episode.artworkUrl).isEqualTo("https://show.example.com/ep1.jpg")
        assertThat(episode.enclosureUrl).isEqualTo("https://cdn.example.com/ep1.mp3")
        assertThat(episode.enclosureType).isEqualTo("audio/mpeg")
        assertThat(episode.enclosureLength).isEqualTo(2048)
        assertThat(episode.chaptersUrl).isNull()
        assertThat(episode.transcriptUrl).isNull()
    }

    @Test
    fun itemsWithoutEnclosureUrlAreSkipped() {
        val result = parse(
            feed(
                items = item("<guid>text-only</guid><title>No audio</title>") +
                    item("""<guid>empty-url</guid><enclosure url="   " type="audio/mpeg"/>""") +
                    item("<guid>playable</guid>" + enclosure("https://cdn.example.com/p.mp3")),
            ),
        )
        assertThat(result.episodes.map { it.guid }).containsExactly("playable")
    }

    @Test
    fun guidFallsBackToEnclosureUrlWhenMissingOrBlank() {
        val result = parse(
            feed(
                items = item(enclosure("https://cdn.example.com/no-guid.mp3")) +
                    item("<guid>   </guid>" + enclosure("https://cdn.example.com/blank-guid.mp3")),
            ),
        )
        assertThat(result.episodes.map { it.guid })
            .containsExactly("https://cdn.example.com/no-guid.mp3", "https://cdn.example.com/blank-guid.mp3")
            .inOrder()
    }

    @Test
    fun missingTitleIsEmptyAndMissingDateIsNull() {
        val episode = parse(feed(items = item(enclosure()))).episodes.single()
        assertThat(episode.title).isEmpty()
        assertThat(episode.pubDate).isNull()
    }

    @Test
    fun mediaContentIsUsedWhenThereIsNoEnclosure() {
        val result = parse(
            feed(
                items = item(
                    """<guid>m</guid><media:content url="https://cdn.example.com/m.m4a" fileSize="555" type="audio/x-m4a"/>""",
                ),
            ),
        )
        val episode = result.episodes.single()
        assertThat(episode.enclosureUrl).isEqualTo("https://cdn.example.com/m.m4a")
        assertThat(episode.enclosureType).isEqualTo("audio/x-m4a")
        assertThat(episode.enclosureLength).isEqualTo(555)
    }

    @Test
    fun enclosureWinsOverMediaContent() {
        val result = parse(
            feed(
                items = item(
                    enclosure("https://cdn.example.com/enc.mp3") +
                        """<media:content url="https://cdn.example.com/media.mp3"/>""",
                ),
            ),
        )
        assertThat(result.episodes.single().enclosureUrl).isEqualTo("https://cdn.example.com/enc.mp3")
    }

    @Test
    fun mediaContentInsideMediaGroupIsFound() {
        val result = parse(
            feed(
                items = item(
                    """<media:group><media:content url="https://cdn.example.com/g.mp3"/></media:group>""",
                ),
            ),
        )
        assertThat(result.episodes.single().enclosureUrl).isEqualTo("https://cdn.example.com/g.mp3")
    }

    @Test
    fun mediaContentWithoutUrlIsSkipped() {
        val result = parse(feed(items = item("""<media:content type="audio/mpeg"/>""")))
        assertThat(result.episodes).isEmpty()
    }

    @Test
    fun zeroEnclosureLengthBecomesNull() {
        val result = parse(feed(items = item("""<enclosure url="https://cdn.example.com/z.mp3" length="0"/>""")))
        assertThat(result.episodes.single().enclosureLength).isNull()
    }

    @Test
    fun descriptionPrefersContentEncodedThenDescriptionThenSummary() {
        val result = parse(
            feed(
                items = item(
                    enclosure() +
                        "<content:encoded>&lt;p&gt;full&lt;/p&gt;</content:encoded>" +
                        "<description>short</description><itunes:summary>sum</itunes:summary>",
                ) +
                    item(enclosure() + "<description>short</description><itunes:summary>sum</itunes:summary>") +
                    item(enclosure() + "<description>   </description><itunes:summary>sum</itunes:summary>") +
                    item(enclosure() + "<content:encoded/>"),
            ),
        )
        assertThat(result.episodes.map { it.descriptionHtml })
            .containsExactly("<p>full</p>", "short", "sum", null)
            .inOrder()
    }

    @Test
    fun cdataIsReadRawAndEntitiesDecode() {
        val result = parse(
            feed(
                items = item(
                    enclosure() +
                        "<title><![CDATA[Tom &amp; Jerry]]></title>" +
                        "<description>Caf&eacute; &amp; cr&egrave;me &#8217;s &lt;b&gt;x&lt;/b&gt;</description>",
                ),
            ),
        )
        val episode = result.episodes.single()
        assertThat(episode.title).isEqualTo("Tom &amp; Jerry")
        assertThat(episode.descriptionHtml).isEqualTo("Café & crème ’s <b>x</b>")
    }

    @Test
    fun htmlOnlyNamedEntitiesDecodeInText() {
        val result = parse(
            feed(items = item(enclosure() + "<title>Dash&mdash;dot&hellip;nbsp&nbsp;yuml&yuml;</title>")),
        )
        assertThat(result.episodes.single().title).isEqualTo("Dash—dot…nbsp yumlÿ")
    }

    @Test
    fun ampersandEscapedEntityIsNotDoubleDecoded() {
        val result = parse(feed(items = item(enclosure() + "<title>&amp;eacute;</title>")))
        assertThat(result.episodes.single().title).isEqualTo("&eacute;")
    }

    @Test
    fun enclosureUrlAmpersandsAreDecoded() {
        val result = parse(feed(items = item("""<enclosure url="https://cdn.example.com/a.mp3?x=1&amp;y=2"/>""")))
        assertThat(result.episodes.single().enclosureUrl).isEqualTo("https://cdn.example.com/a.mp3?x=1&y=2")
    }

    @Test
    fun durationCanBeSecondsOrClock() {
        val result = parse(
            feed(
                items = item(enclosure() + "<itunes:duration>1234.5</itunes:duration>") +
                    item(enclosure() + "<itunes:duration>junk</itunes:duration>"),
            ),
        )
        assertThat(result.episodes.map { it.durationSec }).containsExactly(1235, null).inOrder()
    }

    @Test
    fun nonNumericSeasonAndEpisodeAreNull() {
        val result = parse(
            feed(items = item(enclosure() + "<itunes:season>Season 2</itunes:season><itunes:episode> 7 </itunes:episode>")),
        )
        val episode = result.episodes.single()
        assertThat(episode.season).isNull()
        assertThat(episode.episodeNumber).isEqualTo(7)
    }

    @Test
    fun chaptersUrlIsRead() {
        val result = parse(
            feed(items = item(enclosure() + """<podcast:chapters url="https://show.example.com/ch.json" type="application/json+chapters"/>""")),
        )
        assertThat(result.episodes.single().chaptersUrl).isEqualTo("https://show.example.com/ch.json")
    }

    @Test
    fun transcriptPrefersVttThenSubripThenJsonThenFirstOther() {
        fun transcripts(vararg types: String?) = parse(
            feed(
                items = item(
                    enclosure() + types.joinToString("") { type ->
                        val typeAttr = type?.let { """ type="$it"""" }.orEmpty()
                        """<podcast:transcript url="https://t.example.com/${type?.replace('/', '-') ?: "none"}"$typeAttr/>"""
                    },
                ),
            ),
        ).episodes.single().transcriptUrl

        assertThat(transcripts("text/html", "application/json", "text/vtt", "application/x-subrip"))
            .isEqualTo("https://t.example.com/text-vtt")
        assertThat(transcripts("text/html", "application/x-subrip", "application/json"))
            .isEqualTo("https://t.example.com/application-x-subrip")
        assertThat(transcripts("text/html", "application/json")).isEqualTo("https://t.example.com/application-json")
        assertThat(transcripts("text/html", "text/plain")).isEqualTo("https://t.example.com/text-html")
        assertThat(transcripts(null, "text/plain")).isEqualTo("https://t.example.com/none")
    }

    @Test
    fun transcriptWithBlankUrlIsIgnored() {
        val result = parse(
            feed(
                items = item(
                    enclosure() +
                        """<podcast:transcript url="  " type="text/vtt"/>""" +
                        """<podcast:transcript url="https://t.example.com/x.txt"/>""",
                ),
            ),
        )
        assertThat(result.episodes.single().transcriptUrl).isEqualTo("https://t.example.com/x.txt")
    }

    // Namespaces

    @Test
    fun namespaceUriVariantsAreAccepted() {
        val xml = """
            <rss version="2.0">
              <channel>
                <title>Variants</title>
                <itunes:author xmlns:itunes="https://www.itunes.com/dtds/podcast-1.0.dtd">Variant Author</itunes:author>
                <item>
                  <guid>v1</guid>
                  <enclosure url="https://cdn.example.com/v1.mp3"/>
                  <pod:chapters xmlns:pod="http://podcastindex.org/namespace/1.0" url="https://show.example.com/c1.json"/>
                  <pod:transcript xmlns:pod="https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md"
                                  url="https://show.example.com/t1.vtt" type="text/vtt"/>
                  <itunes:duration xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">00:30</itunes:duration>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val result = parse(xml)
        assertThat(result.author).isEqualTo("Variant Author")
        val episode = result.episodes.single()
        assertThat(episode.chaptersUrl).isEqualTo("https://show.example.com/c1.json")
        assertThat(episode.transcriptUrl).isEqualTo("https://show.example.com/t1.vtt")
        assertThat(episode.durationSec).isEqualTo(30)
    }

    @Test
    fun elementsInUnrelatedNamespacesAreIgnored() {
        val result = parse(
            feed(
                items = item(
                    enclosure() +
                        """<other:duration xmlns:other="urn:example:other">99999</other:duration>""" +
                        """<other:title xmlns:other="urn:example:other">Wrong</other:title>""" +
                        "<title>Right</title>",
                ),
            ),
        )
        val episode = result.episodes.single()
        assertThat(episode.title).isEqualTo("Right")
        assertThat(episode.durationSec).isNull()
    }

    // Robustness

    @Test
    fun unknownElementsAreSkippedIncludingNestedItems() {
        val result = parse(
            feed(
                channelBody = "<unknown><item><title>not an episode</title>${enclosure()}</item></unknown>",
                items = item(
                    enclosure("https://cdn.example.com/real.mp3") +
                        "<weird><deep><title>Hidden</title></deep></weird>" +
                        "<title>Visible</title>",
                ),
            ),
        )
        assertThat(result.episodes.map { it.enclosureUrl }).containsExactly("https://cdn.example.com/real.mp3")
        assertThat(result.episodes.single().title).isEqualTo("Visible")
    }

    @Test
    fun nestedMarkupInsideTitleIsIgnoredButTextIsKept() {
        val result = parse(feed(items = item(enclosure() + "<title>Real <b>bold</b> title</title>")))
        assertThat(result.episodes.single().title).isEqualTo("Real  title")
    }

    @Test
    fun processingInstructionsCommentsAndDoctypeAreTolerated() {
        val xml = """
            <?xml version="1.0"?>
            <!-- comment -->
            <rss version="2.0"><channel><title>T</title>
            <?pi ignored?>
            <item><guid>g</guid><enclosure url="https://cdn.example.com/g.mp3"/></item>
            </channel></rss>
        """.trimIndent()
        assertThat(parse(xml).episodes.single().guid).isEqualTo("g")
    }

    // Errors

    @Test
    fun nonXmlThrowsFeedParseException() {
        assertThrows(FeedParseException::class.java) { parse("this is not xml at all") }
    }

    @Test
    fun emptyInputThrowsFeedParseException() {
        assertThrows(FeedParseException::class.java) { parse("") }
    }

    @Test
    fun htmlPageThrowsFeedParseException() {
        assertThrows(FeedParseException::class.java) {
            parse("<html><body><p>Not a feed</p></body></html>")
        }
    }

    @Test
    fun atomFeedWithoutChannelThrowsFeedParseException() {
        assertThrows(FeedParseException::class.java) {
            parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>x</title></feed>""")
        }
    }

    @Test
    fun truncatedXmlThrowsFeedParseException() {
        assertThrows(FeedParseException::class.java) { parse("<rss><channel><title>x") }
    }
}

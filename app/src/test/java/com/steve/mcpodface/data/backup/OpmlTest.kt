package com.steve.mcpodface.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Opml parser and writer (task 2.4a). */
@RunWith(AndroidJUnit4::class)
class OpmlTest {

    private fun fixture(name: String): InputStream =
        checkNotNull(javaClass.getResourceAsStream("/opml/$name")) { "missing fixture $name" }

    private fun parseString(xml: String): List<OpmlFeed> =
        Opml.parse(xml.byteInputStream(StandardCharsets.UTF_8))

    private fun opml(body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <head><title>t</title></head>
          <body>
        $body
          </body>
        </opml>
    """.trimIndent()

    // ---- fixtures -------------------------------------------------------

    @Test
    fun pocketCasts_flatOutlines_parsedExactly() {
        assertThat(Opml.parse(fixture("pocketcasts.opml"))).containsExactly(
            OpmlFeed("https://feeds.example.net/quiet-hours.xml", "Quiet Hours"),
            OpmlFeed("https://feeds.example.net/brass-bone.xml", "Brass & Bone"),
            OpmlFeed("https://pods.example.org/tidepools/rss", "Tide Pools"),
            OpmlFeed("https://example.com/kitchen.rss?show=7&fmt=rss", "Kitchen Table Stories"),
        ).inOrder()
    }

    @Test
    fun playerFm_nestedCategories_feedsCollectedInDocumentOrder() {
        assertThat(Opml.parse(fixture("playerfm.opml"))).containsExactly(
            OpmlFeed("https://feeds.example.net/morning-wire.xml", "Morning Wire"),
            OpmlFeed("https://civic.example.org/council.rss", "City Council Live"),
            OpmlFeed("https://comedy.example.com/sketch.xml", "Ten Minute Sketch"),
            OpmlFeed("https://loose.example.com/feed", "Loose Feed"),
        ).inOrder()
    }

    @Test
    fun overcast_titleAndText_titleWins() {
        assertThat(Opml.parse(fixture("overcast.opml"))).containsExactly(
            OpmlFeed("https://feeds.example.net/signal-fires", "Signal Fires"),
            OpmlFeed("https://gardenradio.example.org/rss.xml", "Garden Radio"),
            OpmlFeed("https://askno.example.com/podcast.xml", "Ask Nobody"),
        ).inOrder()
    }

    @Test
    fun edgeFile_withBom_entitiesSchemesDuplicatesAndFolders() {
        assertThat(Opml.parse(fixture("edge_mixed.opml"))).containsExactly(
            OpmlFeed("https://example.com/a?x=1&y=2", "Nested & Entities"),
            OpmlFeed("https://feeds.example.net/feedscheme.xml", "Feed Scheme"),
            OpmlFeed("https://wrapped.example.net/rss", "Feed Wrapped"),
            OpmlFeed("http://insecure.example.net/rss", "Feed Http"),
            OpmlFeed("https://pcast.example.net/show", "Pcast"),
            OpmlFeed("https://itpc.example.net/show.xml", "Itpc"),
            OpmlFeed("https://padded.example.net/feed.xml", "Padded"),
            OpmlFeed("https://lower.example.net/x.xml", "Lower attr"),
            OpmlFeed("https://upper.example.net/y.xml", "Upper attr"),
            OpmlFeed("https://title.example.net/t.xml", "Title wins"),
            OpmlFeed("https://cafe.example.net/c.xml", "Café \"Quoted\""),
            OpmlFeed("https://empty-title.example.net/z.xml", null),
        ).inOrder()
    }

    @Test
    fun emptyBody_validDocument_returnsEmptyList() {
        assertThat(Opml.parse(fixture("empty_body.opml"))).isEmpty()
    }

    // ---- URL handling ---------------------------------------------------

    @Test
    fun duplicates_firstOccurrenceWins() {
        val feeds = parseString(opml("""
            <outline type="rss" text="First" xmlUrl="https://a.example.com/rss"/>
            <outline type="rss" text="Second" xmlUrl="https://b.example.com/rss"/>
            <outline type="rss" text="Again" xmlUrl="https://a.example.com/rss"/>
        """.trimIndent()))
        assertThat(feeds).containsExactly(
            OpmlFeed("https://a.example.com/rss", "First"),
            OpmlFeed("https://b.example.com/rss", "Second"),
        ).inOrder()
    }

    @Test
    fun feedScheme_convertedToHttps() {
        val feeds = parseString(opml("""<outline text="X" xmlUrl="feed://feeds.example.net/x.xml"/>"""))
        assertThat(feeds).containsExactly(OpmlFeed("https://feeds.example.net/x.xml", "X"))
    }

    @Test
    fun feedSchemeWrappingHttps_isUnwrapped() {
        val feeds = parseString(opml("""<outline text="X" xmlUrl="feed://https://wrapped.example.net/rss"/>"""))
        assertThat(feeds).containsExactly(OpmlFeed("https://wrapped.example.net/rss", "X"))
    }

    @Test
    fun pcastAndItpcSchemes_convertedToHttps() {
        val feeds = parseString(opml("""
            <outline text="P" xmlUrl="pcast://p.example.net/show"/>
            <outline text="I" xmlUrl="itpc://i.example.net/show.xml"/>
        """.trimIndent()))
        assertThat(feeds).containsExactly(
            OpmlFeed("https://p.example.net/show", "P"),
            OpmlFeed("https://i.example.net/show.xml", "I"),
        ).inOrder()
    }

    @Test
    fun httpAndHttps_keptAsGiven() {
        val feeds = parseString(opml("""
            <outline text="H" xmlUrl="http://plain.example.net/rss"/>
            <outline text="S" xmlUrl="https://secure.example.net/rss"/>
        """.trimIndent()))
        assertThat(feeds).containsExactly(
            OpmlFeed("http://plain.example.net/rss", "H"),
            OpmlFeed("https://secure.example.net/rss", "S"),
        ).inOrder()
    }

    @Test
    fun urlsAreTrimmed() {
        val feeds = parseString(opml("""<outline text="T" xmlUrl="  https://t.example.net/rss  "/>"""))
        assertThat(feeds).containsExactly(OpmlFeed("https://t.example.net/rss", "T"))
    }

    @Test
    fun blankUrls_areSkipped() {
        val feeds = parseString(opml("""
            <outline text="Blank" xmlUrl=""/>
            <outline text="Spaces" xmlUrl="    "/>
            <outline text="Bare scheme" xmlUrl="feed://"/>
            <outline text="Kept" xmlUrl="https://kept.example.net/rss"/>
        """.trimIndent()))
        assertThat(feeds).containsExactly(OpmlFeed("https://kept.example.net/rss", "Kept"))
    }

    @Test
    fun entitiesInUrlAndTitle_areDecoded() {
        val feeds = parseString(opml(
            """<outline text="Tom &amp; Jerry &lt;3 &#233;" xmlUrl="https://e.example.net/a?x=1&amp;y=2"/>"""
        ))
        assertThat(feeds).containsExactly(
            OpmlFeed("https://e.example.net/a?x=1&y=2", "Tom & Jerry <3 é"),
        )
    }

    // ---- structure ------------------------------------------------------

    @Test
    fun nestedOutlines_areCollectedAtAnyDepth() {
        val feeds = parseString(opml("""
            <outline text="Folder">
              <outline text="Sub">
                <outline text="Deep">
                  <outline type="rss" text="Deepest" xmlUrl="https://deep.example.net/rss"/>
                </outline>
              </outline>
            </outline>
        """.trimIndent()))
        assertThat(feeds).containsExactly(OpmlFeed("https://deep.example.net/rss", "Deepest"))
    }

    @Test
    fun folderWithoutXmlUrl_isNotReturned_butChildrenAre() {
        val feeds = parseString(opml("""
            <outline text="Folder" title="Folder"/>
            <outline text="Folder with child">
              <outline type="rss" text="Child" xmlUrl="https://child.example.net/rss"/>
            </outline>
        """.trimIndent()))
        assertThat(feeds).containsExactly(OpmlFeed("https://child.example.net/rss", "Child"))
    }

    @Test
    fun titleFallsBackToText_whenTitleMissingOrBlank() {
        val feeds = parseString(opml("""
            <outline text="From text" xmlUrl="https://one.example.net/rss"/>
            <outline title="  " text="Text when title blank" xmlUrl="https://two.example.net/rss"/>
        """.trimIndent()))
        assertThat(feeds).containsExactly(
            OpmlFeed("https://one.example.net/rss", "From text"),
            OpmlFeed("https://two.example.net/rss", "Text when title blank"),
        ).inOrder()
    }

    @Test
    fun noTitleOrText_givesNullTitle() {
        val feeds = parseString(opml("""<outline xmlUrl="https://bare.example.net/rss"/>"""))
        assertThat(feeds).containsExactly(OpmlFeed("https://bare.example.net/rss", null))
    }

    @Test
    fun attributeNames_matchCaseInsensitively() {
        val feeds = parseString(opml("""
            <outline text="A" xmlUrl="https://a.example.net/rss"/>
            <outline text="B" xmlurl="https://b.example.net/rss"/>
            <outline text="C" XMLURL="https://c.example.net/rss"/>
            <outline text="D" XmlUrl="https://d.example.net/rss"/>
        """.trimIndent()))
        assertThat(feeds.map { it.feedUrl }).containsExactly(
            "https://a.example.net/rss",
            "https://b.example.net/rss",
            "https://c.example.net/rss",
            "https://d.example.net/rss",
        ).inOrder()
    }

    @Test
    fun outlinesOutsideBody_areIgnored() {
        val xml = """
            <?xml version="1.0"?>
            <opml version="2.0">
              <head><title>t</title><outline text="Head" xmlUrl="https://head.example.net/rss"/></head>
              <body><outline text="Body" xmlUrl="https://body.example.net/rss"/></body>
            </opml>
        """.trimIndent()
        assertThat(parseString(xml)).containsExactly(OpmlFeed("https://body.example.net/rss", "Body"))
    }

    @Test
    fun leadingWhitespaceAndBom_areTolerated() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "\n  \r\n".toByteArray(StandardCharsets.UTF_8) +
            opml("""<outline text="Ws" xmlUrl="https://ws.example.net/rss"/>""").toByteArray(StandardCharsets.UTF_8)
        assertThat(Opml.parse(bytes.inputStream())).containsExactly(OpmlFeed("https://ws.example.net/rss", "Ws"))
    }

    // ---- errors ---------------------------------------------------------

    @Test
    fun notXml_throws() {
        assertThrows(OpmlParseException::class.java) {
            parseString("this is definitely not xml at all")
        }
    }

    @Test
    fun emptyInput_throws() {
        assertThrows(OpmlParseException::class.java) { parseString("") }
    }

    @Test
    fun wrongRootElement_throws() {
        assertThrows(OpmlParseException::class.java) {
            parseString("""<?xml version="1.0"?><rss version="2.0"><channel/></rss>""")
        }
    }

    @Test
    fun missingBody_throws() {
        assertThrows(OpmlParseException::class.java) {
            parseString("""<?xml version="1.0"?><opml version="2.0"><head><title>x</title></head></opml>""")
        }
    }

    @Test
    fun truncatedDocument_throws() {
        assertThrows(OpmlParseException::class.java) {
            parseString("""<?xml version="1.0"?><opml version="2.0"><body><outline text="a" xmlUrl="https://a""")
        }
    }

    @Test
    fun htmlPage_throws() {
        assertThrows(OpmlParseException::class.java) {
            parseString("<!DOCTYPE html><html><head></head><body><p>Sign in</p></body></html>")
        }
    }

    // ---- writer ---------------------------------------------------------

    private fun write(feeds: List<OpmlFeed>, title: String = "McPodface subscriptions"): String {
        val out = ByteArrayOutputStream()
        Opml.write(feeds, out, title)
        return out.toString(StandardCharsets.UTF_8.name())
    }

    @Test
    fun write_emitsDeclarationHeadAndOutlines() {
        val xml = write(listOf(OpmlFeed("https://a.example.net/rss", "Alpha")))
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(xml).contains("<opml version=\"2.0\">")
        assertThat(xml).contains("<title>McPodface subscriptions</title>")
        assertThat(xml).containsMatch("<dateCreated>[^<]+</dateCreated>")
        assertThat(xml).contains(
            "<outline type=\"rss\" text=\"Alpha\" title=\"Alpha\" xmlUrl=\"https://a.example.net/rss\"/>"
        )
    }

    @Test
    fun write_customTitle_isUsedInHead() {
        val xml = write(emptyList(), title = "Backup & <test>")
        assertThat(xml).contains("<title>Backup &amp; &lt;test&gt;</title>")
    }

    @Test
    fun write_nullTitle_usesFeedUrlAsLabel() {
        val xml = write(listOf(OpmlFeed("https://bare.example.net/rss", null)))
        assertThat(xml).contains(
            "text=\"https://bare.example.net/rss\" title=\"https://bare.example.net/rss\""
        )
    }

    @Test
    fun write_escapesAmpersandsAndQuotesInAttributes() {
        val xml = write(listOf(OpmlFeed("https://e.example.net/a?x=1&y=2", "Say \"hi\" & <bye>")))
        assertThat(xml).contains("text=\"Say &quot;hi&quot; &amp; &lt;bye&gt;\"")
        assertThat(xml).contains("xmlUrl=\"https://e.example.net/a?x=1&amp;y=2\"")
    }

    @Test
    fun write_isUtf8_withNonAsciiUnescaped() {
        val out = ByteArrayOutputStream()
        Opml.write(listOf(OpmlFeed("https://c.example.net/rss", "Café Ölçu")), out)
        val text = out.toByteArray().toString(StandardCharsets.UTF_8)
        assertThat(text).contains("Café Ölçu")
    }

    @Test
    fun roundTrip_writeThenParse_returnsSameList() {
        val feeds = listOf(
            OpmlFeed("https://example.com/a?x=1&y=2", "Tom & Jerry"),
            OpmlFeed("https://example.net/b", "Less <than> and \"quotes\""),
            OpmlFeed("https://cafe.example.net/c.xml", "Café Ölçu"),
            OpmlFeed("http://plain.example.net/rss", "Plain http"),
        )
        val out = ByteArrayOutputStream()
        Opml.write(feeds, out)
        assertThat(Opml.parse(out.toByteArray().inputStream())).isEqualTo(feeds)
    }
}

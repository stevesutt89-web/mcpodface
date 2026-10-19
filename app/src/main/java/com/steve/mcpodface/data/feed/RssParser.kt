package com.steve.mcpodface.data.feed

import android.util.Xml
import com.steve.mcpodface.data.db.FeedType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** Parses RSS 2.0 podcast feeds with XmlPullParser (PLAN.md §5). */
class RssParser @Inject constructor() {

    /** @throws FeedParseException if the document is not a usable RSS feed. */
    fun parse(input: InputStream, feedUrl: String): ParsedFeed {
        val bytes = try {
            decodeHtmlEntities(input.readBytes())
        } catch (e: IOException) {
            throw FeedParseException("Could not read feed $feedUrl", e)
        }
        val channel: ChannelAcc? = try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(ByteArrayInputStream(bytes), null)
            readDocument(parser)
        } catch (e: XmlPullParserException) {
            throw FeedParseException("Not a valid RSS feed: $feedUrl", e)
        } catch (e: IOException) {
            throw FeedParseException("Could not read feed $feedUrl", e)
        }
        if (channel == null) throw FeedParseException("No <channel> element in $feedUrl")
        return channel.toParsedFeed(feedUrl)
    }

    private fun readDocument(p: XmlPullParser): ChannelAcc? {
        var event = p.next()
        while (event != XmlPullParser.START_TAG) {
            if (event == XmlPullParser.END_DOCUMENT) return null
            event = p.next()
        }
        var channel: ChannelAcc? = null
        p.children {
            if (channel == null && isElement(NO_NS, "channel")) channel = readChannel() else skipElement()
        }
        return channel
    }

    private fun XmlPullParser.readChannel(): ChannelAcc {
        val acc = ChannelAcc()
        children {
            when {
                isElement(NO_NS, "title") -> acc.title = readText()
                isElement(NO_NS, "link") -> acc.websiteUrl = readText()
                isElement(NO_NS, "description") -> acc.description = readText()
                isElement(NO_NS, "managingEditor") -> acc.managingEditor = readText()
                isElement(NO_NS, "author") -> acc.rssAuthor = readText()
                isElement(NO_NS, "image") -> children {
                    if (isElement(NO_NS, "url")) acc.imageUrl = readText() else skipElement()
                }
                isElement(ITUNES_NS, "author") -> acc.itunesAuthor = readText()
                isElement(ITUNES_NS, "summary") -> acc.itunesSummary = readText()
                isElement(ITUNES_NS, "image") -> {
                    acc.itunesImage = attr("href")
                    skipElement()
                }
                isElement(ITUNES_NS, "type") -> acc.itunesType = readText()
                isElement(ATOM_NS, "link") -> {
                    if (attr("rel") == "next") acc.nextPageUrl = attr("href")
                    skipElement()
                }
                isElement(NO_NS, "item") -> acc.items += readItem()
                else -> skipElement()
            }
        }
        return acc
    }

    private fun XmlPullParser.readItem(): ItemAcc {
        val acc = ItemAcc()
        children {
            when {
                isElement(NO_NS, "guid") -> acc.guid = readText()
                isElement(NO_NS, "title") -> acc.title = readText()
                isElement(NO_NS, "description") -> acc.description = readText()
                isElement(NO_NS, "pubDate") -> acc.pubDate = FeedDates.parse(readText())
                isElement(NO_NS, "enclosure") -> {
                    if (acc.enclosure == null) acc.enclosure = readMedia()
                    skipElement()
                }
                isElement(MEDIA_NS, "content") -> {
                    if (acc.mediaContent == null) acc.mediaContent = readMedia()
                    skipElement()
                }
                isElement(MEDIA_NS, "group") -> children {
                    if (isElement(MEDIA_NS, "content")) {
                        if (acc.mediaContent == null) acc.mediaContent = readMedia()
                        skipElement()
                    } else {
                        skipElement()
                    }
                }
                isElement(CONTENT_NS, "encoded") -> acc.contentEncoded = readText()
                isElement(ITUNES_NS, "summary") -> acc.itunesSummary = readText()
                isElement(ITUNES_NS, "image") -> {
                    acc.itunesImage = attr("href")
                    skipElement()
                }
                isElement(ITUNES_NS, "duration") -> acc.durationSec = FeedDurations.parse(readText())
                isElement(ITUNES_NS, "season") -> acc.season = readText()?.toIntOrNull()
                isElement(ITUNES_NS, "episode") -> acc.episode = readText()?.toIntOrNull()
                isElement(PODCAST_NS, "chapters") -> {
                    if (acc.chaptersUrl == null) acc.chaptersUrl = attr("url")
                    skipElement()
                }
                isElement(PODCAST_NS, "transcript") -> {
                    attr("url")?.let { acc.transcripts += Transcript(it, attr("type")) }
                    skipElement()
                }
                else -> skipElement()
            }
        }
        return acc
    }

    /** Reads url/type/length attributes of the current element (enclosure or media:content). */
    private fun XmlPullParser.readMedia(): Media? {
        val url = attr("url") ?: return null
        val length = (attr("length") ?: attr("fileSize"))?.toLongOrNull()?.takeIf { it > 0 }
        return Media(url, attr("type"), length)
    }

    private fun ChannelAcc.toParsedFeed(feedUrl: String): ParsedFeed = ParsedFeed(
        title = title ?: feedUrl,
        author = itunesAuthor ?: managingEditor?.let { personName(it) } ?: rssAuthor?.let { personName(it) },
        descriptionHtml = description ?: itunesSummary,
        artworkUrl = itunesImage ?: imageUrl,
        websiteUrl = websiteUrl,
        feedType = if (itunesType.equals("serial", ignoreCase = true)) FeedType.SERIAL else FeedType.EPISODIC,
        nextPageUrl = nextPageUrl,
        episodes = items.mapNotNull { it.toEpisode() },
    )

    private fun ItemAcc.toEpisode(): ParsedEpisode? {
        val media = enclosure ?: mediaContent ?: return null
        return ParsedEpisode(
            guid = guid ?: media.url,
            title = title ?: "",
            descriptionHtml = contentEncoded ?: description ?: itunesSummary,
            pubDate = pubDate,
            durationSec = durationSec,
            enclosureUrl = media.url,
            enclosureType = media.type,
            enclosureLength = media.length,
            artworkUrl = itunesImage,
            season = season,
            episodeNumber = episode,
            chaptersUrl = chaptersUrl,
            transcriptUrl = transcripts.minByOrNull { transcriptRank(it.type) }?.url,
        )
    }

    /** Lower rank wins; ties keep feed order because minByOrNull returns the first minimum. */
    private fun transcriptRank(type: String?): Int {
        val index = TRANSCRIPT_PREFERENCE.indexOf(type?.substringBefore(';')?.trim()?.lowercase())
        return if (index < 0) Int.MAX_VALUE else index
    }

    /** RSS author fields are often "email (Name)"; keep the name when present. */
    private fun personName(raw: String): String =
        Regex("""\(([^)]+)\)\s*$""").find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: raw

    private class ChannelAcc {
        var title: String? = null
        var websiteUrl: String? = null
        var description: String? = null
        var managingEditor: String? = null
        var rssAuthor: String? = null
        var itunesAuthor: String? = null
        var itunesSummary: String? = null
        var itunesImage: String? = null
        var imageUrl: String? = null
        var itunesType: String? = null
        var nextPageUrl: String? = null
        val items = mutableListOf<ItemAcc>()
    }

    private class ItemAcc {
        var guid: String? = null
        var title: String? = null
        var description: String? = null
        var contentEncoded: String? = null
        var itunesSummary: String? = null
        var pubDate: Long? = null
        var enclosure: Media? = null
        var mediaContent: Media? = null
        var itunesImage: String? = null
        var durationSec: Int? = null
        var season: Int? = null
        var episode: Int? = null
        var chaptersUrl: String? = null
        val transcripts = mutableListOf<Transcript>()
    }

    private class Media(val url: String, val type: String?, val length: Long?)

    private class Transcript(val url: String, val type: String?)

    private companion object {
        /** Namespace keys are normalised by [nsKey], so scheme, "www." and trailing slashes do not matter. */
        val NO_NS = setOf("")
        val ITUNES_NS = setOf("itunes.com/dtds/podcast-1.0.dtd", "itunes.apple.com/dtds/podcast-1.0.dtd")
        val PODCAST_NS = setOf(
            "podcastindex.org/namespace/1.0",
            "github.com/podcastindex-org/podcast-namespace",
            "github.com/podcastindex-org/podcast-namespace/blob/main/docs/1.0.md",
        )
        val CONTENT_NS = setOf("purl.org/rss/1.0/modules/content")
        val MEDIA_NS = setOf("search.yahoo.com/mrss")
        val ATOM_NS = setOf("w3.org/2005/atom")

        /** Preferred transcript MIME types, best first. Anything else is used only if none of these exist. */
        val TRANSCRIPT_PREFERENCE = listOf("text/vtt", "application/x-subrip", "application/srt", "application/json")

        /** Matches `&name;` where name is not one of the five XML predefined entities. */
        val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

        /** Named HTML entities that XmlPullParser rejects, mapped to code points. */
        val HTML_ENTITIES: Map<String, Int> = buildMap {
            // ISO 8859-1 range 160..255 in code-point order.
            val latin1 = (
                "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg " +
                    "plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 " +
                    "iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml " +
                    "Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash " +
                    "Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring " +
                    "aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve " +
                    "oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
                ).split(" ")
            latin1.forEachIndexed { i, name -> put(name, 160 + i) }
            putAll(
                mapOf(
                    "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376,
                    "fnof" to 402, "circ" to 710, "tilde" to 732, "ensp" to 8194, "emsp" to 8195,
                    "thinsp" to 8201, "zwnj" to 8204, "zwj" to 8205, "lrm" to 8206, "rlm" to 8207,
                    "ndash" to 8211, "mdash" to 8212, "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218,
                    "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222, "dagger" to 8224, "Dagger" to 8225,
                    "bull" to 8226, "hellip" to 8230, "permil" to 8240, "prime" to 8242, "Prime" to 8243,
                    "lsaquo" to 8249, "rsaquo" to 8250, "euro" to 8364, "trade" to 8482, "larr" to 8592,
                    "uarr" to 8593, "rarr" to 8594, "darr" to 8595, "harr" to 8596, "hearts" to 9829,
                ),
            )
        }

        /**
         * Rewrites HTML-only named entities to numeric references so the XML parser can read real-world
         * feeds. Works byte-for-byte through ISO-8859-1, so the document's own encoding is untouched.
         */
        /**
         * Rewrites HTML-only named entities (&eacute;, &nbsp;…) to numeric references so the XML
         * parser accepts them; the five XML entities are left alone. A single pass over the raw
         * bytes: entity names are ASCII, so this is safe for UTF-8 and any ASCII-compatible charset.
         * (A regex over the whole feed took ~3ms/KB on device: 15s for a 5MB feed.)
         */
        fun decodeHtmlEntities(bytes: ByteArray): ByteArray {
            var out: java.io.ByteArrayOutputStream? = null
            var copiedUpTo = 0
            var i = 0
            val n = bytes.size
            while (i < n) {
                if (bytes[i] != AMP) {
                    i++
                    continue
                }
                // Read an entity name: a letter, then letters/digits, up to MAX_ENTITY_NAME, then ';'.
                var j = i + 1
                while (j < n && j - i - 1 < MAX_ENTITY_NAME && isAsciiAlnum(bytes[j], first = j == i + 1)) j++
                if (j == i + 1 || j >= n || bytes[j] != SEMI) {
                    i++
                    continue
                }
                val name = String(bytes, i + 1, j - i - 1, Charsets.US_ASCII)
                val codePoint = if (name in XML_ENTITIES) null else HTML_ENTITIES[name]
                if (codePoint != null) {
                    val o = out ?: java.io.ByteArrayOutputStream(n + 64).also { out = it }
                    o.write(bytes, copiedUpTo, i - copiedUpTo)
                    o.write("&#$codePoint;".toByteArray(Charsets.US_ASCII))
                    copiedUpTo = j + 1
                }
                i = j + 1
            }
            val o = out ?: return bytes
            o.write(bytes, copiedUpTo, n - copiedUpTo)
            return o.toByteArray()
        }

        private const val AMP = '&'.code.toByte()
        private const val SEMI = ';'.code.toByte()
        private const val MAX_ENTITY_NAME = 31

        private fun isAsciiAlnum(b: Byte, first: Boolean): Boolean {
            val c = b.toInt()
            val letter = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
            return if (first) letter else letter || c in '0'.code..'9'.code
        }

        /** Normalises a namespace URI so that common variants compare equal. */
        fun nsKey(ns: String?): String = ns.orEmpty().trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

        fun XmlPullParser.isElement(namespaces: Set<String>, local: String): Boolean =
            name == local && nsKey(namespace) in namespaces

        /** Trimmed attribute value, or null when absent or blank. */
        fun XmlPullParser.attr(name: String): String? =
            getAttributeValue(null, name)?.trim()?.takeIf { it.isNotEmpty() }

        /** Text content of the current element, CDATA included, with nested elements ignored. Consumes the end tag. */
        fun XmlPullParser.readText(): String? {
            val sb = StringBuilder()
            var nested = 0
            while (true) {
                when (next()) {
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (nested == 0) text?.let { sb.append(it) }
                    XmlPullParser.START_TAG -> nested++
                    XmlPullParser.END_TAG -> {
                        if (nested == 0) return sb.toString().trim().takeIf { it.isNotEmpty() }
                        nested--
                    }
                    XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
                }
            }
        }

        /** Skips the current element and everything inside it. Consumes the end tag. */
        fun XmlPullParser.skipElement() {
            var level = 1
            while (level > 0) {
                when (next()) {
                    XmlPullParser.START_TAG -> level++
                    XmlPullParser.END_TAG -> level--
                    XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
                }
            }
        }

        /**
         * Calls [block] for each child element of the current element. [block] must consume the child
         * (readText, skipElement, or children). Returns after the parent's end tag.
         */
        inline fun XmlPullParser.children(block: XmlPullParser.() -> Unit) {
            while (true) {
                when (next()) {
                    XmlPullParser.START_TAG -> block()
                    XmlPullParser.END_TAG -> return
                    XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
                }
            }
        }
    }
}

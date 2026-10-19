package com.steve.mcpodface.data.backup

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class OpmlFeed(val feedUrl: String, val title: String?)

class OpmlParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** OPML 2.0 subscription list: parse (any podcast app's export) and write (McPodface backup). */
object Opml {

    private val PODCAST_SCHEMES = listOf("feed://", "pcast://", "itpc://")

    /** All podcast feeds in the document, de-duplicated by URL, in document order. */
    fun parse(input: InputStream): List<OpmlFeed> {
        val bytes = try {
            input.readBytes()
        } catch (e: IOException) {
            throw OpmlParseException("Could not read OPML input", e)
        }
        val body = skipBomAndWhitespace(bytes)

        val feeds = LinkedHashMap<String, OpmlFeed>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(body), null)

            var event = parser.eventType
            while (event != XmlPullParser.START_TAG) {
                if (event == XmlPullParser.END_DOCUMENT) throw OpmlParseException("No root element")
                event = parser.next()
            }
            if (parser.name != "opml") {
                throw OpmlParseException("Root element is <${parser.name}>, expected <opml>")
            }

            var sawBody = false
            var inBody = false
            event = parser.next()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "body" -> {
                            sawBody = true
                            inBody = true
                        }
                        "outline" -> if (inBody) collectOutline(parser, feeds)
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "body") inBody = false
                }
                event = parser.next()
            }
            if (!sawBody) throw OpmlParseException("No <body> element")
        } catch (e: XmlPullParserException) {
            throw OpmlParseException("Malformed OPML: ${e.message}", e)
        } catch (e: IOException) {
            throw OpmlParseException("Could not read OPML input", e)
        }
        return feeds.values.toList()
    }

    /** A standard OPML 2.0 document listing [feeds]. */
    fun write(feeds: List<OpmlFeed>, out: OutputStream, title: String = "McPodface subscriptions") {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<opml version=\"2.0\">\n")
        sb.append("  <head>\n")
        sb.append("    <title>").append(escape(title)).append("</title>\n")
        sb.append("    <dateCreated>").append(rfc822Now()).append("</dateCreated>\n")
        sb.append("  </head>\n")
        sb.append("  <body>\n")
        for (feed in feeds) {
            val label = feed.title?.takeIf { it.isNotBlank() } ?: feed.feedUrl
            sb.append("    <outline type=\"rss\" text=\"").append(escape(label))
                .append("\" title=\"").append(escape(label))
                .append("\" xmlUrl=\"").append(escape(feed.feedUrl))
                .append("\"/>\n")
        }
        sb.append("  </body>\n")
        sb.append("</opml>\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Reads one `<outline>` start tag; folders (no xmlUrl) and blank URLs are ignored. */
    private fun collectOutline(parser: XmlPullParser, feeds: MutableMap<String, OpmlFeed>) {
        var xmlUrl: String? = null
        var title: String? = null
        var text: String? = null
        for (i in 0 until parser.attributeCount) {
            val value = parser.getAttributeValue(i)
            when (parser.getAttributeName(i).lowercase(Locale.ROOT)) {
                "xmlurl" -> xmlUrl = value
                "title" -> title = value
                "text" -> text = value
            }
        }
        val url = normalizeUrl(xmlUrl ?: return) ?: return
        if (url in feeds) return
        val label = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: text?.trim()?.takeIf { it.isNotEmpty() }
        feeds[url] = OpmlFeed(url, label)
    }

    /** Trims, and maps podcast-app scheme prefixes to https. Returns null for a blank URL. */
    private fun normalizeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        for (scheme in PODCAST_SCHEMES) {
            if (url.regionMatches(0, scheme, 0, scheme.length, ignoreCase = true)) {
                val rest = url.substring(scheme.length)
                if (rest.isBlank()) return null
                val isWeb = rest.startsWith("http://", ignoreCase = true) ||
                    rest.startsWith("https://", ignoreCase = true)
                return if (isWeb) rest else "https://$rest"
            }
        }
        return url
    }

    /** Drops a UTF-8 BOM and leading whitespace (in any order) so the XML declaration is first. */
    private fun skipBomAndWhitespace(bytes: ByteArray): ByteArray {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            when {
                b == 0x20 || b == 0x09 || b == 0x0D || b == 0x0A -> i++
                i + 2 < bytes.size &&
                    bytes[i] == 0xEF.toByte() && bytes[i + 1] == 0xBB.toByte() && bytes[i + 2] == 0xBF.toByte() -> i += 3
                else -> break
            }
        }
        return if (i == 0) bytes else bytes.copyOfRange(i, bytes.size)
    }

    /** Escapes for use in XML text or a double-quoted attribute; drops characters XML 1.0 forbids. */
    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (c in value) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\t' -> sb.append("&#9;")
                '\n' -> sb.append("&#10;")
                '\r' -> sb.append("&#13;")
                else -> if (c.code >= 0x20) sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun rfc822Now(): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
}

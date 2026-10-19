package com.steve.mcpodface.data.feed

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Lenient pubDate parsing for podcast feeds: RFC-822 as it is actually written in the wild,
 * with ISO-8601 as a fallback. Returns epoch millis, or null if the text is blank or unparseable.
 */
object FeedDates {

    private val WHITESPACE = Regex("\\s+")
    private val ISO_START = Regex("^\\d{4}-\\d{2}-\\d{2}")

    /**
     * Optional day name ("Tue,", "Tuesday ") followed by a digit, then
     * day, month name, 2- or 4-digit year, optional time, and whatever is left is the zone.
     */
    private val RFC822 = Regex(
        """^(?:[a-z]+\.?,?\s*(?=\d))?""" +
            """(\d{1,2})[\s-]+([a-z]{3})[a-z]*\.?[\s-]+(\d{4}|\d{2})(?!\d)""" +
            """(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?)?""" +
            """\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val NAMED_ZONES = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0,
        "EST" to -5, "EDT" to -4,
        "CST" to -6, "CDT" to -5,
        "MST" to -7, "MDT" to -6,
        "PST" to -8, "PDT" to -7,
        "BST" to 1,
    ).mapValues { (_, hours) -> hours * 3600 }

    private val NUMERIC_ZONE = Regex("""^(?:GMT|UTC|UT)?([+-])(\d{1,2}):?(\d{2})?$""")

    fun parse(s: String?): Long? {
        val text = s?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() } ?: return null
        return if (ISO_START.containsMatchIn(text)) {
            parseIso(text) ?: parseRfc822(text)
        } else {
            parseRfc822(text) ?: parseIso(text)
        }
    }

    private fun parseRfc822(text: String): Long? {
        val m = RFC822.find(text) ?: return null
        val day = m.groupValues[1].toInt()
        val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
        if (month == 0) return null
        val yearText = m.groupValues[3]
        val year = if (yearText.length == 2) {
            // RFC 2822 pivot: 00-49 is 20xx, 50-99 is 19xx.
            val yy = yearText.toInt()
            if (yy < 50) 2000 + yy else 1900 + yy
        } else {
            yearText.toInt()
        }
        val hour = m.groupValues[4].toIntOrNull() ?: 0
        val minute = m.groupValues[5].toIntOrNull() ?: 0
        val second = m.groupValues[6].toIntOrNull() ?: 0
        val offsetSeconds = zoneOffsetSeconds(m.groupValues[7])
        return try {
            val date = LocalDate.of(year, month, day)
            val time = LocalTime.of(hour, minute, second)
            LocalDateTime.of(date, time).toEpochSecond(ZoneOffset.ofTotalSeconds(offsetSeconds)) * 1000
        } catch (e: DateTimeException) {
            null
        }
    }

    /** Named zones from the RFC plus numeric offsets. Unknown zone names are read as UTC. */
    private fun zoneOffsetSeconds(zone: String): Int {
        val cleaned = zone.replace(Regex("""\(.*?\)"""), "").trim().uppercase()
        if (cleaned.isEmpty()) return 0
        NAMED_ZONES[cleaned]?.let { return it }
        val numeric = NUMERIC_ZONE.find(cleaned) ?: return 0
        val (sign, hours, minutes) = numeric.destructured
        val total = hours.toInt() * 3600 + (minutes.takeIf { it.isNotEmpty() }?.toInt() ?: 0) * 60
        return if (sign == "-") -total else total
    }

    private fun parseIso(text: String): Long? {
        val iso = text.replace(' ', 'T')
        runCatching { return OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(iso).toEpochSecond(ZoneOffset.UTC) * 1000 }
        runCatching { return LocalDate.parse(iso).atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000 }
        return null
    }
}

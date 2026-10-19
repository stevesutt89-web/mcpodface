package com.steve.mcpodface.ui.episode

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import com.steve.mcpodface.ui.theme.AppTheme

private val UNDERLINE = SpanStyle(textDecoration = TextDecoration.Underline)

// Matches H:MM:SS / HH:MM:SS (groups 1-3) and M:SS / MM:SS (groups 4-5). Seconds, and minutes after
// an hour, must be 00-59 so "12:75" never matches. Not part of a longer word or colon run ("1:2:03").
private val TIMESTAMP = Regex("""(?<!\w)(?<!\d:)(?:(\d{1,2}):([0-5]\d):([0-5]\d)|(\d{1,3}):([0-5]\d))\b(?!:\d)""")

// Anything that looks like a URL, so a timestamp inside one (e.g. "...?t=1:02:03") is not tappable.
private val URL = Regex("""(?:[a-zA-Z][a-zA-Z0-9+.-]*://|\bwww\.)\S*|\b[\w-]+(?:\.[\w-]+)+/\S*""")

// "10:30am", "9:15 PM": times of day, not positions in the episode.
private val MERIDIEM = Regex("""^\s*[AaPp][Mm](?![A-Za-z0-9_])""")

private val HTML_TAG = Regex("""<[a-zA-Z/!][^>]*>""")

/**
 * Finds tappable episode positions in show-notes text. Returns each match's character range
 * (inclusive) and its position in milliseconds, in document order.
 */
fun findTimestamps(text: String): List<Pair<IntRange, Long>> {
    if (text.isEmpty()) return emptyList()
    val urlRanges = URL.findAll(text).map { it.range }.toList()
    return TIMESTAMP.findAll(text).mapNotNull { match ->
        val range = match.range
        if (urlRanges.any { it.first <= range.last && range.first <= it.last }) return@mapNotNull null
        if (isTimeOfDay(text, range.last + 1)) return@mapNotNull null
        timestampMillis(match)?.let { range to it }
    }.toList()
}

private fun isTimeOfDay(text: String, afterIndex: Int): Boolean {
    if (afterIndex >= text.length) return false
    val tail = text.substring(afterIndex, minOf(text.length, afterIndex + MAX_MERIDIEM_TAIL))
    return MERIDIEM.containsMatchIn(tail)
}

private const val MAX_MERIDIEM_TAIL = 8

private fun timestampMillis(match: MatchResult): Long? {
    val groups = match.groupValues
    val seconds = if (groups[1].isNotEmpty()) {
        groups[1].toLong() * 3600 + groups[2].toLong() * 60 + groups[3].toLong()
    } else {
        groups[4].toLong() * 60 + groups[5].toLong()
    }
    return seconds * 1000L
}

/**
 * Plain-text notes (no tags at all) are escaped and their line breaks become `<br>`, so
 * [AnnotatedString.fromHtml] renders them as written. Anything with markup is passed through.
 */
internal fun notesToHtml(input: String?): String {
    if (input.isNullOrBlank()) return ""
    if (HTML_TAG.containsMatchIn(input)) return input
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\r\n", "\n")
        .replace("\n", "<br>")
}

/** Link style: underlined, and in [accent] when one is given (the episode page's artwork accent). */
private fun linkStyles(accent: Color?): TextLinkStyles = TextLinkStyles(
    if (accent == null) UNDERLINE else SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
)

/** Show notes as styled text: HTML links plus underlined, tappable timestamps (PLAN.md §8.5). */
internal fun buildShowNotes(html: String?, accent: Color? = null, onTimestamp: (Long) -> Unit): AnnotatedString {
    if (html.isNullOrBlank()) return AnnotatedString("")
    val styles = linkStyles(accent)
    val base = AnnotatedString.fromHtml(
        htmlString = notesToHtml(html),
        linkStyles = styles,
    )
    val timestamps = findTimestamps(base.text)
    if (timestamps.isEmpty()) return base
    // A timestamp already inside an HTML link keeps the link; don't stack a second one on it.
    val linkRanges = base.getLinkAnnotations(0, base.length).map { it.start..it.end - 1 }
    return buildAnnotatedString {
        append(base)
        for ((range, millis) in timestamps) {
            if (linkRanges.any { it.first <= range.last && range.first <= it.last }) continue
            addLink(
                LinkAnnotation.Clickable(tag = "timestamp", styles = styles) {
                    onTimestamp(millis)
                },
                start = range.first,
                end = range.last + 1,
            )
        }
    }
}

/** Renders episode show notes. Links open in the browser; timestamps call [onTimestampClick] with milliseconds. */
@Composable
fun ShowNotes(
    html: String?,
    onTimestampClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val currentOnClick = rememberUpdatedState(onTimestampClick)
    val notes = remember(html, accent) { buildShowNotes(html, accent) { millis -> currentOnClick.value(millis) } }
    Text(
        text = notes,
        style = MaterialTheme.typography.bodyLarge,
        color = AppTheme.colors.textPrimary,
        modifier = modifier,
    )
}

package com.steve.mcpodface.ui.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^<>]*>")
private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’",
    "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
)

/**
 * Turns an FTS `snippet()` (PLAN.md §7.3) into display text. Text inside `<b>…</b>` is bold with
 * no colour, which keeps the low-colour look. Other tags are dropped, and basic HTML entities are
 * decoded. Entities are decoded after tags are handled, so `&lt;b&gt;` stays literal text.
 */
internal fun snippetToAnnotatedString(snippet: String): AnnotatedString = buildAnnotatedString {
    var boldDepth = 0
    fun appendText(raw: String) {
        if (raw.isEmpty()) return
        val text = decodeHtmlEntities(raw)
        if (boldDepth > 0) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }
        } else {
            append(text)
        }
    }
    var last = 0
    for (match in TAG.findAll(snippet)) {
        appendText(snippet.substring(last, match.range.first))
        if (match.groupValues[2].equals("b", ignoreCase = true)) {
            boldDepth = if (match.groupValues[1] == "/") (boldDepth - 1).coerceAtLeast(0) else boldDepth + 1
        }
        last = match.range.last + 1
    }
    appendText(snippet.substring(last))
}

/** Decodes the named entities we expect in show notes, plus numeric character references. */
internal fun decodeHtmlEntities(text: String): String = ENTITY.replace(text) { match ->
    val body = match.groupValues[1]
    when {
        body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let { codePointString(it) }
        body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { codePointString(it) }
        else -> NAMED_ENTITIES[body]
    } ?: match.value
}

private fun codePointString(codePoint: Int): String? =
    if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null

/** "128 episodes", "1 episode", or "2,000+ episodes" when the search hit its cap (PLAN.md §7.3). */
internal fun episodeCountLabel(totalCount: Int, capped: Boolean): String = when {
    capped -> String.format("%,d+ episodes", totalCount)
    totalCount == 1 -> "1 episode"
    else -> String.format("%,d episodes", totalCount)
}

/**
 * Converts the picker's selection (UTC midnights from Material's DateRangePicker) into the
 * [publishedFrom, publishedTo) epoch-millis window that [com.steve.mcpodface.data.search.SearchFilters]
 * expects. Both ends are whole local days in [zone], and the end day is included.
 */
internal fun customRangeBounds(startUtcMillis: Long, endUtcMillis: Long, zone: ZoneId): Pair<Long, Long> {
    val first = Instant.ofEpochMilli(minOf(startUtcMillis, endUtcMillis)).atZone(ZoneOffset.UTC).toLocalDate()
    val last = Instant.ofEpochMilli(maxOf(startUtcMillis, endUtcMillis)).atZone(ZoneOffset.UTC).toLocalDate()
    val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return from to to
}

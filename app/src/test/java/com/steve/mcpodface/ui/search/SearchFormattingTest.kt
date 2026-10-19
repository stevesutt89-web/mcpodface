package com.steve.mcpodface.ui.search

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class SearchFormattingTest {

    /** The text and the bold ranges, so each test can state exactly what is bold. */
    private fun bold(snippet: String) = snippetToAnnotatedString(snippet).let { annotated ->
        val ranges = annotated.spanStyles
            .filter { it.item.fontWeight == FontWeight.Bold }
            .map { it.start until it.end }
            .map { annotated.text.substring(it.first, it.last + 1) }
        annotated.text to ranges
    }

    @Test fun boldMarkersBecomeBoldSpansWithTheirTextRemoved() {
        val (text, boldParts) = bold("a <b>climate</b> change in <b>Mars</b>")
        assertThat(text).isEqualTo("a climate change in Mars")
        assertThat(boldParts).containsExactly("climate", "Mars").inOrder()
    }

    @Test fun boldSpansCarryNoColour() {
        val annotated = snippetToAnnotatedString("<b>x</b>")
        assertThat(annotated.spanStyles.map { it.item }).containsExactly(SpanStyle(fontWeight = FontWeight.Bold))
    }

    @Test fun plainSnippetHasNoSpans() {
        val annotated = snippetToAnnotatedString("just text")
        assertThat(annotated.text).isEqualTo("just text")
        assertThat(annotated.spanStyles).isEmpty()
    }

    @Test fun entitiesAreDecodedInTextAndInsideBold() {
        val (text, boldParts) = bold("Tom &amp; Jerry <b>&quot;rock&quot;</b> &#39;x&#39; &#x41;")
        assertThat(text).isEqualTo("Tom & Jerry \"rock\" 'x' A")
        assertThat(boldParts).containsExactly("\"rock\"")
    }

    @Test fun escapedAngleBracketsAreTextNotTags() {
        val (text, boldParts) = bold("&lt;b&gt;literal&lt;/b&gt; <b>real</b>")
        assertThat(text).isEqualTo("<b>literal</b> real")
        assertThat(boldParts).containsExactly("real")
    }

    @Test fun otherTagsAreDroppedAndUnknownEntitiesKept() {
        val (text, _) = bold("<i>slanted</i> &madeup; &amp")
        assertThat(text).isEqualTo("slanted &madeup; &amp")
    }

    @Test fun unbalancedClosingTagDoesNotGoNegative() {
        val (text, boldParts) = bold("x</b> <b>y")
        assertThat(text).isEqualTo("x y")
        assertThat(boldParts).containsExactly("y")
    }

    @Test fun countLabels() {
        assertThat(episodeCountLabel(0, capped = false)).isEqualTo("0 episodes")
        assertThat(episodeCountLabel(1, capped = false)).isEqualTo("1 episode")
        assertThat(episodeCountLabel(128, capped = false)).isEqualTo("128 episodes")
        assertThat(episodeCountLabel(2_000, capped = true)).isEqualTo("2,000+ episodes")
    }

    @Test fun customRangeCoversWholeLocalDaysInclusively() {
        val utc = ZoneId.of("UTC")
        // Material's picker hands back UTC midnights for the first and last selected day.
        val start = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 3, 3, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val (from, to) = customRangeBounds(start, end, utc)
        assertThat(from).isEqualTo(ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, utc).toInstant().toEpochMilli())
        // Exclusive end: the start of the day after the last selected day.
        assertThat(to).isEqualTo(ZonedDateTime.of(2026, 3, 4, 0, 0, 0, 0, utc).toInstant().toEpochMilli())
    }

    @Test fun customRangeUsesTheLocalZoneForDayBoundaries() {
        val newYork = ZoneId.of("America/New_York")
        val day = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val (from, to) = customRangeBounds(day, day, newYork)
        assertThat(from).isEqualTo(ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, newYork).toInstant().toEpochMilli())
        // 1 March 2026 is not a daylight-saving change in New York, so the window is exactly one day.
        assertThat(to - from).isEqualTo(24L * 60 * 60 * 1000)
    }

    @Test fun reversedSelectionIsNormalised() {
        val utc = ZoneId.of("UTC")
        val a = ZonedDateTime.of(2026, 5, 10, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        val b = ZonedDateTime.of(2026, 5, 2, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertThat(customRangeBounds(a, b, utc)).isEqualTo(customRangeBounds(b, a, utc))
    }
}

package com.steve.mcpodface.ui.episode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShowNotesTest {

    private fun millis(text: String): List<Long> = findTimestamps(text).map { it.second }

    @Test fun hourMinuteSecondsConvertsToMillis() {
        assertThat(findTimestamps("at 1:02:03 we")).isEqualTo(listOf(3..9 to 3_723_000L))
    }

    @Test fun twoDigitHourIsAccepted() {
        assertThat(millis("starts 10:02:03")).containsExactly(36_123_000L)
    }

    @Test fun minuteSecondsConvertsToMillis() {
        assertThat(findTimestamps("12:34")).isEqualTo(listOf(0..4 to 754_000L))
    }

    @Test fun zeroMinutesIsAccepted() {
        assertThat(millis("0:45")).containsExactly(45_000L)
    }

    @Test fun timestampInsideSentenceIsFound() {
        assertThat(findTimestamps("Then at 5:07 in a sentence")).isEqualTo(listOf(8..11 to 307_000L))
    }

    @Test fun invalidSecondsAreIgnored() {
        assertThat(findTimestamps("12:75")).isEmpty()
        assertThat(findTimestamps("1:60")).isEmpty()
        assertThat(findTimestamps("1:02:60")).isEmpty()
    }

    @Test fun invalidMinutesAfterHourAreIgnored() {
        assertThat(findTimestamps("1:60:00")).isEmpty()
    }

    @Test fun timesOfDayWithAmOrPmAreExcluded() {
        assertThat(findTimestamps("meet at 10:30am tomorrow")).isEmpty()
        assertThat(findTimestamps("call at 9:15 PM")).isEmpty()
        assertThat(findTimestamps("call at 9:15 pm.")).isEmpty()
    }

    @Test fun wordsAfterTheTimeAreNotMistakenForMeridiem() {
        assertThat(millis("at 9:15 amazing")).containsExactly(555_000L)
    }

    @Test fun timestampInsideUrlIsIgnored() {
        assertThat(findTimestamps("see https://example.com/watch?t=12:34 for it")).isEmpty()
        assertThat(findTimestamps("www.example.com/clip?t=1:02:03")).isEmpty()
        assertThat(findTimestamps("example.com/clip?t=5:07")).isEmpty()
    }

    @Test fun timestampAfterUrlIsStillFound() {
        assertThat(millis("see https://example.com/a then 3:21")).containsExactly(201_000L)
    }

    @Test fun adjacentPunctuationDoesNotStopMatch() {
        assertThat(findTimestamps("(12:34)")).isEqualTo(listOf(1..5 to 754_000L))
        assertThat(millis("ends at 12:34.")).containsExactly(754_000L)
        assertThat(millis("12:34: the bit about bridges")).containsExactly(754_000L)
    }

    @Test fun multipleTimestampsOnOneLineAreAllFoundInOrder() {
        assertThat(millis("Intro 0:45, part two 12:34 and 1:02:03."))
            .containsExactly(45_000L, 754_000L, 3_723_000L).inOrder()
    }

    @Test fun partOfALongerNumberOrColonRunIsIgnored() {
        assertThat(findTimestamps("1:2:03")).isEmpty()
        assertThat(findTimestamps("ab12:34")).isEmpty()
        assertThat(findTimestamps("12:345")).isEmpty()
        assertThat(findTimestamps("Ratio 3:2")).isEmpty()
    }

    @Test fun emptyAndPlainTextHaveNoTimestamps() {
        assertThat(findTimestamps("")).isEmpty()
        assertThat(findTimestamps("No times here.")).isEmpty()
    }

    @Test fun plainTextNotesBecomeEscapedHtmlWithLineBreaks() {
        assertThat(notesToHtml("a < b & c\nnext")).isEqualTo("a &lt; b &amp; c<br>next")
    }

    @Test fun notesWithTagsPassThroughUnchanged() {
        val html = "<p>Hi</p>\n<p>there</p>"
        assertThat(notesToHtml(html)).isEqualTo(html)
    }

    @Test fun blankNotesBecomeEmptyHtml() {
        assertThat(notesToHtml(null)).isEmpty()
        assertThat(notesToHtml("  ")).isEmpty()
    }
}

package com.steve.mcpodface.data.transcripts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranscriptParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("transcripts/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test fun parsesSrtCuesWithTimesAndMultilineText() {
        val segments = TranscriptParser.parse(TranscriptFormat.SRT, fixture("sample.srt"))

        assertThat(segments.map { it.startMs }).containsExactly(0L, 4_500L, 62_000L, 3_600_000L).inOrder()
        assertThat(segments.map { it.endMs }).containsExactly(4_500L, 9_250L, 65_000L, 3_602_000L).inOrder()
        assertThat(segments[1].text).isEqualTo("Today we follow the bridge crew. Across the river.")
        // Inline markup is stripped and entities decoded; the numeric index lines are not text.
        assertThat(segments[2].text).isEqualTo("Stone is heavier than it looks.")
        assertThat(segments[3].text).isEqualTo("An hour in & still going.")
        assertThat(segments.all { it.speaker == null }).isTrue()
    }

    @Test fun parsesWebVttSpeakersAndSkipsNotesAndEmptyCues() {
        val segments = TranscriptParser.parse(TranscriptFormat.VTT, fixture("sample.vtt"))

        // The NOTE block mentions "-->" but has no timing; the last cue has no text.
        assertThat(segments).hasSize(2)
        assertThat(segments[0]).isEqualTo(TranscriptSegment(0L, 4_500L, "Host", "Welcome back to the show."))
        assertThat(segments[1]).isEqualTo(TranscriptSegment(4_500L, 9_250L, "Guest", "We walked the banks every morning."))
    }

    @Test fun parsesPodcastingJsonSegmentsLeniently() {
        val segments = TranscriptParser.parse(TranscriptFormat.JSON, fixture("sample.json"))

        assertThat(segments).containsExactly(
            TranscriptSegment(0L, 4_500L, "Host", "Welcome back to the show."),
            TranscriptSegment(4_500L, 9_250L, null, "Today we follow the bridge crew."),
            TranscriptSegment(12_000L, null, "Guest", "A string time."),
            // Empty body and missing body are dropped; a segment without startTime is kept untimed.
            TranscriptSegment(null, 30_000L, null, "Untimed, but kept."),
        ).inOrder()
    }

    @Test fun malformedJsonGivesNoSegments() {
        assertThat(TranscriptParser.parse(TranscriptFormat.JSON, "{\"segments\": [")).isEmpty()
        assertThat(TranscriptParser.parse(TranscriptFormat.JSON, "{\"other\": []}")).isEmpty()
    }

    @Test fun htmlBecomesOneUntimedBlockWithoutScriptOrHead() {
        val segments = TranscriptParser.parse(TranscriptFormat.HTML, fixture("plain.html"))

        assertThat(segments).hasSize(1)
        assertThat(segments.single().startMs).isNull()
        assertThat(segments.single().text).isEqualTo(
            "Episode 12 transcript Welcome back & thanks for listening. " +
                "Second paragraph with 'quotes'. A new line.",
        )
    }

    @Test fun plainTextIsOneUntimedBlock() {
        val segments = TranscriptParser.parse(TranscriptFormat.TEXT, "  First line.\n\n  Second   line.  \n")

        assertThat(segments).containsExactly(TranscriptSegment(null, null, null, "First line. Second line."))
    }

    @Test fun blankTextGivesNoSegments() {
        assertThat(TranscriptParser.parse(TranscriptFormat.TEXT, "  \n ")).isEmpty()
    }

    @Test fun detectUsesContentTypeFirst() {
        assertThat(TranscriptParser.detect("application/json; charset=utf-8", "https://e.com/t.srt", "x")).isEqualTo(TranscriptFormat.JSON)
        assertThat(TranscriptParser.detect("text/vtt", "https://e.com/t.txt", "x")).isEqualTo(TranscriptFormat.VTT)
        assertThat(TranscriptParser.detect("application/x-subrip", "https://e.com/t", "x")).isEqualTo(TranscriptFormat.SRT)
        assertThat(TranscriptParser.detect("text/html", "https://e.com/t.vtt", "x")).isEqualTo(TranscriptFormat.HTML)
    }

    @Test fun detectFallsBackToExtensionThenContent() {
        assertThat(TranscriptParser.detect("text/plain", "https://e.com/t.srt?token=1", "x")).isEqualTo(TranscriptFormat.SRT)
        assertThat(TranscriptParser.detect(null, "https://e.com/transcript.VTT", "x")).isEqualTo(TranscriptFormat.VTT)
        assertThat(TranscriptParser.detect(null, "https://e.com/t", "WEBVTT\n\n00:00.000 --> 00:01.000\nHi")).isEqualTo(TranscriptFormat.VTT)
        assertThat(TranscriptParser.detect(null, "https://e.com/t", "1\n00:00:00,000 --> 00:00:01,000\nHi")).isEqualTo(TranscriptFormat.SRT)
        assertThat(TranscriptParser.detect(null, "https://e.com/t", "{\"segments\":[]}")).isEqualTo(TranscriptFormat.JSON)
        assertThat(TranscriptParser.detect(null, "https://e.com/t", "<p>Hi</p>")).isEqualTo(TranscriptFormat.HTML)
        assertThat(TranscriptParser.detect(null, "https://e.com/t", "Just some words.")).isEqualTo(TranscriptFormat.TEXT)
    }

    @Test fun segmentAtPositionIsTheOneBeingSpoken() {
        val segments = TranscriptParser.parse(TranscriptFormat.SRT, fixture("sample.srt"))

        assertThat(segmentIndexAt(segments, 0L)).isEqualTo(0)
        assertThat(segmentIndexAt(segments, 4_499L)).isEqualTo(0)
        assertThat(segmentIndexAt(segments, 4_500L)).isEqualTo(1)
        assertThat(segmentIndexAt(segments, 64_000L)).isEqualTo(2)
    }

    @Test fun noSegmentIsCurrentInAGapOrBeforeTheFirstOrWithoutTiming() {
        val gapped = listOf(
            TranscriptSegment(1_000L, 2_000L, null, "One"),
            TranscriptSegment(5_000L, 6_000L, null, "Two"),
        )

        assertThat(segmentIndexAt(gapped, 500L)).isNull()
        assertThat(segmentIndexAt(gapped, 2_500L)).isNull()
        assertThat(segmentIndexAt(gapped, 5_500L)).isEqualTo(1)
        assertThat(segmentIndexAt(gapped, 6_500L)).isNull()
        assertThat(segmentIndexAt(listOf(TranscriptSegment(null, null, null, "Plain")), 100L)).isNull()
        assertThat(segmentIndexAt(emptyList(), 100L)).isNull()
    }
}

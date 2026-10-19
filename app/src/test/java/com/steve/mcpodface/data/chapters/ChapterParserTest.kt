package com.steve.mcpodface.data.chapters

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("chapters/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test fun parsesVisibleChaptersSortedByStart() {
        val chapters = ChapterParser.parse(fixture("chapters.json"))

        // Kept: 0, 95.5, 412 and "toc": "true" at 3723. Dropped: toc false, no/blank title, bad or negative start.
        assertThat(chapters.map { it.title }).containsExactly(
            "Cold open",
            "The first survey",
            "Steel, rivets and a storm",
            "Closing thoughts",
        ).inOrder()
        assertThat(chapters.map { it.startMs }).containsExactly(0L, 95_500L, 412_000L, 3_723_000L).inOrder()
    }

    @Test fun keepsImageAndLinkWhenPresent() {
        val storm = ChapterParser.parse(fixture("chapters.json")).single { it.title.startsWith("Steel") }

        assertThat(storm.imageUrl).isEqualTo("https://example.com/storm.jpg")
        assertThat(storm.url).isEqualTo("https://example.com/storm")
        assertThat(ChapterParser.parse(fixture("chapters.json")).first().imageUrl).isNull()
    }

    @Test fun malformedJsonGivesNoChapters() {
        assertThat(ChapterParser.parseOrNull("{\"chapters\": [{\"startTime\": 0, \"title\": ")).isNull()
        assertThat(ChapterParser.parse("<html>Not found</html>")).isEmpty()
    }

    @Test fun documentWithoutChaptersArrayIsNotAChaptersDocument() {
        assertThat(ChapterParser.parseOrNull("{\"version\":\"1.2.0\"}")).isNull()
        assertThat(ChapterParser.parseOrNull("{\"chapters\":{\"startTime\":0}}")).isNull()
        assertThat(ChapterParser.parseOrNull("[]")).isNull()
    }

    @Test fun emptyChaptersArrayIsAValidEmptyList() {
        assertThat(ChapterParser.parseOrNull("{\"chapters\":[]}")).isEmpty()
    }

    @Test fun fractionalAndStringStartTimesAreAccepted() {
        val chapters = ChapterParser.parse(
            """{"chapters":[{"startTime":"1.25","title":"Text"},{"startTime":2.0006,"title":"Fraction"}]}""",
        )

        assertThat(chapters.map { it.startMs }).containsExactly(1_250L, 2_001L).inOrder()
    }

    @Test fun tocFalseAsStringIsHidden() {
        val chapters = ChapterParser.parse("""{"chapters":[{"startTime":0,"title":"A","toc":"false"}]}""")

        assertThat(chapters).isEmpty()
    }
}

class ChapterMathTest {

    private val chapters = listOf(
        Chapter(startMs = 0L, title = "Intro"),
        Chapter(startMs = 60_000L, title = "Middle"),
        Chapter(startMs = 120_000L, title = "End"),
    )

    @Test fun currentChapterIsTheLastOneStartedAtOrBeforePosition() {
        assertThat(chapterIndexAt(chapters, 0L)).isEqualTo(0)
        assertThat(chapterIndexAt(chapters, 59_999L)).isEqualTo(0)
        assertThat(chapterIndexAt(chapters, 60_000L)).isEqualTo(1)
        assertThat(chapterIndexAt(chapters, 999_000L)).isEqualTo(2)
    }

    @Test fun noCurrentChapterBeforeTheFirstOrWithNoChapters() {
        val late = listOf(Chapter(startMs = 5_000L, title = "Late start"))

        assertThat(chapterIndexAt(late, 4_000L)).isNull()
        assertThat(chapterIndexAt(emptyList(), 4_000L)).isNull()
    }

    @Test fun previousRestartsTheChapterWhenFarInAndGoesBackWhenNear() {
        // 10 s into "Middle": restart it.
        assertThat(previousChapterStartMs(chapters, 70_000L)).isEqualTo(60_000L)
        // 1 s into "Middle": go to "Intro".
        assertThat(previousChapterStartMs(chapters, 61_000L)).isEqualTo(0L)
        // Within the first chapter, previous stays at its start.
        assertThat(previousChapterStartMs(chapters, 1_000L)).isEqualTo(0L)
    }

    @Test fun previousIsNullBeforeTheFirstChapter() {
        val late = listOf(Chapter(startMs = 5_000L, title = "Late start"))

        assertThat(previousChapterStartMs(late, 1_000L)).isNull()
    }

    @Test fun nextGoesToTheFollowingChapterAndEndsAtTheLast() {
        assertThat(nextChapterStartMs(chapters, 10_000L)).isEqualTo(60_000L)
        assertThat(nextChapterStartMs(chapters, 60_000L)).isEqualTo(120_000L)
        assertThat(nextChapterStartMs(chapters, 130_000L)).isNull()
        assertThat(nextChapterStartMs(emptyList(), 0L)).isNull()
    }
}

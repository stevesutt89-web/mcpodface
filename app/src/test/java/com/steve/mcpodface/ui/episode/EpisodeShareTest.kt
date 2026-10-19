package com.steve.mcpodface.ui.episode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeShareTest {

    private val link = "https://example.com/a.mp3"

    @Test fun withoutPositionIsTitleDashPodcastThenLink() {
        assertThat(buildEpisodeShareText("Bridges", "Sample Show", link, positionMs = null))
            .isEqualTo("Bridges — Sample Show\nhttps://example.com/a.mp3")
    }

    @Test fun zeroPositionIsTreatedAsNoPosition() {
        assertThat(buildEpisodeShareText("Bridges", "Sample Show", link, positionMs = 0L))
            .isEqualTo("Bridges — Sample Show\nhttps://example.com/a.mp3")
    }

    @Test fun positionAddsTheTimeToTheTextAndTheLinkFragment() {
        assertThat(buildEpisodeShareText("Bridges", "Sample Show", link, positionMs = 754_000L))
            .isEqualTo("Bridges — Sample Show (at 12:34)\nhttps://example.com/a.mp3#t=12:34")
    }

    @Test fun hourLongPositionUsesHoursAndMinutes() {
        assertThat(buildEpisodeShareText("Long", "Show", link, positionMs = 3_723_000L))
            .isEqualTo("Long — Show (at 1:02:03)\nhttps://example.com/a.mp3#t=1:02:03")
    }

    @Test fun linkWithAFragmentGetsTheTimeInTheQueryBeforeIt() {
        assertThat(withTimestamp("https://example.com/ep?id=7#comments", "0:30"))
            .isEqualTo("https://example.com/ep?id=7&t=0:30#comments")
        assertThat(withTimestamp("https://example.com/ep#comments", "0:30"))
            .isEqualTo("https://example.com/ep?t=0:30#comments")
    }

    @Test fun linkWithAQueryButNoFragmentGetsAFragment() {
        assertThat(withTimestamp("https://example.com/ep?id=7", "0:30"))
            .isEqualTo("https://example.com/ep?id=7#t=0:30")
    }
}

package com.steve.mcpodface.ui.addpodcast

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AddByUrlDialogTest {
    @Test fun blankInputIsNull() {
        assertThat(normalizeFeedUrl("")).isNull()
        assertThat(normalizeFeedUrl("   ")).isNull()
    }

    @Test fun missingSchemeGetsHttps() {
        assertThat(normalizeFeedUrl("example.com/feed.xml")).isEqualTo("https://example.com/feed.xml")
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertThat(normalizeFeedUrl("  https://x.example/a.xml \n")).isEqualTo("https://x.example/a.xml")
    }

    @Test fun explicitHttpIsKept() {
        assertThat(normalizeFeedUrl("http://insecure.example/feed")).isEqualTo("http://insecure.example/feed")
    }

    @Test fun podcastAppSchemesBecomeHttps() {
        assertThat(normalizeFeedUrl("feed://example.com/rss")).isEqualTo("https://example.com/rss")
        assertThat(normalizeFeedUrl("PCAST://example.com/rss")).isEqualTo("https://example.com/rss")
        assertThat(normalizeFeedUrl("itpc://example.com/rss")).isEqualTo("https://example.com/rss")
    }

    @Test fun podcastAppSchemeWrappingAWebUrlIsUnwrapped() {
        assertThat(normalizeFeedUrl("pcast://https://example.com/f.xml")).isEqualTo("https://example.com/f.xml")
    }

    @Test fun podcastAppSchemeWithNothingAfterItIsNull() {
        assertThat(normalizeFeedUrl("feed://")).isNull()
        assertThat(normalizeFeedUrl("feed://   ")).isNull()
    }

    @Test fun schemeLikeTextInsideAQueryIsNotTreatedAsAScheme() {
        assertThat(normalizeFeedUrl("example.com/?u=http://x.example"))
            .isEqualTo("https://example.com/?u=http://x.example")
    }
}

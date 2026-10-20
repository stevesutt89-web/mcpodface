package com.steve.mcpodface.data.links

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PodcastLinksTest {
    @Test fun podcastSchemeBecomesHttpsWithHttpFallback() {
        assertThat(parsePodcastLink("podcast://feeds.example.com/show.xml"))
            .isEqualTo(PodcastLink.Feed("https://feeds.example.com/show.xml", "http://feeds.example.com/show.xml"))
        assertThat(parsePodcastLink("pcast://example.com/rss"))
            .isEqualTo(PodcastLink.Feed("https://example.com/rss", "http://example.com/rss"))
        assertThat(parsePodcastLink("itpc://example.com/rss"))
            .isEqualTo(PodcastLink.Feed("https://example.com/rss", "http://example.com/rss"))
    }

    @Test fun feedSchemeWrappingAFullUrlKeepsIt() {
        assertThat(parsePodcastLink("feed:https://example.com/rss")).isEqualTo(PodcastLink.Feed("https://example.com/rss"))
        assertThat(parsePodcastLink("feed://http://example.com/rss")).isEqualTo(PodcastLink.Feed("http://example.com/rss"))
        assertThat(parsePodcastLink("podcast://https://example.com/a.xml?x=1"))
            .isEqualTo(PodcastLink.Feed("https://example.com/a.xml?x=1"))
    }

    @Test fun applePodcastsLinksGiveTheId() {
        assertThat(parsePodcastLink("https://podcasts.apple.com/au/podcast/the-vergecast/id430333725"))
            .isEqualTo(PodcastLink.ApplePodcasts(430333725))
        assertThat(parsePodcastLink("https://podcasts.apple.com/us/podcast/x/id1200361736?i=1000600000000"))
            .isEqualTo(PodcastLink.ApplePodcasts(1200361736))
        assertThat(parsePodcastLink("https://itunes.apple.com/podcast/id123456789")).isEqualTo(PodcastLink.ApplePodcasts(123456789))
        assertThat(parsePodcastLink("https://podcasts.apple.com/au/browse")).isNull()
    }

    @Test fun subscribeOnAndroidUnwrapsTheFeed() {
        assertThat(parsePodcastLink("https://subscribeonandroid.com/feeds.example.com/show.xml"))
            .isEqualTo(PodcastLink.Feed("https://feeds.example.com/show.xml", "http://feeds.example.com/show.xml"))
        assertThat(parsePodcastLink("https://www.subscribeonandroid.com/https://example.com/rss"))
            .isEqualTo(PodcastLink.Feed("https://example.com/rss"))
    }

    @Test fun sharedTextFindsTheUrlInsideOtherWords() {
        assertThat(parsePodcastLink("Check this out: https://example.com/feed.rss!"))
            .isEqualTo(PodcastLink.Feed("https://example.com/feed.rss"))
        assertThat(parsePodcastLink("The Vergecast on Apple Podcasts https://podcasts.apple.com/au/podcast/the-vergecast/id430333725"))
            .isEqualTo(PodcastLink.ApplePodcasts(430333725))
    }

    @Test fun noUrlGivesNull() {
        assertThat(parsePodcastLink("just some words")).isNull()
        assertThat(parsePodcastLink("")).isNull()
        assertThat(parsePodcastLink("podcast://")).isNull()
    }
}

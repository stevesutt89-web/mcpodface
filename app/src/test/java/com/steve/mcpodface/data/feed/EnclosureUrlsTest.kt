package com.steve.mcpodface.data.feed

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class EnclosureUrlsTest {
    @Test fun normalizesToTheSameKey() {
        val expected = "cdn.example.com/shows/Ep1.mp3"
        val variants = listOf(
            "https://cdn.example.com/shows/Ep1.mp3",
            "http://CDN.Example.com/shows/Ep1.mp3",
            "https://cdn.example.com/shows/Ep1.mp3?updated=123&source=rss",
            "https://cdn.example.com/shows/Ep1.mp3#t=10",
            "https://dts.podtrac.com/redirect.mp3/cdn.example.com/shows/Ep1.mp3",
            "https://www.podtrac.com/pts/redirect.mp3/cdn.example.com/shows/Ep1.mp3",
            "https://chtbl.com/track/ABC123/cdn.example.com/shows/Ep1.mp3",
            "https://pdst.fm/e/chtbl.com/track/ABC123/https://cdn.example.com/shows/Ep1.mp3",
            "https://op3.dev/e,pg=abc/cdn.example.com/shows/Ep1.mp3",
            "https://pfx.vpixl.com/xyz/dts.podtrac.com/redirect.mp3/cdn.example.com/shows/Ep1.mp3",
            "  https://cdn.example.com/shows/Ep1.mp3  ",
        )
        for (v in variants) assertWithMessage(v).that(EnclosureUrls.normalize(v)).isEqualTo(expected)
    }

    @Test fun pathCaseIsPreserved() {
        assertWithMessage("path").that(EnclosureUrls.normalize("https://a.com/A.mp3"))
            .isNotEqualTo(EnclosureUrls.normalize("https://a.com/a.mp3"))
    }
}

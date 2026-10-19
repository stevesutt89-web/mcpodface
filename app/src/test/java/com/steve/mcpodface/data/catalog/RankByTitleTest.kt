package com.steve.mcpodface.data.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RankByTitleTest {
    private fun p(title: String) = CatalogPodcast(feedUrl = "https://x/${title.hashCode()}", title = title)

    @Test fun exactTitleWinsOverPartialMatches() {
        val results = listOf(
            p("Locked On Magic - Daily Podcast On The Orlando Magic"),
            p("The Daily Show: Ears Edition"),
            p("The Daily"),
            p("Daily Boost"),
        )
        assertThat(rankByTitle(results, "the daily").map { it.title }).containsExactly(
            "The Daily",
            "The Daily Show: Ears Edition",
            "Locked On Magic - Daily Podcast On The Orlando Magic",
            "Daily Boost",
        ).inOrder()
    }

    @Test fun caseAndPunctuationAreIgnored() {
        val results = listOf(p("Something else"), p("Radiolab!"))
        assertThat(rankByTitle(results, "RADIOLAB").first().title).isEqualTo("Radiolab!")
    }

    @Test fun otherwiseKeepsSourceOrder() {
        val results = listOf(p("B"), p("A"), p("C"))
        assertThat(rankByTitle(results, "zzz").map { it.title }).containsExactly("B", "A", "C").inOrder()
    }
}

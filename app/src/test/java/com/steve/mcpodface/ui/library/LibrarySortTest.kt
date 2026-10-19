package com.steve.mcpodface.ui.library

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.LibraryPodcast
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.prefs.LibrarySort
import org.junit.Test

class LibrarySortTest {
    private fun podcast(
        id: Long,
        title: String,
        lastNewEpisodeAt: Long? = null,
        subscribedAt: Long? = null,
    ) = LibraryPodcast(
        podcast = PodcastEntity(
            id = id,
            feedUrl = "https://example.com/$id",
            title = title,
            isSubscribed = true,
            subscribedAt = subscribedAt,
            lastNewEpisodeAt = lastNewEpisodeAt,
        ),
        unplayedCount = 0,
    )

    private fun List<LibraryPodcast>.titles() = map { it.podcast.title }

    @Test fun recentlyUpdatedIsNewestFirstWithNullsLastThenTitle() {
        val podcasts = listOf(
            podcast(1, "Old", lastNewEpisodeAt = 100),
            podcast(2, "Never"),
            podcast(3, "Tie B", lastNewEpisodeAt = 300),
            podcast(4, "Also never"),
            podcast(5, "Tie A", lastNewEpisodeAt = 300),
        )

        assertThat(sortLibrary(podcasts, LibrarySort.RECENTLY_UPDATED).titles())
            .containsExactly("Tie A", "Tie B", "Old", "Also never", "Never").inOrder()
    }

    @Test fun alphabeticalIgnoresCaseAndLeadingThe() {
        val podcasts = listOf(
            podcast(1, "Zebra"),
            podcast(2, "The Daily"),
            podcast(3, "apple"),
            podcast(4, "Bananas"),
            podcast(5, "Theatre Show"),
        )

        // "The Daily" sorts as "Daily"; "Theatre Show" keeps its word because "The " needs a space.
        assertThat(sortLibrary(podcasts, LibrarySort.ALPHABETICAL).titles())
            .containsExactly("apple", "Bananas", "The Daily", "Theatre Show", "Zebra").inOrder()
    }

    @Test fun recentlyAddedIsNewestSubscriptionFirstWithNullsLast() {
        val podcasts = listOf(
            podcast(1, "A", subscribedAt = 100),
            podcast(2, "B"),
            podcast(3, "C", subscribedAt = 200),
        )

        assertThat(sortLibrary(podcasts, LibrarySort.RECENTLY_ADDED).titles())
            .containsExactly("C", "A", "B").inOrder()
    }
}

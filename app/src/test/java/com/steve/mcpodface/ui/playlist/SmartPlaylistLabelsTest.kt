package com.steve.mcpodface.ui.playlist

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.search.SearchSort
import com.steve.mcpodface.data.search.StatusFilter
import org.junit.Test

class SmartPlaylistLabelsTest {

    @Test fun podcastsLabelSaysAllWhenNothingIsChosen() {
        assertThat(smartPlaylistPodcastsLabel(0)).isEqualTo("All subscriptions")
        assertThat(smartPlaylistPodcastsLabel(1)).isEqualTo("1 podcast")
        assertThat(smartPlaylistPodcastsLabel(3)).isEqualTo("3 podcasts")
    }

    @Test fun publishedLabelsMatchTheStoredWindows() {
        assertThat(SmartPlaylistPublishedOptions).containsExactly(null, 1, 7, 30, 90).inOrder()
        assertThat(SmartPlaylistPublishedOptions.map { smartPlaylistPublishedLabel(it) })
            .containsExactly("Any time", "Last 24 hours", "Last 7 days", "Last 30 days", "Last 90 days").inOrder()
    }

    @Test fun limitOptionsAndLabels() {
        assertThat(SmartPlaylistLimitOptions).containsExactly(10, 25, 50, 100, 200).inOrder()
        assertThat(smartPlaylistLimitLabel(50)).isEqualTo("50 episodes")
    }

    @Test fun orderLabels() {
        assertThat(SmartPlaylistOrderOptions).containsExactly(SearchSort.NEWEST, SearchSort.OLDEST).inOrder()
        assertThat(smartPlaylistOrderLabel(SearchSort.NEWEST)).isEqualTo("Newest first")
        assertThat(smartPlaylistOrderLabel(SearchSort.OLDEST)).isEqualTo("Oldest first")
    }

    @Test fun episodeStatusOptionsOmitPlayed() {
        assertThat(SmartPlaylistStatusOptions).containsExactly(
            StatusFilter.NOT_PLAYED, StatusFilter.UNPLAYED, StatusFilter.IN_PROGRESS, StatusFilter.ANY,
        ).inOrder()
    }

    @Test fun matchLabelReadsTheCountAndCapsAtTheLimit() {
        assertThat(smartPlaylistMatchLabel(0, 50)).isEqualTo("No episodes match")
        assertThat(smartPlaylistMatchLabel(1, 50)).isEqualTo("Matches 1 episode")
        assertThat(smartPlaylistMatchLabel(12, 50)).isEqualTo("Matches 12 episodes")
        assertThat(smartPlaylistMatchLabel(50, 50)).isEqualTo("Matches 50+ episodes")
    }

    @Test fun titleDependsOnNew() {
        assertThat(smartPlaylistEditorTitle(isNew = true)).isEqualTo("New smart playlist")
        assertThat(smartPlaylistEditorTitle(isNew = false)).isEqualTo("Edit smart playlist")
    }
}

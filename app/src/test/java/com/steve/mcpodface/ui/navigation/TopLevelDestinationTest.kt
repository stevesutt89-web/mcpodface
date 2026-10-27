package com.steve.mcpodface.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.prefs.AppTab
import org.junit.Test

class TopLevelDestinationTest {
    @Test fun tabsAreLatestLibraryPlaylistsUpNextDiscover() {
        assertThat(TopLevelDestination.entries.map { it.label })
            .containsExactly("Latest", "Library", "Playlists", "Up Next", "Discover").inOrder()
    }

    @Test fun storedDownloadsTabFallsBackToLatest() {
        assertThat(TopLevelDestination.of(AppTab.DOWNLOADS)).isEqualTo(TopLevelDestination.LATEST)
    }

    @Test fun storedSearchTabRestoresSearch() {
        assertThat(TopLevelDestination.of(AppTab.SEARCH)).isEqualTo(TopLevelDestination.SEARCH)
    }
}

package com.steve.mcpodface.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.steve.mcpodface.data.prefs.AppTab
import kotlinx.serialization.Serializable

// Type-safe Navigation Compose routes. Add new destinations here.

@Serializable data object LatestRoute
@Serializable data object LibraryRoute
@Serializable data object DownloadsRoute
@Serializable data object UpNextRoute
@Serializable data object SearchRoute
@Serializable data object SettingsRoute
@Serializable data class PodcastRoute(val podcastId: Long)
@Serializable data class EpisodeRoute(val episodeId: Long)
@Serializable data object StarredRoute
@Serializable data object HistoryRoute
/** Episode search scoped to one podcast (PLAN.md §7.1). The scope chip can widen it. */
@Serializable data class EpisodeSearchRoute(val podcastId: Long)
/** One playlist: a manual playlist or a smart playlist (PLAN.md §8.11). */
@Serializable data class PlaylistRoute(val playlistId: Long)
/** Create (playlistId = 0) or edit a smart playlist's rules (PLAN.md §8.11). */
@Serializable data class SmartPlaylistEditorRoute(val playlistId: Long = 0L)

/** The five bottom-bar tabs, in order (PLAN.md §8.1). */
enum class TopLevelDestination(
    val tab: AppTab,
    val route: Any,
    val label: String,
    val icon: ImageVector,
) {
    LATEST(AppTab.LATEST, LatestRoute, "Latest", Icons.Outlined.NewReleases),
    LIBRARY(AppTab.LIBRARY, LibraryRoute, "Library", Icons.Outlined.GridView),
    DOWNLOADS(AppTab.DOWNLOADS, DownloadsRoute, "Downloads", Icons.Outlined.DownloadForOffline),
    UP_NEXT(AppTab.UP_NEXT, UpNextRoute, "Up Next", Icons.AutoMirrored.Outlined.QueueMusic),
    SEARCH(AppTab.SEARCH, SearchRoute, "Search", Icons.Outlined.Search);

    companion object {
        fun of(tab: AppTab): TopLevelDestination = entries.first { it.tab == tab }
    }
}

package com.steve.mcpodface.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.ui.graphics.vector.ImageVector
import com.steve.mcpodface.data.prefs.AppTab
import kotlinx.serialization.Serializable

// Type-safe Navigation Compose routes. Add new destinations here.

@Serializable data object LatestRoute
@Serializable data object LibraryRoute
/** Downloads, pushed from a tile on the Playlists tab (PLAN.md §8.7). */
@Serializable data object DownloadsRoute
@Serializable data object UpNextRoute
@Serializable data object SearchRoute
/** The Playlists tab: Starred, Downloads and playlists as a grid (PLAN.md §8.11). */
@Serializable data object PlaylistsRoute
@Serializable data object SettingsRoute
/** One section of Settings, e.g. Playback. [section] is a [com.steve.mcpodface.ui.settings.SettingsSection] name. */
@Serializable data class SettingsSectionRoute(val section: String)
@Serializable data class PodcastRoute(val podcastId: Long)
@Serializable data class EpisodeRoute(val episodeId: Long)
@Serializable data object StarredRoute
@Serializable data object HistoryRoute
/** Listening stats: hero numbers, a 30-day chart and top podcasts (PLAN.md §13). */
@Serializable data object StatsRoute
/**
 * Search of the subscribed podcasts' episodes (PLAN.md §7), opened from the search button on Latest
 * and Library ([podcastId] = 0), or scoped to one podcast from its page (§7.1). The scope chip can widen it.
 */
@Serializable data class EpisodeSearchRoute(val podcastId: Long = 0L)
/** One playlist: a manual playlist or a smart playlist (PLAN.md §8.11). */
@Serializable data class PlaylistRoute(val playlistId: Long)
/** Create (playlistId = 0) or edit a smart playlist's rules (PLAN.md §8.11). */
@Serializable data class SmartPlaylistEditorRoute(val playlistId: Long = 0L)

/** The five bottom-bar tabs, in order (PLAN.md §8.1). Downloads is opened from Playlists, not a tab. */
enum class TopLevelDestination(
    val tab: AppTab,
    val route: Any,
    val label: String,
    val icon: ImageVector,
) {
    LATEST(AppTab.LATEST, LatestRoute, "Latest", Icons.Outlined.Inbox),
    LIBRARY(AppTab.LIBRARY, LibraryRoute, "Library", Icons.Outlined.GridView),
    PLAYLISTS(AppTab.PLAYLISTS, PlaylistsRoute, "Playlists", Icons.Outlined.LibraryMusic),
    UP_NEXT(AppTab.UP_NEXT, UpNextRoute, "Up Next", Icons.AutoMirrored.Outlined.QueueMusic),
    // Finding new podcasts. Searching your own podcasts is the search button on Latest and Library.
    SEARCH(AppTab.SEARCH, SearchRoute, "Discover", Icons.Outlined.Explore);

    companion object {
        /** A stored [AppTab.DOWNLOADS] (no longer a tab) restores as Latest. */
        fun of(tab: AppTab): TopLevelDestination = entries.firstOrNull { it.tab == tab } ?: LATEST
    }
}

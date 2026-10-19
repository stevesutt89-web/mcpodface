package com.steve.mcpodface.ui.playlist

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

/** One playlist (PLAN.md §8.11). Placeholder until the playlist screen task lands. */
@Composable
fun PlaylistScreen(onBack: () -> Unit, onOpenEpisode: (Long) -> Unit, onEditRules: (playlistId: Long) -> Unit) {
    PlaceholderScreen(title = "Playlist", onBack = onBack)
}

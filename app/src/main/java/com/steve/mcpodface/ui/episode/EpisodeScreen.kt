package com.steve.mcpodface.ui.episode

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

@Composable
fun EpisodeScreen(episodeId: Long, onBack: () -> Unit, onOpenPodcast: (Long) -> Unit) {
    PlaceholderScreen(title = "Episode", onBack = onBack)
}

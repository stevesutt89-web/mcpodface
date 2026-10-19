package com.steve.mcpodface.ui.podcast

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

@Composable
fun PodcastScreen(podcastId: Long, onBack: () -> Unit, onOpenEpisode: (Long) -> Unit) {
    PlaceholderScreen(title = "Podcast", onBack = onBack)
}

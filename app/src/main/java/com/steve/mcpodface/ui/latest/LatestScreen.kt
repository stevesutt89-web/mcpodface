package com.steve.mcpodface.ui.latest

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

@Composable
fun LatestScreen(onOpenSettings: () -> Unit, onOpenPodcast: (Long) -> Unit, onOpenEpisode: (Long) -> Unit) {
    PlaceholderScreen(title = "Latest", onOpenSettings = onOpenSettings)
}

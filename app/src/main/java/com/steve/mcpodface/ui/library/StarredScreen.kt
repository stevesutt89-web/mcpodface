package com.steve.mcpodface.ui.library

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

@Composable
fun StarredScreen(onBack: () -> Unit, onOpenEpisode: (Long) -> Unit) {
    PlaceholderScreen(title = "Starred", onBack = onBack)
}

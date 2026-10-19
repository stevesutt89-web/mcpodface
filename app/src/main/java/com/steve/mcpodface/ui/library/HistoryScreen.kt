package com.steve.mcpodface.ui.library

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

/** Listening history. Task 4.5 records sessions; this screen lists them. */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenEpisode: (Long) -> Unit) {
    PlaceholderScreen(title = "History", onBack = onBack)
}

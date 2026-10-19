package com.steve.mcpodface.ui.playlist

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

/** Smart playlist rules editor (PLAN.md §8.11). Placeholder until the editor task lands. */
@Composable
fun SmartPlaylistEditorScreen(onBack: () -> Unit, onSaved: (playlistId: Long) -> Unit) {
    PlaceholderScreen(title = "Smart playlist", onBack = onBack)
}

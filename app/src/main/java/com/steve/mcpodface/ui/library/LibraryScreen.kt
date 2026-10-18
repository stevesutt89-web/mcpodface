package com.steve.mcpodface.ui.library

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

@Composable
fun LibraryScreen(onOpenSettings: () -> Unit) {
    PlaceholderScreen(title = "Library", onOpenSettings = onOpenSettings)
}

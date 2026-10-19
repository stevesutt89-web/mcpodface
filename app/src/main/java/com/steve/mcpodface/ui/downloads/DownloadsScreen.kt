package com.steve.mcpodface.ui.downloads

import androidx.compose.runtime.Composable
import com.steve.mcpodface.ui.components.PlaceholderScreen

@Composable
fun DownloadsScreen(onOpenSettings: () -> Unit = {}) {
    PlaceholderScreen(title = "Downloads", onOpenSettings = onOpenSettings)
}

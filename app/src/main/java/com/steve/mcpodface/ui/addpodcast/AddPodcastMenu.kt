package com.steve.mcpodface.ui.addpodcast

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

/**
 * Top-bar action on the Search tab: "Add by URL", "Import OPML", "Export OPML" (PLAN.md §8.9).
 * Stub: task 2.4b implements the menu, dialog and import flow behind this signature.
 */
@Composable
fun AddPodcastMenu(onOpenPodcast: (Long) -> Unit) {
    IconButton(onClick = { /* TODO(2.4b) */ }) {
        Icon(Icons.Outlined.Add, contentDescription = "Add podcast")
    }
}

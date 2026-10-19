package com.steve.mcpodface.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * "Your podcasts" mode until task 6.3 adds local episode search. [contentPadding] keeps the text
 * clear of the floating chrome at the bottom of the screen.
 */
@Composable
fun YourPodcastsPlaceholder(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(
        modifier.fillMaxSize().padding(contentPadding).padding(Spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Search your episodes — coming soon",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@ThemePreviews
@Composable
private fun YourPodcastsPlaceholderPreview() {
    AppTheme { Surface { YourPodcastsPlaceholder() } }
}

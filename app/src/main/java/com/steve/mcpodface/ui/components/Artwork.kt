package com.steve.mcpodface.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ArtworkShape
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/** Square podcast or episode artwork with rounded corners and a neutral placeholder (PLAN.md §11). */
@Composable
fun Artwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val frame = modifier.size(size).clip(ArtworkShape)
    if (url.isNullOrBlank()) {
        ArtworkPlaceholder(frame)
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = frame,
            contentScale = ContentScale.Crop,
            loading = { ArtworkPlaceholder(Modifier.fillMaxSize()) },
            error = { ArtworkPlaceholder(Modifier.fillMaxSize()) },
        )
    }
}

@Composable
private fun ArtworkPlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier.background(AppTheme.colors.surfaceGrouped),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Podcasts,
            contentDescription = null,
            tint = AppTheme.colors.textSecondary.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxSize(0.45f),
        )
    }
}

@ThemePreviews
@Composable
private fun ArtworkPreview() {
    AppTheme {
        Surface {
            Row(
                Modifier.padding(Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                // Null url and a url that cannot load (no network in previews) both show the placeholder.
                Artwork(url = null, contentDescription = null)
                Artwork(url = "https://example.invalid/cover.jpg", contentDescription = "Cover")
                Artwork(url = null, contentDescription = null, size = 96.dp)
            }
        }
    }
}

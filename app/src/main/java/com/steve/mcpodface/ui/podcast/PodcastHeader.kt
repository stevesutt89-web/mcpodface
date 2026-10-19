package com.steve.mcpodface.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.core.HtmlText
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private const val COLLAPSED_DESCRIPTION_LINES = 3

/**
 * Podcast detail header (PLAN.md §8.4, §11): centred artwork with a soft shadow, title and author,
 * a glass Subscribe / Subscribed button beside the settings button, and the show notes in a glass
 * card. Shows a notice when the feed has gone (410). Subscribe is tinted with [accent].
 */
@Composable
fun PodcastHeader(
    podcast: PodcastEntity,
    onSubscribe: () -> Unit,
    onSubscribedClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AppTheme.colors.accent,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShadowedArtwork(url = podcast.artworkUrl, contentDescription = podcast.title, size = 200.dp)
        Spacer(Modifier.height(Spacing.m))
        Text(
            text = podcast.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = colors.textPrimary,
        )
        podcast.author?.takeIf { it.isNotBlank() }?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.m))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            if (podcast.isSubscribed) {
                GlassButton(onClick = onSubscribedClick) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Subscribed")
                }
            } else {
                GlassButton(onClick = onSubscribe, style = GlassStyle.Prominent, accent = accent) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Subscribe")
                }
            }
            // TODO(7.2): open the per-podcast settings sheet.
            GlassIconButton(
                icon = Icons.Rounded.Tune,
                contentDescription = "Podcast settings (coming soon)",
                onClick = {},
                enabled = false,
            )
        }
        if (podcast.feedGone) {
            Text(
                text = "Feed no longer available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }
        Description(
            html = podcast.descriptionHtml,
            accent = accent,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
        )
    }
}

/** Artwork in a continuous 28dp rounded shape with a soft shadow (PLAN.md §11). */
@Composable
internal fun ShadowedArtwork(
    url: String?,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val artShape = remember { RoundedRectangle(28.dp) }
    // The outer layer casts the shadow; the inner box clips the image to the same shape.
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                shadowElevation = 24.dp.toPx()
                shape = artShape
            },
    ) {
        Box(Modifier.fillMaxSize().clip(artShape).background(AppTheme.colors.surfaceGrouped)) {
            if (!url.isNullOrBlank() && !LocalInspectionMode.current) {
                AsyncImage(
                    model = url,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Plain-text show notes, collapsed to three lines with a More / Less toggle when they overflow. */
@Composable
private fun Description(html: String?, accent: Color, modifier: Modifier = Modifier) {
    val text = remember(html) { HtmlText.toPlainText(html) }
    if (text.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    val cardShape = remember { RoundedRectangle(24.dp) }
    Column(
        modifier = modifier
            .glass(cardShape, GlassStyle.Regular)
            .padding(Spacing.m),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> overflows = expanded || result.hasVisualOverflow },
        )
        if (overflows) {
            TextButton(
                onClick = { expanded = !expanded },
            ) {
                Text(if (expanded) "Less" else "More", color = accent)
            }
        }
    }
}

@ThemePreviews
@Composable
private fun PodcastHeaderSubscribedPreview() {
    AppTheme {
        Surface {
            PodcastHeader(
                podcast = previewPodcast(subscribed = true),
                onSubscribe = {},
                onSubscribedClick = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PodcastHeaderNotSubscribedPreview() {
    AppTheme {
        Surface {
            PodcastHeader(
                podcast = previewPodcast(subscribed = false),
                onSubscribe = {},
                onSubscribedClick = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PodcastHeaderFeedGonePreview() {
    AppTheme {
        Surface {
            PodcastHeader(
                podcast = previewPodcast(subscribed = true, feedGone = true),
                onSubscribe = {},
                onSubscribedClick = {},
            )
        }
    }
}

internal fun previewPodcast(
    subscribed: Boolean = false,
    feedGone: Boolean = false,
): PodcastEntity = PodcastEntity(
    id = 1L,
    feedUrl = "https://example.invalid/feed.xml",
    title = "Radiolab",
    author = "WNYC Studios",
    descriptionHtml = "<p>Radiolab is a show about big questions and small details. Every week we follow " +
        "a single idea wherever it leads, from the lab bench to the back of a taxi.</p>" +
        "<p>Hosts take turns asking the questions nobody thought to ask.</p>",
    isSubscribed = subscribed,
    feedGone = feedGone,
)

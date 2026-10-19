package com.steve.mcpodface.ui.podcast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.core.HtmlText
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.theme.AppButtonDefaults
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private const val COLLAPSED_DESCRIPTION_LINES = 3

/**
 * Podcast detail header (PLAN.md §8.4): large artwork, title, author, Subscribe / Subscribed button,
 * an expandable plain-text description, and a notice when the feed has gone (410).
 */
@Composable
fun PodcastHeader(
    podcast: PodcastEntity,
    onSubscribe: () -> Unit,
    onSubscribedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(url = podcast.artworkUrl, contentDescription = podcast.title, size = 160.dp)
        Spacer(Modifier.height(Spacing.m))
        Text(
            text = podcast.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = colors.textPrimary,
        )
        podcast.author?.takeIf { it.isNotBlank() }?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.m))
        if (podcast.isSubscribed) {
            // TODO(7.2): open the per-podcast settings sheet.
            OutlinedButton(onClick = onSubscribedClick) {
                Text("Subscribed ✓")
            }
        } else {
            Button(onClick = onSubscribe, colors = AppButtonDefaults.primaryColors()) {
                Text("Subscribe")
            }
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
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
        )
    }
}

/** Plain-text show notes, collapsed to three lines with a More / Less toggle when they overflow. */
@Composable
private fun Description(html: String?, modifier: Modifier = Modifier) {
    val text = remember(html) { HtmlText.toPlainText(html) }
    if (text.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(modifier) {
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
                Text(if (expanded) "Less" else "More")
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

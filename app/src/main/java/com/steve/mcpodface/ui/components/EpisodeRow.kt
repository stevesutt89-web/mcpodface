package com.steve.mcpodface.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * One episode in a list (PLAN.md §8.2): artwork, meta line, title, summary or search snippet,
 * then a footer with play/progress, duration or time left, and download / star / queue state.
 */
@Composable
fun EpisodeRow(
    item: EpisodeListItem,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    showPodcastTitle: Boolean = true,
    snippet: AnnotatedString? = null,
    now: Long = System.currentTimeMillis(),
    onLongClick: (() -> Unit)? = null,
    /** Replaces the publish date in the meta line, e.g. "Played 3h ago" in History. */
    dateLabel: String? = null,
) {
    val colors = AppTheme.colors
    val episode = item.episode
    val played = episode.playState == PlayState.PLAYED
    val inProgress = episode.playState == PlayState.IN_PROGRESS
    val progress = if (inProgress) playProgress(episode.positionMs, episode.durationSec) else null

    val title = episode.title.ifBlank { "Untitled episode" }
    val meta = buildList {
        if (showPodcastTitle && item.podcastTitle.isNotBlank()) add(item.podcastTitle)
        add(dateLabel ?: formatRelativeDate(episode.pubDate, now))
        if (!episode.inCurrentFeed) add("Archived")
    }.joinToString(" · ")
    val summary: AnnotatedString? = snippet?.takeIf { it.text.isNotBlank() }
        ?: episode.summaryText.takeIf { it.isNotBlank() }?.let { AnnotatedString(it) }
    val footer = when (episode.playState) {
        PlayState.UNPLAYED -> formatDuration(episode.durationSec)
        PlayState.IN_PROGRESS -> formatTimeLeft(episode.durationSec, episode.positionMs)
        PlayState.PLAYED -> "Played"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.m, vertical = 12.dp),
    ) {
        Row {
            Artwork(
                url = episode.artworkUrl ?: item.podcastArtworkUrl,
                contentDescription = null,
                size = 56.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (played) colors.textSecondary else colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayProgressButton(
                progress = progress,
                isPlaying = isPlaying,
                onClick = onPlayClick,
            )
            Spacer(Modifier.width(Spacing.s))
            if (footer.isNotEmpty()) {
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            if (inProgress) {
                Spacer(Modifier.width(Spacing.s))
                ProgressBar(progress = progress ?: 0f)
            }
            Spacer(Modifier.weight(1f))
            item.downloadState?.let { DownloadStateIcon(it) }
            if (episode.isStarred) {
                Spacer(Modifier.width(Spacing.s))
                StateIcon(Icons.Rounded.Star, "Starred", colors.accent)
            }
            if (item.inQueue) {
                Spacer(Modifier.width(Spacing.s))
                StateIcon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, "In Up Next", colors.textSecondary)
            }
        }
    }
}

/** A 3dp capsule progress bar, shown after the time-left text of an in-progress episode. */
@Composable
private fun ProgressBar(progress: Float) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .width(48.dp)
            .height(3.dp)
            .clip(Capsule())
            .background(colors.textPrimary.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(colors.accent),
        )
    }
}

private fun playProgress(positionMs: Long, durationSec: Int?): Float? {
    val duration = durationSec ?: return null
    if (duration <= 0) return null
    return (positionMs.toFloat() / (duration * 1000f)).coerceIn(0f, 1f)
}

@Composable
private fun DownloadStateIcon(state: DownloadState) {
    val colors = AppTheme.colors
    when (state) {
        DownloadState.COMPLETED -> StateIcon(Icons.Rounded.DownloadDone, "Downloaded", colors.accent)
        DownloadState.DOWNLOADING -> StateIcon(Icons.Rounded.Downloading, "Downloading", colors.textSecondary)
        DownloadState.QUEUED -> StateIcon(Icons.Rounded.Downloading, "Download queued", colors.textSecondary)
        DownloadState.FAILED -> StateIcon(Icons.Rounded.ErrorOutline, "Download failed", MaterialTheme.colorScheme.error)
        DownloadState.PAUSED_NETWORK -> StateIcon(Icons.Rounded.WifiOff, "Paused, waiting for network", colors.textSecondary)
    }
}

@Composable
private fun StateIcon(icon: ImageVector, description: String, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}

// ---- Previews -------------------------------------------------------------------------------

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z
private const val HOUR = 3_600_000L
private const val DAY = 86_400_000L

// Preview-only sample data, shared with LargeTitleScaffold's preview.
internal fun previewEpisodeItems(): List<EpisodeListItem> = listOf(
    previewItem(
        id = 1, title = "The cold open: why the bridge still matters",
        podcast = "Radiolab", pubDate = PREVIEW_NOW - 3 * HOUR, durationSec = 2700,
        summary = "We walk the length of the old river crossing and ask who it was built for.",
    ),
    previewItem(
        id = 2, title = "Part two: the engineers who argued", podcast = "Radiolab",
        pubDate = PREVIEW_NOW - 2 * DAY, durationSec = 3900, state = PlayState.IN_PROGRESS,
        positionMs = 2_400_000L, download = DownloadState.COMPLETED, starred = true, queued = true,
        summary = "Two competing plans, one budget, and a surveyor's notebook.",
    ),
    previewItem(
        id = 3, title = "Played already", podcast = "Hard Fork",
        pubDate = PREVIEW_NOW - 5 * DAY, durationSec = 1800, state = PlayState.PLAYED,
        summary = "A quick weekly round-up of product launches.",
    ),
    previewItem(
        id = 4, title = "Downloading now", podcast = "The Daily",
        pubDate = PREVIEW_NOW - 26 * HOUR, durationSec = 1320, download = DownloadState.DOWNLOADING,
    ),
    previewItem(
        id = 5, title = "Archived from an old feed", podcast = "Old Show",
        pubDate = 1_728_734_400_000L, durationSec = 3600, inFeed = false,
        summary = "Retired from the feed but still on this phone.",
    ),
    previewItem(
        id = 6, title = "", podcast = "Blank Title Podcast",
        pubDate = PREVIEW_NOW - 40 * HOUR, durationSec = 45,
        download = DownloadState.FAILED,
    ),
    previewItem(
        id = 7, title = "Waiting for wifi", podcast = "Serial Feed",
        pubDate = PREVIEW_NOW - 10 * DAY, durationSec = 90, download = DownloadState.PAUSED_NETWORK,
        summary = "Paused because the download is set to wifi only.",
    ),
)

private fun previewItem(
    id: Long,
    title: String,
    podcast: String,
    pubDate: Long,
    durationSec: Int?,
    state: PlayState = PlayState.UNPLAYED,
    positionMs: Long = 0L,
    summary: String = "",
    download: DownloadState? = null,
    starred: Boolean = false,
    queued: Boolean = false,
    inFeed: Boolean = true,
): EpisodeListItem = EpisodeListItem(
    episode = EpisodeEntity(
        id = id,
        podcastId = 1L,
        guid = "guid-$id",
        title = title,
        summaryText = summary,
        pubDate = pubDate,
        durationSec = durationSec,
        enclosureUrl = "https://example.invalid/$id.mp3",
        firstSeenAt = 0L,
        playState = state,
        positionMs = positionMs,
        isStarred = starred,
        inCurrentFeed = inFeed,
    ),
    podcastTitle = podcast,
    podcastArtworkUrl = null,
    downloadState = download,
    downloadBytes = null,
    inQueue = queued,
)

@ThemePreviews
@Composable
private fun EpisodeRowPreview() {
    AppTheme {
        Surface {
            Column {
                previewEpisodeItems().forEach { item ->
                    EpisodeRow(item = item, onClick = {}, onPlayClick = {}, now = PREVIEW_NOW)
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun EpisodeRowSnippetPreview() {
    val snippet = buildAnnotatedString {
        append("...the surveyor kept a ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("notebook") }
        append(" of every ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bridge") }
        append(" he measured...")
    }
    AppTheme {
        Surface {
            EpisodeRow(
                item = previewEpisodeItems()[1],
                onClick = {},
                onPlayClick = {},
                isPlaying = true,
                showPodcastTitle = false,
                snippet = snippet,
                now = PREVIEW_NOW,
            )
        }
    }
}

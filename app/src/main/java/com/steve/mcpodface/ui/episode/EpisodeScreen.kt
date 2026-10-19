package com.steve.mcpodface.ui.episode

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.GlassTopBarScaffold
import com.steve.mcpodface.ui.components.formatDuration
import com.steve.mcpodface.ui.components.formatRelativeDate
import com.steve.mcpodface.ui.components.formatTimeLeft
import com.steve.mcpodface.ui.components.glass.ArtworkAmbience
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.glass.rememberArtworkAccent
import com.steve.mcpodface.ui.podcast.ShadowedArtwork
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

@Composable
fun EpisodeScreen(episodeId: Long, onBack: () -> Unit, onOpenPodcast: (Long) -> Unit) {
    // The ViewModel reads episodeId from the route; the key keeps one instance per episode on the back stack.
    val viewModel: EpisodeViewModel = hiltViewModel(key = "episode-$episodeId")
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    EpisodeContent(
        state = state,
        onBack = onBack,
        onOpenPodcast = { state.episode?.let { onOpenPodcast(it.podcastId) } },
        onPlayClick = viewModel::togglePlayPause,
        onTogglePlayed = viewModel::togglePlayed,
        onShare = {
            val episode = state.episode ?: return@EpisodeContent
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    shareText(episode.title, state.podcast?.title.orEmpty(), episode.enclosureUrl),
                )
            }
            context.startActivity(Intent.createChooser(intent, null))
        },
        onToggleQueue = viewModel::toggleQueue,
        onToggleStarred = viewModel::toggleStarred,
        onTimestampClick = viewModel::onTimestampClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeContent(
    state: EpisodeUiState,
    onBack: () -> Unit,
    onOpenPodcast: () -> Unit,
    onPlayClick: () -> Unit,
    onTogglePlayed: () -> Unit,
    onShare: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleStarred: () -> Unit,
    onTimestampClick: (Long) -> Unit,
) {
    val episode = state.episode
    val podcast = state.podcast
    // The episode artwork (or the podcast's) washes the page and tints the Play button and accents.
    val artworkUrl = episode?.artworkUrl ?: podcast?.artworkUrl
    val artworkAccent by rememberArtworkAccent(artworkUrl)
    val accent: Color = artworkAccent ?: AppTheme.colors.accent
    var menuOpen by remember { mutableStateOf(false) }

    GlassPage(background = { ArtworkAmbience(artworkUrl, fadeTo = 0.6f) }) {
        GlassTopBarScaffold(
            background = Color.Transparent,
            navigationIcon = { BackButton(onClick = onBack) },
            actions = {
                if (episode != null) {
                    Box {
                        GlassIconButton(
                            icon = Icons.Rounded.MoreVert,
                            contentDescription = "More",
                            onClick = { menuOpen = true },
                            size = 40.dp,
                            iconSize = 20.dp,
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedRectangle(20.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (state.isPlayed) "Mark as unplayed" else "Mark as played") },
                                onClick = {
                                    menuOpen = false
                                    onTogglePlayed()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    menuOpen = false
                                    onShare()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Go to podcast") },
                                enabled = podcast != null,
                                onClick = {
                                    menuOpen = false
                                    onOpenPodcast()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            },
        ) { padding ->
            when {
                state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                episode == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Episode not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.colors.textSecondary,
                    )
                }
                else -> EpisodeBody(
                    state = state,
                    episode = episode,
                    podcast = podcast,
                    accent = accent,
                    contentPadding = padding,
                    onOpenPodcast = onOpenPodcast,
                    onPlayClick = onPlayClick,
                    onToggleQueue = onToggleQueue,
                    onToggleStarred = onToggleStarred,
                    onTimestampClick = onTimestampClick,
                )
            }
        }
    }
}

@Composable
private fun EpisodeBody(
    state: EpisodeUiState,
    episode: EpisodeEntity,
    podcast: PodcastEntity?,
    accent: Color,
    contentPadding: PaddingValues,
    onOpenPodcast: () -> Unit,
    onPlayClick: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleStarred: () -> Unit,
    onTimestampClick: (Long) -> Unit,
) {
    val colors = AppTheme.colors
    val inProgress = state.isInProgress
    val fraction = episode.durationSec?.takeIf { it > 0 }
        ?.let { (state.positionMs / (it * 1000f)).coerceIn(0f, 1f) }
    val meta = listOf(
        formatRelativeDate(episode.pubDate, state.now),
        if (inProgress) formatTimeLeft(episode.durationSec, state.positionMs) else formatDuration(episode.durationSec),
    ).filter { it.isNotBlank() }.joinToString(" · ")
    val notesShape = remember { RoundedRectangle(28.dp) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Content scrolls under the glass top bar and the floating chrome.
            .padding(contentPadding)
            .padding(horizontal = Spacing.m)
            .padding(bottom = Spacing.l),
    ) {
        ShadowedArtwork(
            url = episode.artworkUrl ?: podcast?.artworkUrl,
            contentDescription = episode.title,
            size = 220.dp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = Spacing.s),
        )
        Spacer(Modifier.height(Spacing.m))
        if (podcast != null) {
            Text(
                text = podcast.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenPodcast),
            )
        }
        Text(
            text = episode.title.ifBlank { "Untitled episode" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = colors.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (inProgress && fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(0.6f)
                    .padding(top = Spacing.s)
                    .height(2.dp),
                color = accent,
                trackColor = colors.hairline,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            GlassButton(
                onClick = onPlayClick,
                style = GlassStyle.Prominent,
                accent = accent,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = if (state.isPlayingThis) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(state.playLabel)
            }
            // Downloads arrive in task 5.4; the button is shown disabled until then.
            GlassIconButton(
                icon = if (state.isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                contentDescription = if (state.isDownloaded) "Downloaded" else "Download (coming soon)",
                onClick = {},
                enabled = false,
            )
            GlassIconButton(
                icon = if (state.inQueue) Icons.AutoMirrored.Rounded.PlaylistAddCheck else Icons.AutoMirrored.Rounded.PlaylistAdd,
                contentDescription = if (state.inQueue) "Remove from Up Next" else "Add to Up Next",
                onClick = onToggleQueue,
                tint = if (state.inQueue) accent else null,
            )
            GlassIconButton(
                icon = if (state.isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (state.isStarred) "Unstar" else "Star",
                onClick = onToggleStarred,
                tint = if (state.isStarred) accent else null,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.m)
                .glass(notesShape, GlassStyle.Regular)
                .padding(20.dp),
        ) {
            Text(
                text = "Show notes",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(Spacing.s))
            ShowNotes(
                html = episode.descriptionHtml ?: episode.descriptionText,
                onTimestampClick = onTimestampClick,
                accent = accent,
            )
        }
    }
}

// ---- Previews -------------------------------------------------------------------------------

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z
private const val PREVIEW_DAY = 86_400_000L

private const val PREVIEW_NOTES = """<p>This week we follow the bridge crew from the first survey to the final cable.
Jump to <a href="https://example.com/bridge-story">the bridge story</a> or listen from 12:34 for the storm.</p>
<p>Chapter markers: 0:45 intro, 5:07 in a sentence, and the long section at 1:02:03.</p>
<p>Full transcript and photos are on the <a href="https://example.com/notes">show site</a>.</p>"""

private fun previewPodcast() = PodcastEntity(
    id = 1,
    feedUrl = "https://example.com/feed.xml",
    title = "Sample Show",
    isSubscribed = true,
)

private fun previewEpisode(playState: PlayState, positionMs: Long = 0L) = EpisodeEntity(
    id = 1,
    podcastId = 1,
    guid = "preview-1",
    title = "The bridge that almost did not get built",
    descriptionHtml = PREVIEW_NOTES,
    pubDate = PREVIEW_NOW - 2 * PREVIEW_DAY,
    durationSec = 2_700,
    enclosureUrl = "https://example.com/episode.mp3",
    playState = playState,
    positionMs = positionMs,
    firstSeenAt = 0,
)

@ThemePreviews
@Composable
private fun EpisodeContentPreview() {
    AppTheme {
        Surface {
            EpisodeContent(
                state = EpisodeUiState(
                    loaded = true,
                    episode = previewEpisode(PlayState.UNPLAYED),
                    podcast = previewPodcast(),
                    inQueue = true,
                    now = PREVIEW_NOW,
                ),
                onBack = {},
                onOpenPodcast = {},
                onPlayClick = {},
                onTogglePlayed = {},
                onShare = {},
                onToggleQueue = {},
                onToggleStarred = {},
                onTimestampClick = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun EpisodeContentInProgressPreview() {
    AppTheme {
        Surface {
            EpisodeContent(
                state = EpisodeUiState(
                    loaded = true,
                    episode = previewEpisode(PlayState.IN_PROGRESS, positionMs = 900_000L).copy(isStarred = true),
                    podcast = previewPodcast(),
                    now = PREVIEW_NOW,
                ),
                onBack = {},
                onOpenPodcast = {},
                onPlayClick = {},
                onTogglePlayed = {},
                onShare = {},
                onToggleQueue = {},
                onToggleStarred = {},
                onTimestampClick = {},
            )
        }
    }
}

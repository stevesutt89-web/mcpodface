package com.steve.mcpodface.ui.episode

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.formatDuration
import com.steve.mcpodface.ui.components.formatRelativeDate
import com.steve.mcpodface.ui.components.formatTimeLeft
import com.steve.mcpodface.ui.theme.AppButtonDefaults
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
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    if (episode != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                episode == null -> Text(
                    text = "Episode not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> EpisodeBody(
                    state = state,
                    episode = episode,
                    podcast = podcast,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.m)
            .padding(bottom = Spacing.l),
    ) {
        Artwork(
            url = episode.artworkUrl ?: podcast?.artworkUrl,
            contentDescription = episode.title,
            size = 200.dp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = Spacing.s),
        )
        Spacer(Modifier.height(Spacing.m))
        if (podcast != null) {
            Text(
                text = podcast.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenPodcast),
            )
        }
        Text(
            text = episode.title.ifBlank { "Untitled episode" },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        if (inProgress && fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.s)
                    .height(2.dp),
                color = colors.accent,
                trackColor = colors.hairline,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onPlayClick, colors = AppButtonDefaults.primaryColors()) {
                Icon(
                    imageVector = if (state.isPlayingThis) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(state.playLabel)
            }
            Spacer(Modifier.weight(1f))
            // Downloads arrive in task 5.4; the button is shown disabled until then.
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    imageVector = if (state.isDownloaded) Icons.Outlined.DownloadDone else Icons.Outlined.Download,
                    contentDescription = if (state.isDownloaded) "Downloaded" else "Download (coming soon)",
                )
            }
            IconButton(onClick = onToggleQueue) {
                Icon(
                    imageVector = if (state.inQueue) {
                        Icons.AutoMirrored.Outlined.PlaylistAddCheck
                    } else {
                        Icons.AutoMirrored.Outlined.PlaylistAdd
                    },
                    contentDescription = if (state.inQueue) "Remove from Up Next" else "Add to Up Next",
                    tint = if (state.inQueue) colors.accent else colors.textPrimary,
                )
            }
            IconButton(onClick = onToggleStarred) {
                Icon(
                    imageVector = if (state.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (state.isStarred) "Unstar" else "Star",
                    tint = if (state.isStarred) colors.accent else colors.textPrimary,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = Spacing.m),
            color = colors.hairline,
        )
        ShowNotes(
            html = episode.descriptionHtml ?: episode.descriptionText,
            onTimestampClick = onTimestampClick,
            modifier = Modifier.padding(top = Spacing.m),
        )
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

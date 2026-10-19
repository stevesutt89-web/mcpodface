package com.steve.mcpodface.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.PlaylistEntity
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.db.PlaylistWithCount
import com.steve.mcpodface.ui.addpodcast.GlassDialogScope
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.SettingsButton
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.library.ShapedArtwork
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Playlists tab (PLAN.md §8.11): a two-column grid of mosaic tiles, Starred and Downloads first,
 * then the user's playlists. "+" in the top bar makes a new playlist or smart playlist.
 */
@Composable
fun PlaylistsScreen(
    onOpenSettings: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onNewSmartPlaylist: () -> Unit,
) {
    val viewModel: PlaylistsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnOpenPlaylist by rememberUpdatedState(onOpenPlaylist)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PlaylistsEvent.OpenPlaylist -> currentOnOpenPlaylist(event.playlistId)
            }
        }
    }

    PlaylistsContent(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenStarred = onOpenStarred,
        onOpenDownloads = onOpenDownloads,
        onOpenPlaylist = onOpenPlaylist,
        onNewSmartPlaylist = onNewSmartPlaylist,
        onCreatePlaylist = viewModel::createPlaylist,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsContent(
    state: PlaylistsUiState,
    onOpenSettings: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onNewSmartPlaylist: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var newPlaylistOpen by rememberSaveable { mutableStateOf(false) }

    GlassPage {
        LargeTitleScaffold(
            title = "Playlists",
            background = Color.Transparent,
            actions = {
                NewPlaylistMenu(
                    onNewPlaylist = { newPlaylistOpen = true },
                    onNewSmartPlaylist = onNewSmartPlaylist,
                )
                SettingsButton(onOpenSettings)
            },
        ) { padding ->
            val layoutDirection = LocalLayoutDirection.current
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + Spacing.m,
                    top = padding.calculateTopPadding() + Spacing.s,
                    end = padding.calculateEndPadding(layoutDirection) + Spacing.m,
                    bottom = padding.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                item(key = "starred") {
                    PlaylistTile(
                        title = "Starred",
                        subtitle = episodeCount(state.starredCount),
                        artworks = state.starredArtworks,
                        placeholderIcon = Icons.Outlined.StarOutline,
                        onClick = onOpenStarred,
                    )
                }
                item(key = "downloads") {
                    PlaylistTile(
                        title = "Downloads",
                        subtitle = episodeCount(state.downloadCount),
                        artworks = state.downloadArtworks,
                        placeholderIcon = Icons.Outlined.DownloadForOffline,
                        onClick = onOpenDownloads,
                    )
                }
                items(state.playlists, key = { it.playlist.id }) { entry ->
                    val playlist = entry.playlist
                    val isSmart = playlist.kind == PlaylistKind.SMART
                    PlaylistTile(
                        title = playlist.name,
                        subtitle = if (isSmart) "Smart playlist" else episodeCount(entry.itemCount),
                        artworks = state.artworks[playlist.id].orEmpty(),
                        placeholderIcon = when (playlist.kind) {
                            PlaylistKind.MANUAL -> Icons.AutoMirrored.Outlined.PlaylistPlay
                            // LISTEN_LATER is retired and never listed; it only exists so the enum stays exhaustive.
                            PlaylistKind.SMART, PlaylistKind.LISTEN_LATER -> Icons.Outlined.AutoAwesome
                        },
                        onClick = { onOpenPlaylist(playlist.id) },
                    )
                }
            }
        }
    }

    if (newPlaylistOpen) {
        NewPlaylistDialog(
            onDismiss = { newPlaylistOpen = false },
            onCreate = { name ->
                newPlaylistOpen = false
                onCreatePlaylist(name)
            },
        )
    }
}

private fun episodeCount(count: Int) = if (count == 1) "1 episode" else "$count episodes"

/** The top-bar "+": New playlist or New smart playlist. */
@Composable
private fun NewPlaylistMenu(onNewPlaylist: () -> Unit, onNewSmartPlaylist: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassIconButton(
            icon = Icons.Outlined.Add,
            contentDescription = "New playlist",
            onClick = { open = true },
            size = 40.dp,
            iconSize = 20.dp,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedRectangle(20.dp),
        ) {
            DropdownMenuItem(
                text = { Text("New playlist") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null) },
                onClick = {
                    open = false
                    onNewPlaylist()
                },
            )
            DropdownMenuItem(
                text = { Text("New smart playlist") },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                onClick = {
                    open = false
                    onNewSmartPlaylist()
                },
            )
        }
    }
}

/** A square mosaic with the title and a secondary line below, like Spotify's library grid. */
@Composable
private fun PlaylistTile(
    title: String,
    subtitle: String,
    artworks: List<String>,
    placeholderIcon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        PlaylistMosaic(
            artworks = artworks,
            placeholderIcon = placeholderIcon,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Up to four podcast artworks as a 2×2 mosaic. One artwork fills the square; two or three leave
 * the remaining cells as filled tiles; none shows [placeholderIcon].
 */
@Composable
private fun PlaylistMosaic(artworks: List<String>, placeholderIcon: ImageVector, modifier: Modifier = Modifier) {
    val shape = remember { RoundedRectangle(20.dp) }
    val colors = AppTheme.colors
    Box(modifier.clip(shape).background(colors.surfaceGrouped)) {
        when (artworks.size) {
            0 -> Icon(
                placeholderIcon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.align(Alignment.Center).size(48.dp),
            )
            1 -> ShapedArtwork(
                url = artworks.first(),
                contentDescription = null,
                shape = RectangleShape,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Column(Modifier.fillMaxSize()) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (col in 0 until 2) {
                            val url = artworks.getOrNull(row * 2 + col)
                            val cell = Modifier.weight(1f).fillMaxHeight()
                            if (url != null) {
                                ShapedArtwork(url = url, contentDescription = null, shape = RectangleShape, modifier = cell)
                            } else {
                                Box(cell.background(colors.accentSubtle))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Name dialog for a new manual playlist. Create stays disabled until the name has text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val trimmed = name.trim()
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedRectangle(28.dp),
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (trimmed.isNotEmpty()) onCreate(trimmed) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                GlassButton(
                    onClick = { onCreate(trimmed) },
                    enabled = trimmed.isNotEmpty(),
                    style = GlassStyle.Prominent,
                ) { Text("Create") }
            },
            dismissButton = {
                GlassButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }
}

// ---- Previews -------------------------------------------------------------------------------

private fun previewPlaylists(): List<PlaylistWithCount> = listOf(
    PlaylistWithCount(PlaylistEntity(id = 2, name = "Road trip", kind = PlaylistKind.MANUAL, createdAt = 0L), 7),
    PlaylistWithCount(
        PlaylistEntity(id = 3, name = "Unplayed tech", kind = PlaylistKind.SMART, rulesJson = null, createdAt = 0L),
        0,
    ),
)

@ThemePreviews
@Composable
private fun PlaylistsScreenPreview() {
    AppTheme {
        Surface {
            PlaylistsContent(
                state = PlaylistsUiState(
                    playlists = previewPlaylists(),
                    starredCount = 12,
                    downloadCount = 1,
                    loaded = true,
                ),
                onOpenSettings = {}, onOpenStarred = {}, onOpenDownloads = {},
                onOpenPlaylist = {}, onNewSmartPlaylist = {}, onCreatePlaylist = {},
            )
        }
    }
}

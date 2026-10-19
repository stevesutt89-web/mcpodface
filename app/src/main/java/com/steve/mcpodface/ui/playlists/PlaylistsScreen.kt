package com.steve.mcpodface.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.steve.mcpodface.ui.components.SearchButton
import com.steve.mcpodface.ui.components.SettingsButton
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glassCard
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Playlists tab (PLAN.md §8.11): Starred, History and Stats rows, then the user's playlists with
 * "New playlist" and "New smart playlist". Search and Settings sit in the top bar.
 */
@Composable
fun PlaylistsScreen(
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
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
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onOpenStarred = onOpenStarred,
        onOpenHistory = onOpenHistory,
        onOpenStats = onOpenStats,
        onOpenPlaylist = onOpenPlaylist,
        onNewSmartPlaylist = onNewSmartPlaylist,
        onCreatePlaylist = viewModel::createPlaylist,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsContent(
    state: PlaylistsUiState,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
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
                SearchButton(onOpenSearch)
                SettingsButton(onOpenSettings)
            },
        ) { padding ->
            val layoutDirection = LocalLayoutDirection.current
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + Spacing.m,
                    top = padding.calculateTopPadding() + Spacing.s,
                    end = padding.calculateEndPadding(layoutDirection) + Spacing.m,
                    bottom = padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                item(key = "nav-card") {
                    NavCard(onOpenStarred = onOpenStarred, onOpenHistory = onOpenHistory, onOpenStats = onOpenStats)
                }
                item(key = "playlists-header") {
                    SectionHeader("Playlists")
                }
                item(key = "playlists-card") {
                    PlaylistsCard(
                        playlists = state.playlists,
                        onOpenPlaylist = onOpenPlaylist,
                        onNewPlaylist = { newPlaylistOpen = true },
                        onNewSmartPlaylist = onNewSmartPlaylist,
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

/** Section title above a glass card, same style as the Downloads sections. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = AppTheme.colors.textSecondary,
        modifier = Modifier.padding(start = Spacing.m, top = Spacing.s, bottom = Spacing.xs),
    )
}

/** Starred, History and Stats as one glass card, the rows separated by hairlines. */
@Composable
private fun NavCard(onOpenStarred: () -> Unit, onOpenHistory: () -> Unit, onOpenStats: () -> Unit) {
    val shape = remember { RoundedRectangle(24.dp) }
    Column(
        Modifier
            .fillMaxWidth()
            .glassCard(shape)
            .clip(shape),
    ) {
        NavRow(Icons.Outlined.StarOutline, "Starred", onClick = onOpenStarred)
        HorizontalDivider(
            modifier = Modifier.padding(start = Spacing.m + 24.dp + Spacing.m),
            color = AppTheme.colors.hairline,
        )
        NavRow(Icons.Outlined.History, "History", onClick = onOpenHistory)
        HorizontalDivider(
            modifier = Modifier.padding(start = Spacing.m + 24.dp + Spacing.m),
            color = AppTheme.colors.hairline,
        )
        NavRow(Icons.Outlined.BarChart, "Stats", onClick = onOpenStats)
    }
}

/** Starred / History / Stats row: accent icon, label, chevron. */
@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.m))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.textSecondary)
    }
}

/**
 * The user's playlists (manual with counts, smart marked "Smart"), then the two "New" actions
 * (PLAN.md §8.11). One glass card, hairlines between rows.
 */
@Composable
private fun PlaylistsCard(
    playlists: List<PlaylistWithCount>,
    onOpenPlaylist: (Long) -> Unit,
    onNewPlaylist: () -> Unit,
    onNewSmartPlaylist: () -> Unit,
) {
    val shape = remember { RoundedRectangle(24.dp) }
    val dividerInset = Modifier.padding(start = Spacing.m + 24.dp + Spacing.m)
    Column(
        Modifier
            .fillMaxWidth()
            .glassCard(shape)
            .clip(shape),
    ) {
        playlists.forEach { entry ->
            val playlist = entry.playlist
            val isSmart = playlist.kind == PlaylistKind.SMART
            PlaylistRow(
                icon = when (playlist.kind) {
                    PlaylistKind.MANUAL -> Icons.AutoMirrored.Outlined.PlaylistPlay
                    // LISTEN_LATER is retired and never listed; it only exists so the enum stays exhaustive.
                    PlaylistKind.SMART, PlaylistKind.LISTEN_LATER -> Icons.Outlined.AutoAwesome
                },
                label = playlist.name,
                trailing = if (isSmart) "Smart" else entry.itemCount.toString(),
                onClick = { onOpenPlaylist(playlist.id) },
            )
            HorizontalDivider(modifier = dividerInset, color = AppTheme.colors.hairline)
        }
        PlaylistRow(Icons.Outlined.Add, "New playlist", trailing = null, onClick = onNewPlaylist)
        HorizontalDivider(modifier = dividerInset, color = AppTheme.colors.hairline)
        PlaylistRow(Icons.Outlined.AutoAwesome, "New smart playlist", trailing = null, onClick = onNewSmartPlaylist)
    }
}

/** One playlist row: accent icon, name, optional trailing count or "Smart", chevron. */
@Composable
private fun PlaylistRow(icon: ImageVector, label: String, trailing: String?, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.m))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.textSecondary)
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
                state = PlaylistsUiState(playlists = previewPlaylists(), loaded = true),
                onOpenSearch = {}, onOpenSettings = {}, onOpenStarred = {}, onOpenHistory = {}, onOpenStats = {},
                onOpenPlaylist = {}, onNewSmartPlaylist = {}, onCreatePlaylist = {},
            )
        }
    }
}

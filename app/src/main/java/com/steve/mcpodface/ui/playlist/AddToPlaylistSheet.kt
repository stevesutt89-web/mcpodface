package com.steve.mcpodface.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * "Add to playlist" sheet (PLAN.md §8.11): Listen Later and the manual playlists, each with a tick
 * when the episode is in it, plus "New playlist". Tapping a row toggles membership.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(episodeId: Long, onDismiss: () -> Unit) {
    // Keyed per episode so each sheet gets its own bound ViewModel.
    val viewModel: AddToPlaylistViewModel = hiltViewModel(key = "add-to-playlist-$episodeId")
    LaunchedEffect(viewModel, episodeId) { viewModel.bind(episodeId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetShape = remember { RoundedRectangle(32.dp) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = sheetShape,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        // The sheet is its own window: don't let its glass sample the page's layer (PLAN.md §11).
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            AddToPlaylistContent(
                state = state,
                onToggle = viewModel::toggle,
                onCreate = { name ->
                    viewModel.createPlaylistAndAdd(name)
                    onDismiss()
                },
            )
        }
    }
}

/** The sheet body without the ModalBottomSheet wrapper, so it can be previewed directly. */
@Composable
internal fun AddToPlaylistContent(
    state: AddToPlaylistUiState,
    onToggle: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.m).padding(bottom = Spacing.l)) {
        Text(
            "Add to playlist",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = Spacing.s),
        )
        state.rows.forEach { row ->
            PlaylistRow(
                icon = if (row.kind == PlaylistKind.LISTEN_LATER) Icons.Rounded.WatchLater else Icons.AutoMirrored.Rounded.PlaylistPlay,
                name = row.name,
                subtitle = episodeCountLabel(row.itemCount),
                checked = row.contains,
                onClick = { onToggle(row.playlistId) },
            )
        }
        NewPlaylistRow(onCreate = onCreate)
    }
}

@Composable
private fun PlaylistRow(
    icon: ImageVector,
    name: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        if (checked) {
            Icon(Icons.Outlined.Check, contentDescription = "In $name", tint = colors.accent, modifier = Modifier.size(22.dp))
        }
    }
}

/** A row that expands into a name field and a Create button. */
@Composable
private fun NewPlaylistRow(onCreate: (String) -> Unit) {
    val colors = AppTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }

    if (!expanded) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.m))
            Text("New playlist", style = MaterialTheme.typography.bodyLarge, color = colors.accent)
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.s))
            GlassButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Create")
            }
        }
    }
}

private fun episodeCountLabel(count: Int): String = when (count) {
    0 -> "No episodes"
    1 -> "1 episode"
    else -> "$count episodes"
}

@ThemePreviews
@Composable
private fun AddToPlaylistContentPreview() {
    AppTheme {
        Surface {
            AddToPlaylistContent(
                state = AddToPlaylistUiState(
                    rows = listOf(
                        AddToPlaylistRow(1L, "Listen Later", PlaylistKind.LISTEN_LATER, itemCount = 4, contains = true),
                        AddToPlaylistRow(2L, "Morning commute", PlaylistKind.MANUAL, itemCount = 12, contains = false),
                        AddToPlaylistRow(3L, "Science", PlaylistKind.MANUAL, itemCount = 1, contains = true),
                    ),
                    loaded = true,
                ),
                onToggle = {},
                onCreate = {},
            )
        }
    }
}

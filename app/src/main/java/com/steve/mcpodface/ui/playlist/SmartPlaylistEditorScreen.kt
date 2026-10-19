package com.steve.mcpodface.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.playlist.SmartRules
import com.steve.mcpodface.data.search.DurationFilter
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.EpisodeRow
import com.steve.mcpodface.ui.components.GlassTopBarScaffold
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.search.durationLabel
import com.steve.mcpodface.ui.search.statusLabel
import com.steve.mcpodface.ui.settings.ChoiceRow
import com.steve.mcpodface.ui.settings.SectionHeader
import com.steve.mcpodface.ui.settings.SettingsCard
import com.steve.mcpodface.ui.settings.SettingsRow
import com.steve.mcpodface.ui.settings.SwitchRow
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import kotlinx.coroutines.launch

/** Every change the editor can make. Previews use the no-op defaults. */
data class SmartPlaylistEditorActions(
    val onNameChange: (String) -> Unit = {},
    val onRulesChange: ((SmartRules) -> SmartRules) -> Unit = {},
    val onTogglePodcast: (Long) -> Unit = {},
    val onAllPodcasts: () -> Unit = {},
    val onSave: () -> Unit = {},
)

/**
 * Smart playlist rules editor (PLAN.md §8.11): name, grouped rules, and a live preview of what the
 * rules match. Saving creates or updates the playlist and hands its id to [onSaved].
 */
@Composable
fun SmartPlaylistEditorScreen(onBack: () -> Unit, onSaved: (playlistId: Long) -> Unit) {
    val viewModel: SmartPlaylistEditorViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SmartPlaylistEditorContent(
        state = state,
        actions = SmartPlaylistEditorActions(
            onNameChange = viewModel::onNameChange,
            onRulesChange = viewModel::onRulesChange,
            onTogglePodcast = viewModel::togglePodcast,
            onAllPodcasts = viewModel::selectAllPodcasts,
            onSave = { scope.launch { viewModel.save()?.let(onSaved) } },
        ),
        onBack = onBack,
    )
}

/** The editor body, stateless apart from which sheet is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartPlaylistEditorContent(
    state: SmartPlaylistEditorUiState,
    actions: SmartPlaylistEditorActions,
    onBack: () -> Unit,
) {
    var showPodcasts by remember { mutableStateOf(false) }
    val colors = AppTheme.colors
    GlassPage {
        GlassTopBarScaffold(
            title = smartPlaylistEditorTitle(state.isNew),
            navigationIcon = { BackButton(onBack) },
            actions = { SaveAction(enabled = state.canSave, onClick = actions.onSave) },
            background = Color.Transparent,
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                if (state.notFound) {
                    item(key = "not-found") {
                        Text(
                            "This smart playlist no longer exists.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                            modifier = Modifier.fillMaxWidth().padding(Spacing.m),
                        )
                    }
                }
                if (state.loaded && !state.notFound) {
                    val rules = state.rules
                    item(key = "name") {
                        CardItem {
                            EditorTextField(label = "Name", initial = state.name, onChange = actions.onNameChange)
                        }
                    }
                    item(key = "episodes") {
                        CardItem(header = "Episodes") {
                            SettingsRow(
                                title = "Podcasts",
                                modifier = Modifier.clickable(role = Role.Button) { showPodcasts = true },
                                trailing = {
                                    Text(
                                        smartPlaylistPodcastsLabel(rules.podcastIds.size),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.textSecondary,
                                    )
                                },
                            )
                            ChoiceRow(
                                title = "Episodes",
                                options = SmartPlaylistStatusOptions.map { it to statusLabel(it) },
                                selected = rules.status,
                                onSelect = { status -> actions.onRulesChange { it.copy(status = status) } },
                            )
                            ChoiceRow(
                                title = "Published",
                                options = SmartPlaylistPublishedOptions.map { it to smartPlaylistPublishedLabel(it) },
                                selected = rules.publishedWithinDays,
                                onSelect = { days -> actions.onRulesChange { it.copy(publishedWithinDays = days) } },
                            )
                            ChoiceRow(
                                title = "Length",
                                options = DurationFilter.entries.map { it to durationLabel(it) },
                                selected = rules.duration,
                                onSelect = { duration -> actions.onRulesChange { it.copy(duration = duration) } },
                            )
                            SwitchRow(
                                title = "Downloaded only",
                                checked = rules.downloadedOnly,
                                onChange = { on -> actions.onRulesChange { it.copy(downloadedOnly = on) } },
                            )
                            SwitchRow(
                                title = "Starred only",
                                checked = rules.starredOnly,
                                onChange = { on -> actions.onRulesChange { it.copy(starredOnly = on) } },
                                divider = false,
                            )
                        }
                    }
                    item(key = "matching") {
                        CardItem(header = "Matching") {
                            EditorTextField(
                                label = "Contains words",
                                initial = rules.text,
                                onChange = { text -> actions.onRulesChange { it.copy(text = text) } },
                            )
                            Text(
                                SmartPlaylistWordsHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.s),
                            )
                            SwitchRow(
                                title = "Title only",
                                checked = rules.titleOnly,
                                onChange = { on -> actions.onRulesChange { it.copy(titleOnly = on) } },
                                divider = false,
                            )
                        }
                    }
                    item(key = "order") {
                        CardItem(header = "Order and size") {
                            ChoiceRow(
                                title = "Order",
                                options = SmartPlaylistOrderOptions.map { it to smartPlaylistOrderLabel(it) },
                                selected = rules.sort,
                                onSelect = { sort -> actions.onRulesChange { it.copy(sort = sort) } },
                            )
                            ChoiceRow(
                                title = "Limit",
                                options = SmartPlaylistLimitOptions.map { it to smartPlaylistLimitLabel(it) },
                                selected = rules.limit,
                                onSelect = { limit -> actions.onRulesChange { it.copy(limit = limit) } },
                                divider = false,
                            )
                        }
                    }
                    item(key = "preview-header") { SectionHeader(text = "Preview") }
                    item(key = "preview-count") {
                        val matches = state.preview
                        Text(
                            text = if (matches == null) {
                                "Finding matches…"
                            } else {
                                smartPlaylistMatchLabel(matches.size, rules.limit)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        )
                    }
                    val shown = state.preview.orEmpty().take(SmartPlaylistPreviewRows)
                    if (shown.isNotEmpty()) {
                        item(key = "preview-rows") {
                            CardItem {
                                shown.forEachIndexed { index, episode ->
                                    // Preview rows only show; tapping one does nothing.
                                    EpisodeRow(
                                        item = episode,
                                        onClick = {},
                                        onPlayClick = {},
                                        showPodcastTitle = true,
                                        now = state.now,
                                    )
                                    if (index < shown.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = Spacing.m),
                                            color = colors.hairline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPodcasts) {
        PodcastPickerSheet(
            subscriptions = state.subscriptions,
            selected = state.rules.podcastIds,
            onToggle = actions.onTogglePodcast,
            onAll = actions.onAllPodcasts,
            onDismiss = { showPodcasts = false },
        )
    }
}

@Composable
private fun SaveAction(enabled: Boolean, onClick: () -> Unit) {
    GlassButton(
        onClick = onClick,
        enabled = enabled,
        style = GlassStyle.Prominent,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.padding(end = Spacing.s),
    ) {
        Text("Save", style = MaterialTheme.typography.labelLarge)
    }
}

/** One grouped glass card, inset from the screen edges, with an optional section heading above it. */
@Composable
private fun CardItem(header: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
        if (header != null) SectionHeader(text = header)
        SettingsCard(content = content)
    }
}

/**
 * A text field that keeps its own text while typing (so the cursor doesn't jump) and reports every
 * change. [initial] is read once, when the field first appears; the ViewModel holds the same value.
 */
@Composable
private fun EditorTextField(label: String, initial: String, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val colors = AppTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(Spacing.m),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            focusedLabelColor = colors.accent,
            cursorColor = colors.accent,
        ),
    )
}

/**
 * Multi-select of subscriptions (PLAN.md §8.11). "All subscriptions" is the empty selection. The
 * sheet is its own window, so it doesn't sample the page backdrop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastPickerSheet(
    subscriptions: List<PodcastEntity>,
    selected: List<Long>,
    onToggle: (Long) -> Unit,
    onAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = remember { RoundedRectangle(32.dp) },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.m).padding(bottom = Spacing.l)) {
                Text(
                    "Podcasts",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    item(key = "all") {
                        PodcastPickerRow(
                            title = "All subscriptions",
                            podcast = null,
                            checked = selected.isEmpty(),
                            onClick = onAll,
                        )
                    }
                    items(subscriptions, key = { it.id }) { podcast ->
                        PodcastPickerRow(
                            title = podcast.title,
                            podcast = podcast,
                            checked = podcast.id in selected,
                            onClick = { onToggle(podcast.id) },
                        )
                    }
                }
                if (subscriptions.isEmpty()) {
                    Text(
                        "You have no subscriptions yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = Spacing.s),
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastPickerRow(title: String, podcast: PodcastEntity?, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (podcast != null) {
            Artwork(url = podcast.artworkUrl, contentDescription = null, size = 40.dp)
        } else {
            Spacer(Modifier.size(40.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.m),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = colors.accent),
        )
    }
}

private val previewPodcasts = listOf(
    PodcastEntity(id = 1, feedUrl = "https://example.com/radiolab", title = "Radiolab", isSubscribed = true),
    PodcastEntity(id = 2, feedUrl = "https://example.com/hardcore", title = "Hardcore History", isSubscribed = true),
    PodcastEntity(id = 3, feedUrl = "https://example.com/rest", title = "Rest Is History", isSubscribed = true),
)

// Sample "now" for previews, matching the EpisodeRow sample data (2026-10-19T12:00Z).
private const val PREVIEW_NOW = 1_792_411_200_000L

@ThemePreviews
@Composable
private fun SmartPlaylistEditorNewPreview() {
    AppTheme {
        Surface {
            SmartPlaylistEditorContent(
                state = SmartPlaylistEditorUiState(isNew = true, loaded = true, subscriptions = previewPodcasts),
                actions = SmartPlaylistEditorActions(),
                onBack = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun SmartPlaylistEditorEditPreview() {
    AppTheme {
        Surface {
            SmartPlaylistEditorContent(
                state = SmartPlaylistEditorUiState(
                    isNew = false,
                    loaded = true,
                    name = "Fresh from Radiolab",
                    rules = SmartRules(podcastIds = listOf(1, 3), publishedWithinDays = 7, text = "bridge", limit = 25),
                    subscriptions = previewPodcasts,
                    preview = previewEpisodeItems(),
                    now = PREVIEW_NOW,
                    canSave = true,
                ),
                actions = SmartPlaylistEditorActions(),
                onBack = {},
            )
        }
    }
}

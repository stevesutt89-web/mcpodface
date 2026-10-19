package com.steve.mcpodface.ui.podcastsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.settings.ActionRow
import com.steve.mcpodface.ui.settings.ChoiceRow
import com.steve.mcpodface.ui.settings.SectionHeader
import com.steve.mcpodface.ui.settings.SettingsCard
import com.steve.mcpodface.ui.settings.SettingsRow
import com.steve.mcpodface.ui.settings.SwitchRow
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Per-podcast settings (PLAN.md §8.4): every row overrides one global setting, and "Default (x)" clears
 * the override. Each change is saved immediately. The sheet is its own window, so its glass has no backdrop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSettingsSheet(podcastId: Long, onDismiss: () -> Unit) {
    val viewModel: PodcastSettingsViewModel =
        hiltViewModel<PodcastSettingsViewModel, PodcastSettingsViewModel.Factory>(
            key = "podcast-settings-$podcastId",
        ) { it.create(podcastId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shape = remember { RoundedRectangle(32.dp) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = shape,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            PodcastSettingsContent(
                state = state,
                onUpdate = viewModel::update,
                onTogglePaused = viewModel::togglePaused,
                onBackfillNow = viewModel::backfillNow,
            )
        }
    }
}

/** The sheet body without the ModalBottomSheet wrapper, so it can be previewed directly. */
@Composable
private fun PodcastSettingsContent(
    state: PodcastSettingsUiState,
    onUpdate: ((PodcastSettingsEntity) -> PodcastSettingsEntity) -> Unit,
    onTogglePaused: () -> Unit = {},
    onBackfillNow: () -> Unit = {},
) {
    if (!state.loaded) return
    val colors = AppTheme.colors
    val ctx = state.inheritance
    val e = state.settings
    val o = PodcastSettingsOptions

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.m)
            .padding(bottom = Spacing.l),
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Settings for this podcast",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.xs),
        )

        SectionHeader("Downloads")
        SettingsCard {
            ChoiceRow(
                title = "Auto-download",
                options = o.autoDownload.options(ctx),
                selected = o.autoDownload.selected(e),
                onSelect = { v -> onUpdate { o.autoDownload.apply(it, v) } },
            )
            ChoiceRow(
                title = "Keep latest",
                options = o.keepLatest.options(ctx),
                selected = o.keepLatest.selected(e),
                onSelect = { v -> onUpdate { o.keepLatest.apply(it, v) } },
            )
            ChoiceRow(
                title = "Download on",
                options = o.allowCellular.options(ctx),
                selected = o.allowCellular.selected(e),
                onSelect = { v -> onUpdate { o.allowCellular.apply(it, v) } },
                divider = false,
            )
        }

        SectionHeader("Auto-delete")
        SettingsCard {
            ChoiceRow(
                title = "Delete played",
                options = o.deletePlayed.options(ctx),
                selected = o.deletePlayed.selected(e),
                onSelect = { v -> onUpdate { o.deletePlayed.apply(it, v) } },
            )
            ChoiceRow(
                title = "Delete episodes older than",
                options = o.maxAgeDays.options(ctx),
                selected = o.maxAgeDays.selected(e),
                onSelect = { v -> onUpdate { o.maxAgeDays.apply(it, v) } },
                divider = false,
            )
        }

        SectionHeader("Playback")
        SettingsCard {
            ChoiceRow(
                title = "Episode order",
                options = o.sortOrder.options(ctx),
                selected = o.sortOrder.selected(e),
                onSelect = { v -> onUpdate { o.sortOrder.apply(it, v) } },
            )
            ChoiceRow(
                title = "Playback speed",
                options = o.playbackSpeed.options(ctx),
                selected = o.playbackSpeed.selected(e),
                onSelect = { v -> onUpdate { o.playbackSpeed.apply(it, v) } },
            )
            ChoiceRow(
                title = "Skip intro",
                options = o.skipIntro.options,
                selected = o.skipIntro.selected(e),
                onSelect = { v -> onUpdate { o.skipIntro.apply(it, v) } },
            )
            ChoiceRow(
                title = "Skip outro",
                options = o.skipOutro.options,
                selected = o.skipOutro.selected(e),
                onSelect = { v -> onUpdate { o.skipOutro.apply(it, v) } },
                divider = false,
            )
        }

        SectionHeader("Notifications and Up Next")
        SettingsCard {
            SwitchRow(
                title = "New episode notifications",
                checked = e.notifyNewEpisodes,
                onChange = { v -> onUpdate { o.applyNotifyNewEpisodes(it, v) } },
            )
            ChoiceRow(
                title = "Add new episodes to Up Next",
                options = o.autoAddToQueue.options,
                selected = o.autoAddToQueue.selected(e),
                onSelect = { v -> onUpdate { o.autoAddToQueue.apply(it, v) } },
                divider = false,
            )
        }

        SectionHeader("Archive")
        SettingsCard {
            ChoiceRow(
                title = "Archive backfill",
                options = o.backfill.options(ctx),
                selected = o.backfill.selected(e),
                onSelect = { v -> onUpdate { o.backfill.apply(it, v) } },
            )
            val archive = state.archive
            SettingsRow(
                title = archive?.headline ?: "Archive",
                subtitle = archive?.detail,
                divider = true,
            )
            val phase = archive?.phase
            val canPause = phase != null && phase != ArchivePhase.COMPLETE && phase != ArchivePhase.OFF
            if (canPause) {
                ActionRow(
                    title = if (phase == ArchivePhase.PAUSED) "Resume archive" else "Pause archive",
                    onClick = onTogglePaused,
                )
            }
            ActionRow(
                title = "Backfill now",
                onClick = onBackfillNow,
                enabled = state.backfillOn && phase != ArchivePhase.COMPLETE,
                divider = false,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PodcastSettingsContentPreview() {
    AppTheme {
        Surface {
            PodcastSettingsContent(
                state = PodcastSettingsUiState(
                    loaded = true,
                    title = "Radiolab",
                    feedType = FeedType.SERIAL,
                    settings = PodcastSettingsEntity(
                        podcastId = 1L,
                        keepLatestCount = 3,
                        skipIntroSec = 30,
                        autoDownload = false,
                    ),
                    global = GlobalSettings(),
                    backfillOn = true,
                    archive = PodcastArchiveStatus(
                        phase = ArchivePhase.BACKFILLING,
                        headline = "Archive: 412 of ~1,200 episodes · backfilling",
                        detail = null,
                    ),
                ),
                onUpdate = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PodcastSettingsArchiveFailedPreview() {
    AppTheme {
        Surface {
            PodcastSettingsContent(
                state = PodcastSettingsUiState(
                    loaded = true,
                    title = "Radiolab",
                    feedType = FeedType.SERIAL,
                    settings = PodcastSettingsEntity(podcastId = 1L),
                    global = GlobalSettings(),
                    backfillOn = true,
                    archive = PodcastArchiveStatus(
                        phase = ArchivePhase.FAILED,
                        headline = "Archive: 88 episodes · failed",
                        detail = "Last error: HTTP 503. Next try after 3:40 pm.",
                    ),
                ),
                onUpdate = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PodcastSettingsContentDefaultsPreview() {
    AppTheme {
        Surface {
            PodcastSettingsContent(
                state = PodcastSettingsUiState(
                    loaded = true,
                    title = "The Daily",
                    feedType = FeedType.EPISODIC,
                    settings = PodcastSettingsEntity(podcastId = 2L),
                    global = GlobalSettings(),
                ),
                onUpdate = {},
            )
        }
    }
}

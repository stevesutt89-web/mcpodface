package com.steve.mcpodface.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steve.mcpodface.BuildConfig
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.ThemeMode
import com.steve.mcpodface.ui.addpodcast.GlassDialogScope
import com.steve.mcpodface.ui.addpodcast.ImportProgressDialog
import com.steve.mcpodface.ui.addpodcast.ImportResultDialog
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.formatBytes
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.podcastsettings.archiveProgressLine
import com.steve.mcpodface.ui.podcastsettings.requestsLine
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private const val OPML_MIME_TYPE = "text/x-opml"
private val IMPORT_MIME_TYPES = arrayOf("text/x-opml", "text/xml", "application/xml", "*/*")
private const val EXPORT_FILE_NAME = "mcpodface-subscriptions.opml"

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importHidden by viewModel.importHidden.collectAsStateWithLifecycle()
    val pendingCleanup by viewModel.pendingCleanup.collectAsStateWithLifecycle()
    val archive by viewModel.archive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val batteryUnrestricted = rememberBatteryUnrestricted()

    // The database size changes as backfill and refresh write, so re-read it whenever Settings resumes.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshDatabaseSize()
        onPauseOrDispose {}
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importOpml(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OPML_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.exportOpml(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { text -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    SettingsContent(
        settings = settings,
        batteryUnrestricted = batteryUnrestricted,
        onBack = onBack,
        onUpdate = viewModel::update,
        onImportOpml = { importPicker.launch(IMPORT_MIME_TYPES) },
        onExportOpml = { exportPicker.launch(EXPORT_FILE_NAME) },
        onOpenBatterySettings = { openBatterySettings(context) },
        archive = archive,
        onBackfillNow = viewModel::backfillNow,
    )

    // A setting change that would delete downloads waits here until the user confirms (PLAN.md §6.5).
    pendingCleanup?.let { pending ->
        CleanupPreviewDialog(
            count = pending.count,
            bytes = pending.bytes,
            onRemove = viewModel::confirmCleanup,
            onCancel = viewModel::cancelCleanup,
        )
    }

    // Same progress and result dialogs as the Search tab's OPML import (the import runs in the app scope).
    val progress = importProgress
    if (progress != null) {
        if (progress.running) {
            if (!importHidden) {
                ImportProgressDialog(progress = progress, onHide = viewModel::hideImport)
            }
        } else {
            GlassDialogScope {
                ImportResultDialog(progress = progress, onDismiss = viewModel::dismissImportResult)
            }
        }
    }
}

@Composable
private fun SettingsContent(
    settings: GlobalSettings,
    batteryUnrestricted: Boolean,
    onBack: () -> Unit,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
    onOpenBatterySettings: () -> Unit = {},
    archive: ArchiveOverview = ArchiveOverview(),
    onBackfillNow: () -> Unit = {},
) {
    GlassPage {
        LargeTitleScaffold(
            title = "Settings",
            navigationIcon = { BackButton(onBack) },
            background = Color.Transparent,
        ) { padding ->
            LazyColumn(
                contentPadding = padding + PaddingValues(horizontal = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                appearanceSection(settings, onUpdate)
                downloadsSection(settings, onUpdate)
                autoDeleteSection(settings, onUpdate)
                playbackSection(settings, onUpdate)
                refreshSection(settings, onUpdate)
                archiveSection(settings, onUpdate, archive, onBackfillNow)
                backupSection(onImportOpml, onExportOpml)
                batterySection(batteryUnrestricted, onOpenBatterySettings)
                aboutSection()
            }
        }
    }
}

private fun LazyListScope.appearanceSection(settings: GlobalSettings, onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit) {
    item(key = "header-appearance") { SectionHeader("Appearance", Icons.Rounded.Palette) }
    item(key = "card-theme") {
        SettingsCard {
            ThemeMode.entries.forEachIndexed { index, mode ->
                ThemeRow(
                    mode = mode,
                    selected = settings.theme == mode,
                    divider = index < ThemeMode.entries.lastIndex,
                    onSelect = { onUpdate { it.copy(theme = mode) } },
                )
            }
        }
    }
    item(key = "card-display") {
        SettingsCard {
            SwitchRow(
                title = "Pure black dark mode",
                icon = Icons.Rounded.DarkMode,
                checked = settings.pureBlack,
                onChange = { v -> onUpdate { it.copy(pureBlack = v) } },
            )
            SwitchRow(
                title = "Tint player from artwork",
                icon = Icons.Rounded.Image,
                checked = settings.tintPlayer,
                onChange = { v -> onUpdate { it.copy(tintPlayer = v) } },
            )
            SwitchRow(
                title = "Use dynamic color",
                icon = Icons.Rounded.Palette,
                checked = settings.dynamicColor,
                onChange = { v -> onUpdate { it.copy(dynamicColor = v) } },
                divider = false,
            )
        }
    }
}

private fun LazyListScope.downloadsSection(settings: GlobalSettings, onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit) {
    item(key = "header-downloads") { SectionHeader("Downloads", Icons.Rounded.Download) }
    item(key = "card-downloads") {
        SettingsCard {
            SwitchRow(
                title = "Auto-download new episodes",
                checked = settings.autoDownload,
                onChange = { v -> onUpdate { it.copy(autoDownload = v) } },
            )
            ChoiceRow(
                title = "Keep latest",
                options = SettingsOptions.keepLatest.options,
                selected = SettingsOptions.keepLatest.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.keepLatest.apply(it, v) } },
            )
            ChoiceRow(
                title = "Download on",
                options = SettingsOptions.downloadOn.options,
                selected = SettingsOptions.downloadOn.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.downloadOn.apply(it, v) } },
            )
            SwitchRow(
                title = "Only while charging",
                checked = settings.downloadOnlyWhileCharging,
                onChange = { v -> onUpdate { it.copy(downloadOnlyWhileCharging = v) } },
            )
            SwitchRow(
                title = "Stream on mobile data",
                checked = settings.streamOnCellular,
                subtitle = if (settings.streamOnCellular) {
                    null
                } else {
                    "Off: episodes must be downloaded to play away from Wi-Fi"
                },
                onChange = { v -> onUpdate { it.copy(streamOnCellular = v) } },
                divider = false,
            )
        }
    }
}

private fun LazyListScope.autoDeleteSection(settings: GlobalSettings, onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit) {
    item(key = "header-autodelete") { SectionHeader("Auto-delete", Icons.Rounded.AutoDelete) }
    item(key = "card-autodelete") {
        SettingsCard {
            ChoiceRow(
                title = "Delete played episodes",
                options = SettingsOptions.deletePlayed.options,
                selected = SettingsOptions.deletePlayed.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.deletePlayed.apply(it, v) } },
            )
            ChoiceRow(
                title = "Delete episodes older than",
                options = SettingsOptions.maxAgeDays.options,
                selected = SettingsOptions.maxAgeDays.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.maxAgeDays.apply(it, v) } },
            )
            SwitchRow(
                title = "Count manual downloads toward limit",
                checked = settings.countManualTowardLimit,
                onChange = { v -> onUpdate { it.copy(countManualTowardLimit = v) } },
                divider = false,
            )
        }
    }
}

private fun LazyListScope.playbackSection(settings: GlobalSettings, onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit) {
    item(key = "header-playback") { SectionHeader("Playback", Icons.Rounded.PlayCircle) }
    item(key = "card-playback") {
        SettingsCard {
            ChoiceRow(
                title = "Skip back",
                options = SettingsOptions.skipBack.options,
                selected = SettingsOptions.skipBack.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.skipBack.apply(it, v) } },
            )
            ChoiceRow(
                title = "Skip forward",
                options = SettingsOptions.skipForward.options,
                selected = SettingsOptions.skipForward.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.skipForward.apply(it, v) } },
            )
            ChoiceRow(
                title = "Default speed",
                options = SettingsOptions.defaultSpeed.options,
                selected = SettingsOptions.defaultSpeed.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.defaultSpeed.apply(it, v) } },
            )
            SwitchRow(
                title = "Autoplay next episode",
                checked = settings.autoplayNext,
                subtitle = "From the same podcast when Up Next is empty",
                onChange = { v -> onUpdate { it.copy(autoplayNext = v) } },
            )
            SwitchRow(
                title = "Rewind after a long pause",
                checked = settings.resumeRewind,
                subtitle = "Back 3 seconds after a pause of 5+ minutes",
                onChange = { v -> onUpdate { it.copy(resumeRewind = v) } },
            )
            ChoiceRow(
                title = "Mark as played at",
                options = SettingsOptions.markPlayed.options,
                selected = SettingsOptions.markPlayed.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.markPlayed.apply(it, v) } },
            )
            SwitchRow(
                title = "Trim silence",
                checked = settings.trimSilence,
                onChange = { v -> onUpdate { it.copy(trimSilence = v) } },
            )
            SwitchRow(
                title = "Voice boost",
                checked = settings.voiceBoost,
                onChange = { v -> onUpdate { it.copy(voiceBoost = v) } },
                divider = false,
            )
        }
    }
}

private fun LazyListScope.refreshSection(settings: GlobalSettings, onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit) {
    item(key = "header-refresh") { SectionHeader("Refresh", Icons.Rounded.Refresh) }
    item(key = "card-refresh") {
        SettingsCard {
            ChoiceRow(
                title = "Check for new episodes",
                options = SettingsOptions.refreshInterval.options,
                selected = SettingsOptions.refreshInterval.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.refreshInterval.apply(it, v) } },
            )
            SwitchRow(
                title = "New episode notifications",
                checked = settings.notifyNewEpisodes,
                onChange = { v -> onUpdate { it.copy(notifyNewEpisodes = v) } },
                divider = false,
            )
        }
    }
}

private fun LazyListScope.archiveSection(
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    archive: ArchiveOverview,
    onBackfillNow: () -> Unit,
) {
    item(key = "header-archive") { SectionHeader("Search & archive", Icons.Rounded.ManageSearch) }
    item(key = "card-archive") {
        val archiveOn = settings.backfillEnabled
        SettingsCard {
            SwitchRow(
                title = "Build episode archive",
                checked = settings.backfillEnabled,
                subtitle = "Slowly fetches older episodes for long-running shows",
                onChange = { v -> onUpdate { it.copy(backfillEnabled = v) } },
            )
            SwitchRow(
                title = "Archive on Wi-Fi only",
                checked = settings.backfillWifiOnly,
                enabled = archiveOn,
                onChange = { v -> onUpdate { it.copy(backfillWifiOnly = v) } },
            )
            SwitchRow(
                title = "Archive only while charging",
                checked = settings.backfillChargingOnly,
                enabled = archiveOn,
                onChange = { v -> onUpdate { it.copy(backfillChargingOnly = v) } },
            )
            ChoiceRow(
                title = "Daily Podcast Index requests",
                options = SettingsOptions.apiDailyBudget.options,
                selected = SettingsOptions.apiDailyBudget.selected(settings),
                onSelect = { v -> onUpdate { SettingsOptions.apiDailyBudget.apply(it, v) } },
                enabled = archiveOn,
            )
            SettingsRow(
                title = archiveProgressLine(archive.progress),
                enabled = archiveOn,
            )
            ValueRow(
                title = "Podcast Index requests today",
                value = requestsLine(archive.usage),
                enabled = archiveOn,
            )
            archive.pausedLabel?.let { label ->
                SettingsRow(
                    title = label,
                    subtitle = "Podcast Index returned a rate limit. Backfill resumes after the pause.",
                    enabled = archiveOn,
                )
            }
            ValueRow(
                title = "Database size",
                value = formatBytes(archive.databaseBytes),
            )
            ActionRow(
                title = "Backfill now",
                onClick = onBackfillNow,
                enabled = archiveOn,
                divider = false,
            )
        }
    }
}

private fun LazyListScope.backupSection(onImportOpml: () -> Unit, onExportOpml: () -> Unit) {
    item(key = "header-backup") { SectionHeader("Backup and transfer", Icons.Rounded.ImportExport) }
    item(key = "card-backup") {
        SettingsCard {
            ActionRow(title = "Import OPML", onClick = onImportOpml)
            ActionRow(title = "Export OPML", onClick = onExportOpml)
            ActionRow(title = "Export all data", onClick = {}, subtitle = "Coming soon", enabled = false)
            ActionRow(title = "Import all data", onClick = {}, subtitle = "Coming soon", enabled = false, divider = false)
        }
    }
}

private fun LazyListScope.batterySection(batteryUnrestricted: Boolean, onOpenBatterySettings: () -> Unit) {
    item(key = "header-battery") { SectionHeader("Battery", Icons.Rounded.BatteryChargingFull) }
    item(key = "card-battery") {
        SettingsCard {
            ActionRow(
                title = "Battery optimisation",
                onClick = onOpenBatterySettings,
                subtitle = if (batteryUnrestricted) {
                    "Unrestricted"
                } else {
                    "Optimised — background refresh may be delayed"
                },
                subtitleColor = if (batteryUnrestricted) {
                    AppTheme.colors.textSecondary
                } else {
                    MaterialTheme.colorScheme.error
                },
                divider = true,
            )
            Text(
                "On Samsung, also set McPodface to Unrestricted in Settings → Apps → McPodface → Battery.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            )
        }
    }
}

private fun LazyListScope.aboutSection() {
    item(key = "header-about") { SectionHeader("About", Icons.Rounded.Info) }
    item(key = "card-about") {
        SettingsCard {
            SettingsRow(
                title = "McPodface",
                icon = Icons.Rounded.Podcasts,
                divider = false,
                trailing = {
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.colors.textSecondary,
                    )
                },
            )
        }
    }
}

/** Whether Android is allowed to run this app in the background without battery restrictions. */
private fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

/** Re-checked every time the screen resumes, so returning from the system dialog updates the row. */
@Composable
private fun rememberBatteryUnrestricted(): Boolean {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    LifecycleResumeEffect(context) {
        unrestricted = isBatteryUnrestricted(context)
        onPauseOrDispose {}
    }
    return unrestricted
}

private fun openBatterySettings(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(request)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@ThemePreviews
@Composable
private fun SettingsPreview() {
    AppTheme {
        Surface {
            SettingsContent(
                settings = GlobalSettings(),
                batteryUnrestricted = false,
                onBack = {},
                onUpdate = {},
            )
        }
    }
}

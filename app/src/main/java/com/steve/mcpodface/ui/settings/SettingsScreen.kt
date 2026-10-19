package com.steve.mcpodface.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.BuildConfig
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.prefs.ThemeMode
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Phase 0 version: only the Appearance group. Task 7.1 builds the full grouped settings (PLAN.md §8.10).

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<GlobalSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    fun update(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(settings = settings, onBack = onBack, onUpdate = viewModel::update)
}

@Composable
private fun SettingsContent(
    settings: GlobalSettings,
    onBack: () -> Unit,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
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
                item(key = "header-appearance") { SectionHeader("Appearance") }
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
                            title = "Use dynamic color",
                            icon = Icons.Rounded.Palette,
                            checked = settings.dynamicColor,
                            onChange = { v -> onUpdate { it.copy(dynamicColor = v) } },
                            divider = false,
                        )
                    }
                }
                item(key = "header-about") { SectionHeader("About") }
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
        }
    }
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/** Switch colours shared by settings and the speed sheet: accent track, white thumb, no border. */
@Composable
internal fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AppTheme.colors.accent,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = AppTheme.colors.textPrimary.copy(alpha = 0.15f),
    uncheckedBorderColor = Color.Transparent,
)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.textSecondary,
        modifier = Modifier.padding(start = Spacing.m, top = Spacing.l),
    )
}

/** A rounded glass group of rows (iOS Settings card). */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = remember { RoundedRectangle(24.dp) }
    Column(
        Modifier
            .fillMaxWidth()
            .glass(shape, GlassStyle.Regular)
            .clip(shape),
        content = content,
    )
}

/**
 * One row in a card: an optional leading icon in a 30dp accent tile, the title, and a trailing
 * slot. At least 52dp tall. [divider] draws the inset hairline below it (off for the last row).
 */
@Composable
private fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    divider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedRectangle(8.dp))
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Spacing.m),
                thickness = 0.5.dp,
                color = colors.hairline,
            )
        }
    }
}

@Composable
private fun ThemeRow(mode: ThemeMode, selected: Boolean, divider: Boolean, onSelect: () -> Unit) {
    SettingsRow(
        title = mode.label(),
        divider = divider,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        trailing = {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = AppTheme.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    divider: Boolean = true,
) {
    SettingsRow(
        title = title,
        icon = icon,
        divider = divider,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked = checked, onCheckedChange = null, colors = appSwitchColors()) },
    )
}

@ThemePreviews
@Composable
private fun SettingsPreview() {
    AppTheme { Surface { SettingsContent(GlobalSettings(), onBack = {}, onUpdate = {}) } }
}

package com.steve.mcpodface.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.prefs.ThemeMode
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    settings: GlobalSettings,
    onBack: () -> Unit,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionHeader("Appearance")
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(
                            selected = settings.theme == mode,
                            role = Role.RadioButton,
                            onClick = { onUpdate { it.copy(theme = mode) } },
                        )
                        .padding(horizontal = Spacing.m, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(mode.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    RadioButton(selected = settings.theme == mode, onClick = null)
                }
            }
            SwitchRow("Pure black dark mode", settings.pureBlack) { v -> onUpdate { it.copy(pureBlack = v) } }
            SwitchRow("Use dynamic color", settings.dynamicColor) { v -> onUpdate { it.copy(dynamicColor = v) } }
        }
    }
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.textSecondary,
        modifier = Modifier.padding(start = Spacing.m, top = Spacing.l, bottom = Spacing.s),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@ThemePreviews
@Composable
private fun SettingsPreview() {
    AppTheme { Surface { SettingsContent(GlobalSettings(), onBack = {}, onUpdate = {}) } }
}

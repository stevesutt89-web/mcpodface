package com.steve.mcpodface

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.prefs.AppTab
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.prefs.ThemeMode
import com.steve.mcpodface.ui.navigation.AppShell
import com.steve.mcpodface.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    /** null until DataStore has loaded; the splash screen stays up until then. */
    val settings: StateFlow<GlobalSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onTabSelected(tab: AppTab) {
        viewModelScope.launch { settingsRepository.update { it.copy(lastTab = tab) } }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.settings.value == null }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            // Read once: later tab changes are persisted but must not rebuild the nav graph.
            val startTab = remember { s.lastTab }
            val dark = when (s.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            EdgeToEdge(dark)
            AppTheme(themeMode = s.theme, pureBlack = s.pureBlack, dynamicColor = s.dynamicColor) {
                AppShell(startTab = startTab, onTabSelected = viewModel::onTabSelected)
            }
        }
    }

    /** Transparent system bars with icon colours that follow the app theme, not just the system one. */
    @Composable
    private fun EdgeToEdge(dark: Boolean) {
        DisposableEffect(dark) {
            val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose {}
        }
    }
}

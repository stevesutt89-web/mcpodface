package com.steve.mcpodface

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.playback.BrowseTree
import com.steve.mcpodface.playback.PlayerController
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.prefs.AppTab
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.prefs.ThemeMode
import com.steve.mcpodface.data.feed.SubscriptionRepository
import com.steve.mcpodface.data.links.PodcastLinkOpener
import com.steve.mcpodface.ui.navigation.AppShell
import com.steve.mcpodface.ui.navigation.ShellEvent
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val browseTree: BrowseTree,
    private val episodeDao: EpisodeDao,
    private val playerController: PlayerController,
    private val linkOpener: PodcastLinkOpener,
) : ViewModel() {
    private val _shellEvents = Channel<ShellEvent>(Channel.BUFFERED)

    /** Podcast pages to open and messages to show, for AppShell. Buffered until it collects. */
    val shellEvents: Flow<ShellEvent> = _shellEvents.receiveAsFlow()

    /** A podcast link opened in, or shared to, the app (PLAN.md §8.9). */
    fun openLink(text: String) {
        viewModelScope.launch {
            _shellEvents.send(ShellEvent.Message("Opening podcast…"))
            _shellEvents.send(
                when (val result = linkOpener.open(text)) {
                    is SubscriptionRepository.OpenResult.Ok -> ShellEvent.OpenPodcast(result.podcastId)
                    is SubscriptionRepository.OpenResult.Failed -> ShellEvent.Message(result.message)
                },
            )
        }
    }

    /** null until DataStore has loaded; the splash screen stays up until then. */
    val settings: StateFlow<GlobalSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onTabSelected(tab: AppTab) {
        viewModelScope.launch { settingsRepository.update { it.copy(lastTab = tab) } }
    }

    /**
     * Voice search from Android Auto or Assistant ("play … on McPodface"): plays the best match,
     * or resumes the last played episode when the query is blank.
     */
    fun playFromSearch(query: String?) {
        viewModelScope.launch {
            val id = if (query.isNullOrBlank()) {
                episodeDao.getLastPlayed()?.id
            } else {
                browseTree.search(query).firstOrNull()?.mediaId?.toLongOrNull()
            }
            id?.let { playerController.play(it) }
        }
    }

    fun onNotificationPermissionAsked() {
        viewModelScope.launch { settingsRepository.update { it.copy(notificationPermissionAsked = true) } }
    }
}

/**
 * Asks for POST_NOTIFICATIONS once, on first run (PLAN.md §12). The "asked" flag is written to
 * DataStore before the system dialog opens, so it is never shown again, whatever the answer.
 */
@Composable
private fun NotificationPermissionPrompt(alreadyAsked: Boolean, onAsked: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or refused: nothing else depends on the answer, and the prompt is not repeated.
    }
    LaunchedEffect(alreadyAsked) {
        if (alreadyAsked) return@LaunchedEffect
        onAsked()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@ThemePreviews
@Composable
private fun NotificationPermissionPromptPreview() {
    AppTheme {
        NotificationPermissionPrompt(alreadyAsked = true, onAsked = {})
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.settings.value == null }
        if (savedInstanceState == null) handleIntent(intent)

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
            AppTheme(
                themeMode = s.theme,
                pureBlack = s.pureBlack,
                dynamicColor = s.dynamicColor,
                accentColor = s.accentColor,
                glassStyle = s.glassStyle,
                reduceEffects = s.reduceEffects,
            ) {
                NotificationPermissionPrompt(
                    alreadyAsked = s.notificationPermissionAsked,
                    onAsked = viewModel::onNotificationPermissionAsked,
                )
                AppShell(
                    startTab = startTab,
                    onTabSelected = viewModel::onTabSelected,
                    shellEvents = viewModel.shellEvents,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ->
                viewModel.playFromSearch(intent.getStringExtra(SearchManager.QUERY))
            Intent.ACTION_VIEW -> intent.dataString?.let(viewModel::openLink)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(viewModel::openLink)
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

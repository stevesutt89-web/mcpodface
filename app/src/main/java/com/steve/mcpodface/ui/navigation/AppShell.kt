package com.steve.mcpodface.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.steve.mcpodface.ui.components.glass.GlassTab
import com.steve.mcpodface.ui.components.glass.GlassTabBar
import com.steve.mcpodface.ui.components.glass.LocalChromeBackdrop
import com.steve.mcpodface.ui.components.glass.LocalChromePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.steve.mcpodface.data.prefs.AppTab
import com.steve.mcpodface.ui.downloads.DownloadsScreen
import com.steve.mcpodface.ui.episode.EpisodeScreen
import com.steve.mcpodface.ui.latest.LatestScreen
import com.steve.mcpodface.ui.library.HistoryScreen
import com.steve.mcpodface.ui.playlist.PlaylistScreen
import com.steve.mcpodface.ui.playlist.SmartPlaylistEditorScreen
import androidx.navigation.NavDestination.Companion.hasRoute
import com.steve.mcpodface.ui.library.LibraryScreen
import com.steve.mcpodface.ui.library.StarredScreen
import com.steve.mcpodface.ui.player.FullPlayer
import com.steve.mcpodface.ui.player.MiniPlayer
import com.steve.mcpodface.ui.player.PlayerViewModel
import com.steve.mcpodface.ui.podcast.PodcastScreen
import com.steve.mcpodface.ui.queue.UpNextScreen
import com.steve.mcpodface.ui.search.EpisodeSearchScreen
import com.steve.mcpodface.ui.search.SearchScreen
import com.steve.mcpodface.ui.settings.SettingsScreen

/**
 * Bottom tabs + nav graph. [startTab] is read once at launch (the splash screen waits for it);
 * [onTabSelected] persists the tab so the next launch reopens it.
 */
@Composable
fun AppShell(
    startTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentTop = TopLevelDestination.entries.firstOrNull { dest ->
        currentDestination?.hierarchy?.any { it.hasRoute(dest.route::class) } == true
    }

    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Keeps the last state so the mini player can animate out after playback is cleared.
    var lastPlayerState by remember { mutableStateOf(playerState) }
    if (playerState != null) lastPlayerState = playerState

    LaunchedEffect(playerViewModel) {
        playerViewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    // Nothing loaded any more (e.g. playback ended and was cleared): close the full player too.
    LaunchedEffect(playerState == null) {
        if (playerState == null) showFullPlayer = false
    }

    // Full player open/close. Once it fully covers the screen the app underneath is not drawn at
    // all (it was being re-rendered, glass and all, behind an opaque player on every frame).
    val fullPlayerTransition = remember { MutableTransitionState(false) }
    fullPlayerTransition.targetState = showFullPlayer && playerState != null
    val playerCoversScreen = fullPlayerTransition.currentState && fullPlayerTransition.targetState &&
        fullPlayerTransition.isIdle

    val chromeBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    var chromeHeight by remember { mutableStateOf(0.dp) }
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Content fills the whole screen and is recorded into chromeBackdrop; the floating glass
    // mini player and tab bar sit on top and refract it (PLAN.md §11).
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalChromePadding provides PaddingValues(bottom = chromeHeight + navBarBottom)) {
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.of(startTab).route,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (playerCoversScreen) 0f else 1f }
                .layerBackdrop(chromeBackdrop),
        ) {
            val openSettings = { navController.navigate(SettingsRoute) }
            val openPodcast = { id: Long -> navController.navigate(PodcastRoute(id)) }
            val openEpisode = { id: Long -> navController.navigate(EpisodeRoute(id)) }
            val back: () -> Unit = { navController.popBackStack() }

            composable<LatestRoute> {
                LatestScreen(onOpenSettings = openSettings, onOpenPodcast = openPodcast, onOpenEpisode = openEpisode)
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenSettings = openSettings,
                    onOpenPodcast = openPodcast,
                    onOpenStarred = { navController.navigate(StarredRoute) },
                    onOpenHistory = { navController.navigate(HistoryRoute) },
                    onOpenPlaylist = { id -> navController.navigate(PlaylistRoute(id)) },
                    onNewSmartPlaylist = { navController.navigate(SmartPlaylistEditorRoute()) },
                )
            }
            composable<StarredRoute> { StarredScreen(onBack = back, onOpenEpisode = openEpisode) }
            composable<HistoryRoute> { HistoryScreen(onBack = back, onOpenEpisode = openEpisode) }
            composable<PlaylistRoute> {
                PlaylistScreen(
                    onBack = back,
                    onOpenEpisode = openEpisode,
                    onEditRules = { id -> navController.navigate(SmartPlaylistEditorRoute(id)) },
                )
            }
            composable<SmartPlaylistEditorRoute> {
                SmartPlaylistEditorScreen(
                    onBack = back,
                    onSaved = { id ->
                        // A new playlist opens its page in place of the editor; an edited one goes back to it.
                        navController.popBackStack()
                        if (navController.currentBackStackEntry?.destination?.hasRoute(PlaylistRoute::class) != true) {
                            navController.navigate(PlaylistRoute(id))
                        }
                    },
                )
            }
            composable<DownloadsRoute> { DownloadsScreen(onOpenSettings = openSettings) }
            composable<UpNextRoute> { UpNextScreen(onOpenSettings = openSettings) }
            composable<SearchRoute> {
                SearchScreen(onOpenPodcast = openPodcast, onOpenEpisode = openEpisode, onOpenSettings = openSettings)
            }
            composable<SettingsRoute> { SettingsScreen(onBack = back) }
            composable<PodcastRoute> { entry ->
                val podcastId = entry.toRoute<PodcastRoute>().podcastId
                PodcastScreen(
                    podcastId = podcastId,
                    onBack = back,
                    onOpenEpisode = openEpisode,
                    onSearch = { navController.navigate(EpisodeSearchRoute(podcastId)) },
                )
            }
            composable<EpisodeSearchRoute> {
                EpisodeSearchScreen(onBack = back, onOpenEpisode = openEpisode)
            }
            composable<EpisodeRoute> { entry ->
                EpisodeScreen(episodeId = entry.toRoute<EpisodeRoute>().episodeId, onBack = back, onOpenPodcast = openPodcast)
            }
        }
        }

        SnackbarHost(
            snackbarHostState,
            Modifier.align(Alignment.BottomCenter).padding(bottom = chromeHeight + navBarBottom + 4.dp),
        )

        CompositionLocalProvider(LocalChromeBackdrop provides chromeBackdrop) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = if (playerCoversScreen) 0f else 1f }
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 6.dp)
                    .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } + 6.dp },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(
                    visible = playerState != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    lastPlayerState?.let { state ->
                        MiniPlayer(
                            state = state,
                            onOpen = { showFullPlayer = true },
                            onTogglePlayPause = playerViewModel::togglePlayPause,
                            onSkipForward = playerViewModel::skipForward,
                        )
                    }
                }
                GlassTabBar(
                    tabs = remember { TopLevelDestination.entries.map { GlassTab(it.label, it.icon) } },
                    selectedIndex = currentTop?.ordinal ?: -1,
                    onSelect = { index ->
                        val dest = TopLevelDestination.entries[index]
                        onTabSelected(dest.tab)
                        // Settings is app-wide, not part of a tab: close it before switching so the
                        // tab doesn't reopen on it later.
                        navController.popBackStack<SettingsRoute>(inclusive = true)
                        if (dest == currentTop) {
                            // Tapping the current tab again returns to its top screen (iOS style).
                            navController.popBackStack(dest.route, inclusive = false)
                        } else {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }

        // Full player: a full-screen layer over everything, sliding up from the bottom.
        AnimatedVisibility(
            visibleState = fullPlayerTransition,
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it },
            exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = 500f)) { it },
        ) {
            lastPlayerState?.let { state ->
                FullPlayer(state = state, viewModel = playerViewModel, onDismiss = { showFullPlayer = false })
            }
        }
    }
}

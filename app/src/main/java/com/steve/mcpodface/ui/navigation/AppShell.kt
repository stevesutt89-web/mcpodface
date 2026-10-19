package com.steve.mcpodface.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.steve.mcpodface.ui.library.LibraryScreen
import com.steve.mcpodface.ui.library.StarredScreen
import com.steve.mcpodface.ui.player.FullPlayer
import com.steve.mcpodface.ui.player.MiniPlayer
import com.steve.mcpodface.ui.player.PlayerViewModel
import com.steve.mcpodface.ui.podcast.PodcastScreen
import com.steve.mcpodface.ui.queue.UpNextScreen
import com.steve.mcpodface.ui.search.SearchScreen
import com.steve.mcpodface.ui.settings.SettingsScreen
import com.steve.mcpodface.ui.theme.AppTheme

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

    LaunchedEffect(playerViewModel) {
        playerViewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    // Nothing loaded any more (e.g. playback ended and was cleared): close the full player too.
    LaunchedEffect(playerState == null) {
        if (playerState == null) showFullPlayer = false
    }

    Scaffold(
        // Screens draw their own top bars and handle the status bar inset themselves.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                playerState?.let { state ->
                    MiniPlayer(
                        state = state,
                        onOpen = { showFullPlayer = true },
                        onTogglePlayPause = playerViewModel::togglePlayPause,
                        onSkipForward = playerViewModel::skipForward,
                    )
                }
                HorizontalDivider(color = AppTheme.colors.hairline)
                NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    TopLevelDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = dest == currentTop,
                            onClick = {
                                onTabSelected(dest.tab)
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppTheme.colors.accent,
                                selectedTextColor = AppTheme.colors.textPrimary,
                                indicatorColor = AppTheme.colors.accentSubtle,
                                unselectedIconColor = AppTheme.colors.textSecondary,
                                unselectedTextColor = AppTheme.colors.textSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.of(startTab).route,
            modifier = Modifier.padding(padding),
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
                )
            }
            composable<StarredRoute> { StarredScreen(onBack = back, onOpenEpisode = openEpisode) }
            composable<HistoryRoute> { HistoryScreen(onBack = back, onOpenEpisode = openEpisode) }
            composable<DownloadsRoute> { DownloadsScreen() }
            composable<UpNextRoute> { UpNextScreen() }
            composable<SearchRoute> { SearchScreen(onOpenPodcast = openPodcast) }
            composable<SettingsRoute> { SettingsScreen(onBack = back) }
            composable<PodcastRoute> { entry ->
                PodcastScreen(podcastId = entry.toRoute<PodcastRoute>().podcastId, onBack = back, onOpenEpisode = openEpisode)
            }
            composable<EpisodeRoute> { entry ->
                EpisodeScreen(episodeId = entry.toRoute<EpisodeRoute>().episodeId, onBack = back, onOpenPodcast = openPodcast)
            }
        }
    }

    playerState?.let { state ->
        if (showFullPlayer) {
            FullPlayer(state = state, viewModel = playerViewModel, onDismiss = { showFullPlayer = false })
        }
    }
}

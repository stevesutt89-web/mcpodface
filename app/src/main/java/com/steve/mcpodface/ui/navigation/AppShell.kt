package com.steve.mcpodface.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.steve.mcpodface.data.prefs.AppTab
import com.steve.mcpodface.ui.downloads.DownloadsScreen
import com.steve.mcpodface.ui.latest.LatestScreen
import com.steve.mcpodface.ui.library.LibraryScreen
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

    Scaffold(
        // Screens draw their own top bars and handle the status bar inset themselves.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // TODO(4.2): MiniPlayer goes here, above the navigation bar.
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
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.of(startTab).route,
            modifier = Modifier.padding(padding),
        ) {
            val openSettings = { navController.navigate(SettingsRoute) }
            composable<LatestRoute> { LatestScreen(onOpenSettings = openSettings) }
            composable<LibraryRoute> { LibraryScreen(onOpenSettings = openSettings) }
            composable<DownloadsRoute> { DownloadsScreen() }
            composable<UpNextRoute> { UpNextScreen() }
            composable<SearchRoute> { SearchScreen() }
            composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

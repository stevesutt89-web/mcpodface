package com.steve.mcpodface.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.RowScope
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Screen frame with a large collapsing title (PLAN.md §8.3, §11). The top bar handles the status
 * bar; the shell already handles the bottom bar, so the Scaffold passes zero content insets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) }, // M3 animates headlineMedium (expanded) -> titleLarge (collapsed)
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding -> content(padding) }
}

/** Back arrow for a top bar's navigation slot. */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
    }
}

@ThemePreviews
@Composable
private fun LargeTitleScaffoldPreview() {
    AppTheme {
        Surface {
            LargeTitleScaffold(title = "Latest") { padding ->
                LazyColumn(contentPadding = padding) {
                    items(previewEpisodeItems(), key = { it.episode.guid }) { item ->
                        EpisodeRow(
                            item = item,
                            onClick = {},
                            onPlayClick = {},
                            now = 1_792_411_200_000L,
                        )
                    }
                }
            }
        }
    }
}

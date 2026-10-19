package com.steve.mcpodface.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.LocalChromePadding
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Screen frame with a large collapsing title over scrolling content (PLAN.md §8.3, §11).
 *
 * Content draws edge to edge and scrolls *under* the top bar and the shell's floating chrome:
 * pass the given [PaddingValues] as your list's `contentPadding` (not as Modifier.padding), so
 * the first and last items still start and end clear of the bars. As the title collapses the bar
 * turns into frosted glass refracting the content beneath it. Top-bar glass buttons
 * ([GlassIconButton]) refract the content too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    background: Color = MaterialTheme.colorScheme.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    GlassBarScaffold(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        background = background,
        glassAlpha = { scrollBehavior.state.collapsedFraction },
        topBar = {
            LargeTopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) }, // M3 animates headlineMedium (expanded) -> titleLarge (collapsed)
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        content = content,
    )
}

/**
 * Small-title variant for detail screens (podcast, episode, settings). The bar is clear glass that
 * frosts as soon as content scrolls beneath it. Pass [background] = Color.Transparent to show a
 * GlassPage background (e.g. artwork ambience) through it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopBarScaffold(
    modifier: Modifier = Modifier,
    title: String = "",
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    background: Color = MaterialTheme.colorScheme.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    GlassBarScaffold(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        background = background,
        glassAlpha = { scrollBehavior.state.overlappedFraction },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassBarScaffold(
    modifier: Modifier,
    scrollBehavior: TopAppBarScrollBehavior,
    background: Color,
    glassAlpha: () -> Float,
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val contentBackdrop = rememberLayerBackdrop()
    val chrome = LocalChromePadding.current
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Box {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = glassAlpha() }
                        .glass(RectangleShape, GlassStyle.Regular, backdrop = contentBackdrop),
                )
                // Toolbar glass buttons refract the content scrolling beneath them.
                CompositionLocalProvider(LocalGlassBackdrop provides contentBackdrop) { topBar() }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
            content(
                PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = chrome.calculateBottomPadding() + 16.dp,
                ),
            )
        }
    }
}

/** Back button: a small glass circle, iOS 26 style. */
@Composable
fun BackButton(onClick: () -> Unit) {
    GlassIconButton(
        icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
        contentDescription = "Back",
        onClick = onClick,
        size = 40.dp,
        iconSize = 18.dp,
        modifier = Modifier.padding(start = 8.dp),
    )
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

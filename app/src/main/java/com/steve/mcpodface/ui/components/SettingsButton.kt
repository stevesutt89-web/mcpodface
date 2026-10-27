package com.steve.mcpodface.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.theme.Spacing

/** The glass gear shown at the top right of every tab, so Settings is always one tap away. */
@Composable
fun SettingsButton(onClick: () -> Unit) {
    GlassIconButton(
        icon = Icons.Rounded.Settings,
        contentDescription = "Settings",
        onClick = onClick,
        size = 40.dp,
        iconSize = 20.dp,
        modifier = Modifier.padding(start = 6.dp, end = Spacing.s),
    )
}

/** The glass magnifier on Latest and Library: searches the episodes of your subscribed podcasts. */
@Composable
fun SearchButton(onClick: () -> Unit) {
    GlassIconButton(
        icon = Icons.Rounded.Search,
        contentDescription = "Search your podcasts",
        onClick = onClick,
        size = 40.dp,
        iconSize = 20.dp,
        modifier = Modifier.padding(end = Spacing.xs),
    )
}

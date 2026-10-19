package com.steve.mcpodface.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * The glass search icon in the top-right action row of each tab. It opens the pushed Search screen
 * (PLAN.md §8.9). Sized like [SettingsButton]; it sits to the left of the gear.
 */
@Composable
fun SearchButton(onClick: () -> Unit) {
    GlassIconButton(
        icon = Icons.Outlined.Search,
        contentDescription = "Search",
        onClick = onClick,
        size = 40.dp,
        iconSize = 20.dp,
        modifier = Modifier.padding(start = 6.dp),
    )
}

@ThemePreviews
@Composable
private fun SearchButtonPreview() {
    AppTheme {
        Surface { SearchButton(onClick = {}) }
    }
}

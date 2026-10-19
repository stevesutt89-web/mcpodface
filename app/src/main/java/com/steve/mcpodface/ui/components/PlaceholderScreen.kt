package com.steve.mcpodface.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Temporary screen body used until each tab is built. Delete once no screen uses it.
 * Tabs get a large glass title; pushed screens (with [onBack]) get the small glass top bar.
 * The "Coming soon" state is a centred glass circle with an icon.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    onOpenSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val actions: @Composable RowScope.() -> Unit = {
        if (onOpenSettings != null) {
            GlassIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings,
                modifier = Modifier.padding(end = Spacing.s),
            )
        }
    }
    GlassPage {
        if (onBack != null) {
            GlassTopBarScaffold(
                title = title,
                navigationIcon = { BackButton(onBack) },
                actions = actions,
                background = Color.Transparent,
                content = { padding -> ComingSoon(padding) },
            )
        } else {
            LargeTitleScaffold(
                title = title,
                actions = actions,
                background = Color.Transparent,
                content = { padding -> ComingSoon(padding) },
            )
        }
    }
}

/** Centred empty state. Not a scrolling list, so the bar and chrome padding is applied directly. */
@Composable
private fun ComingSoon(padding: PaddingValues) {
    val circle = remember { Capsule() }
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).glass(circle, GlassStyle.Regular),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Podcasts,
                    contentDescription = null,
                    tint = AppTheme.colors.textSecondary,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(Spacing.m))
            Text(
                "Coming soon",
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PlaceholderScreenPreview() {
    AppTheme { Surface { PlaceholderScreen("Library", onOpenSettings = {}) } }
}

@ThemePreviews
@Composable
private fun PlaceholderScreenBackPreview() {
    AppTheme { Surface { PlaceholderScreen("History", onBack = {}) } }
}

package com.steve.mcpodface.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews

/** Temporary screen body used until each tab is built. Delete once no screen uses it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    title: String,
    onOpenSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )
        Box(Modifier.fillMaxSize().padding(), contentAlignment = Alignment.Center) {
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
    AppTheme { androidx.compose.material3.Surface { PlaceholderScreen("Library", onOpenSettings = {}) } }
}

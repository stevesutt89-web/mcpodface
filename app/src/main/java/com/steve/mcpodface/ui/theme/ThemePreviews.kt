package com.steve.mcpodface.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.data.prefs.ThemeMode

/** Light + dark previews for any composable: annotate a preview function with @ThemePreviews. */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ThemePreviews

@ThemePreviews
@Composable
private fun ThemeSwatchPreview() {
    AppTheme(themeMode = ThemeMode.SYSTEM) {
        Surface {
            Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("Library", style = MaterialTheme.typography.headlineMedium)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(ArtworkShape)
                            .background(AppTheme.colors.surfaceGrouped),
                    )
                    Column(Modifier.padding(start = Spacing.m)) {
                        Text("Radiolab · 2d ago", style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.textSecondary)
                        Text("Episode title in bold", style = MaterialTheme.typography.titleMedium)
                        Text("The first part of the show notes in secondary text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.textSecondary)
                    }
                }
                LinearProgressIndicator(progress = { 0.4f }, modifier = Modifier.fillMaxWidth())
                HorizontalDivider(color = AppTheme.colors.hairline)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Button(onClick = {}, colors = AppButtonDefaults.primaryColors()) { Text("Subscribe") }
                    Spacer(Modifier.size(Spacing.m))
                    Switch(checked = true, onCheckedChange = {})
                }
                Spacer(Modifier.height(Spacing.s))
            }
        }
    }
}

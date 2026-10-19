package com.steve.mcpodface.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.prefs.ThemeMode
import com.steve.mcpodface.ui.components.glass.glassCard
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing

/** Opacity of a row that is unavailable (for example archive rows while the archive is off). */
internal const val DisabledRowAlpha = 0.45f

internal fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/** Switch colours shared by settings and the speed sheet: accent track, white thumb, no border. */
@Composable
internal fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AppTheme.colors.accent,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = AppTheme.colors.textPrimary.copy(alpha = 0.15f),
    uncheckedBorderColor = Color.Transparent,
)

/** Section heading with a small accent icon tile, e.g. "Downloads". */
@Composable
internal fun SectionHeader(text: String, icon: ImageVector? = null) {
    Row(
        Modifier.padding(start = Spacing.m, top = Spacing.l, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedRectangle(6.dp))
                    .background(AppTheme.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.textSecondary,
        )
    }
}

/** A rounded glass group of rows (iOS Settings card). */
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = remember { RoundedRectangle(24.dp) }
    Column(
        Modifier
            .fillMaxWidth()
            .glassCard(shape)
            .clip(shape),
        content = content,
    )
}

/**
 * One row in a card: an optional leading icon in a 30dp accent tile, the title with an optional
 * secondary line, and a trailing slot. At least 52dp tall. [divider] draws the inset hairline
 * below it (off for the last row). [enabled] = false dims the whole row.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    subtitleColor: Color = AppTheme.colors.textSecondary,
    divider: Boolean = true,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else DisabledRowAlpha)) {
        Row(
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedRectangle(8.dp))
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                    )
                }
            }
            trailing()
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Spacing.m),
                thickness = 0.5.dp,
                color = colors.hairline,
            )
        }
    }
}

/** A tappable row with no value: used for import/export and the battery setting. */
@Composable
internal fun ActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = AppTheme.colors.textSecondary,
    divider: Boolean = true,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        divider = divider,
        enabled = enabled,
        modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

@Composable
internal fun ThemeRow(mode: ThemeMode, selected: Boolean, divider: Boolean, onSelect: () -> Unit) {
    SettingsRow(
        title = mode.label(),
        divider = divider,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        trailing = {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = AppTheme.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
    divider: Boolean = true,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        icon = icon,
        subtitle = subtitle,
        divider = divider,
        enabled = enabled,
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onChange,
        ),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = appSwitchColors(),
            )
        },
    )
}

/** A value-only row, e.g. "Requests used today". */
@Composable
internal fun ValueRow(title: String, value: String, divider: Boolean = true, enabled: Boolean = true) {
    SettingsRow(
        title = title,
        divider = divider,
        enabled = enabled,
        trailing = {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.textSecondary,
            )
        },
    )
}

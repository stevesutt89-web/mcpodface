package com.steve.mcpodface.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.ui.theme.AppTheme

/**
 * A settings row whose value is picked from a dropdown. The current option's label is shown as
 * trailing secondary text; the menu is anchored to the row and marks the current option with an
 * accent check.
 */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    divider: Boolean = true,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box {
        SettingsRow(
            title = title,
            icon = icon,
            divider = divider,
            enabled = enabled,
            modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { open = true },
            trailing = {
                Text(
                    currentLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.colors.textSecondary,
                )
            },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedRectangle(20.dp),
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                    trailingIcon = {
                        if (value == selected) {
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
        }
    }
}

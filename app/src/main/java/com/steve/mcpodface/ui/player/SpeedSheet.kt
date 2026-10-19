package com.steve.mcpodface.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import kotlin.math.roundToInt

private val PRESET_SPEEDS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 3.0f

/** Speed picker sheet (PLAN.md §8.6). Changes apply immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    speed: Float,
    onSpeedChange: (speed: Float, rememberForPodcast: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        SpeedSheetContent(speed = speed, onSpeedChange = onSpeedChange, onDone = onDismiss)
    }
}

/** The sheet body without the ModalBottomSheet wrapper, so it can be previewed directly. */
@Composable
internal fun SpeedSheetContent(
    speed: Float,
    onSpeedChange: (speed: Float, rememberForPodcast: Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val colors = AppTheme.colors
    var value by remember(speed) { mutableFloatStateOf(roundSpeed(speed)) }
    var rememberForPodcast by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m).padding(bottom = Spacing.l),
    ) {
        Text(
            text = formatSpeed(value),
            style = MaterialTheme.typography.headlineLarge,
            color = colors.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        Slider(
            value = value,
            onValueChange = { value = roundSpeed(it) },
            onValueChangeFinished = { onSpeedChange(value, rememberForPodcast) },
            valueRange = MIN_SPEED..MAX_SPEED,
            steps = 24, // 0.5 to 3.0 in 0.1 steps
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.hairline,
            ),
        )
        Spacer(Modifier.height(Spacing.s))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            PRESET_SPEEDS.forEach { preset ->
                FilterChip(
                    selected = value == preset,
                    onClick = {
                        value = preset
                        onSpeedChange(preset, rememberForPodcast)
                    },
                    label = { Text(formatSpeed(preset)) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.l))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Remember for this podcast",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = rememberForPodcast,
                onCheckedChange = {
                    rememberForPodcast = it
                    onSpeedChange(value, it)
                },
            )
        }
        Spacer(Modifier.height(Spacing.s))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) {
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Snaps a slider value to the 0.1 grid, e.g. 1.4000001 -> 1.4. */
internal fun roundSpeed(speed: Float): Float = (speed * 10f).roundToInt() / 10f

@ThemePreviews
@Composable
private fun SpeedSheetPreview() {
    AppTheme {
        Surface {
            SpeedSheetContent(speed = 1.4f, onSpeedChange = { _, _ -> }, onDone = {})
        }
    }
}

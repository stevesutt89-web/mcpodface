package com.steve.mcpodface.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassChip
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.glass.glassCard
import com.steve.mcpodface.ui.settings.appSwitchColors
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import kotlin.math.roundToInt

private val PRESET_SPEEDS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 3.0f

/**
 * Speed picker sheet (PLAN.md §8.6, §11). Changes apply immediately. The sheet is a separate
 * window, so its glass uses the inherited page backdrop or the translucent fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    speed: Float,
    onSpeedChange: (speed: Float, rememberForPodcast: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetShape = remember { RoundedRectangle(32.dp) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = sheetShape,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        // The sheet is its own window: don't let its glass sample the player page's layer.
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            SpeedSheetContent(speed = speed, onSpeedChange = onSpeedChange, onDone = onDismiss)
        }
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
    val cardShape = remember { RoundedRectangle(24.dp) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m).padding(bottom = Spacing.l),
    ) {
        Text(
            text = formatSpeed(value),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = colors.accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
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
                inactiveTrackColor = colors.textPrimary.copy(alpha = 0.15f),
            ),
        )
        Spacer(Modifier.height(Spacing.s))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            PRESET_SPEEDS.forEach { preset ->
                GlassChip(
                    label = formatSpeed(preset),
                    selected = value == preset,
                    onClick = {
                        value = preset
                        onSpeedChange(preset, rememberForPodcast)
                    },
                )
            }
        }
        Spacer(Modifier.height(Spacing.l))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .glassCard(cardShape)
                .toggleable(
                    value = rememberForPodcast,
                    role = Role.Switch,
                    onValueChange = {
                        rememberForPodcast = it
                        onSpeedChange(value, it)
                    },
                )
                .padding(horizontal = Spacing.m, vertical = 12.dp),
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
                onCheckedChange = null,
                colors = appSwitchColors(),
            )
        }
        Spacer(Modifier.height(Spacing.l))
        GlassButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            style = GlassStyle.Prominent,
        ) {
            Text("Done")
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

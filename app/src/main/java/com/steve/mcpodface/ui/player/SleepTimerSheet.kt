package com.steve.mcpodface.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.components.glass.glassCard
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private val SLEEP_MINUTES = listOf(5, 15, 30, 45, 60)

/**
 * Sleep timer sheet (PLAN.md §8.6): 5–60 minutes or end of episode. While a timer runs it shows
 * the time left with "+5 min" and "Turn off". Shaking the phone also adds 5 minutes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    timer: SleepTimerUi,
    onChoice: (SleepChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = remember { RoundedRectangle(32.dp) },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        // The sheet is its own window: don't let its glass sample the player page's layer.
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            SleepTimerSheetContent(timer = timer, onChoice = { onChoice(it); if (it !is SleepChoice.Extend) onDismiss() })
        }
    }
}

@Composable
internal fun SleepTimerSheetContent(timer: SleepTimerUi, onChoice: (SleepChoice) -> Unit) {
    val colors = AppTheme.colors
    val cardShape = remember { RoundedRectangle(24.dp) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.m).padding(bottom = Spacing.l)) {
        Text(
            text = when (timer) {
                SleepTimerUi.Off -> "Sleep timer"
                SleepTimerUi.EndOfEpisode -> "Stopping at end of episode"
                is SleepTimerUi.Remaining -> formatClock(timer.ms)
            },
            style = if (timer is SleepTimerUi.Remaining) {
                MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.headlineMedium
            },
            color = if (timer == SleepTimerUi.Off) colors.textPrimary else colors.accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
        )
        if (timer is SleepTimerUi.Remaining) {
            Text(
                text = "Shake your phone to add 5 minutes",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.l))
        if (timer != SleepTimerUi.Off) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (timer is SleepTimerUi.Remaining) {
                    GlassButton(onClick = { onChoice(SleepChoice.Extend) }, modifier = Modifier.weight(1f)) {
                        Text("+5 min")
                    }
                }
                GlassButton(
                    onClick = { onChoice(SleepChoice.Off) },
                    modifier = Modifier.weight(1f),
                    style = GlassStyle.Prominent,
                ) {
                    Text("Turn off")
                }
            }
            Spacer(Modifier.height(Spacing.l))
        }
        Column(Modifier.fillMaxWidth().clip(cardShape).glassCard(cardShape)) {
            val options = SLEEP_MINUTES.map { "$it minutes" to SleepChoice.Minutes(it) } +
                ("End of episode" to SleepChoice.EndOfEpisode)
            options.forEachIndexed { i, (label, choice) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = Spacing.m), color = colors.textPrimary.copy(alpha = 0.08f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onChoice(choice) }
                        .padding(horizontal = Spacing.m, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                }
            }
        }
    }
}

/** Compact label beside the moon icon: "14:32" or "End". */
internal fun sleepLabel(timer: SleepTimerUi): String? = when (timer) {
    SleepTimerUi.Off -> null
    SleepTimerUi.EndOfEpisode -> "End"
    is SleepTimerUi.Remaining -> formatClock(timer.ms)
}

@ThemePreviews
@Composable
private fun SleepTimerSheetPreview() {
    AppTheme {
        Surface {
            SleepTimerSheetContent(timer = SleepTimerUi.Remaining(14 * 60_000L + 32_000), onChoice = {})
        }
    }
}

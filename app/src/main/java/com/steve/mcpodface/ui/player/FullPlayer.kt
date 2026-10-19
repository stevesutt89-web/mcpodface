package com.steve.mcpodface.ui.player

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.components.glass.ArtworkAmbience
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.glass.glassCard
import com.steve.mcpodface.ui.components.glass.rememberArtworkAccent
import com.steve.mcpodface.ui.episode.ShowNotes
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Full player (PLAN.md §8.6, §11): a true full-screen layer (drawn by the shell over everything,
 * edge to edge behind the status and gesture bars) washed in the artwork's colours, with Liquid
 * Glass controls tinted by an accent taken from the artwork. Drag the grabber or artwork down,
 * or use back, to close it. (A ModalBottomSheet left inset strips and rounded edges.)
 */
@Composable
fun FullPlayer(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val dismissThreshold = with(LocalDensity.current) { 140.dp.toPx() }
    BackHandler(onBack = onDismiss)

    val dismissDrag = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = {
                scope.launch {
                    if (dragY.value > dismissThreshold) onDismiss()
                    else dragY.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f))
                }
            },
            onDragCancel = { scope.launch { dragY.animateTo(0f) } },
        ) { change, dy ->
            change.consume()
            scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) }
        }
    }

    // The position ticks twice a second. Only the timeline should redraw for that, so the rest of
    // the player gets a copy without it (equal between ticks, so Compose skips it) and the timeline
    // reads the live position through a lambda.
    val latest = rememberUpdatedState(state)
    val sleep = viewModel.sleepTimerUi.collectAsState()
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = dragY.value }) {
        FullPlayerContent(
            state = state.copy(positionMs = 0L),
            positionMs = { latest.value.positionMs },
            onSeek = viewModel::seekTo,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSkipBack = viewModel::skipBack,
            onSkipForward = viewModel::skipForward,
            onSpeedChange = viewModel::setSpeed,
            onOpenOutputSwitcher = { openOutputSwitcher(context) },
            dismissDrag = dismissDrag,
            sleepTimer = { sleep.value },
            onSleepChoice = viewModel::setSleepTimer,
        )
    }
}

/** Player content without the dismiss wrapper, so it can be previewed directly. [dismissDrag] goes on the grabber and artwork. */
@Composable
fun FullPlayerContent(
    state: PlayerUiState,
    onSeek: (positionMs: Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeedChange: (speed: Float, rememberForPodcast: Boolean) -> Unit,
    onOpenOutputSwitcher: () -> Unit,
    dismissDrag: Modifier = Modifier,
    positionMs: () -> Long = { state.positionMs },
    sleepTimer: () -> SleepTimerUi = { SleepTimerUi.Off },
    onSleepChoice: (SleepChoice) -> Unit = {},
) {
    val colors = AppTheme.colors
    val artworkAccent by rememberArtworkAccent(if (state.tintFromArtwork) state.artworkUrl else null)
    val accent = artworkAccent ?: colors.accent
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showSleepSheet by rememberSaveable { mutableStateOf(false) }

    // The artwork breathes: full size while playing, springs back a little when paused.
    val artScale by animateFloatAsState(
        targetValue = if (state.isPlaying || state.isBuffering) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 260f),
        label = "artScale",
    )

    GlassPage(
        background = {
            if (state.tintFromArtwork) {
                ArtworkAmbience(state.artworkUrl, fadeTo = 1f, intensity = 1.25f)
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
        ) {
            // Grabber, with a generous touch area for the drag-down-to-close gesture.
            Box(
                Modifier.fillMaxWidth().height(28.dp).then(dismissDrag),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 5.dp)
                        .clip(Capsule())
                        .background(colors.textPrimary.copy(alpha = 0.25f)),
                )
            }
            Spacer(Modifier.height(Spacing.m))
            BoxWithConstraints(Modifier.fillMaxWidth().then(dismissDrag), contentAlignment = Alignment.Center) {
                Artwork(
                    url = state.artworkUrl,
                    contentDescription = null,
                    size = maxWidth,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = artScale
                            scaleY = artScale
                            shadowElevation = 28.dp.toPx() * artScale
                            shape = RoundedRectangle(28.dp)
                            clip = true
                        },
                )
            }
            Spacer(Modifier.height(Spacing.xl))
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = state.podcastTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(Spacing.m))
            Timeline(
                positionMs = positionMs,
                durationMs = state.durationMs,
                speed = state.speed,
                accent = accent,
                onSeek = onSeek,
            )

            Spacer(Modifier.height(Spacing.l))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                GlassIconButton(
                    icon = skipBackIcon(state.skipBackSec),
                    contentDescription = "Skip back ${state.skipBackSec} seconds",
                    onClick = onSkipBack,
                    style = GlassStyle.Clear,
                    size = 64.dp,
                    iconSize = 32.dp,
                )
                Box(contentAlignment = Alignment.Center) {
                    GlassIconButton(
                        icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        onClick = onTogglePlayPause,
                        style = GlassStyle.Prominent,
                        accent = accent,
                        size = 84.dp,
                        iconSize = 44.dp,
                    )
                    if (state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(84.dp), color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
                    }
                }
                GlassIconButton(
                    icon = skipForwardIcon(state.skipForwardSec),
                    contentDescription = "Skip forward ${state.skipForwardSec} seconds",
                    onClick = onSkipForward,
                    style = GlassStyle.Clear,
                    size = 64.dp,
                    iconSize = 32.dp,
                )
            }

            Spacer(Modifier.height(Spacing.l))
            // Secondary controls grouped in one glass capsule.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .glass(remember { Capsule() }, GlassStyle.Regular)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatSpeed(state.speed),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier
                        .clip(Capsule())
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                            showSpeedSheet = true
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                SleepButton(sleepTimer, accent) { showSleepSheet = true }
                CapsuleIcon(Icons.Rounded.SpeakerGroup, "Output device", onClick = onOpenOutputSwitcher)
            }

            if (!state.descriptionHtml.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.l))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassCard(remember { RoundedRectangle(28.dp) })
                        .padding(20.dp),
                ) {
                    Text(
                        text = "Show notes",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    ShowNotes(html = state.descriptionHtml, onTimestampClick = onSeek)
                }
            }
        }
    }

    if (showSpeedSheet) {
        SpeedSheet(
            speed = state.speed,
            onSpeedChange = onSpeedChange,
            onDismiss = { showSpeedSheet = false },
        )
    }
    if (showSleepSheet) {
        SleepTimerSheet(timer = sleepTimer(), onChoice = onSleepChoice, onDismiss = { showSleepSheet = false })
    }
}

/** Moon icon, plus the time left while a timer runs. Reads the timer itself so its 1s ticks redraw only this. */
@Composable
private fun SleepButton(timer: () -> SleepTimerUi, accent: Color, onClick: () -> Unit) {
    val label = sleepLabel(timer())
    Row(
        Modifier
            .height(44.dp)
            .clip(Capsule())
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Bedtime,
            contentDescription = if (label == null) "Sleep timer" else "Sleep timer, $label",
            tint = if (label == null) AppTheme.colors.textPrimary.copy(alpha = 0.9f) else accent,
            modifier = Modifier.size(22.dp),
        )
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = accent)
        }
    }
}

/**
 * Standard Material slider plus elapsed/remaining times. The drag position is held *here*, so
 * while dragging only this small composable recomposes, not the whole player (that made
 * scrubbing jerky). The seek happens on release.
 */
@Composable
private fun Timeline(
    positionMs: () -> Long,
    durationMs: Long,
    speed: Float,
    accent: Color,
    onSeek: (Long) -> Unit,
) {
    val colors = AppTheme.colors
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val playedFraction = if (durationMs > 0) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownFraction = dragFraction ?: playedFraction
    val shownPositionMs = (shownFraction * durationMs).toLong()
    Column {
        Slider(
            value = shownFraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = colors.textPrimary.copy(alpha = 0.16f),
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatClock(shownPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Text(
                text = if (durationMs > 0) formatRemaining(durationMs, shownPositionMs, speed) else "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun CapsuleIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(Capsule())
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = AppTheme.colors.textPrimary.copy(alpha = if (enabled) 0.9f else 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Back icon matching the configured interval; falls back to a generic replay icon. */
private fun skipBackIcon(seconds: Int): ImageVector = when (seconds) {
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Outlined.Replay
}

/** Opens the system media output switcher for this app (PLAN.md §8.6). Silently does nothing if absent. */
private fun openOutputSwitcher(context: Context) {
    val intent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
        .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
    runCatching { context.startActivity(intent) }
}

@ThemePreviews
@Composable
private fun FullPlayerPlayingPreview() {
    AppTheme {
        FullPlayerContent(
            state = previewPlayerState(isPlaying = true),
            onSeek = {},
            onTogglePlayPause = {},
            onSkipBack = {},
            onSkipForward = {},
            onSpeedChange = { _, _ -> },
            onOpenOutputSwitcher = {},
        )
    }
}

@ThemePreviews
@Composable
private fun FullPlayerPausedPreview() {
    AppTheme {
        FullPlayerContent(
            state = previewPlayerState(isPlaying = false),
            onSeek = {},
            onTogglePlayPause = {},
            onSkipBack = {},
            onSkipForward = {},
            onSpeedChange = { _, _ -> },
            onOpenOutputSwitcher = {},
        )
    }
}


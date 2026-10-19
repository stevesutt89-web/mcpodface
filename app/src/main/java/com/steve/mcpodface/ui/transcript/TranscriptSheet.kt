package com.steve.mcpodface.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.transcripts.TranscriptSegment
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.player.formatClock
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Transcript sheet, opened from the episode page and the full player (PLAN.md §13). Timed segments
 * seek and play this episode when tapped. While the episode is loaded, the spoken segment is highlighted
 * and kept in view unless the listener is scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(episodeId: Long, onDismiss: () -> Unit) {
    val viewModel: TranscriptViewModel = hiltViewModel(key = "transcript-$episodeId")
    LaunchedEffect(episodeId) { viewModel.open(episodeId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentIndex = viewModel.currentIndex.collectAsStateWithLifecycle()
    val sheetShape = remember { RoundedRectangle(32.dp) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = sheetShape,
        containerColor = MaterialTheme.colorScheme.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The sheet is its own window: don't let its glass sample the page's layer.
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            TranscriptSheetContent(
                state = state,
                currentIndex = currentIndex,
                onSegmentClick = viewModel::onSegmentClick,
            )
        }
    }
}

/** The sheet body without the ModalBottomSheet wrapper, so it can be previewed directly. */
@Composable
internal fun TranscriptSheetContent(
    state: TranscriptUiState,
    currentIndex: State<Int?>,
    onSegmentClick: (Long) -> Unit,
) {
    val colors = AppTheme.colors
    val listState = rememberLazyListState()

    // Keep the spoken segment in view, unless the listener is scrolling. Reads the index through the State,
    // so the sheet body itself does not recompose on each change.
    LaunchedEffect(listState) {
        snapshotFlow { currentIndex.value }.collect { index ->
            if (index == null || listState.isScrollInProgress) return@collect
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!visible) {
                val thirdOfViewport = listState.layoutInfo.viewportSize.height / 3
                listState.animateScrollToItem(index, -thirdOfViewport)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .padding(horizontal = Spacing.m)
            .padding(bottom = Spacing.s),
    ) {
        Text(
            text = "Transcript",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = Spacing.s),
        )
        when {
            state.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.segments.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.hasTranscript) "The transcript could not be loaded." else "No transcript for this episode.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                itemsIndexed(state.segments) { index, segment ->
                    SegmentRow(
                        segment = segment,
                        isCurrent = currentIndex.value == index,
                        onClick = segment.startMs?.let { start -> { onSegmentClick(start) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentRow(segment: TranscriptSegment, isCurrent: Boolean, onClick: (() -> Unit)?) {
    val colors = AppTheme.colors
    val highlight = remember { RoundedRectangle(16.dp) }
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(highlight)
        .then(if (isCurrent) Modifier.background(colors.accent.copy(alpha = 0.14f)) else Modifier)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics { selected = isCurrent }
        .padding(horizontal = 12.dp, vertical = 10.dp)
    Row(modifier = rowModifier, verticalAlignment = Alignment.Top) {
        if (segment.startMs != null) {
            Text(
                text = formatClock(segment.startMs),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) colors.accent else colors.textSecondary,
                modifier = Modifier.width(56.dp).padding(top = 3.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            segment.speaker?.let { speaker ->
                Text(
                    text = speaker,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) colors.textPrimary else colors.textPrimary.copy(alpha = 0.85f),
            )
        }
    }
}

private val previewSegments = listOf(
    TranscriptSegment(0L, 6_000L, "Host", "Welcome back to the show. Today we follow the bridge crew."),
    TranscriptSegment(6_000L, 14_000L, null, "The first survey went out in the spring, before the river rose."),
    TranscriptSegment(14_000L, 22_000L, "Guest", "We walked the banks every morning for a month."),
    TranscriptSegment(22_000L, null, null, "An untimed line, shown without a time and not tappable."),
)

@ThemePreviews
@Composable
private fun TranscriptSheetContentPreview() {
    AppTheme {
        Surface {
            TranscriptSheetContent(
                state = TranscriptUiState(loading = false, episodeId = 1L, hasTranscript = true, segments = previewSegments),
                currentIndex = remember { mutableStateOf<Int?>(1) },
                onSegmentClick = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun TranscriptSheetEmptyPreview() {
    AppTheme {
        Surface {
            TranscriptSheetContent(
                state = TranscriptUiState(loading = false, episodeId = 1L, hasTranscript = false),
                currentIndex = remember { mutableStateOf<Int?>(null) },
                onSegmentClick = {},
            )
        }
    }
}

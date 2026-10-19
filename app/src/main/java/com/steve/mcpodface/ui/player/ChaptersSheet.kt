package com.steve.mcpodface.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.chapters.Chapter
import com.steve.mcpodface.data.chapters.chapterIndexAt
import com.steve.mcpodface.data.chapters.nextChapterStartMs
import com.steve.mcpodface.data.chapters.previousChapterStartMs
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * "Chapter 3 · Title" under the podcast title. Reads the position through [positionMs] inside a derived
 * state, so only this line redraws, and only when the current chapter changes.
 */
@Composable
fun ChapterLine(chapters: List<Chapter>, positionMs: () -> Long, modifier: Modifier = Modifier) {
    val index by remember(chapters, positionMs) { derivedStateOf { chapterIndexAt(chapters, positionMs()) } }
    val current = index ?: return
    Text(
        text = "Chapter ${current + 1} · ${chapters[current].title}",
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Chapter list sheet (PLAN.md §8.6). Tapping a chapter seeks there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    chapters: List<Chapter>,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
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
            ChaptersSheetContent(chapters = chapters, positionMs = positionMs, onSeek = onSeek)
        }
    }
}

/** The sheet body without the ModalBottomSheet wrapper, so it can be previewed directly. */
@Composable
internal fun ChaptersSheetContent(
    chapters: List<Chapter>,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
) {
    val colors = AppTheme.colors
    val currentIndex by remember(chapters, positionMs) { derivedStateOf { chapterIndexAt(chapters, positionMs()) } }
    val listState = rememberLazyListState()
    // Open with the current chapter in view.
    LaunchedEffect(chapters) {
        chapterIndexAt(chapters, positionMs())?.let { listState.scrollToItem(it) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m).padding(bottom = Spacing.l),
    ) {
        Text(
            text = "Chapters",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = Spacing.s),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        ) {
            itemsIndexed(chapters) { index, chapter ->
                ChapterRow(
                    chapter = chapter,
                    isCurrent = index == currentIndex,
                    onClick = { onSeek(chapter.startMs) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            GlassButton(
                onClick = { previousChapterStartMs(chapters, positionMs())?.let(onSeek) },
                style = GlassStyle.Regular,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Previous chapter")
            }
            GlassButton(
                onClick = { nextChapterStartMs(chapters, positionMs())?.let(onSeek) },
                style = GlassStyle.Regular,
                modifier = Modifier.weight(1f),
            ) {
                Text("Next chapter")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, isCurrent: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val highlight = remember { RoundedRectangle(16.dp) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(highlight)
            .then(if (isCurrent) Modifier.background(colors.accent.copy(alpha = 0.14f)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = isCurrent }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(url = chapter.imageUrl, contentDescription = null, size = 44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) colors.accent else colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatClock(chapter.startMs),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

private val previewChapters = listOf(
    Chapter(startMs = 0L, title = "Cold open"),
    Chapter(startMs = 95_000L, title = "The first survey"),
    Chapter(startMs = 412_000L, title = "Steel, rivets and a storm"),
    Chapter(startMs = 3_723_000L, title = "Closing thoughts"),
)

@ThemePreviews
@Composable
private fun ChapterLinePreview() {
    AppTheme {
        Surface {
            ChapterLine(chapters = previewChapters, positionMs = { 420_000L }, modifier = Modifier.padding(Spacing.m))
        }
    }
}

@ThemePreviews
@Composable
private fun ChaptersSheetPreview() {
    AppTheme {
        Surface {
            ChaptersSheetContent(chapters = previewChapters, positionMs = { 420_000L }, onSeek = {})
        }
    }
}

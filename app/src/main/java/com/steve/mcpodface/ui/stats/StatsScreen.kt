package com.steve.mcpodface.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.FinishedCounts
import com.steve.mcpodface.data.db.PodcastListeningTotal
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.glassCard
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import java.time.LocalDate
import kotlin.math.max

/** Listening stats (PLAN.md §13), reached from the Library's Starred/History card. */
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val viewModel: StatsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatsContent(state = state, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsContent(state: StatsUiState, onBack: () -> Unit) {
    GlassPage {
        LargeTitleScaffold(
            title = "Stats",
            background = Color.Transparent,
            navigationIcon = { BackButton(onClick = onBack) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.m,
                    end = Spacing.m,
                    top = padding.calculateTopPadding() + Spacing.s,
                    bottom = padding.calculateBottomPadding() + Spacing.l,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                if (state.loaded && state.isEmpty) {
                    item(key = "empty") { EmptyStats(Modifier.fillParentMaxSize()) }
                } else if (state.loaded) {
                    statsItems(state)
                }
            }
        }
    }
}

/** The loaded stats blocks, in reading order. */
private fun LazyListScope.statsItems(state: StatsUiState) {
        item(key = "total") {
            StatsCard(Modifier.fillMaxWidth()) {
                Label("Listening time")
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    formatListeningTime(state.totalListenedMs),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary,
                )
            }
        }
        item(key = "hero-row") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                StatsCard(Modifier.weight(1f)) {
                    Label("Episodes finished")
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        state.finished.total.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary,
                    )
                    Text(
                        "${state.finished.last30Days} in the last 30 days",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.textSecondary,
                    )
                }
                StatsCard(Modifier.weight(1f)) {
                    Label("Saved by speed")
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        formatListeningTime(state.timeSavedMs),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary,
                    )
                    Text(
                        "Playing faster",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }
        }
        item(key = "chart") {
            StatsCard(Modifier.fillMaxWidth()) {
                Label("Last 30 days")
                Spacer(Modifier.height(Spacing.m))
                DailyBarChart(bars = state.bars)
                Spacer(Modifier.height(Spacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    StatBlock("This week", formatListeningTime(state.week.thisWeekMs))
                    StatBlock("Last week", formatListeningTime(state.week.lastWeekMs))
                }
                changeLabel(state.week.thisWeekMs, state.week.lastWeekMs)?.let { change ->
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        change,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }
        }
        item(key = "streaks") {
            StatsCard(Modifier.fillMaxWidth()) {
                Label("Daily streak")
                Text(
                    "Days with at least 5 minutes of listening",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    StatBlock("Current", formatDays(state.streaks.current))
                    StatBlock("Longest", formatDays(state.streaks.longest))
                }
            }
        }
        item(key = "top-podcasts-title") {
            Text(
                "Top podcasts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textSecondary,
                modifier = Modifier.padding(start = Spacing.xs, top = Spacing.s),
            )
        }
        itemsIndexed(state.topPodcasts, key = { _, item -> item.podcastId }) { _, item ->
            StatsCard(Modifier.fillMaxWidth()) {
                TopPodcastRow(
                    item = item,
                    share = shareOf(item.listenedMs, state.totalListenedMs),
                )
            }
        }
}

/** A glass card holding one stats block. */
@Composable
private fun StatsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember { RoundedRectangle(24.dp) }
    Column(
        modifier
            .glassCard(shape)
            .padding(Spacing.m),
        content = content,
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = AppTheme.colors.textSecondary,
    )
}

/** A caption above a value: "This week" over "2 h 5 min", "Current" over "4 days". */
@Composable
private fun StatBlock(caption: String, value: String) {
    Column {
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.textPrimary,
        )
    }
}

/** Thirty columns in the accent colour, scaled to the busiest day. Quiet days show as a stub. */
@Composable
private fun DailyBarChart(bars: List<DayBar>) {
    val accent = AppTheme.colors.accent
    val quiet = AppTheme.colors.hairline
    val total = bars.sumOf { it.listenedMs }
    val description = "Listening per day for the last ${bars.size} days, ${formatListeningTime(total)} in total"
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = description },
    ) {
        if (bars.isEmpty()) return@Canvas
        val peak = max(1L, bars.maxOf { it.listenedMs })
        val slot = size.width / bars.size
        val barWidth = slot * 0.6f
        val minHeight = 2.dp.toPx()
        val radius = CornerRadius(barWidth / 2f)
        bars.forEachIndexed { index, bar ->
            val height = if (bar.listenedMs <= 0L) minHeight
            else max(minHeight, size.height * bar.listenedMs.toFloat() / peak.toFloat())
            drawRoundRect(
                color = if (bar.listenedMs > 0L) accent else quiet,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }
    }
}

/** Podcast artwork, name, time and a thin bar showing this show's share of all listening. */
@Composable
private fun TopPodcastRow(item: PodcastListeningTotal, share: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(url = item.artworkUrl, contentDescription = null, size = 44.dp)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.podcastTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    formatListeningTime(item.listenedMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(AppTheme.colors.hairline, RoundedRectangle(2.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(share.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(AppTheme.colors.accent, RoundedRectangle(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun EmptyStats(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = Spacing.l),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.BarChart,
            contentDescription = null,
            tint = AppTheme.colors.accent,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(Spacing.m))
        Text(
            "Play something and your stats will show up here",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Previews -------------------------------------------------------------------------------

private val PREVIEW_TODAY: LocalDate = LocalDate.of(2026, 10, 19)
private const val PREVIEW_MINUTE_MS = 60_000L

private fun previewState(): StatsUiState {
    // A varied month: most days listen 15 to 105 minutes, some days are quiet.
    val totals = (0 until CHART_DAYS).associate { back ->
        val day = PREVIEW_TODAY.minusDays(back.toLong())
        val minutes = if (back % 6 == 4) 0L else ((back * 37) % 7 + 1) * 15L
        day to minutes * PREVIEW_MINUTE_MS
    }
    val podcasts = listOf(
        "Radio Lab Deep Dive" to 6.5,
        "Long Walk Stories" to 3.0,
        "Morning Brief" to 1.5,
        "History Unpacked" to 1.0,
        "Kitchen Table" to 0.5,
    )
    val total = totals.values.sum()
    return StatsUiState(
        loaded = true,
        totalListenedMs = total,
        finished = FinishedCounts(total = 214, last30Days = 17),
        timeSavedMs = 42 * 60 * PREVIEW_MINUTE_MS,
        bars = dailyBars(totals, PREVIEW_TODAY),
        week = weekComparison(totals, PREVIEW_TODAY),
        streaks = streaks(totals, PREVIEW_TODAY),
        topPodcasts = podcasts.mapIndexed { index, (title, hours) ->
            PodcastListeningTotal(
                podcastId = index + 1L,
                podcastTitle = title,
                artworkUrl = null,
                listenedMs = (hours * 60 * PREVIEW_MINUTE_MS).toLong(),
            )
        },
        isEmpty = false,
    )
}

@ThemePreviews
@Composable
private fun StatsPreview() {
    AppTheme {
        Surface {
            StatsContent(state = previewState(), onBack = {})
        }
    }
}

@ThemePreviews
@Composable
private fun StatsEmptyPreview() {
    AppTheme {
        Surface {
            StatsContent(state = StatsUiState(loaded = true, isEmpty = true), onBack = {})
        }
    }
}

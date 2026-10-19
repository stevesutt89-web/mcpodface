package com.steve.mcpodface.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.FinishedCounts
import com.steve.mcpodface.data.db.ListeningSessionDao
import com.steve.mcpodface.data.db.PodcastListeningTotal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToLong

data class StatsUiState(
    /** False until the first emission, so the empty state does not flash. */
    val loaded: Boolean = false,
    val totalListenedMs: Long = 0L,
    val finished: FinishedCounts = FinishedCounts(total = 0, last30Days = 0),
    val timeSavedMs: Long = 0L,
    /** The last [CHART_DAYS] days, oldest first. */
    val bars: List<DayBar> = emptyList(),
    val week: WeekComparison = WeekComparison(thisWeekMs = 0L, lastWeekMs = 0L),
    val streaks: StreakStats = StreakStats(current = 0, longest = 0),
    val topPodcasts: List<PodcastListeningTotal> = emptyList(),
    /** True when there are no listening sessions at all. */
    val isEmpty: Boolean = false,
)

/** Listening stats (PLAN.md §13). Day buckets use the system zone and the injected [Clock]. */
@HiltViewModel
class StatsViewModel @Inject constructor(
    sessions: ListeningSessionDao,
    clock: Clock,
) : ViewModel() {

    val state: StateFlow<StatsUiState> = run {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(clock.now()).atZone(zone).toLocalDate()
        combine(
            sessions.observeTotalListenedMs(),
            sessions.observeFinishedCounts(since = windowStartMillis(today, zone)),
            sessions.observeTimeSavedMs(),
            sessions.observeSessionTimes(),
            sessions.observeTopPodcasts(),
        ) { total, finished, savedMs, rows, top ->
            val totals = dailyListenedMs(rows, zone)
            StatsUiState(
                loaded = true,
                totalListenedMs = total,
                finished = finished,
                timeSavedMs = savedMs.roundToLong(),
                bars = dailyBars(totals, today),
                week = weekComparison(totals, today),
                streaks = streaks(totals, today),
                topPodcasts = top,
                isEmpty = rows.isEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
    }
}

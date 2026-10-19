package com.steve.mcpodface.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.HistoryItem
import com.steve.mcpodface.data.db.ListeningSessionDao
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import com.steve.mcpodface.ui.components.playedToggleTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** A History section: "Today", "Yesterday", "This week" or "Earlier", with its episodes newest first. */
data class HistorySection(val title: String, val items: List<HistoryItem>)

data class HistoryUiState(
    val sections: List<HistorySection> = emptyList(),
    /** False until the first emission, so the empty state does not flash. */
    val loaded: Boolean = false,
    /** Wall-clock time for relative dates in the rows. */
    val now: Long = 0L,
    val nowPlaying: NowPlaying? = null,
    /** Episodes in Listen Later, for each row's swipe label (PLAN.md §8.11). */
    val listenLaterIds: Set<Long> = emptySet(),
)

/**
 * Splits history into day buckets by the episode's last-listened date in [zone]. "This week" runs
 * from Monday up to, but not including, yesterday. Timestamps after [now] count as today. Empty
 * sections are dropped. Input order is kept inside each section.
 */
fun groupHistory(
    items: List<HistoryItem>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<HistorySection> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val todayItems = mutableListOf<HistoryItem>()
    val yesterdayItems = mutableListOf<HistoryItem>()
    val thisWeek = mutableListOf<HistoryItem>()
    val earlier = mutableListOf<HistoryItem>()
    for (item in items) {
        val date = Instant.ofEpochMilli(item.lastListenedAt).atZone(zone).toLocalDate()
        when {
            date >= today -> todayItems.add(item)
            date == yesterday -> yesterdayItems.add(item)
            date >= weekStart -> thisWeek.add(item)
            else -> earlier.add(item)
        }
    }
    return listOf(
        HistorySection("Today", todayItems),
        HistorySection("Yesterday", yesterdayItems),
        HistorySection("This week", thisWeek),
        HistorySection("Earlier", earlier),
    ).filter { it.items.isNotEmpty() }
}

/** History screen (PLAN.md §8.3): recently listened episodes, grouped by day. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessions: ListeningSessionDao,
    private val episodes: EpisodeRepository,
    private val player: PlayerController,
    private val playlists: PlaylistRepository,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<HistoryUiState> = combine(
        sessions.observeRecentlyPlayed(),
        player.nowPlaying,
        playlists.observeListenLaterIds(),
    ) { items, nowPlaying, listenLater ->
        val now = clock.now()
        HistoryUiState(
            sections = groupHistory(items, now),
            loaded = true,
            now = now,
            nowPlaying = nowPlaying,
            listenLaterIds = listenLater,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun togglePlay(episodeId: Long) = player.togglePlayPause(episodeId)

    fun clearHistory() {
        viewModelScope.launch { sessions.clearHistory() }
    }

    /** Swipe right: Listen Later add or remove (PLAN.md §8.11). */
    fun toggleListenLater(episodeId: Long) {
        viewModelScope.launch { playlists.toggleListenLater(episodeId) }
    }

    /** Swipe left: played or unplayed. Finishing an episode here does not change its history position. */
    fun togglePlayed(episode: EpisodeEntity) {
        val target = playedToggleTarget(episode.playState)
        viewModelScope.launch { episodes.setPlayed(episode.id, target) }
    }
}

package com.steve.mcpodface.ui.downloads

import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.db.DownloadDao
import com.steve.mcpodface.data.db.DownloadListItem
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.DownloadTotals
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastDownloadSize
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.download.DownloadManager
import com.steve.mcpodface.download.DownloadPaths
import com.steve.mcpodface.domain.policy.EffectiveSettings
import com.steve.mcpodface.domain.policy.resolve
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val TICK_MS = 60_000L

/** Sort order of the Downloaded section (PLAN.md §8.7). */
enum class DownloadSort { DATE_DOWNLOADED, PUBLISHED, PODCAST }

/** A finished download with its size and the cleanup label (null when no rule will delete it). */
data class DownloadedRow(
    val item: DownloadListItem,
    val sizeBytes: Long,
    val deletionLabel: String?,
)

data class DownloadsUiState(
    /** False until the first snapshot, so the empty state does not flash. */
    val loaded: Boolean = false,
    val downloading: List<DownloadListItem> = emptyList(),
    val queued: List<DownloadListItem> = emptyList(),
    val failed: List<DownloadListItem> = emptyList(),
    /** Visible Downloaded rows: sorted and filtered. */
    val downloaded: List<DownloadedRow> = emptyList(),
    /** Every completed download, unfiltered, for the bulk actions. */
    val allDownloaded: List<DownloadedRow> = emptyList(),
    val episodeCount: Int = 0,
    /** Sum of the files on disk when known, else the database total. */
    val totalBytes: Long = 0L,
    val freeBytes: Long? = null,
    val podcastSizes: List<PodcastDownloadSize> = emptyList(),
    val sort: DownloadSort = DownloadSort.DATE_DOWNLOADED,
    val unplayedOnly: Boolean = false,
    /** Multi-select: non-empty means selection mode is on. */
    val selected: Set<Long> = emptySet(),
    /** Wall-clock time the labels were computed for. */
    val now: Long = 0L,
)

/** User-controlled inputs that do not come from the database. */
internal data class DownloadsControls(
    val sort: DownloadSort = DownloadSort.DATE_DOWNLOADED,
    val unplayedOnly: Boolean = false,
    val selected: Set<Long> = emptySet(),
)

/** Disk figures from the IO thread. Both null until the first check finishes. */
internal data class StorageStats(val diskBytes: Long?, val freeBytes: Long?)

// ---- Pure helpers (PLAN.md §8.7), unit-tested without Android ----------------------------------

/** Size of a download: the file size once known, else what has been fetched so far. */
internal fun downloadSizeBytes(item: DownloadListItem): Long =
    item.download.totalBytes ?: item.download.bytesDownloaded

/** The total to show: the disk sum when it is known (it is the truth), else the database total. */
internal fun displayedTotalBytes(databaseBytes: Long, diskBytes: Long?): Long = diskBytes ?: databaseBytes

/** Sorts, then optionally hides played episodes. Ties break on episode id so the order is stable. */
internal fun arrangeDownloaded(rows: List<DownloadedRow>, sort: DownloadSort, unplayedOnly: Boolean): List<DownloadedRow> {
    val visible = if (unplayedOnly) rows.filter { it.item.playState != PlayState.PLAYED } else rows
    return when (sort) {
        DownloadSort.DATE_DOWNLOADED -> visible.sortedWith(
            compareByDescending<DownloadedRow> { it.item.download.downloadedAt ?: Long.MIN_VALUE }
                .thenBy { it.item.download.episodeId },
        )
        DownloadSort.PUBLISHED -> visible.sortedWith(
            compareByDescending<DownloadedRow> { it.item.pubDate }.thenBy { it.item.download.episodeId },
        )
        DownloadSort.PODCAST -> visible.sortedWith(
            compareBy<DownloadedRow>({ it.item.podcastTitle.lowercase() }, { it.item.podcastId })
                .thenByDescending { it.item.pubDate }
                .thenBy { it.item.download.episodeId },
        )
    }
}

internal fun buildDownloadedRow(
    item: DownloadListItem,
    global: GlobalSettings,
    overrides: Map<Long, PodcastSettingsEntity>,
    now: Long,
): DownloadedRow {
    val effective: EffectiveSettings = resolve(global, overrides[item.podcastId], item.feedType)
    val eta = deletionEta(
        download = item.download,
        playState = item.playState,
        playedAt = item.playedAt,
        pubDate = item.pubDate,
        isStarred = item.isStarred,
        settings = effective,
        now = now,
    )
    return DownloadedRow(item, downloadSizeBytes(item), formatDeletionLabel(eta, now))
}

/** Splits the joined rows into the sections. Downloaded rows are kept unsorted here. */
internal data class DownloadsSnapshot(
    val downloading: List<DownloadListItem>,
    val queued: List<DownloadListItem>,
    val failed: List<DownloadListItem>,
    val completed: List<DownloadedRow>,
    val now: Long,
)

internal fun buildSnapshot(
    items: List<DownloadListItem>,
    global: GlobalSettings,
    overrides: Map<Long, PodcastSettingsEntity>,
    now: Long,
): DownloadsSnapshot {
    val byState = items.groupBy { it.download.state }
    return DownloadsSnapshot(
        downloading = byState[DownloadState.DOWNLOADING].orEmpty(),
        queued = items.filter {
            it.download.state == DownloadState.QUEUED || it.download.state == DownloadState.PAUSED_NETWORK
        },
        failed = byState[DownloadState.FAILED].orEmpty(),
        completed = byState[DownloadState.COMPLETED].orEmpty().map { buildDownloadedRow(it, global, overrides, now) },
        now = now,
    )
}

internal fun buildUiState(
    snapshot: DownloadsSnapshot,
    totals: DownloadTotals,
    sizes: List<PodcastDownloadSize>,
    storage: StorageStats,
    controls: DownloadsControls,
): DownloadsUiState {
    val completedIds = snapshot.completed.mapTo(HashSet()) { it.item.download.episodeId }
    return DownloadsUiState(
        loaded = true,
        downloading = snapshot.downloading,
        queued = snapshot.queued,
        failed = snapshot.failed,
        downloaded = arrangeDownloaded(snapshot.completed, controls.sort, controls.unplayedOnly),
        allDownloaded = snapshot.completed,
        episodeCount = totals.episodeCount,
        totalBytes = displayedTotalBytes(totals.totalBytes, storage.diskBytes),
        freeBytes = storage.freeBytes,
        podcastSizes = sizes,
        sort = controls.sort,
        unplayedOnly = controls.unplayedOnly,
        selected = controls.selected.intersect(completedIds),
        now = snapshot.now,
    )
}

/** Free bytes on the volume holding [root], walking up to the nearest folder that exists. */
private fun freeSpaceBytes(root: File): Long? {
    val existing = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() } ?: return null
    return try {
        StatFs(existing.path).availableBytes
    } catch (e: IllegalArgumentException) {
        null
    }
}

// ---- ViewModel ---------------------------------------------------------------------------------

/**
 * Downloads tab (PLAN.md §8.7). Every change goes through [DownloadManager]; this class only reads
 * the database and the disk.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadDao: DownloadDao,
    private val settingsRepository: SettingsRepository,
    private val downloadManager: DownloadManager,
    private val paths: DownloadPaths,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val controls = MutableStateFlow(DownloadsControls())

    /** Ticks so "Deletes in 18h" stays current while the screen is open. */
    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(clock.now())
            delay(TICK_MS)
        }
    }

    private val settings: Flow<Pair<GlobalSettings, Map<Long, PodcastSettingsEntity>>> = combine(
        settingsRepository.settings,
        downloadDao.observePodcastSettings(),
    ) { global, overrides -> global to overrides.associateBy { it.podcastId } }

    private val snapshot: Flow<DownloadsSnapshot> = combine(
        downloadDao.observeDownloadItems(),
        settings,
        ticks,
    ) { items, (global, overrides), now -> buildSnapshot(items, global, overrides, now) }

    /**
     * Disk figures for the completed files, recomputed only when the set of files changes. Starts
     * empty so the database total shows at once.
     */
    private val storage: Flow<StorageStats> = downloadDao.observeDownloadItems()
        .map { items ->
            items.mapNotNull { it.download.takeIf { d -> d.state == DownloadState.COMPLETED }?.filePath }.sorted()
        }
        .distinctUntilChanged()
        .mapLatest { files ->
            withContext(io) {
                StorageStats(
                    diskBytes = files.sumOf { File(it).length() },
                    freeBytes = freeSpaceBytes(paths.root),
                )
            }
        }
        .onStart { emit(StorageStats(diskBytes = null, freeBytes = null)) }

    val state: StateFlow<DownloadsUiState> = combine(
        snapshot,
        combine(downloadDao.observeTotals(), downloadDao.observeSizesByPodcast(), storage) { totals, sizes, disk ->
            Header(totals, sizes, disk)
        },
        controls,
    ) { snap, header, ctl ->
        buildUiState(snap, header.totals, header.sizes, header.storage, ctl)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    private data class Header(
        val totals: DownloadTotals,
        val sizes: List<PodcastDownloadSize>,
        val storage: StorageStats,
    )

    fun cancel(episodeId: Long) {
        viewModelScope.launch { downloadManager.cancel(episodeId) }
    }

    fun retry(episodeId: Long) {
        viewModelScope.launch { downloadManager.retry(episodeId) }
    }

    /** Removes one download (swipe, or "remove" on a failed row). */
    fun delete(episodeId: Long) {
        viewModelScope.launch { downloadManager.delete(episodeId) }
    }

    /** Bulk delete for the bulk actions and multi-select. Clears the selection. */
    fun deleteEpisodes(episodeIds: Collection<Long>) {
        val ids = episodeIds.toList()
        controls.update { it.copy(selected = emptySet()) }
        viewModelScope.launch { downloadManager.deleteAll(ids) }
    }

    fun setSort(sort: DownloadSort) {
        controls.update { it.copy(sort = sort) }
    }

    fun setUnplayedOnly(unplayedOnly: Boolean) {
        controls.update { it.copy(unplayedOnly = unplayedOnly) }
    }

    /** Long-press on a Downloaded row starts multi-select with that row. */
    fun startSelection(episodeId: Long) {
        controls.update { it.copy(selected = it.selected + episodeId) }
    }

    fun toggleSelected(episodeId: Long) {
        controls.update {
            it.copy(selected = if (episodeId in it.selected) it.selected - episodeId else it.selected + episodeId)
        }
    }

    fun clearSelection() {
        controls.update { it.copy(selected = emptySet()) }
    }
}

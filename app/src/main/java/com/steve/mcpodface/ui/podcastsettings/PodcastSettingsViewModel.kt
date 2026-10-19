package com.steve.mcpodface.ui.podcastsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.db.PodcastSettingsDao
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything the per-podcast settings sheet shows. */
data class PodcastSettingsUiState(
    /** False until Room has emitted the podcast, so the sheet can wait before showing rows. */
    val loaded: Boolean = false,
    val title: String = "",
    val feedType: FeedType = FeedType.EPISODIC,
    /** The override row, or a blank row (every field inherits) when none has been saved yet. */
    val settings: PodcastSettingsEntity = PodcastSettingsEntity(podcastId = 0L),
    val global: GlobalSettings = GlobalSettings(),
) {
    internal val inheritance: Inheritance get() = Inheritance(global, feedType)
}

/**
 * Backs the per-podcast settings sheet (PLAN.md §8.4). Created per podcast through [Factory], so the
 * podcast id is an assisted parameter rather than a route argument.
 */
@HiltViewModel(assistedFactory = PodcastSettingsViewModel.Factory::class)
class PodcastSettingsViewModel @AssistedInject constructor(
    @Assisted private val podcastId: Long,
    podcastDao: PodcastDao,
    private val podcastSettingsDao: PodcastSettingsDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(podcastId: Long): PodcastSettingsViewModel
    }

    /** Serialises read-modify-write so two quick taps cannot overwrite each other's field. */
    private val writeLock = Mutex()

    val uiState: StateFlow<PodcastSettingsUiState> = combine(
        podcastDao.observeById(podcastId),
        podcastSettingsDao.observe(podcastId),
        settingsRepository.settings,
    ) { podcast, settings, global ->
        PodcastSettingsUiState(
            loaded = true,
            title = podcast?.title.orEmpty(),
            feedType = podcast?.feedType ?: FeedType.EPISODIC,
            settings = settings ?: PodcastSettingsEntity(podcastId = podcastId),
            global = global,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastSettingsUiState())

    /**
     * Applies [transform] to the stored row, creating it with [podcastId] when it does not exist yet.
     * The transform must only change the fields it owns; everything else is written back unchanged.
     */
    fun update(transform: (PodcastSettingsEntity) -> PodcastSettingsEntity) {
        viewModelScope.launch {
            writeLock.withLock {
                val existing = podcastSettingsDao.get(podcastId) ?: PodcastSettingsEntity(podcastId = podcastId)
                podcastSettingsDao.upsert(transform(existing))
            }
        }
    }
}

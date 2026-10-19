package com.steve.mcpodface.ui.library

import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.playback.NoOpPlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal const val LIBRARY_TEST_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z
internal const val LIBRARY_TEST_HOUR = 3_600_000L

internal class FakeLibrarySettings : SettingsRepository {
    private val state = MutableStateFlow(GlobalSettings())
    override val settings: Flow<GlobalSettings> = state
    override suspend fun update(transform: (GlobalSettings) -> GlobalSettings): GlobalSettings =
        transform(state.value).also { state.value = it }
}

/** Room and the ViewModels' stateIn run on other threads, so wait in real time. */
internal suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T =
    withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

internal suspend fun eventually(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
    withTimeout(5_000) { while (!condition()) delay(20) }
}

internal class RecordingPlayer : NoOpPlayerController() {
    val toggled = mutableListOf<Long>()
    override fun togglePlayPause(episodeId: Long) { toggled += episodeId }
}

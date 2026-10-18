package com.steve.mcpodface.data.prefs

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    /** Emits the current settings, then again on every change. */
    val settings: Flow<GlobalSettings>

    /** Atomically reads, transforms and writes the settings. Returns the new value. */
    suspend fun update(transform: (GlobalSettings) -> GlobalSettings): GlobalSettings
}

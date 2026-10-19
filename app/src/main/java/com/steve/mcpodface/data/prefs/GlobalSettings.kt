package com.steve.mcpodface.data.prefs

// Pure Kotlin: domain/policy reads this, so no Android imports here.

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppTab { LATEST, LIBRARY, DOWNLOADS, UP_NEXT, SEARCH }

enum class SearchMode { YOUR_PODCASTS, DISCOVER }

enum class LibrarySort { RECENTLY_UPDATED, ALPHABETICAL, RECENTLY_ADDED }

enum class LibraryLayout { GRID, LIST }

enum class LatestFilter { ALL, UNPLAYED, DOWNLOADED }

/** Every global setting from PLAN.md §10, with its default. */
data class GlobalSettings(
    // Appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = true,
    /** Artwork-coloured full player (the Liquid Glass look); off = neutral background, app accent. */
    val tintPlayer: Boolean = true,
    val dynamicColor: Boolean = false,
    // UI state
    val lastTab: AppTab = AppTab.LATEST,
    val searchMode: SearchMode = SearchMode.YOUR_PODCASTS,
    val librarySort: LibrarySort = LibrarySort.RECENTLY_UPDATED,
    val libraryLayout: LibraryLayout = LibraryLayout.GRID,
    val latestFilter: LatestFilter = LatestFilter.ALL,
    // Downloads
    val autoDownload: Boolean = true,
    val keepLatestCount: Int = 5,
    val allowCellularDownload: Boolean = false,
    val downloadOnlyWhileCharging: Boolean = false,
    val streamOnCellular: Boolean = true,
    // Auto-delete
    val deletePlayed: Boolean = true,
    val deletePlayedDelayHours: Int = 24,
    val maxAgeDays: Int = 30,
    val countManualTowardLimit: Boolean = false,
    // Playback
    val markPlayedPercent: Int = 95,
    val skipBackSec: Int = 10,
    val skipForwardSec: Int = 30,
    val defaultSpeed: Float = 1.0f,
    val autoplayNext: Boolean = false,
    val resumeRewind: Boolean = true,
    val trimSilence: Boolean = false,
    val voiceBoost: Boolean = false,
    // Refresh. 0 = manual only.
    val refreshIntervalHours: Int = 4,
    val notifyNewEpisodes: Boolean = true,
    // Search & archive
    val backfillEnabled: Boolean = true,
    val backfillWifiOnly: Boolean = true,
    val backfillChargingOnly: Boolean = false,
    val apiDailyBudget: Int = 100,
    // Rate limiter state (PLAN.md §7.4). Written only by the RateLimiter.
    val apiRequestsToday: Int = 0,
    /** Local epoch day that apiRequestsToday belongs to. */
    val apiBudgetDay: Long = 0,
    /** Epoch millis; Podcast Index backfill is paused until then after a 429. */
    val apiPausedUntil: Long = 0,
    // First run (PLAN.md §12). Set once the POST_NOTIFICATIONS prompt has been shown.
    val notificationPermissionAsked: Boolean = false,
)

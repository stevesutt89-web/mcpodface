package com.steve.mcpodface.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Key names match PLAN.md §10 and are part of the backup format (§9). Don't rename them. */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<GlobalSettings> =
        dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun update(transform: (GlobalSettings) -> GlobalSettings): GlobalSettings =
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { write(transform(prefs.toSettings())) }
        }.toSettings()

    /**
     * Every persisted setting for the backup (PLAN.md §9), as DataStore key name → string value.
     * Written through the same mapping as [update], so the keys are always the DataStore names.
     * Rate-limiter state (apiRequestsToday, apiBudgetDay, apiPausedUntil) is left out.
     */
    suspend fun backupEntries(): Map<String, String> {
        val settings = dataStore.data.first().toSettings()
        return emptyPreferences().toMutablePreferences()
            .apply { write(settings) }
            .asMap()
            .filterKeys { it.name !in RATE_LIMITER_KEY_NAMES }
            .map { (key, value) -> key.name to value.toString() }
            .toMap()
    }

    /**
     * Restores settings from a backup's [backupEntries] map. Each value is parsed as its key's type;
     * unknown keys, unparseable values, rate-limiter state and per-install keys are skipped.
     */
    suspend fun restoreEntries(entries: Map<String, String>) {
        dataStore.updateData { prefs ->
            val typed = emptyPreferences().toMutablePreferences().apply { write(prefs.toSettings()) }.asMap()
            prefs.toMutablePreferences().apply {
                for ((key, current) in typed) {
                    if (key.name in RATE_LIMITER_KEY_NAMES || key.name in PER_INSTALL_KEY_NAMES) continue
                    val raw = entries[key.name] ?: continue
                    val value: Any = when (current) {
                        is Boolean -> raw.toBooleanStrictOrNull()
                        is Int -> raw.toIntOrNull()
                        is Long -> raw.toLongOrNull()
                        is Float -> raw.toFloatOrNull()
                        is String -> raw
                        else -> null
                    } ?: continue
                    @Suppress("UNCHECKED_CAST")
                    this[key as Preferences.Key<Any>] = value
                }
            }
        }
    }

    private object K {
        val theme = stringPreferencesKey("theme")
        val pureBlack = booleanPreferencesKey("pureBlack")
        val tintPlayer = booleanPreferencesKey("tintPlayer")
        val dynamicColor = booleanPreferencesKey("dynamicColor")
        val lastTab = stringPreferencesKey("lastTab")
        val searchMode = stringPreferencesKey("searchMode")
        val librarySort = stringPreferencesKey("librarySort")
        val libraryLayout = stringPreferencesKey("libraryLayout")
        val latestFilter = stringPreferencesKey("latestFilter")
        val autoDownload = booleanPreferencesKey("autoDownload")
        val keepLatestCount = intPreferencesKey("keepLatestCount")
        val allowCellularDownload = booleanPreferencesKey("allowCellularDownload")
        val downloadOnlyWhileCharging = booleanPreferencesKey("downloadOnlyWhileCharging")
        val streamOnCellular = booleanPreferencesKey("streamOnCellular")
        val deletePlayed = booleanPreferencesKey("deletePlayed")
        val deletePlayedDelayHours = intPreferencesKey("deletePlayedDelayHours")
        val maxAgeDays = intPreferencesKey("maxAgeDays")
        val countManualTowardLimit = booleanPreferencesKey("countManualTowardLimit")
        val markPlayedPercent = intPreferencesKey("markPlayedPercent")
        val skipBackSec = intPreferencesKey("skipBackSec")
        val skipForwardSec = intPreferencesKey("skipForwardSec")
        val defaultSpeed = floatPreferencesKey("defaultSpeed")
        val autoplayNext = booleanPreferencesKey("autoplayNext")
        val resumeRewind = booleanPreferencesKey("resumeRewind")
        val trimSilence = booleanPreferencesKey("trimSilence")
        val voiceBoost = booleanPreferencesKey("voiceBoost")
        val refreshIntervalHours = intPreferencesKey("refreshIntervalHours")
        val notifyNewEpisodes = booleanPreferencesKey("notifyNewEpisodes")
        val backfillEnabled = booleanPreferencesKey("backfillEnabled")
        val backfillWifiOnly = booleanPreferencesKey("backfillWifiOnly")
        val backfillChargingOnly = booleanPreferencesKey("backfillChargingOnly")
        val apiDailyBudget = intPreferencesKey("apiDailyBudget")
        val apiRequestsToday = intPreferencesKey("apiRequestsToday")
        val apiBudgetDay = longPreferencesKey("apiBudgetDay")
        val apiPausedUntil = longPreferencesKey("apiPausedUntil")
        val notificationPermissionAsked = booleanPreferencesKey("notificationPermissionAsked")
    }

    private fun Preferences.toSettings(): GlobalSettings {
        val d = GlobalSettings()
        return GlobalSettings(
            theme = enumOr(this[K.theme], d.theme),
            pureBlack = this[K.pureBlack] ?: d.pureBlack,
            tintPlayer = this[K.tintPlayer] ?: d.tintPlayer,
            dynamicColor = this[K.dynamicColor] ?: d.dynamicColor,
            lastTab = enumOr(this[K.lastTab], d.lastTab),
            searchMode = enumOr(this[K.searchMode], d.searchMode),
            librarySort = enumOr(this[K.librarySort], d.librarySort),
            libraryLayout = enumOr(this[K.libraryLayout], d.libraryLayout),
            latestFilter = enumOr(this[K.latestFilter], d.latestFilter),
            autoDownload = this[K.autoDownload] ?: d.autoDownload,
            keepLatestCount = this[K.keepLatestCount] ?: d.keepLatestCount,
            allowCellularDownload = this[K.allowCellularDownload] ?: d.allowCellularDownload,
            downloadOnlyWhileCharging = this[K.downloadOnlyWhileCharging] ?: d.downloadOnlyWhileCharging,
            streamOnCellular = this[K.streamOnCellular] ?: d.streamOnCellular,
            deletePlayed = this[K.deletePlayed] ?: d.deletePlayed,
            deletePlayedDelayHours = this[K.deletePlayedDelayHours] ?: d.deletePlayedDelayHours,
            maxAgeDays = this[K.maxAgeDays] ?: d.maxAgeDays,
            countManualTowardLimit = this[K.countManualTowardLimit] ?: d.countManualTowardLimit,
            markPlayedPercent = this[K.markPlayedPercent] ?: d.markPlayedPercent,
            skipBackSec = this[K.skipBackSec] ?: d.skipBackSec,
            skipForwardSec = this[K.skipForwardSec] ?: d.skipForwardSec,
            defaultSpeed = this[K.defaultSpeed] ?: d.defaultSpeed,
            autoplayNext = this[K.autoplayNext] ?: d.autoplayNext,
            resumeRewind = this[K.resumeRewind] ?: d.resumeRewind,
            trimSilence = this[K.trimSilence] ?: d.trimSilence,
            voiceBoost = this[K.voiceBoost] ?: d.voiceBoost,
            refreshIntervalHours = this[K.refreshIntervalHours] ?: d.refreshIntervalHours,
            notifyNewEpisodes = this[K.notifyNewEpisodes] ?: d.notifyNewEpisodes,
            backfillEnabled = this[K.backfillEnabled] ?: d.backfillEnabled,
            backfillWifiOnly = this[K.backfillWifiOnly] ?: d.backfillWifiOnly,
            backfillChargingOnly = this[K.backfillChargingOnly] ?: d.backfillChargingOnly,
            apiDailyBudget = this[K.apiDailyBudget] ?: d.apiDailyBudget,
            apiRequestsToday = this[K.apiRequestsToday] ?: d.apiRequestsToday,
            apiBudgetDay = this[K.apiBudgetDay] ?: d.apiBudgetDay,
            apiPausedUntil = this[K.apiPausedUntil] ?: d.apiPausedUntil,
            notificationPermissionAsked = this[K.notificationPermissionAsked] ?: d.notificationPermissionAsked,
        )
    }

    private fun MutablePreferences.write(s: GlobalSettings) {
        this[K.theme] = s.theme.name
        this[K.pureBlack] = s.pureBlack
        this[K.tintPlayer] = s.tintPlayer
        this[K.dynamicColor] = s.dynamicColor
        this[K.lastTab] = s.lastTab.name
        this[K.searchMode] = s.searchMode.name
        this[K.librarySort] = s.librarySort.name
        this[K.libraryLayout] = s.libraryLayout.name
        this[K.latestFilter] = s.latestFilter.name
        this[K.autoDownload] = s.autoDownload
        this[K.keepLatestCount] = s.keepLatestCount
        this[K.allowCellularDownload] = s.allowCellularDownload
        this[K.downloadOnlyWhileCharging] = s.downloadOnlyWhileCharging
        this[K.streamOnCellular] = s.streamOnCellular
        this[K.deletePlayed] = s.deletePlayed
        this[K.deletePlayedDelayHours] = s.deletePlayedDelayHours
        this[K.maxAgeDays] = s.maxAgeDays
        this[K.countManualTowardLimit] = s.countManualTowardLimit
        this[K.markPlayedPercent] = s.markPlayedPercent
        this[K.skipBackSec] = s.skipBackSec
        this[K.skipForwardSec] = s.skipForwardSec
        this[K.defaultSpeed] = s.defaultSpeed
        this[K.autoplayNext] = s.autoplayNext
        this[K.resumeRewind] = s.resumeRewind
        this[K.trimSilence] = s.trimSilence
        this[K.voiceBoost] = s.voiceBoost
        this[K.refreshIntervalHours] = s.refreshIntervalHours
        this[K.notifyNewEpisodes] = s.notifyNewEpisodes
        this[K.backfillEnabled] = s.backfillEnabled
        this[K.backfillWifiOnly] = s.backfillWifiOnly
        this[K.backfillChargingOnly] = s.backfillChargingOnly
        this[K.apiDailyBudget] = s.apiDailyBudget
        this[K.apiRequestsToday] = s.apiRequestsToday
        this[K.apiBudgetDay] = s.apiBudgetDay
        this[K.apiPausedUntil] = s.apiPausedUntil
        this[K.notificationPermissionAsked] = s.notificationPermissionAsked
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private companion object {
        val RATE_LIMITER_KEY_NAMES = setOf("apiRequestsToday", "apiBudgetDay", "apiPausedUntil")

        /** Belong to this install, not the user: never restored from a backup. */
        val PER_INSTALL_KEY_NAMES = setOf("notificationPermissionAsked")
    }
}

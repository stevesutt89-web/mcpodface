package com.steve.mcpodface.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.steve.mcpodface.backfill.BackfillWorker
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.backup.Opml
import com.steve.mcpodface.data.backup.OpmlExporter
import com.steve.mcpodface.data.backup.OpmlImportProgress
import com.steve.mcpodface.data.backup.OpmlImporter
import com.steve.mcpodface.data.backup.OpmlParseException
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.BackfillStateDao
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.refresh.PolicyRunner
import com.steve.mcpodface.ui.podcastsettings.ApiUsage
import com.steve.mcpodface.ui.podcastsettings.ArchiveProgress
import com.steve.mcpodface.ui.podcastsettings.apiUsage
import com.steve.mcpodface.ui.podcastsettings.archiveProgress
import com.steve.mcpodface.ui.podcastsettings.pausedUntilLine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Archive totals for Settings → Search & archive (PLAN.md §8.10). */
data class ArchiveOverview(
    val progress: ArchiveProgress = ArchiveProgress(complete = 0, total = 0),
    val usage: ApiUsage = ApiUsage(requestsToday = 0, dailyBudget = GlobalSettings().apiDailyBudget, pausedUntil = 0L),
    /** "Paused until 3:40 pm" while the Podcast Index 429 pause runs, otherwise null. */
    val pausedLabel: String? = null,
    val databaseBytes: Long = 0L,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val policyRunner: PolicyRunner,
    private val importer: OpmlImporter,
    private val exporter: OpmlExporter,
    private val podcastDao: PodcastDao,
    private val backfillStateDao: BackfillStateDao,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val settings: StateFlow<GlobalSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    /** Size of the database file plus its WAL, in bytes. Read off the main thread by [refreshDatabaseSize]. */
    private val databaseBytes = MutableStateFlow(0L)

    /** Archive progress, API requests today and the database size, for the "Search & archive" block. */
    val archive: StateFlow<ArchiveOverview> = combine(
        podcastDao.observeSubscribed(),
        backfillStateDao.observeAll(),
        settingsRepository.settings,
        databaseBytes,
    ) { subscribed, states, global, bytes ->
        val now = clock.now()
        val usage = apiUsage(global, now)
        ArchiveOverview(
            progress = archiveProgress(subscribed.map { it.id }, states),
            usage = usage,
            pausedLabel = pausedUntilLine(usage, now),
            databaseBytes = bytes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveOverview())

    init {
        refreshDatabaseSize()
    }

    /** Re-reads the database size, e.g. when Settings resumes after a refresh or backfill has run. */
    fun refreshDatabaseSize() {
        viewModelScope.launch {
            val bytes = withContext(io) {
                val db = context.getDatabasePath(AppDatabase.NAME)
                db.length() + File(db.path + "-wal").length()
            }
            databaseBytes.value = bytes
        }
    }

    /** One backfill run as soon as the global constraints allow (PLAN.md §7.4). */
    fun backfillNow() {
        viewModelScope.launch {
            BackfillWorker.runNow(WorkManager.getInstance(context), settingsRepository.settings.first())
        }
    }

    /** OPML import progress; null when there is nothing to show. */
    val importProgress: StateFlow<OpmlImportProgress?> = importer.progress

    /** True after "Hide" is tapped on a running import; reset when a new import starts. */
    private val _importHidden = MutableStateFlow(false)
    val importHidden: StateFlow<Boolean> = _importHidden.asStateFlow()

    /** One-line messages for the user (shown as toasts by the screen). */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /**
     * A global change that would delete downloads, waiting for the user to confirm (PLAN.md §6.5).
     * [count] and [bytes] come from [PolicyRunner.previewCleanup]; nothing is deleted until confirmed.
     */
    data class PendingCleanup(
        val transform: (GlobalSettings) -> GlobalSettings,
        val count: Int,
        val bytes: Long,
    )

    private val _pendingCleanup = MutableStateFlow<PendingCleanup?>(null)
    val pendingCleanup: StateFlow<PendingCleanup?> = _pendingCleanup.asStateFlow()

    /**
     * Applies a global setting change. Changes that keep at least as many downloads as before apply
     * at once. Ones that would delete files are previewed first: with nothing to delete they apply
     * straight away, otherwise they wait in [pendingCleanup] until [confirmCleanup] or [cancelCleanup].
     */
    fun update(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch {
            val before = settingsRepository.settings.first()
            if (!CleanupChange.reducesRetention(before, transform(before))) {
                settingsRepository.update(transform)
                return@launch
            }
            val (count, bytes) = policyRunner.previewCleanup(transform)
            if (count == 0) {
                settingsRepository.update(transform)
            } else {
                _pendingCleanup.value = PendingCleanup(transform, count, bytes)
            }
        }
    }

    /** The user confirmed the preview: apply the setting, then delete the files now. */
    fun confirmCleanup() {
        val pending = _pendingCleanup.value ?: return
        _pendingCleanup.value = null
        viewModelScope.launch {
            settingsRepository.update(pending.transform)
            policyRunner.runCleanup()
        }
    }

    /** The user cancelled the preview: the setting stays as it was. */
    fun cancelCleanup() {
        _pendingCleanup.value = null
    }

    /** Reads an OPML file chosen with the Storage Access Framework and starts the background import. */
    fun importOpml(uri: Uri) {
        viewModelScope.launch {
            val feeds = try {
                withContext(io) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open the file")
                    input.use { Opml.parse(it) }
                }
            } catch (e: OpmlParseException) {
                _messages.send(e.message ?: "Not a valid OPML file")
                return@launch
            } catch (e: IOException) {
                _messages.send("Could not read the file")
                return@launch
            }
            when {
                feeds.isEmpty() -> _messages.send("No podcast feeds found in that file")
                importer.progress.value?.running == true -> _messages.send("An import is already running")
                else -> {
                    _importHidden.value = false
                    importer.start(feeds)
                }
            }
        }
    }

    fun hideImport() {
        _importHidden.value = true
    }

    fun dismissImportResult() {
        importer.dismissResult()
    }

    /** Writes all subscriptions to [uri] as OPML and reports the count. */
    fun exportOpml(uri: Uri) {
        viewModelScope.launch {
            val message = try {
                val count = withContext(io) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Could not open the file")
                    out.use { exporter.export(it) }
                }
                "Exported $count ${if (count == 1) "podcast" else "podcasts"}"
            } catch (e: IOException) {
                "Export failed: ${e.message ?: "could not write the file"}"
            }
            _messages.send(message)
        }
    }
}

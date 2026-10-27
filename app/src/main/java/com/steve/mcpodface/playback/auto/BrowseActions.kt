package com.steve.mcpodface.playback.auto

import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.download.DownloadManager
import com.steve.mcpodface.ui.components.playedToggleTarget
import com.steve.mcpodface.ui.latest.DownloadMenuAction
import com.steve.mcpodface.ui.latest.downloadMenuAction
import javax.inject.Inject

/**
 * Row actions on car lists (docs/ANDROID-AUTO.md, task A8): Up Next on/off, download or remove the
 * download, star, mark played. LibraryCallback.onCustomCommand sends AutoCommands.ROW_ACTIONS here
 * with the row's episode id (wired in A7).
 *
 * Each action calls the same repository or manager method the phone UI calls (the Latest, Podcast,
 * Starred, History and Episode ViewModels), so side effects match: the download tombstone on delete,
 * playedAt on mark played, and so on. The car cannot show the mobile-data prompt, so a download that
 * would need it is queued with "Wait for Wi-Fi" (allowCellular = false), which is what the phone's
 * prompt does when the user picks that option.
 */
class BrowseActions @Inject constructor(
    private val db: AppDatabase,
    private val queue: QueueRepository,
    private val downloads: DownloadManager,
    private val episodes: EpisodeRepository,
) {
    suspend fun run(action: String, episodeId: Long): SessionResult {
        if (action !in AutoCommands.ROW_ACTIONS) {
            return SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        }
        val episode = db.episodeDao().getById(episodeId)
            ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        when (action) {
            // Swipe right / Up Next menu: add at the top, or remove when already queued.
            AutoCommands.ROW_UP_NEXT -> queue.toggle(episodeId)
            AutoCommands.ROW_DOWNLOAD -> toggleDownload(episodeId)
            // Starred toggle, as EpisodeViewModel.toggleStarred / LatestViewModel.toggleStar.
            AutoCommands.ROW_STAR -> episodes.setStarred(episodeId, starred = !episode.isStarred)
            // Swipe left: played or unplayed, as the phone's togglePlayed does.
            AutoCommands.ROW_MARK_PLAYED -> episodes.setPlayed(episodeId, played = playedToggleTarget(episode.playState))
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    /** The long-press download item: download, remove or cancel, chosen by the row's download state. */
    private suspend fun toggleDownload(episodeId: Long) {
        val state = db.downloadDao().get(episodeId)?.state
        when (downloadMenuAction(state)) {
            DownloadMenuAction.DOWNLOAD -> {
                // Phone: ask about mobile data first. Car: no prompt, so take "Wait for Wi-Fi" when it would apply.
                val needsPrompt = downloads.needsCellularPrompt(episodeId)
                downloads.enqueue(
                    episodeId,
                    DownloadSource.MANUAL,
                    allowCellular = if (needsPrompt) false else null,
                )
            }
            DownloadMenuAction.REMOVE -> downloads.delete(episodeId)
            DownloadMenuAction.CANCEL -> downloads.cancel(episodeId)
        }
    }
}

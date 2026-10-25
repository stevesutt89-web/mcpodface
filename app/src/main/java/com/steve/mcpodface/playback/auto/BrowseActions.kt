package com.steve.mcpodface.playback.auto

import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.download.DownloadManager
import javax.inject.Inject

/**
 * Row actions on car lists (docs/ANDROID-AUTO.md, task A8): Up Next on/off, download or remove the
 * download, star, mark played. LibraryCallback.onCustomCommand sends AutoCommands.ROW_ACTIONS here
 * with the row's episode id (wired in A7).
 */
class BrowseActions @Inject constructor(
    private val db: AppDatabase,
    private val queue: QueueRepository,
    private val downloads: DownloadManager,
) {
    suspend fun run(action: String, episodeId: Long): SessionResult =
        SessionResult(SessionError.ERROR_NOT_SUPPORTED)
}

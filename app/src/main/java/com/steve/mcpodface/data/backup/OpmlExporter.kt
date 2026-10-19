package com.steve.mcpodface.data.backup

import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.db.PodcastDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject

/** Writes every subscribed podcast as OPML (PLAN.md §9 `subscriptions.opml`). */
class OpmlExporter @Inject constructor(
    private val podcastDao: PodcastDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Writes to [out] (not closed here) and returns the number of podcasts written. */
    suspend fun export(out: OutputStream): Int = withContext(io) {
        val feeds = podcastDao.getSubscribed().map { OpmlFeed(it.feedUrl, it.title) }
        Opml.write(feeds, out)
        feeds.size
    }
}

package com.steve.mcpodface.playback.auto

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.QueueItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real Room flows on real threads: each test starts the invalidator, makes one DB change, waits for
 * the expected id, then waits past the 500 ms debounce to check the counts.
 */
@RunWith(AndroidJUnit4::class)
class BrowseInvalidatorTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var invalidator: BrowseInvalidator
    private val notified = CopyOnWriteArrayList<String>()
    private val ids = mutableMapOf<String, Long>()
    private var newsId = 0L
    private var serialId = 0L
    private val now = 20_000L * 86_400_000L

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        invalidator = BrowseInvalidator(db)
        newsId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://n/feed", title = "News", isSubscribed = true))
        serialId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://s/feed", title = "A Serial", isSubscribed = true, feedType = FeedType.SERIAL))
        db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "Unsubscribed", isSubscribed = false))
        for ((guid, podcast) in listOf("n1" to newsId, "n2" to newsId, "s1" to serialId)) {
            ids[guid] = db.episodeDao().insertAll(
                listOf(EpisodeEntity(podcastId = podcast, guid = guid, title = "Ep $guid", pubDate = now - 86_400_000L, enclosureUrl = "https://cdn/$guid.mp3", firstSeenAt = 0)),
            ).single()
        }
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Lets the collectors subscribe, so the first emission is not raced by the test's own write. */
    private suspend fun settle() = delay(300)

    private suspend fun awaitNotify(id: String) {
        withTimeout(5_000) {
            while (id !in notified) delay(20)
        }
    }

    private suspend fun quietFor(ms: Long = 1_000) = delay(ms)

    private fun count(id: String) = notified.count { it == id }

    @Test fun initialEmissionsAreNotNotified() = runBlocking<Unit> {
        invalidator.start(scope) { notified += it }
        quietFor(1_500)
        assertThat(notified).isEmpty()
    }

    @Test fun queueBurstNotifiesUpNextOnce() = runBlocking<Unit> {
        invalidator.start(scope) { notified += it }
        settle()
        db.queueDao().upsertAll(listOf(QueueItemEntity(ids.getValue("n1"), 0)))
        db.queueDao().upsertAll(listOf(QueueItemEntity(ids.getValue("n2"), 1)))
        db.queueDao().remove(ids.getValue("n1"))
        awaitNotify("upnext")
        quietFor()
        assertThat(count("upnext")).isEqualTo(1)
    }

    @Test fun downloadCompletionNotifiesDownloads() = runBlocking<Unit> {
        invalidator.start(scope) { notified += it }
        settle()
        db.downloadDao().upsert(DownloadEntity(ids.getValue("s1"), DownloadState.COMPLETED, DownloadSource.MANUAL, filePath = "/x", downloadedAt = 1))
        awaitNotify("downloads")
        quietFor()
        assertThat(count("downloads")).isEqualTo(1)
    }

    @Test fun markingPlayedNotifiesLatestAndOnlyThatPodcast() = runBlocking<Unit> {
        invalidator.start(scope) { notified += it }
        settle()
        db.episodeDao().setPlayState(ids.getValue("n1"), PlayState.PLAYED, playedAt = now, positionMs = null)
        awaitNotify("latest")
        quietFor()
        assertThat(count("latest")).isEqualTo(1)
        assertThat(count("inprogress")).isEqualTo(1)
        assertThat(count("history")).isEqualTo(1)
        assertThat(count("podcast:$newsId")).isEqualTo(1)
        assertThat(count("podcast:$serialId")).isEqualTo(0)
    }

    @Test fun subscribingNotifiesPodcasts() = runBlocking<Unit> {
        invalidator.start(scope) { notified += it }
        settle()
        val unsubscribed = db.podcastDao().getByFeedUrl("https://x/feed")!!
        db.podcastDao().update(unsubscribed.copy(isSubscribed = true))
        awaitNotify("podcasts")
        quietFor()
        assertThat(count("podcasts")).isEqualTo(1)
    }

    @Test fun starringNotifiesStarred() = runBlocking<Unit> {
        invalidator.start(scope) { notified += it }
        settle()
        db.episodeDao().setStarred(ids.getValue("s1"), true)
        awaitNotify("starred")
        quietFor()
        assertThat(count("starred")).isEqualTo(1)
    }

    @Test fun startTwiceDoesNotDoubleNotify() = runBlocking<Unit> {
        invalidator.start(scope) { notified += it }
        invalidator.start(scope) { notified += it }
        settle()
        db.queueDao().upsertAll(listOf(QueueItemEntity(ids.getValue("n1"), 0)))
        awaitNotify("upnext")
        quietFor()
        assertThat(count("upnext")).isEqualTo(1)
    }
}

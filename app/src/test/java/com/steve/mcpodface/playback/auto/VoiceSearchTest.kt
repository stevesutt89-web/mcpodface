package com.steve.mcpodface.playback.auto

import android.os.Bundle
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceSearchTest {
    private lateinit var db: AppDatabase
    private lateinit var voice: VoiceSearch
    private val day = 86_400_000L
    private val now = 20_000 * day

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val clock = Clock { now }
        voice = VoiceSearch(db, QueueRepository(db), SearchRepository(db, clock, Dispatchers.Unconfined))
    }

    @After fun tearDown() = db.close()

    private fun resolve(query: String, artist: String? = null, title: String? = null): List<Long> = runBlocking {
        val extras = Bundle().apply {
            artist?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
            title?.let { putString(MediaStore.EXTRA_MEDIA_TITLE, it) }
        }
        voice.resolve(query, extras)
    }

    private fun podcast(
        title: String,
        feedType: FeedType = FeedType.EPISODIC,
        subscribed: Boolean = true,
    ): Long = runBlocking {
        db.podcastDao().insert(PodcastEntity(feedUrl = "https://feed/$title", title = title, isSubscribed = subscribed, feedType = feedType))
    }

    private fun episode(
        podcastId: Long,
        guid: String,
        daysAgo: Int,
        text: String = "",
        state: PlayState = PlayState.UNPLAYED,
        positionMs: Long = 0,
        lastPlayedAt: Long? = null,
    ): Long = runBlocking {
        db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId, guid = guid, title = "Ep $guid", descriptionText = text,
                    pubDate = now - daysAgo * day, enclosureUrl = "https://cdn/$guid.mp3", firstSeenAt = 0,
                    playState = state, positionMs = positionMs, lastPlayedAt = lastPlayedAt,
                ),
            ),
        ).single()
    }

    @Test fun blankQueryResumesTheLastPlayedEpisode() = runBlocking<Unit> {
        val show = podcast("Hard Fork")
        episode(show, "old", daysAgo = 5, state = PlayState.PLAYED, lastPlayedAt = now - 3 * day)
        val latest = episode(show, "recent", daysAgo = 4, positionMs = 9_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - day)
        episode(show, "fresh", daysAgo = 1)

        assertThat(voice.resolve("   ", Bundle())).containsExactly(latest)
    }

    @Test fun blankQueryWithNothingPlayedUsesFirstUpNext() = runBlocking<Unit> {
        val show = podcast("Hard Fork")
        episode(show, "fresh", daysAgo = 1)
        val queued = episode(show, "queued", daysAgo = 2)
        val second = episode(show, "second", daysAgo = 3)
        db.queueDao().replaceAll(listOf(queued, second))

        assertThat(voice.resolve("", Bundle())).containsExactly(queued)
    }

    @Test fun blankQueryWithNothingReturnsEmpty() = runBlocking<Unit> {
        podcast("Hard Fork")
        assertThat(voice.resolve("", Bundle())).isEmpty()
    }

    @Test fun exactPodcastTitlePlaysItsNewestUnplayed() {
        val show = podcast("Hard Fork")
        episode(show, "old", daysAgo = 10)
        val newest = episode(show, "new", daysAgo = 1)
        assertThat(resolve("Hard Fork")).containsExactly(newest)
    }

    @Test fun thePrefixAndPunctuationAreIgnored() {
        val show = podcast("The Daily")
        episode(show, "a", daysAgo = 2)
        val newest = episode(show, "b", daysAgo = 1)
        assertThat(resolve("play the daily!")).containsExactly(newest)
        assertThat(resolve("Play, 'The Daily'?")).containsExactly(newest)
    }

    @Test fun artistExtraIsUsedWhenTheQueryIsGeneric() {
        val show = podcast("Tech Talk")
        val newest = episode(show, "t1", daysAgo = 1)
        episode(podcast("Other Show"), "o1", daysAgo = 0)
        assertThat(resolve("play some music", artist = "Tech Talk")).containsExactly(newest)
    }

    @Test fun inProgressEpisodeBeatsTheNewestUnplayed() {
        val show = podcast("Hard Fork")
        val halfway = episode(show, "half", daysAgo = 10, positionMs = 5_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - day)
        episode(show, "newest", daysAgo = 1)
        assertThat(resolve("Hard Fork")).containsExactly(halfway)
    }

    @Test fun serialPodcastPlaysTheOldestUnplayed() = runBlocking<Unit> {
        val serial = podcast("A Serial", feedType = FeedType.SERIAL)
        episode(serial, "s1", daysAgo = 90, state = PlayState.PLAYED)
        val oldestUnplayed = episode(serial, "s2", daysAgo = 80)
        episode(serial, "s3", daysAgo = 10)

        assertThat(resolve("play a serial")).containsExactly(oldestUnplayed)
    }

    @Test fun explicitNewestFirstOverridesSerialDefault() = runBlocking<Unit> {
        val serial = podcast("A Serial", feedType = FeedType.SERIAL)
        episode(serial, "s2", daysAgo = 80)
        val newest = episode(serial, "s3", daysAgo = 10)
        db.podcastSettingsDao().upsert(PodcastSettingsEntity(podcastId = serial, sortOrder = SortOrder.NEWEST_FIRST))

        assertThat(resolve("play a serial")).containsExactly(newest)
    }

    @Test fun unsubscribedPodcastsAreIgnored() {
        val hidden = podcast("Quiet Notes", subscribed = false)
        episode(hidden, "q1", daysAgo = 1, text = "nothing to see")
        assertThat(resolve("quiet notes")).isEmpty()
    }

    @Test fun noPodcastMatchFallsBackToFullTextSearch() = runBlocking<Unit> {
        val show = podcast("Hard Fork")
        val rockets = episode(show, "r1", daysAgo = 1, text = "about rockets")
        val moreRockets = episode(podcast("Space Weekly"), "r2", daysAgo = 2, text = "rockets again")
        episode(show, "other", daysAgo = 3, text = "cooking")

        assertThat(voice.resolve("rockets", Bundle())).containsExactly(rockets, moreRockets).inOrder()
    }

    @Test fun nothingMatchingReturnsEmpty() {
        podcast("Hard Fork")
        assertThat(resolve("zzqqxx")).isEmpty()
    }
}

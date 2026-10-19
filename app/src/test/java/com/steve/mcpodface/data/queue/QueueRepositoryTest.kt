package com.steve.mcpodface.data.queue

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var queue: QueueRepository
    private val ids = mutableMapOf<String, Long>()

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queue = QueueRepository(db)
        val podcast = db.podcastDao().insert(PodcastEntity(feedUrl = "https://a/feed", title = "Show", isSubscribed = true))
        for (guid in listOf("a", "b", "c", "d")) {
            ids[guid] = db.episodeDao().insertAll(
                listOf(
                    EpisodeEntity(
                        podcastId = podcast, guid = guid, title = guid, pubDate = 0L,
                        enclosureUrl = "https://cdn/$guid.mp3", firstSeenAt = 0,
                    ),
                ),
            ).single()
        }
    }

    @After fun tearDown() = db.close()

    @Test fun replaceAllSetsTheQueueInOrderAndDropsTheOldOne() = runBlocking<Unit> {
        queue.addLast(ids.getValue("a"))
        queue.addLast(ids.getValue("b"))

        queue.replaceAll(listOf(ids.getValue("d"), ids.getValue("b"), ids.getValue("c")))

        assertThat(queue.observeIds().first())
            .containsExactly(ids["d"], ids["b"], ids["c"]).inOrder()
    }

    @Test fun replaceAllWithDuplicatesKeepsTheFirstOccurrence() = runBlocking<Unit> {
        queue.replaceAll(listOf(ids.getValue("a"), ids.getValue("b"), ids.getValue("a")))

        assertThat(queue.observeIds().first()).containsExactly(ids["a"], ids["b"]).inOrder()
    }

    @Test fun replaceAllWithNothingEmptiesTheQueue() = runBlocking<Unit> {
        queue.addLast(ids.getValue("a"))

        queue.replaceAll(emptyList())

        assertThat(queue.observeIds().first()).isEmpty()
    }
}

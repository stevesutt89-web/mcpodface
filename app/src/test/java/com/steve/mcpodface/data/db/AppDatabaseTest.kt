package com.steve.mcpodface.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun seedEpisode(title: String, notes: String): Long {
        val podcastId = db.podcastDao().getByFeedUrl("https://example.com/feed")?.id
            ?: db.podcastDao().insert(PodcastEntity(feedUrl = "https://example.com/feed", title = "Show"))
        return db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId, guid = title, title = title, descriptionText = notes,
                    pubDate = 0, enclosureUrl = "https://example.com/$title.mp3", firstSeenAt = 0,
                ),
            ),
        ).single()
    }

    @Test fun duplicateGuidIsIgnored() = runTest {
        val first = seedEpisode("Ep 1", "")
        val second = seedEpisode("Ep 1", "")
        assertThat(first).isGreaterThan(0)
        assertThat(second).isEqualTo(-1)
    }

    @Test fun ftsIndexFollowsEpisodesAndIgnoresDiacritics() = runTest {
        val id = seedEpisode("Climate special", "We talk about the café economy")
        seedEpisode("Space news", "NASA and ESA updates")

        val cursor = db.openHelper.readableDatabase.query(
            "SELECT rowid FROM episode_fts WHERE episode_fts MATCH ?", arrayOf("cafe*"),
        )
        val ids = buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        cursor.close()
        assertThat(ids).containsExactly(id)
    }

    @Test fun deletingPodcastCascadesToEpisodes() = runTest {
        val episodeId = seedEpisode("Ep", "")
        db.openHelper.writableDatabase.execSQL("DELETE FROM podcasts")
        assertThat(db.episodeDao().getById(episodeId)).isNull()
    }
}

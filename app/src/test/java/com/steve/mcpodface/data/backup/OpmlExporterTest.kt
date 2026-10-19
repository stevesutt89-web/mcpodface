package com.steve.mcpodface.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.PodcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class OpmlExporterTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    @Test fun writesEverySubscribedPodcastAndNoOthers() = runTest {
        val dao = db.podcastDao()
        dao.insert(PodcastEntity(feedUrl = "https://a.example/feed", title = "Alpha & Co", isSubscribed = true))
        dao.insert(PodcastEntity(feedUrl = "https://b.example/feed", title = "Beta", isSubscribed = true))
        dao.insert(PodcastEntity(feedUrl = "https://c.example/feed", title = "Previewed only", isSubscribed = false))

        val out = ByteArrayOutputStream()
        val count = OpmlExporter(dao, Dispatchers.IO).export(out)

        assertThat(count).isEqualTo(2)
        val parsed = Opml.parse(ByteArrayInputStream(out.toByteArray()))
        assertThat(parsed.map { it.feedUrl to it.title }).containsExactly(
            "https://a.example/feed" to "Alpha & Co",
            "https://b.example/feed" to "Beta",
        )
    }

    @Test fun noSubscriptionsWritesValidEmptyDocument() = runTest {
        val out = ByteArrayOutputStream()
        val count = OpmlExporter(db.podcastDao(), Dispatchers.IO).export(out)

        assertThat(count).isEqualTo(0)
        assertThat(Opml.parse(ByteArrayInputStream(out.toByteArray()))).isEmpty()
    }
}

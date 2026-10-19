package com.steve.mcpodface.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseTreeTest {
    private lateinit var db: AppDatabase
    private lateinit var tree: BrowseTree
    private val day = 86_400_000L
    private val now = 20_000 * day
    private val ids = mutableMapOf<String, Long>()
    private var serial = 0L

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val clock = Clock { now }
        val search = SearchRepository(db, clock, Dispatchers.Unconfined)
        tree = BrowseTree(db, search, clock)
        val news = db.podcastDao().insert(PodcastEntity(feedUrl = "https://n/feed", title = "News", isSubscribed = true, artworkUrl = "https://n/art.jpg"))
        serial = db.podcastDao().insert(PodcastEntity(feedUrl = "https://s/feed", title = "A Serial", isSubscribed = true, feedType = FeedType.SERIAL))
        db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "Unsubscribed", isSubscribed = false))
        for ((guid, podcast, daysAgo) in listOf(Triple("n1", news, 1), Triple("n2", news, 2), Triple("s1", serial, 90), Triple("s2", serial, 80))) {
            ids[guid] = db.episodeDao().insertAll(
                listOf(EpisodeEntity(podcastId = podcast, guid = guid, title = "Ep $guid", pubDate = now - daysAgo * day, enclosureUrl = "https://cdn/$guid.mp3", firstSeenAt = 0, descriptionText = "about rockets")),
            ).single()
        }
    }

    @After fun tearDown() = db.close()

    private fun titles(parent: String) = runBlocking { tree.children(parent).map { it.mediaMetadata.title.toString() } }

    @Test fun rootListsTheFoldersInOrder() {
        val root = runBlocking { tree.children(BrowseTree.ROOT) }
        assertThat(root.map { it.mediaMetadata.title.toString() }).containsExactly("Up Next", "Latest", "Downloads", "Podcasts").inOrder()
        assertThat(root.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false }).isTrue()
    }

    @Test fun episodesArePlayableAndKeyedByEpisodeId() = runBlocking<Unit> {
        val latest = tree.children(BrowseTree.LATEST)
        assertThat(latest.map { it.mediaMetadata.title.toString() }).containsExactly("Ep n1", "Ep n2").inOrder() // last 30 days only
        val first = latest.first()
        assertThat(first.mediaId).isEqualTo(ids.getValue("n1").toString())
        assertThat(first.mediaMetadata.isPlayable).isTrue()
        assertThat(first.mediaMetadata.artist.toString()).isEqualTo("News")
        assertThat(first.mediaMetadata.artworkUri.toString()).isEqualTo("https://n/art.jpg")
    }

    @Test fun upNextAndDownloadsFollowTheirSources() = runBlocking<Unit> {
        db.queueDao().replaceAll(listOf(ids.getValue("s2"), ids.getValue("n1")))
        db.downloadDao().upsert(DownloadEntity(ids.getValue("s1"), DownloadState.COMPLETED, DownloadSource.MANUAL, filePath = "/x", downloadedAt = 1))
        db.downloadDao().upsert(DownloadEntity(ids.getValue("n1"), DownloadState.DOWNLOADING, DownloadSource.MANUAL))

        assertThat(titles(BrowseTree.UP_NEXT)).containsExactly("Ep s2", "Ep n1").inOrder()
        assertThat(titles(BrowseTree.DOWNLOADS)).containsExactly("Ep s1")
    }

    @Test fun podcastsAreSubscribedOnlyAndSerialShowsOpenOldestFirst() {
        assertThat(titles(BrowseTree.PODCASTS)).containsExactly("A Serial", "News").inOrder()
        assertThat(titles(BrowseTree.PODCAST_PREFIX + serial)).containsExactly("Ep s1", "Ep s2").inOrder()
        assertThat(titles("podcast:nope")).isEmpty()
        assertThat(titles("something-else")).isEmpty()
    }

    @Test fun voiceSearchFindsEpisodes() = runBlocking<Unit> {
        assertThat(tree.search("rockets")).hasSize(4)
        assertThat(tree.search("  ")).isEmpty()
    }
}

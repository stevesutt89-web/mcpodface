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
import com.steve.mcpodface.data.db.ListeningSessionEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.playlist.SmartRules
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.playback.auto.artworkUri
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
    private var news = 0L
    private var serial = 0L

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val clock = Clock { now }
        val search = SearchRepository(db, clock, Dispatchers.Unconfined)
        val playlists = PlaylistRepository(db, search, clock)
        tree = BrowseTree(db, search, playlists, clock)
        news = db.podcastDao().insert(PodcastEntity(feedUrl = "https://n/feed", title = "News", isSubscribed = true, artworkUrl = "https://n/art.jpg"))
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

    private fun setPosition(guid: String, positionMs: Long, state: PlayState) = runBlocking {
        db.episodeDao().updatePosition(ids.getValue(guid), positionMs, state, now)
    }

    private fun listen(guid: String, startedAgo: Long) = runBlocking {
        db.listeningSessionDao().insert(ListeningSessionEntity(episodeId = ids.getValue(guid), startedAt = now - startedAgo, listenedMs = 1_000, speed = 1f))
    }

    @Test fun rootHasExactlyFourTabsInOrder() {
        val root = runBlocking { tree.children(BrowseTree.ROOT) }
        assertThat(root.map { it.mediaMetadata.title.toString() }).containsExactly("Up Next", "Latest", "Podcasts", "Library").inOrder()
        assertThat(root.map { it.mediaId }).containsExactly("upnext", "latest", "podcasts", "library").inOrder()
        assertThat(root.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false }).isTrue()
    }

    @Test fun libraryFoldersAreInOrder() {
        val library = runBlocking { tree.children(BrowseTree.LIBRARY) }
        assertThat(library.map { it.mediaId }).containsExactly("downloads", "inprogress", "starred", "playlists", "history").inOrder()
        assertThat(library.map { it.mediaMetadata.title.toString() })
            .containsExactly("Downloads", "In progress", "Starred", "Playlists", "History").inOrder()
        assertThat(library.all { it.mediaMetadata.isBrowsable == true }).isTrue()
    }

    @Test fun episodesArePlayableAndKeyedByEpisodeId() = runBlocking<Unit> {
        val latest = tree.children(BrowseTree.LATEST)
        assertThat(latest.map { it.mediaMetadata.title.toString() }).containsExactly("Ep n1", "Ep n2").inOrder() // last 30 days only
        val first = latest.first()
        assertThat(first.mediaId).isEqualTo(ids.getValue("n1").toString())
        assertThat(first.mediaMetadata.isPlayable).isTrue()
        assertThat(first.mediaMetadata.artist.toString()).isEqualTo("News")
        assertThat(first.mediaMetadata.artworkUri).isEqualTo(artworkUri("https://n/art.jpg")) // via ArtworkProvider
    }

    @Test fun latestHidesPlayedEpisodes() = runBlocking<Unit> {
        setPosition("n2", 0, PlayState.PLAYED)
        assertThat(titles(BrowseTree.LATEST)).containsExactly("Ep n1")
    }

    @Test fun upNextAndDownloadsFollowTheirSources() = runBlocking<Unit> {
        db.queueDao().replaceAll(listOf(ids.getValue("s2"), ids.getValue("n1")))
        db.downloadDao().upsert(DownloadEntity(ids.getValue("s1"), DownloadState.COMPLETED, DownloadSource.MANUAL, filePath = "/x", downloadedAt = 1))
        db.downloadDao().upsert(DownloadEntity(ids.getValue("n1"), DownloadState.DOWNLOADING, DownloadSource.MANUAL))

        assertThat(titles(BrowseTree.UP_NEXT)).containsExactly("Ep s2", "Ep n1").inOrder()
        assertThat(titles(BrowseTree.DOWNLOADS)).containsExactly("Ep s1")
    }

    @Test fun podcastsAreSubscribedOnlyAndSerialShowsOpenOldestFirst() {
        val podcasts = runBlocking { tree.children(BrowseTree.PODCASTS) }
        assertThat(podcasts.map { it.mediaMetadata.title.toString() }).containsExactly("A Serial", "News").inOrder()
        assertThat(podcasts.map { it.mediaId }).containsExactly("podcast:$serial", "podcast:$news").inOrder()
        assertThat(titles(BrowseTree.PODCAST_PREFIX + serial)).containsExactly("Ep s1", "Ep s2").inOrder()
        assertThat(titles("podcast:nope")).isEmpty()
        assertThat(titles("something-else")).isEmpty()
    }

    @Test fun noSubscriptionsGivesAnEmptyPodcastsTab() = runBlocking<Unit> {
        db.podcastDao().getSubscribed().forEach { db.podcastDao().update(it.copy(isSubscribed = false)) }
        assertThat(titles(BrowseTree.PODCASTS)).isEmpty()
    }

    @Test fun podcastInProgressEpisodesComeFirst() = runBlocking<Unit> {
        // Serial is oldest-first (s1, s2); s2 is part-way through, so it moves to the top.
        setPosition("s2", 5_000, PlayState.IN_PROGRESS)
        assertThat(titles(BrowseTree.PODCAST_PREFIX + serial)).containsExactly("Ep s2", "Ep s1").inOrder()

        // News is newest-first (n1, n2); a finished (PLAYED) episode with a position is not in progress.
        setPosition("n2", 5_000, PlayState.IN_PROGRESS)
        setPosition("n1", 9_000, PlayState.PLAYED)
        assertThat(titles(BrowseTree.PODCAST_PREFIX + news)).containsExactly("Ep n2", "Ep n1").inOrder()
    }

    @Test fun starredEpisodesAreListed() = runBlocking<Unit> {
        db.episodeDao().setStarred(ids.getValue("n2"), true)
        assertThat(titles(BrowseTree.STARRED)).containsExactly("Ep n2")
    }

    @Test fun inProgressListsPartlyPlayedEpisodesMostRecentFirst() = runBlocking<Unit> {
        setPosition("s1", 1_000, PlayState.IN_PROGRESS)
        setPosition("n2", 4_000, PlayState.IN_PROGRESS)
        setPosition("n1", 500, PlayState.PLAYED) // finished: excluded even with a position
        listen("s1", startedAgo = 5_000)
        listen("n2", startedAgo = 1_000) // most recent
        listen("n1", startedAgo = 2_000)
        assertThat(titles(BrowseTree.IN_PROGRESS)).containsExactly("Ep n2", "Ep s1").inOrder()
    }

    @Test fun playlistsListManualAndSmartPlaylistsAndTheirEpisodes() = runBlocking<Unit> {
        val playlists = PlaylistRepository(db, SearchRepository(db, Clock { now }, Dispatchers.Unconfined), Clock { now })
        val manual = playlists.createManual("Road trip")
        playlists.add(manual, ids.getValue("n1"))
        playlists.add(manual, ids.getValue("s2"))
        val smart = playlists.createSmart("Starred", SmartRules(starredOnly = true))
        db.episodeDao().setStarred(ids.getValue("s1"), true)

        val folders = tree.children(BrowseTree.PLAYLISTS)
        assertThat(folders.map { it.mediaId }).containsExactly("playlist:$manual", "playlist:$smart").inOrder()
        assertThat(folders.map { it.mediaMetadata.title.toString() }).containsExactly("Road trip", "Starred").inOrder()
        assertThat(titles("playlist:$manual")).containsExactly("Ep n1", "Ep s2").inOrder()
        assertThat(titles("playlist:$smart")).containsExactly("Ep s1")
        assertThat(titles("playlist:999")).isEmpty()
    }

    @Test fun historyIsNewestListenFirst() = runBlocking<Unit> {
        listen("n2", startedAgo = 1_000)
        listen("s1", startedAgo = 500) // listened to most recently
        assertThat(titles(BrowseTree.HISTORY)).containsExactly("Ep s1", "Ep n2").inOrder()
    }

    @Test fun emptyStatesReturnNothing() = runBlocking<Unit> {
        assertThat(titles(BrowseTree.IN_PROGRESS)).isEmpty()
        assertThat(titles(BrowseTree.STARRED)).isEmpty()
        assertThat(titles(BrowseTree.PLAYLISTS)).isEmpty()
        assertThat(titles(BrowseTree.HISTORY)).isEmpty()
        assertThat(titles("nonsense")).isEmpty()
    }

    @Test fun voiceSearchFindsEpisodes() = runBlocking<Unit> {
        assertThat(tree.search("rockets")).hasSize(4)
        assertThat(tree.search("  ")).isEmpty()
    }

    @Test fun recentIsTheLastPlayedEpisode() = runBlocking<Unit> {
        assertThat(tree.children(BrowseTree.RECENT)).isEmpty()
        setPosition("s2", 60_000, PlayState.IN_PROGRESS)
        val recent = tree.children(BrowseTree.RECENT)
        assertThat(recent.map { it.mediaId }).containsExactly(ids.getValue("s2").toString())
        assertThat(recent.single().mediaMetadata.isPlayable).isTrue()
        assertThat(recent.single().mediaMetadata.artist.toString()).isEqualTo("A Serial")
    }
}

package com.steve.mcpodface.data.playlist

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
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.data.search.SearchSort
import com.steve.mcpodface.data.search.StatusFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: PlaylistRepository
    private val day = 86_400_000L
    private var now = 20_000 * day
    private var show = 0L
    private var other = 0L
    private val ids = mutableMapOf<String, Long>()

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val clock = Clock { now++ }
        repo = PlaylistRepository(db, SearchRepository(db, clock, Dispatchers.Unconfined), clock)
        show = db.podcastDao().insert(PodcastEntity(feedUrl = "https://a/feed", title = "Show", isSubscribed = true))
        other = db.podcastDao().insert(PodcastEntity(feedUrl = "https://b/feed", title = "Other", isSubscribed = true))
        episode("new", show, daysAgo = 1)
        episode("week", show, daysAgo = 6)
        episode("old", show, daysAgo = 60)
        episode("played", show, daysAgo = 2, state = PlayState.PLAYED)
        episode("elsewhere", other, daysAgo = 3)
    }

    @After fun tearDown() = db.close()

    private suspend fun episode(guid: String, podcast: Long, daysAgo: Int, state: PlayState = PlayState.UNPLAYED) {
        ids[guid] = db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcast, guid = guid, title = "Episode $guid", pubDate = 20_000 * day - daysAgo * day,
                    enclosureUrl = "https://cdn/$guid.mp3", firstSeenAt = 0, playState = state,
                ),
            ),
        ).single()
    }

    private fun guids(playlistId: Long) = runBlocking {
        withTimeout(5_000) { repo.observeEpisodes(playlistId).first().map { it.episode.guid } }
    }

    @Test fun listenLaterExistsOnceAndComesFirst() = runBlocking<Unit> {
        repo.createManual("Mine")
        val a = repo.listenLaterId()
        val b = repo.listenLaterId()
        assertThat(a).isEqualTo(b)
        val all = repo.observePlaylists().first()
        assertThat(all.first().playlist.kind).isEqualTo(PlaylistKind.LISTEN_LATER)
        assertThat(all.map { it.playlist.name }).containsExactly("Listen Later", "Mine").inOrder()
    }

    @Test fun swipeTogglesListenLaterWithNewestOnTop() = runBlocking<Unit> {
        assertThat(repo.toggleListenLater(ids.getValue("old"))).isTrue()
        assertThat(repo.toggleListenLater(ids.getValue("new"))).isTrue()
        val ll = repo.listenLaterId()
        assertThat(guids(ll)).containsExactly("new", "old").inOrder()
        assertThat(repo.observeListenLaterIds().first()).containsExactly(ids["new"], ids["old"])

        assertThat(repo.toggleListenLater(ids.getValue("old"))).isFalse()
        assertThat(guids(ll)).containsExactly("new")
    }

    @Test fun finishedEpisodesLeaveListenLaterButNotManualPlaylists() = runBlocking<Unit> {
        val mine = repo.createManual("Mine")
        repo.toggleListenLater(ids.getValue("new"))
        repo.add(mine, ids.getValue("new"))
        repo.onEpisodeFinished(ids.getValue("new"))
        assertThat(guids(repo.listenLaterId())).isEmpty()
        assertThat(guids(mine)).containsExactly("new")
    }

    @Test fun manualPlaylistAddsInOrderWithoutDuplicatesAndReorders() = runBlocking<Unit> {
        val mine = repo.createManual("  Road trip  ")
        repo.add(mine, ids.getValue("old"))
        repo.add(mine, ids.getValue("new"))
        repo.add(mine, ids.getValue("week"))
        repo.add(mine, ids.getValue("new"))
        assertThat(guids(mine)).containsExactly("old", "new", "week").inOrder()

        repo.move(mine, 2, 0)
        assertThat(guids(mine)).containsExactly("week", "old", "new").inOrder()
        repo.remove(mine, ids.getValue("old"))
        assertThat(guids(mine)).containsExactly("week", "new").inOrder()
        assertThat(repo.episodeIds(mine)).containsExactly(ids["week"], ids["new"]).inOrder()
        assertThat(db.playlistDao().getById(mine)!!.name).isEqualTo("Road trip")
    }

    @Test fun smartPlaylistFollowsItsRules() = runBlocking<Unit> {
        val fresh = repo.createSmart("Fresh", SmartRules(podcastIds = listOf(show), publishedWithinDays = 7))
        // Not played (default), within 7 days, this podcast only, newest first.
        assertThat(guids(fresh)).containsExactly("new", "week").inOrder()

        repo.updateRules(fresh, SmartRules(status = StatusFilter.ANY, sort = SearchSort.OLDEST, limit = 2))
        assertThat(guids(fresh)).containsExactly("old", "week").inOrder()
    }

    @Test fun smartPlaylistUpdatesLive() = runBlocking<Unit> {
        val dl = repo.createSmart("Downloaded", SmartRules(downloadedOnly = true))
        assertThat(guids(dl)).isEmpty()
        db.downloadDao().upsert(DownloadEntity(ids.getValue("week"), DownloadState.COMPLETED, DownloadSource.MANUAL, filePath = "/x"))
        assertThat(guids(dl)).containsExactly("week")
    }

    @Test fun smartPlaylistsCannotHoldItemsAndListenLaterCannotBeDeletedOrRenamed() = runBlocking<Unit> {
        val smart = repo.createSmart("S", SmartRules())
        repo.add(smart, ids.getValue("new"))
        assertThat(db.playlistDao().getItems(smart)).isEmpty()

        val ll = repo.listenLaterId()
        repo.delete(ll)
        repo.rename(ll, "Nope")
        assertThat(db.playlistDao().getById(ll)!!.name).isEqualTo("Listen Later")
        repo.delete(smart)
        assertThat(db.playlistDao().getById(smart)).isNull()
    }

    @Test fun deletingAnEpisodeRemovesItFromPlaylists() = runBlocking<Unit> {
        repo.toggleListenLater(ids.getValue("old"))
        db.podcastDao().deleteAll()
        assertThat(guids(repo.listenLaterId())).isEmpty()
    }

    @Test fun rulesSurviveJsonAndBadJsonFallsBack() {
        val rules = SmartRules(podcastIds = listOf(3, 4), status = StatusFilter.UNPLAYED, publishedWithinDays = 14, text = "space", limit = 25)
        assertThat(SmartRules.fromJson(rules.toJson())).isEqualTo(rules)
        assertThat(SmartRules.fromJson("{not json")).isEqualTo(SmartRules())
        assertThat(SmartRules.fromJson(null)).isEqualTo(SmartRules())
    }
}

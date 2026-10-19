package com.steve.mcpodface.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.BackfillStateEntity
import com.steve.mcpodface.data.db.BackfillStatus
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.ListeningSessionEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.QueueAdd
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.feed.EpisodeMerger
import com.steve.mcpodface.data.feed.FeedFetcher
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.RssParser
import com.steve.mcpodface.data.network.NetworkModule
import com.steve.mcpodface.data.prefs.DataStoreSettingsRepository
import com.steve.mcpodface.data.prefs.ThemeMode
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.playlist.SmartRules
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.data.db.PlaylistEntity
import com.steve.mcpodface.data.db.PlaylistItemEntity
import com.steve.mcpodface.data.db.PlaylistKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val clock = Clock { now }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One phone: a database and its settings. */
    private inner class Phone(name: String) {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val settings = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("$name.preferences_pb") })
        private val feeds = FeedRepository(db, FeedFetcher(NetworkModule.okHttpClient(), RssParser(), Dispatchers.Unconfined), EpisodeMerger(), clock)

        fun exporter() = BackupExporter(db, settings, OpmlExporter(db.podcastDao(), Dispatchers.IO), clock, Dispatchers.IO)
        val playlists = PlaylistRepository(db, SearchRepository(db, clock, Dispatchers.Unconfined), clock)
        fun importer() = BackupImporter(db, settings, feeds, { error("no refresh in tests") }, playlists, scope, Dispatchers.IO)

        suspend fun backup(includeArchive: Boolean = true): ByteArray =
            ByteArrayOutputStream().also { exporter().export(it, includeArchive) }.toByteArray()

        suspend fun restore(bytes: ByteArray, mode: ImportMode = ImportMode.MERGE) =
            importer().import(ByteArrayInputStream(bytes), mode, refreshAfter = false)
    }

    private val phones = mutableListOf<Phone>()
    private fun phone(name: String) = Phone(name).also { phones += it }

    private lateinit var old: Phone

    @Before fun setUp() = runBlocking<Unit> {
        old = phone("old")
        val db = old.db
        val a = db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://a/feed", title = "A", isSubscribed = true, subscribedAt = 5, podcastIndexId = 9, authUser = "u", authPassword = "p"),
        )
        val b = db.podcastDao().insert(PodcastEntity(feedUrl = "https://b/feed", title = "B", isSubscribed = true, subscribedAt = 6))
        db.podcastSettingsDao().upsert(
            PodcastSettingsEntity(podcastId = a, keepLatestCount = 2, sortOrder = SortOrder.OLDEST_FIRST, playbackSpeed = 1.5f, autoAddToQueue = QueueAdd.TOP),
        )
        db.episodeDao().insertAll(
            (1..30).map { i ->
                EpisodeEntity(
                    podcastId = if (i <= 20) a else b, guid = "g$i", title = "Episode $i", descriptionHtml = "<p>notes $i</p>",
                    descriptionText = "notes $i", pubDate = now - i * 1000L, durationSec = 600, enclosureUrl = "https://cdn/$i.mp3", firstSeenAt = 0,
                )
            },
        )
        fun id(g: String) = runBlocking { db.episodeDao().getForPodcast(if (g.drop(1).toInt() <= 20) a else b).first { it.guid == g }.id }
        db.episodeDao().setPlayState(id("g1"), PlayState.PLAYED, playedAt = now - 100, positionMs = 0)
        db.episodeDao().updatePosition(id("g2"), 123_000, PlayState.IN_PROGRESS, now - 50)
        db.episodeDao().setStarred(id("g25"), true)
        db.queueDao().replaceAll(listOf(id("g3"), id("g21")))
        db.listeningSessionDao().insert(ListeningSessionEntity(episodeId = id("g2"), startedAt = now - 5_000, listenedMs = 4_000, speed = 1.5f))
        db.backfillStateDao().upsert(BackfillStateEntity(podcastId = a, status = BackfillStatus.COMPLETE, rssPagingDone = true, indexDone = true, episodesAdded = 12))
        old.settings.update { it.copy(theme = ThemeMode.DARK, keepLatestCount = 3, skipForwardSec = 45, apiRequestsToday = 77) }
    }

    @After fun tearDown() {
        phones.forEach { it.db.close() }
        scope.cancel()
    }

    private suspend fun Phone.episode(feed: String, guid: String): EpisodeEntity =
        db.episodeDao().getForPodcast(db.podcastDao().getByFeedUrl(feed)!!.id).first { it.guid == guid }

    @Test fun roundTripIntoAnEmptyPhoneRestoresEverything() = runBlocking<Unit> {
        val new = phone("new")
        val result = new.restore(old.backup()) as ImportResult.Ok

        assertThat(result.archiveEpisodes).isEqualTo(30)
        val a = new.db.podcastDao().getByFeedUrl("https://a/feed")!!
        assertThat(a.isSubscribed).isTrue()
        assertThat(a.podcastIndexId).isEqualTo(9)
        assertThat(a.authUser).isEqualTo("u")
        assertThat(a.authPassword).isNull() // passwords are never put in backups
        val s = new.db.podcastSettingsDao().get(a.id)!!
        assertThat(s.keepLatestCount).isEqualTo(2)
        assertThat(s.sortOrder).isEqualTo(SortOrder.OLDEST_FIRST)
        assertThat(s.autoAddToQueue).isEqualTo(QueueAdd.TOP)

        // The archive recreated every episode with its show notes.
        assertThat(new.db.episodeDao().getForPodcast(a.id)).hasSize(20)
        assertThat(new.episode("https://a/feed", "g7").descriptionText).contains("notes 7")

        val g1 = new.episode("https://a/feed", "g1")
        assertThat(g1.playState).isEqualTo(PlayState.PLAYED)
        assertThat(g1.playedAt).isEqualTo(now - 100)
        val g2 = new.episode("https://a/feed", "g2")
        assertThat(g2.playState).isEqualTo(PlayState.IN_PROGRESS)
        assertThat(g2.positionMs).isEqualTo(123_000)
        assertThat(new.episode("https://b/feed", "g25").isStarred).isTrue()

        val queue = new.db.queueDao().getAll().sortedBy { it.position }.map { q -> new.db.episodeDao().getById(q.episodeId)!!.guid }
        assertThat(queue).containsExactly("g3", "g21").inOrder()
        val sessions = new.db.listeningSessionDao().getAll()
        assertThat(sessions.single().listenedMs).isEqualTo(4_000)
        assertThat(new.db.backfillStateDao().get(a.id)!!.status).isEqualTo(BackfillStatus.COMPLETE)

        val settings = new.settings.settings.first()
        assertThat(settings.theme).isEqualTo(ThemeMode.DARK)
        assertThat(settings.keepLatestCount).isEqualTo(3)
        assertThat(settings.skipForwardSec).isEqualTo(45)
        assertThat(settings.apiRequestsToday).isEqualTo(0) // rate-limiter state stays per phone
    }

    @Test fun withoutTheArchiveStatefulEpisodesAreStillCreated() = runBlocking<Unit> {
        val new = phone("new")
        new.restore(old.backup(includeArchive = false))
        val a = new.db.podcastDao().getByFeedUrl("https://a/feed")!!
        assertThat(new.db.episodeDao().getForPodcast(a.id).map { it.guid }).containsAtLeast("g1", "g2", "g3")
        assertThat(new.episode("https://a/feed", "g2").positionMs).isEqualTo(123_000)
    }

    @Test fun importingTwiceDuplicatesNothing() = runBlocking<Unit> {
        val new = phone("new")
        val bytes = old.backup()
        new.restore(bytes)
        new.restore(bytes)
        assertThat(new.db.episodeDao().getForPodcast(new.db.podcastDao().getByFeedUrl("https://a/feed")!!.id)).hasSize(20)
        assertThat(new.db.queueDao().getAll()).hasSize(2)
        assertThat(new.db.listeningSessionDao().getAll()).hasSize(1)
    }

    @Test fun mergeKeepsTheMoreRecentLocalProgressAndAddsToTheQueue() = runBlocking<Unit> {
        val new = phone("new")
        val bytes = old.backup()
        new.restore(bytes)
        // Since the backup, the new phone listened further into g2 and queued g4.
        val g2 = new.episode("https://a/feed", "g2")
        new.db.episodeDao().updatePosition(g2.id, 500_000, PlayState.IN_PROGRESS, now)
        val g4 = new.episode("https://a/feed", "g4")
        new.db.queueDao().replaceAll(listOf(g4.id))

        new.restore(bytes, ImportMode.MERGE)

        assertThat(new.episode("https://a/feed", "g2").positionMs).isEqualTo(500_000)
        val queue = new.db.queueDao().getAll().sortedBy { it.position }.map { new.db.episodeDao().getById(it.episodeId)!!.guid }
        assertThat(queue).containsExactly("g4", "g3", "g21").inOrder()
    }

    @Test fun replaceWipesWhatWasThere() = runBlocking<Unit> {
        val new = phone("new")
        new.db.podcastDao().insert(PodcastEntity(feedUrl = "https://local/feed", title = "Local", isSubscribed = true))
        new.restore(old.backup(), ImportMode.REPLACE)
        assertThat(new.db.podcastDao().getByFeedUrl("https://local/feed")).isNull()
        assertThat(new.db.podcastDao().getSubscribed().map { it.feedUrl }).containsExactly("https://a/feed", "https://b/feed")
    }

    @Test fun playlistsRoundTrip() = runBlocking<Unit> {
        val a = old.db.podcastDao().getByFeedUrl("https://a/feed")!!.id
        val road = old.playlists.createManual("Road trip")
        old.playlists.add(road, old.episode("https://b/feed", "g22").id)
        old.playlists.add(road, old.episode("https://a/feed", "g6").id)
        old.playlists.createSmart("Fresh A", SmartRules(podcastIds = listOf(a), publishedWithinDays = 7, limit = 20))

        val new = phone("new")
        new.restore(old.backup(includeArchive = false))

        val all = new.db.playlistDao().getAll()
        assertThat(all.map { it.kind }).doesNotContain(PlaylistKind.LISTEN_LATER)
        val roadNew = all.single { it.name == "Road trip" }
        assertThat(new.db.playlistDao().getItems(roadNew.id).map { new.db.episodeDao().getById(it.episodeId)!!.guid })
            .containsExactly("g22", "g6").inOrder()
        val smart = all.single { it.kind == PlaylistKind.SMART }
        val rules = SmartRules.fromJson(smart.rulesJson)
        assertThat(rules.podcastIds).containsExactly(new.db.podcastDao().getByFeedUrl("https://a/feed")!!.id)
        assertThat(rules.publishedWithinDays).isEqualTo(7)
        assertThat(rules.limit).isEqualTo(20)
    }

    @Test fun retiredListenLaterFromAnOlderBackupAppendsToUpNext() = runBlocking<Unit> {
        // An older phone's backup still has a Listen Later playlist: its items join Up Next, skipping queued ones.
        val legacy = old.db.playlistDao().insert(PlaylistEntity(name = "Listen Later", kind = PlaylistKind.LISTEN_LATER, position = -1, createdAt = 0L))
        val items = listOf(old.episode("https://a/feed", "g5"), old.episode("https://a/feed", "g3"), old.episode("https://b/feed", "g22"))
        old.db.playlistDao().insertItems(items.mapIndexed { i, episode -> PlaylistItemEntity(legacy, episode.id, i, addedAt = 0L) })

        val new = phone("new")
        new.restore(old.backup(includeArchive = false))

        assertThat(new.db.playlistDao().getAll().map { it.kind }).doesNotContain(PlaylistKind.LISTEN_LATER)
        val queue = new.db.queueDao().getAll().sortedBy { it.position }.map { new.db.episodeDao().getById(it.episodeId)!!.guid }
        assertThat(queue).containsExactly("g3", "g21", "g5", "g22").inOrder()
    }

    @Test fun rejectsFilesThatAreNotBackups() = runBlocking<Unit> {
        val new = phone("new")
        val notABackup = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("hello.txt")); it.write("hi".toByteArray()) }
        }.toByteArray()
        assertThat(new.restore(notABackup)).isInstanceOf(ImportResult.Failed::class.java)
        assertThat(new.restore("not a zip".toByteArray())).isInstanceOf(ImportResult.Failed::class.java)
    }

    @Test fun rejectsANewerFormat() = runBlocking<Unit> {
        val new = phone("new")
        val manifest = """{"formatVersion":99,"appVersion":"9","createdAt":"x","counts":{"podcasts":0,"episodes":0,"queue":0,"history":0,"backfill":0}}"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry(BackupEntries.MANIFEST)); it.write(manifest.toByteArray()) }
        }.toByteArray()
        val r = new.restore(bytes) as ImportResult.Failed
        assertThat(r.message).contains("newer version")
    }
}

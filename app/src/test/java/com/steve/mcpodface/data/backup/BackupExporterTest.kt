package com.steve.mcpodface.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.BuildConfig
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
import com.steve.mcpodface.data.prefs.DataStoreSettingsRepository
import com.steve.mcpodface.data.prefs.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class BackupExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: DataStoreSettingsRepository
    private lateinit var exporter: BackupExporter

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("settings.preferences_pb") },
        )
        exporter = BackupExporter(
            db = db,
            settings = settings,
            opml = OpmlExporter(db.podcastDao(), Dispatchers.IO),
            clock = Clock { FIXED_NOW },
            io = Dispatchers.IO,
        )
    }

    @After fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun exportEntries(includeArchive: Boolean): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        exporter.export(out, includeArchive)
        return unzip(out.toByteArray())
    }

    /** Subscribed A (with settings and auth), subscribed B, unsubscribed C, and states for each. */
    private suspend fun seed(): Seed {
        val dao = db.podcastDao()
        val podcastA = dao.insert(
            PodcastEntity(
                feedUrl = FEED_A, title = "Alpha", artworkUrl = "https://a.example/art.jpg",
                subscribedAt = 100, podcastIndexId = 42, authUser = "user", authPassword = "pass",
                isSubscribed = true,
            ),
        )
        val podcastB = dao.insert(PodcastEntity(feedUrl = FEED_B, title = "Beta", isSubscribed = true))
        val podcastC = dao.insert(PodcastEntity(feedUrl = FEED_C, title = "Previewed", isSubscribed = false))

        db.podcastSettingsDao().upsert(
            PodcastSettingsEntity(
                podcastId = podcastA, autoDownload = false, keepLatestCount = 3,
                sortOrder = SortOrder.OLDEST_FIRST, playbackSpeed = 1.5f, skipIntroSec = 5,
                autoAddToQueue = QueueAdd.TOP,
            ),
        )

        val ids = db.episodeDao().insertAll(
            listOf(
                episode(podcastA, "a1", 1000, PlayState.PLAYED, playedAt = 500, lastPlayedAt = 500),
                episode(podcastA, "a2", 2000, PlayState.IN_PROGRESS, positionMs = 12_345, lastPlayedAt = 600),
                episode(podcastA, "a3", 3000, starred = true),
                episode(podcastA, "a4", 4000), // default state: not in the backup
                episode(podcastB, "b1", 1500), // default state, but queued: included
                episode(podcastB, "b2", 2500, blocked = true),
                episode(podcastC, "c1", 10), // unsubscribed and default: not in the backup
            ),
        )
        val seed = Seed(a1 = ids[0], a2 = ids[1], b1 = ids[4])

        db.queueDao().replaceAll(listOf(seed.b1, seed.a2))
        db.listeningSessionDao().insert(
            ListeningSessionEntity(episodeId = seed.a1, startedAt = 500, listenedMs = 400, speed = 1.0f),
        )
        db.listeningSessionDao().insert(
            ListeningSessionEntity(episodeId = seed.a2, startedAt = 600, listenedMs = 1000, speed = 1.25f),
        )
        db.backfillStateDao().upsert(
            BackfillStateEntity(
                podcastId = podcastA, status = BackfillStatus.COMPLETE, rssPagingDone = true,
                requestsMade = 5, episodesAdded = 2, oldestKnownPubDate = 1000,
            ),
        )
        db.backfillStateDao().upsert(BackfillStateEntity(podcastId = podcastC, status = BackfillStatus.PENDING))
        settings.update { it.copy(theme = ThemeMode.DARK, apiRequestsToday = 17, apiPausedUntil = 99) }
        return seed
    }

    @Test fun zipHoldsEveryPartAndDataJsonMatchesTheDatabase() = runTest {
        seed()
        val entries = exportEntries(includeArchive = true)

        assertThat(entries.keys).containsExactly(
            BackupEntries.MANIFEST, BackupEntries.DATA, BackupEntries.OPML, BackupEntries.ARCHIVE,
        ).inOrder()

        val manifest = BackupJson.decodeFromString(BackupManifest.serializer(), entries.text(BackupEntries.MANIFEST))
        assertThat(manifest.formatVersion).isEqualTo(1)
        assertThat(manifest.appVersion).isEqualTo(BuildConfig.VERSION_NAME)
        assertThat(manifest.createdAt).isEqualTo("2026-10-19T10:00:00Z")
        assertThat(manifest.counts).isEqualTo(
            BackupCounts(podcasts = 2, episodes = 5, queue = 2, history = 2, backfill = 1, archiveEpisodes = 7),
        )

        val data = BackupJson.decodeFromString(BackupData.serializer(), entries.text(BackupEntries.DATA))
        assertThat(data.formatVersion).isEqualTo(1)
        assertThat(data.podcasts).containsExactly(
            PodcastBackup(
                feedUrl = FEED_A, title = "Alpha", artworkUrl = "https://a.example/art.jpg",
                subscribedAt = 100, podcastIndexId = 42, authUser = "user", authPassword = "pass",
                settings = PodcastSettingsBackup(
                    autoDownload = false, keepLatestCount = 3, sortOrder = "OLDEST_FIRST",
                    playbackSpeed = 1.5f, skipIntroSec = 5, autoAddToQueue = "TOP",
                ),
            ),
            PodcastBackup(feedUrl = FEED_B, title = "Beta"),
        )
        assertThat(data.episodes.map { it.feedUrl to it.guid }).containsExactly(
            FEED_A to "a1", FEED_A to "a2", FEED_A to "a3", FEED_B to "b1", FEED_B to "b2",
        ).inOrder()
        assertThat(data.episodes[1]).isEqualTo(
            EpisodeBackup(
                feedUrl = FEED_A, guid = "a2", enclosureUrl = "https://x.example/a2.mp3", title = "Title a2",
                pubDate = 2000, durationSec = 1800, playState = "IN_PROGRESS", positionMs = 12_345,
                lastPlayedAt = 600,
            ),
        )
        assertThat(data.episodes[0].playState).isEqualTo("PLAYED")
        assertThat(data.episodes[0].playedAt).isEqualTo(500)
        assertThat(data.episodes[2].isStarred).isTrue()
        assertThat(data.episodes[4].autoDownloadBlocked).isTrue()

        assertThat(data.queue).containsExactly(QueueBackup(FEED_B, "b1"), QueueBackup(FEED_A, "a2")).inOrder()
        assertThat(data.history).containsExactly(
            HistoryBackup(FEED_A, "a1", startedAt = 500, listenedMs = 400, speed = 1.0f),
            HistoryBackup(FEED_A, "a2", startedAt = 600, listenedMs = 1000, speed = 1.25f),
        ).inOrder()
        assertThat(data.backfill).containsExactly(
            BackfillBackup(
                feedUrl = FEED_A, status = "COMPLETE", rssPagingDone = true,
                requestsMade = 5, episodesAdded = 2, oldestKnownPubDate = 1000,
            ),
        )

        val opml = Opml.parse(ByteArrayInputStream(entries.getValue(BackupEntries.OPML)))
        assertThat(opml.map { it.feedUrl }).containsExactly(FEED_A, FEED_B).inOrder()

        val archive = gunzipLines(entries.getValue(BackupEntries.ARCHIVE))
        assertThat(archive).hasSize(7)
        val unsubscribedLine = archive.map { BackupJson.decodeFromString(ArchiveEpisodeLine.serializer(), it) }
            .single { it.guid == "c1" }
        assertThat(unsubscribedLine.feedUrl).isEqualTo(FEED_C)
    }

    @Test fun settingsUseDataStoreKeyNamesAndLeaveOutRateLimiterState() = runTest {
        settings.update { it.copy(theme = ThemeMode.DARK, apiRequestsToday = 17, apiPausedUntil = 99) }
        val entries = settings.backupEntries()

        assertThat(entries).containsEntry("theme", "DARK")
        assertThat(entries).containsEntry("apiDailyBudget", "100")
        assertThat(entries).containsEntry("defaultSpeed", "1.0")
        assertThat(entries.keys).containsNoneOf("apiRequestsToday", "apiBudgetDay", "apiPausedUntil")
        assertThat(entries.keys).containsExactlyElementsIn(EXPECTED_SETTING_KEYS)

        // The same keys reach data.json.
        seed()
        val data = BackupJson.decodeFromString(
            BackupData.serializer(),
            exportEntries(includeArchive = false).getValue(BackupEntries.DATA).decodeToString(),
        )
        assertThat(data.settings).containsExactlyEntriesIn(settings.backupEntries())
    }

    @Test fun archiveIsOmittedWhenNotRequested() = runTest {
        seed()
        val entries = exportEntries(includeArchive = false)

        assertThat(entries.keys).containsExactly(BackupEntries.MANIFEST, BackupEntries.DATA, BackupEntries.OPML).inOrder()
        val manifest = BackupJson.decodeFromString(BackupManifest.serializer(), entries.text(BackupEntries.MANIFEST))
        assertThat(manifest.counts.archiveEpisodes).isNull()
    }

    @Test fun archiveStreamsFiftyThousandEpisodes() = runTest {
        val podcastId = db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://big.example/feed", title = "Big", isSubscribed = true),
        )
        for (batch in 0 until EPISODE_COUNT / BATCH_SIZE) {
            db.episodeDao().insertAll(
                (0 until BATCH_SIZE).map { i ->
                    val n = batch * BATCH_SIZE + i
                    EpisodeEntity(
                        podcastId = podcastId,
                        guid = "guid-$n",
                        title = "Episode $n",
                        descriptionHtml = "<p>Show notes $n</p>",
                        descriptionText = "Show notes $n",
                        pubDate = 1_700_000_000_000L + n,
                        durationSec = 60 * (n % 90),
                        enclosureUrl = "https://big.example/$n.mp3",
                        firstSeenAt = 0,
                    )
                },
            )
        }

        var lastProgress = 0f
        val out = ByteArrayOutputStream()
        exporter.export(out, includeArchive = true) { lastProgress = it }
        val entries = unzip(out.toByteArray())

        val manifest = BackupJson.decodeFromString(BackupManifest.serializer(), entries.text(BackupEntries.MANIFEST))
        assertThat(manifest.counts.archiveEpisodes).isEqualTo(EPISODE_COUNT)

        val archive = entries.getValue(BackupEntries.ARCHIVE)
        val lineCount = GZIPInputStream(ByteArrayInputStream(archive)).bufferedReader(Charsets.UTF_8).useLines { it.count() }
        assertThat(lineCount).isEqualTo(EPISODE_COUNT)

        val lines = gunzipLines(archive)
        val last = BackupJson.decodeFromString(ArchiveEpisodeLine.serializer(), lines.last())
        assertThat(last.guid).isEqualTo("guid-${EPISODE_COUNT - 1}")
        assertThat(last.description).isEqualTo("<p>Show notes ${EPISODE_COUNT - 1}</p>")
        assertThat(last.durationSec).isEqualTo(60 * ((EPISODE_COUNT - 1) % 90))
        assertThat(lastProgress).isEqualTo(1f)
    }

    private data class Seed(val a1: Long, val a2: Long, val b1: Long)

    private fun episode(
        podcastId: Long,
        guid: String,
        pubDate: Long,
        playState: PlayState = PlayState.UNPLAYED,
        positionMs: Long = 0,
        playedAt: Long? = null,
        lastPlayedAt: Long? = null,
        starred: Boolean = false,
        blocked: Boolean = false,
    ) = EpisodeEntity(
        podcastId = podcastId,
        guid = guid,
        title = "Title $guid",
        pubDate = pubDate,
        durationSec = 1800,
        enclosureUrl = "https://x.example/$guid.mp3",
        playState = playState,
        positionMs = positionMs,
        playedAt = playedAt,
        lastPlayedAt = lastPlayedAt,
        isStarred = starred,
        autoDownloadBlocked = blocked,
        firstSeenAt = 0,
    )

    private fun Map<String, ByteArray>.text(name: String): String = getValue(name).decodeToString()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            generateSequence { zin.nextEntry }.forEach { entry -> result[entry.name] = zin.readBytes() }
        }
        return result
    }

    private fun gunzipLines(gz: ByteArray): List<String> =
        GZIPInputStream(ByteArrayInputStream(gz)).bufferedReader(Charsets.UTF_8).readLines()

    private companion object {
        const val FEED_A = "https://a.example/feed"
        const val FEED_B = "https://b.example/feed"
        const val FEED_C = "https://c.example/feed"
        const val EPISODE_COUNT = 50_000
        const val BATCH_SIZE = 1_000
        val FIXED_NOW: Long = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli()

        /** PLAN.md §10 keys, plus notificationPermissionAsked. Rate-limiter keys are excluded. */
        val EXPECTED_SETTING_KEYS = setOf(
            "theme", "pureBlack", "tintPlayer", "dynamicColor", "lastTab", "searchMode", "librarySort",
            "libraryLayout", "latestFilter", "autoDownload", "keepLatestCount", "allowCellularDownload",
            "downloadOnlyWhileCharging", "streamOnCellular", "deletePlayed", "deletePlayedDelayHours",
            "maxAgeDays", "countManualTowardLimit", "markPlayedPercent", "skipBackSec", "skipForwardSec",
            "defaultSpeed", "autoplayNext", "resumeRewind", "trimSilence", "voiceBoost", "refreshIntervalHours",
            "notifyNewEpisodes", "backfillEnabled", "backfillWifiOnly", "backfillChargingOnly",
            "apiDailyBudget", "notificationPermissionAsked",
        )
    }
}

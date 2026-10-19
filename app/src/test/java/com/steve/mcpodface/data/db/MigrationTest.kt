package com.steve.mcpodface.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Builds a real version-1 database from the exported schema (app/schemas/…/1.json), fills it,
 * then opens it with the current Room database and [ALL_MIGRATIONS]. Room validates the migrated
 * schema against the current one and throws on any mismatch; the old data must survive.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    private fun schema(version: Int) =
        JSONObject(File("schemas/com.steve.mcpodface.data.db.AppDatabase/$version.json").readText()).getJSONObject("database")

    private fun createVersion1() {
        context.deleteDatabase(name)
        val db = schema(1)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.use { sql ->
            val entities = db.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) sql.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val views = db.optJSONArray("views")
            if (views != null) for (i in 0 until views.length()) sql.execSQL(views.getJSONObject(i).getString("createSql"))
            val setup = db.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO podcasts (id, feedUrl, title, isSubscribed, feedGone, feedType) VALUES (1, 'https://x/feed', 'Old show', 1, 0, 'EPISODIC')")
            sql.execSQL(
                "INSERT INTO episodes (id, podcastId, guid, title, descriptionText, summaryText, pubDate, enclosureUrl, origin, " +
                    "inCurrentFeed, playState, positionMs, isStarred, autoDownloadBlocked, firstSeenAt) " +
                    "VALUES (7, 1, 'g', 'Old episode', '', '', 1, 'https://x/1.mp3', 'FEED', 1, 'IN_PROGRESS', 4200, 1, 0, 0)",
            )
        }
    }

    @Test fun migratesVersion1ToCurrentKeepingData() = runBlocking<Unit> {
        createVersion1()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val episode = db.episodeDao().getById(7)!!
            assertThat(episode.title).isEqualTo("Old episode")
            assertThat(episode.positionMs).isEqualTo(4200)
            assertThat(episode.isStarred).isTrue()
            assertThat(db.podcastDao().getById(1)!!.title).isEqualTo("Old show")
            // The new tables work.
            val id = db.playlistDao().insert(PlaylistEntity(name = "Mine", kind = PlaylistKind.MANUAL, createdAt = 0))
            db.playlistDao().insertItems(listOf(PlaylistItemEntity(id, 7, 0, 0)))
            assertThat(db.playlistDao().getItems(id)).hasSize(1)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}

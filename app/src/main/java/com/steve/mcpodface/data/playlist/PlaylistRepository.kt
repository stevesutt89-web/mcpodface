package com.steve.mcpodface.data.playlist

import androidx.room.withTransaction
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlaylistEntity
import com.steve.mcpodface.data.db.PlaylistItemEntity
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.db.PlaylistWithCount
import com.steve.mcpodface.data.search.SearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlists (PLAN.md §8.11): manual playlists the user adds episodes to, and smart playlists whose
 * episodes come from saved [SmartRules] evaluated by the search engine. Listen Later has been
 * retired: swipe right now toggles Up Next (see [QueueRepository]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlaylistRepository @Inject constructor(
    private val db: AppDatabase,
    private val search: SearchRepository,
    private val clock: Clock,
) {
    private val dao get() = db.playlistDao()

    /** Every MANUAL and SMART playlist for the Library, in the user's order. */
    fun observePlaylists(): Flow<List<PlaylistWithCount>> = dao.observeAll()

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = dao.observeById(id)

    /** The playlist's episodes: stored items for manual lists, live rules for smart ones. */
    fun observeEpisodes(playlistId: Long): Flow<List<EpisodeListItem>> =
        dao.observeById(playlistId)
            .distinctUntilChanged { a, b -> a?.kind == b?.kind && a?.rulesJson == b?.rulesJson }
            .flatMapLatest { playlist ->
                when {
                    playlist == null -> flowOf(emptyList())
                    playlist.kind == PlaylistKind.SMART -> smartEpisodes(SmartRules.fromJson(playlist.rulesJson))
                    else -> dao.observeItems(playlist.id)
                }
            }

    /** What [rules] currently match; also the smart editor's live preview. */
    fun smartEpisodes(rules: SmartRules): Flow<List<EpisodeListItem>> =
        search.search(
            text = rules.text,
            filters = rules.toFilters(clock.now()),
            sort = rules.sort,
            pageSize = rules.limit.coerceIn(1, SmartRules.MAX_LIMIT),
            listAllWhenBlank = true,
        ).map { results -> results.items.map { it.item } }

    /** The manual playlists holding [episodeId], for ticks in the "Add to playlist" sheet. */
    fun observePlaylistIdsFor(episodeId: Long): Flow<Set<Long>> = dao.observePlaylistIdsFor(episodeId).map { it.toSet() }

    /**
     * One-time move, run at startup (PLAN.md §8.11): appends every Listen Later episode, in list order,
     * to the end of Up Next (skipping ones already queued), then deletes the retired playlist.
     * Does nothing when there is no Listen Later playlist.
     */
    suspend fun moveListenLaterToUpNext() = db.withTransaction {
        val retired = dao.getAll().filter { it.kind == PlaylistKind.LISTEN_LATER }
        if (retired.isEmpty()) return@withTransaction
        val queue = db.queueDao()
        val upNext = queue.getAll().map { it.episodeId }.toMutableList()
        for (playlist in retired) {
            for (item in dao.getItems(playlist.id)) {
                if (item.episodeId !in upNext) upNext += item.episodeId
            }
            dao.delete(playlist.id)
        }
        queue.replaceAll(upNext)
    }

    suspend fun createManual(name: String): Long = create(name, PlaylistKind.MANUAL, null)

    suspend fun createSmart(name: String, rules: SmartRules): Long = create(name, PlaylistKind.SMART, rules.toJson())

    private suspend fun create(name: String, kind: PlaylistKind, rulesJson: String?): Long = db.withTransaction {
        dao.insert(
            PlaylistEntity(
                name = name.trim().ifEmpty { "Playlist" }, kind = kind, rulesJson = rulesJson,
                position = dao.maxPlaylistPosition() + 1, createdAt = clock.now(),
            ),
        )
    }

    suspend fun rename(playlistId: Long, name: String) {
        val p = dao.getById(playlistId) ?: return
        if (name.isBlank()) return
        dao.update(p.copy(name = name.trim()))
    }

    suspend fun updateRules(playlistId: Long, rules: SmartRules) {
        val p = dao.getById(playlistId) ?: return
        if (p.kind != PlaylistKind.SMART) return
        dao.update(p.copy(rulesJson = rules.toJson()))
    }

    suspend fun delete(playlistId: Long) = dao.delete(playlistId)

    /** Appends to a manual playlist; no-op for smart playlists and the retired Listen Later kind. */
    suspend fun add(playlistId: Long, episodeId: Long) = db.withTransaction {
        val p = dao.getById(playlistId) ?: return@withTransaction
        if (p.kind == PlaylistKind.SMART || p.kind == PlaylistKind.LISTEN_LATER) return@withTransaction
        dao.insertItems(listOf(PlaylistItemEntity(playlistId, episodeId, dao.maxItemPosition(playlistId) + 1, clock.now())))
    }

    suspend fun remove(playlistId: Long, episodeId: Long) = dao.deleteItem(playlistId, episodeId)

    /** Drag reorder: moves the item at [fromIndex] to [toIndex] and renumbers. */
    suspend fun move(playlistId: Long, fromIndex: Int, toIndex: Int) = db.withTransaction {
        val items = dao.getItems(playlistId).toMutableList()
        if (fromIndex !in items.indices || toIndex !in items.indices) return@withTransaction
        items.add(toIndex, items.removeAt(fromIndex))
        dao.deleteItems(playlistId)
        dao.insertItems(items.mapIndexed { i, item -> item.copy(position = i) })
    }

    /** The playlist's episode ids in order right now, for "Play all". */
    suspend fun episodeIds(playlistId: Long): List<Long> = observeEpisodes(playlistId).first().map { it.episode.id }
}

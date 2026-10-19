package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A playlist for the Library list. [itemCount] is 0 for SMART playlists (their size is computed live). */
data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val itemCount: Int,
)

@Dao
interface PlaylistDao {
    /** Listen Later first, then the user's playlists in their order. */
    @Query(
        """
        SELECT pl.*, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = pl.id) AS itemCount
        FROM playlists pl
        ORDER BY CASE pl.kind WHEN 'LISTEN_LATER' THEN 0 ELSE 1 END, pl.position, pl.id
        """,
    )
    fun observeAll(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists ORDER BY position, id")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE kind = :kind ORDER BY id LIMIT 1")
    suspend fun getFirstOfKind(kind: PlaylistKind): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlists")
    suspend fun maxPlaylistPosition(): Int

    /** A LISTEN_LATER or MANUAL playlist's episodes, in playlist order. */
    @Query(
        """
        SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl,
               d.state AS downloadState,
               COALESCE(d.totalBytes, d.bytesDownloaded) AS downloadBytes,
               EXISTS (SELECT 1 FROM queue_items q WHERE q.episodeId = e.id) AS inQueue
        FROM playlist_items i
        INNER JOIN episodes e ON e.id = i.episodeId
        INNER JOIN podcasts p ON p.id = e.podcastId
        LEFT JOIN downloads d ON d.episodeId = e.id
        WHERE i.playlistId = :playlistId
        ORDER BY i.position, i.addedAt
        """,
    )
    fun observeItems(playlistId: Long): Flow<List<EpisodeListItem>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position, addedAt")
    suspend fun getItems(playlistId: Long): List<PlaylistItemEntity>

    /** Episode ids in any playlist of [kind] (Listen Later: for the swipe label). */
    @Query(
        """
        SELECT i.episodeId FROM playlist_items i
        INNER JOIN playlists pl ON pl.id = i.playlistId
        WHERE pl.kind = :kind
        """,
    )
    fun observeEpisodeIdsOfKind(kind: PlaylistKind): Flow<List<Long>>

    /** Ids of the manual playlists (and Listen Later) that contain [episodeId]. */
    @Query("SELECT playlistId FROM playlist_items WHERE episodeId = :episodeId")
    fun observePlaylistIdsFor(episodeId: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND episodeId = :episodeId")
    suspend fun deleteItem(playlistId: Long, episodeId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItems(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxItemPosition(playlistId: Long): Int

    @Query("SELECT COALESCE(MIN(position), 0) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun minItemPosition(playlistId: Long): Int

    /** Drops [episodeId] from every playlist of [kind] (finished episodes leave Listen Later). */
    @Query(
        """
        DELETE FROM playlist_items
        WHERE episodeId = :episodeId AND playlistId IN (SELECT id FROM playlists WHERE kind = :kind)
        """,
    )
    suspend fun removeFromKind(kind: PlaylistKind, episodeId: Long)
}

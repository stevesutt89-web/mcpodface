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
    /** The MANUAL and SMART playlists in the user's order. The retired LISTEN_LATER kind is left out. */
    @Query(
        """
        SELECT pl.*, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = pl.id) AS itemCount
        FROM playlists pl
        WHERE pl.kind != 'LISTEN_LATER'
        ORDER BY pl.position, pl.id
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

    /** A MANUAL playlist's episodes, in playlist order. */
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

    /** Ids of the playlists that contain [episodeId]. */
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
}

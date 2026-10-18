package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ListeningSessionDao {
    @Insert
    suspend fun insert(session: ListeningSessionEntity): Long

    @Update
    suspend fun update(session: ListeningSessionEntity)

    @Query("SELECT * FROM listening_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<ListeningSessionEntity>
}

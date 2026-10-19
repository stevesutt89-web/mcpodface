package com.steve.mcpodface.data.queue

import androidx.room.withTransaction
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.QueueItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The Up Next queue (PLAN.md §8.8). Positions are kept dense: 0..n-1. */
@Singleton
class QueueRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.queueDao()

    fun observeIds(): Flow<List<Long>> = dao.observe().map { items -> items.map { it.episodeId } }

    suspend fun addLast(episodeId: Long) = edit { ids -> ids - episodeId + episodeId }

    suspend fun addFirst(episodeId: Long) = edit { ids -> listOf(episodeId) + (ids - episodeId) }

    suspend fun remove(episodeId: Long) = edit { ids -> ids - episodeId }

    suspend fun move(fromIndex: Int, toIndex: Int) = edit { ids ->
        if (fromIndex !in ids.indices || toIndex !in ids.indices) ids
        else ids.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    suspend fun clear() = dao.clear()

    /** Replaces the whole queue with [episodeIds], in order (Play all / Shuffle on a playlist). */
    suspend fun replaceAll(episodeIds: List<Long>) = db.withTransaction { dao.replaceAll(episodeIds.distinct()) }

    private suspend fun edit(transform: (List<Long>) -> List<Long>) = db.withTransaction {
        val current = dao.getAll().map(QueueItemEntity::episodeId)
        val next = transform(current)
        if (next != current) dao.replaceAll(next)
    }
}

package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.watcher.data.model.TimelineEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineEventDao {
    @Query("SELECT * FROM timeline_events WHERE runId = :runId ORDER BY timestampSeconds ASC, id ASC")
    fun observeEventsForRun(runId: Long): Flow<List<TimelineEventEntity>>

    @Query("SELECT * FROM timeline_events WHERE runId = :runId ORDER BY timestampSeconds ASC, id ASC LIMIT :limit")
    fun observeEventsForRunLimited(runId: Long, limit: Int): Flow<List<TimelineEventEntity>>

    @Query("SELECT COUNT(*) FROM timeline_events WHERE runId = :runId")
    fun observeEventCountForRun(runId: Long): Flow<Int>

    @Query("SELECT * FROM timeline_events WHERE runId = :runId ORDER BY timestampSeconds ASC, id ASC")
    suspend fun getEventsForRun(runId: Long): List<TimelineEventEntity>

    @Query("DELETE FROM timeline_events WHERE runId = :runId")
    suspend fun deleteByRunId(runId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<TimelineEventEntity>)
}

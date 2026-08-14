package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.watcher.data.model.VideoAiTraceEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoAiTraceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: VideoAiTraceEventEntity): Long

    @Query("SELECT * FROM video_ai_trace_events WHERE traceId = :traceId ORDER BY sequence ASC, id ASC")
    fun observeForTrace(traceId: String): Flow<List<VideoAiTraceEventEntity>>

    @Query("SELECT * FROM video_ai_trace_events WHERE runId = :runId ORDER BY sequence ASC, id ASC")
    fun observeForRun(runId: Long): Flow<List<VideoAiTraceEventEntity>>

    @Query("SELECT * FROM video_ai_trace_events WHERE runId = :runId ORDER BY sequence ASC, id ASC")
    suspend fun getForRun(runId: Long): List<VideoAiTraceEventEntity>

    @Query("DELETE FROM video_ai_trace_events WHERE runId = :runId")
    suspend fun deleteForRun(runId: Long)
}

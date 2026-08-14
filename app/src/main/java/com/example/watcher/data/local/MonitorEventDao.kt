package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.watcher.data.model.MonitorEventEntity
import kotlinx.coroutines.flow.Flow

data class MonitorEventRunSummary(
    val runId: Long,
    val eventCount: Int,
    val frameCount: Int,
    val previewFramePath: String?
)

@Dao
interface MonitorEventDao {
    @Query("SELECT * FROM monitor_events ORDER BY timestamp ASC, id ASC")
    fun observeAllEvents(): Flow<List<MonitorEventEntity>>

    @Query(
        """
        SELECT
            runId,
            COUNT(*) AS eventCount,
            SUM(CASE WHEN frameImagePath IS NOT NULL AND frameImagePath != '' THEN 1 ELSE 0 END) AS frameCount,
            MAX(NULLIF(frameImagePath, '')) AS previewFramePath
        FROM monitor_events
        GROUP BY runId
        """
    )
    fun observeRunSummaries(): Flow<List<MonitorEventRunSummary>>

    @Query("SELECT * FROM monitor_events WHERE runId = :runId ORDER BY timestamp ASC, id ASC")
    fun observeEventsForRun(runId: Long): Flow<List<MonitorEventEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM monitor_events
            WHERE runId = :runId
            ORDER BY timestamp DESC, id DESC
            LIMIT :limit
        )
        ORDER BY timestamp ASC, id ASC
        """
    )
    fun observeRecentEventsForRun(runId: Long, limit: Int): Flow<List<MonitorEventEntity>>

    @Query("SELECT COUNT(*) FROM monitor_events WHERE runId = :runId")
    fun observeEventCountForRun(runId: Long): Flow<Int>

    @Query("SELECT * FROM monitor_events WHERE runId = :runId ORDER BY timestamp ASC, id ASC")
    suspend fun getEventsForRun(runId: Long): List<MonitorEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MonitorEventEntity): Long

    @Query("DELETE FROM monitor_events WHERE runId = :runId")
    suspend fun deleteByRunId(runId: Long)
}

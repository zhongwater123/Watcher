package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.watcher.data.model.MonitorMediaEntity
import kotlinx.coroutines.flow.Flow

data class MonitorMediaRunSummary(
    val runId: Long,
    val mediaCount: Int,
    val previewPath: String?
)

@Dao
interface MonitorMediaDao {
    @Query("SELECT * FROM monitor_media ORDER BY createdAt DESC, id DESC")
    fun observeAllMedia(): Flow<List<MonitorMediaEntity>>

    @Query(
        """
        SELECT
            runId,
            COUNT(*) AS mediaCount,
            MAX(localFilePath) AS previewPath
        FROM monitor_media
        GROUP BY runId
        """
    )
    fun observeRunSummaries(): Flow<List<MonitorMediaRunSummary>>

    @Query("SELECT * FROM monitor_media WHERE runId = :runId ORDER BY createdAt DESC, id DESC")
    fun observeMediaForRun(runId: Long): Flow<List<MonitorMediaEntity>>

    @Query("SELECT * FROM monitor_media WHERE runId = :runId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecentMediaForRun(runId: Long, limit: Int): Flow<List<MonitorMediaEntity>>

    @Query("SELECT COUNT(*) FROM monitor_media WHERE runId = :runId")
    fun observeMediaCountForRun(runId: Long): Flow<Int>

    @Query("SELECT * FROM monitor_media WHERE runId = :runId ORDER BY createdAt DESC, id DESC")
    suspend fun getMediaForRun(runId: Long): List<MonitorMediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: MonitorMediaEntity): Long

    @Query("DELETE FROM monitor_media WHERE runId = :runId")
    suspend fun deleteByRunId(runId: Long)
}

package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.watcher.data.model.VideoSegmentRun
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoSegmentRunDao {
    @Query("SELECT * FROM video_segment_runs WHERE localFilePath IS NOT NULL AND localFilePath != '' ORDER BY updatedAt DESC")
    fun observeAllSegmentsWithFiles(): Flow<List<VideoSegmentRun>>

    @Query("SELECT * FROM video_segment_runs WHERE id = :id")
    suspend fun getById(id: Long): VideoSegmentRun?

    @Query("SELECT * FROM video_segment_runs WHERE runId = :runId ORDER BY segmentIndex ASC")
    suspend fun getSegmentsForRun(runId: Long): List<VideoSegmentRun>

    @Query("SELECT * FROM video_segment_runs WHERE runId = :runId ORDER BY segmentIndex ASC")
    fun observeSegmentsForRun(runId: Long): Flow<List<VideoSegmentRun>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(segment: VideoSegmentRun): Long

    @Update
    suspend fun update(segment: VideoSegmentRun)

    @Transaction
    suspend fun upsert(segment: VideoSegmentRun): Long {
        return if (segment.id == 0L) {
            insert(segment)
        } else {
            update(segment)
            segment.id
        }
    }
}

package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.watcher.data.model.VideoProcessRun
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoProcessRunDao {
    @Query("SELECT * FROM video_process_runs ORDER BY updatedAt DESC")
    fun observeAllRuns(): Flow<List<VideoProcessRun>>

    @Query("SELECT * FROM video_process_runs ORDER BY updatedAt DESC LIMIT 20")
    fun observeRecentRuns(): Flow<List<VideoProcessRun>>

    @Query("SELECT * FROM video_process_runs WHERE recordingScenario = :recordingScenario ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentRunsForScenario(recordingScenario: String, limit: Int): Flow<List<VideoProcessRun>>

    @Query("SELECT * FROM video_process_runs WHERE id = :id")
    fun observeRunById(id: Long): Flow<VideoProcessRun?>

    @Query("SELECT * FROM video_process_runs WHERE id = :id")
    suspend fun getRunById(id: Long): VideoProcessRun?

    @Query("DELETE FROM video_process_runs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(run: VideoProcessRun): Long

    @Update
    suspend fun update(run: VideoProcessRun)

    @Transaction
    suspend fun upsert(run: VideoProcessRun): Long {
        return if (run.id == 0L) {
            insert(run)
        } else {
            update(run)
            run.id
        }
    }
}

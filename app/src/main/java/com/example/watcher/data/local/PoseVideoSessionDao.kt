package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.watcher.data.local.pose.PoseVideoSession
import kotlinx.coroutines.flow.Flow

@Dao
interface PoseVideoSessionDao {

    @Query("SELECT * FROM pose_video_sessions WHERE scenario = :scenario ORDER BY createdAt DESC")
    fun observeByScenario(scenario: String): Flow<List<PoseVideoSession>>

    @Query("SELECT * FROM pose_video_sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PoseVideoSession>>

    @Query("SELECT * FROM pose_video_sessions WHERE id = :id")
    suspend fun getById(id: Long): PoseVideoSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: PoseVideoSession): Long

    @Update
    suspend fun update(session: PoseVideoSession)

    @Query("UPDATE pose_video_sessions SET processingStatus = :status, processingProgress = :progress, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, progress: Float, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pose_video_sessions SET processingStatus = :status, processingError = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateError(id: Long, status: String, error: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM pose_video_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

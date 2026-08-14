package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.watcher.data.model.VideoAudioAssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoAudioAssetDao {
    @Query("SELECT * FROM video_audio_assets WHERE runId = :runId ORDER BY assetType ASC, segmentIndex ASC, id ASC")
    fun observeForRun(runId: Long): Flow<List<VideoAudioAssetEntity>>

    @Query("SELECT * FROM video_audio_assets WHERE runId = :runId ORDER BY assetType ASC, segmentIndex ASC, id ASC LIMIT :limit")
    fun observeForRunLimited(runId: Long, limit: Int): Flow<List<VideoAudioAssetEntity>>

    @Query("SELECT COUNT(*) FROM video_audio_assets WHERE runId = :runId")
    fun observeCountForRun(runId: Long): Flow<Int>

    @Query("SELECT * FROM video_audio_assets WHERE runId = :runId ORDER BY assetType ASC, segmentIndex ASC, id ASC")
    suspend fun getForRun(runId: Long): List<VideoAudioAssetEntity>

    @Query("SELECT * FROM video_audio_assets WHERE localFilePath IS NOT NULL AND localFilePath != '' ORDER BY updatedAt DESC")
    fun observeAllAudioAssetsWithFiles(): Flow<List<VideoAudioAssetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: VideoAudioAssetEntity): Long
}

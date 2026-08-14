package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoRemoteFileBindingDao {
    @Query("SELECT * FROM video_remote_file_bindings WHERE runId = :runId ORDER BY assetKind ASC, segmentRunId ASC, id ASC")
    fun observeForRun(runId: Long): Flow<List<VideoRemoteFileBindingEntity>>

    @Query("SELECT * FROM video_remote_file_bindings WHERE runId = :runId AND assetKind = :assetKind ORDER BY updatedAt DESC, id DESC")
    fun observeForRunAndAssetKind(runId: Long, assetKind: String): Flow<List<VideoRemoteFileBindingEntity>>

    @Query("SELECT * FROM video_remote_file_bindings WHERE runId = :runId ORDER BY assetKind ASC, segmentRunId ASC, id ASC LIMIT :limit")
    fun observeForRunLimited(runId: Long, limit: Int): Flow<List<VideoRemoteFileBindingEntity>>

    @Query("SELECT COUNT(*) FROM video_remote_file_bindings WHERE runId = :runId")
    fun observeCountForRun(runId: Long): Flow<Int>

    @Query("SELECT * FROM video_remote_file_bindings WHERE runId = :runId ORDER BY assetKind ASC, segmentRunId ASC, id ASC")
    suspend fun getForRun(runId: Long): List<VideoRemoteFileBindingEntity>

    @Query(
        """
        SELECT * FROM video_remote_file_bindings
        WHERE runId = :runId AND assetKind = :assetKind AND localPath = :localPath
        LIMIT 1
        """
    )
    suspend fun findForLocalFile(
        runId: Long,
        assetKind: String,
        localPath: String
    ): VideoRemoteFileBindingEntity?

    @Query("SELECT * FROM video_remote_file_bindings WHERE arkFileId = :fileId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findByFileId(fileId: String): VideoRemoteFileBindingEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(binding: VideoRemoteFileBindingEntity): Long

    @Update
    suspend fun update(binding: VideoRemoteFileBindingEntity)

    @Transaction
    suspend fun upsert(binding: VideoRemoteFileBindingEntity): Long {
        return if (binding.id == 0L) {
            val insertedId = insert(binding)
            if (insertedId > 0L) {
                insertedId
            } else {
                val existing = findForLocalFile(binding.runId, binding.assetKind, binding.localPath)
                if (existing != null) {
                    update(binding.copy(id = existing.id))
                    existing.id
                } else {
                    insertedId
                }
            }
        } else {
            update(binding)
            binding.id
        }
    }
}

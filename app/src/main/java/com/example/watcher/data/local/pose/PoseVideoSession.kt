package com.example.watcher.data.local.pose

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pose_video_sessions",
    indices = [
        Index("scenario"),
        Index("processingStatus")
    ]
)
data class PoseVideoSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val scenario: String,
    val title: String,
    val sourceVideoPath: String,
    val sourceVideoDurationMs: Long = 0L,
    val sourceVideoWidth: Int = 0,
    val sourceVideoHeight: Int = 0,
    val sourceFps: Int = 30,
    val frameCount: Int = 0,
    val landmarkCount: Int = 33,
    val rawPoseFilePath: String = "",
    val smoothPoseFilePath: String = "",
    val processingStatus: String = ProcessingStatus.PENDING,
    val processingProgress: Float = 0f,
    val clipStartMs: Long = 0L,
    val clipEndMs: Long = 0L,
    val processingError: String? = null,
    val thumbnailPath: String? = null,
    val beatFilePath: String = "",
    val audioFileId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    object ProcessingStatus {
        const val PENDING = "pending"       // Never completed a full pass
        const val READY = "ready"           // Completed at least one full pass
        const val SEGMENTED = "segmented"   // Motion segmentation complete
    }
}

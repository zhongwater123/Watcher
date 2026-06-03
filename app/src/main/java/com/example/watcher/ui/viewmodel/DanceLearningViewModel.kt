package com.example.watcher.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.watcher.data.local.AppDatabase
import com.example.watcher.data.local.pose.PoseVideoSession
import com.example.watcher.ui.screens.FramePickerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DanceLearningViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.poseVideoSessionDao()

    val sessions = dao.observeByScenario("dance_learning")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pending import state (between file pick and confirm)
    private val _pendingVideoPath = MutableStateFlow<String?>(null)
    val pendingVideoPath: StateFlow<String?> = _pendingVideoPath.asStateFlow()

    private val _pendingVideoDurationMs = MutableStateFlow(0L)
    val pendingVideoDurationMs: StateFlow<Long> = _pendingVideoDurationMs.asStateFlow()

    private val _pendingVideoMetadata = MutableStateFlow(VideoImportMetadata())
    val pendingVideoMetadata: StateFlow<VideoImportMetadata> = _pendingVideoMetadata.asStateFlow()

    /**
     * Step 1: User picks a video. Copy to app storage and return local path.
     * Navigates to FramePicker after this.
     */
    fun prepareVideo(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
            val videosDir = File(context.filesDir, "pose_videos")
            videosDir.mkdirs()
            val fileName = "dance_${System.currentTimeMillis()}.mp4"
            val localFile = File(videosDir, fileName)
            localFile.outputStream().use { out ->
                inputStream.copyTo(out)
            }
            inputStream.close()

            // Get video metadata: duration, fps, dimensions, exact frame count
            val metadata = extractVideoMetadata(localFile)

            _pendingVideoPath.value = localFile.absolutePath
            _pendingVideoDurationMs.value = metadata.durationMs
            _pendingVideoMetadata.value = metadata
        }
    }

    /**
     * Extract a frame at a specific time (for cover preview in FramePicker).
     */
    fun extractFrameAtTime(timeMs: Long): Bitmap? {
        val videoPath = _pendingVideoPath.value ?: return null
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            val bitmap = retriever.getFrameAtTime(
                timeMs * 1000L, // Convert ms to microseconds
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            retriever.release()
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Step 2: User confirms title, cover frame, and clip range → save session (no processing).
     * Processing happens during playback (real-time detection).
     */
    fun confirmImport(result: FramePickerResult) {
        val videoPath = _pendingVideoPath.value ?: return
        val metadata = _pendingVideoMetadata.value
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()

            // Save cover thumbnail
            val thumbnailDir = File(context.filesDir, "pose_thumbnails")
            thumbnailDir.mkdirs()
            val thumbnailFile = File(thumbnailDir, "cover_${System.currentTimeMillis()}.jpg")
            val coverSaved = saveCoverThumbnail(videoPath, result.coverTimeMs, thumbnailFile)

            val session = PoseVideoSession(
                scenario = "dance_learning",
                title = result.title.ifBlank { "舞蹈 ${sessions.value.size + 1}" },
                sourceVideoPath = videoPath,
                sourceVideoDurationMs = metadata.durationMs,
                sourceVideoWidth = metadata.width,
                sourceVideoHeight = metadata.height,
                sourceFps = metadata.fps,
                frameCount = metadata.exactFrameCount,
                clipStartMs = result.clipStartMs,
                clipEndMs = result.clipEndMs,
                thumbnailPath = if (coverSaved) thumbnailFile.absolutePath else null,
                processingStatus = PoseVideoSession.ProcessingStatus.PENDING
            )
            dao.upsert(session)

            // Clear pending state
            _pendingVideoPath.value = null
            _pendingVideoDurationMs.value = 0L
            _pendingVideoMetadata.value = VideoImportMetadata()
        }
    }

    fun cancelImport() {
        val path = _pendingVideoPath.value
        _pendingVideoPath.value = null
        _pendingVideoDurationMs.value = 0L
        // Clean up copied file
        if (path != null) {
            viewModelScope.launch(Dispatchers.IO) {
                File(path).delete()
            }
        }
    }

    private val _segmentationResult = MutableStateFlow<String?>(null)
    val segmentationResult: StateFlow<String?> = _segmentationResult.asStateFlow()

    fun runSegmentation(session: PoseVideoSession) {
        viewModelScope.launch(Dispatchers.IO) {
            val poseDir = File(getApplication<Application>().filesDir, "pose_data")
            val poseFile = File(poseDir, "session_${session.id}.pose")
            if (!poseFile.exists()) {
                _segmentationResult.value = "错误: .pose 文件不存在"
                return@launch
            }

            val engine = com.example.watcher.data.local.pose.DanceSegmentationEngine()
            val result = engine.segment(poseFile, session.id)
            if (result == null) {
                _segmentationResult.value = "切分失败: 帧数据不足"
                return@launch
            }

            val segFile = File(poseDir, "session_${session.id}.segments.json")
            engine.saveToFile(result, segFile)

            // Update status to SEGMENTED
            val latest = dao.getById(session.id) ?: session
            dao.upsert(latest.copy(
                processingStatus = PoseVideoSession.ProcessingStatus.SEGMENTED,
                updatedAt = System.currentTimeMillis()
            ))

            _segmentationResult.value = "切分完成: ${result.atomicMoves.size} 个动作, ${result.phrases.size} 个短语。可在预览中查看彩色骨骼效果。"
        }
    }

    fun clearSegmentationResult() {
        _segmentationResult.value = null
    }

    fun markSessionReady(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = dao.getById(sessionId) ?: return@launch
            // Check if segmentation was completed
            val segFile = File(
                File(getApplication<Application>().filesDir, "pose_data"),
                "session_${sessionId}.segments.json"
            )
            val status = if (segFile.exists()) {
                PoseVideoSession.ProcessingStatus.SEGMENTED
            } else {
                PoseVideoSession.ProcessingStatus.READY
            }
            dao.upsert(session.copy(
                processingStatus = status,
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = dao.getById(sessionId) ?: return@launch
            dao.upsert(session.copy(
                title = newTitle.trim(),
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = dao.getById(sessionId) ?: return@launch
            listOfNotNull(
                session.sourceVideoPath,
                session.rawPoseFilePath,
                session.smoothPoseFilePath,
                session.thumbnailPath,
                session.beatFilePath
            ).forEach { path ->
                if (path.isNotBlank()) File(path).delete()
            }
            // Also clean up the pose_data files by convention
            val poseDir = File(getApplication<Application>().filesDir, "pose_data")
            File(poseDir, "session_${sessionId}.pose").delete()
            File(poseDir, "session_${sessionId}.beat").delete()
            File(poseDir, "session_${sessionId}.segments.json").delete()
            dao.deleteById(sessionId)
        }
    }

    /**
     * Extract accurate video metadata including exact frame count.
     * Uses MediaExtractor to count actual samples (fast, no decoding).
     */
    private fun extractVideoMetadata(file: File): VideoImportMetadata {
        var durationMs = 0L
        var width = 0
        var height = 0
        var fps = 30

        // Get basic metadata from retriever
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val temp = width; width = height; height = temp
            }
            retriever.release()
        } catch (_: Exception) {}

        // Count exact frames using MediaExtractor (fast — only reads container index)
        var exactFrameCount = 0
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val videoTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (videoTrackIndex != null) {
                val format = extractor.getTrackFormat(videoTrackIndex)
                // Try to get frame rate from container
                val containerFps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                if (containerFps != null && containerFps > 0) fps = containerFps

                // Count samples (= exact frame count)
                extractor.selectTrack(videoTrackIndex)
                while (extractor.sampleTime >= 0) {
                    exactFrameCount++
                    extractor.advance()
                }
            }
            extractor.release()
        } catch (_: Exception) {}

        // Fallback: if extractor failed, estimate from duration × fps
        if (exactFrameCount == 0 && durationMs > 0) {
            exactFrameCount = ((durationMs / 1000.0) * fps).toInt()
        }

        return VideoImportMetadata(
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            exactFrameCount = exactFrameCount
        )
    }

    private fun saveCoverThumbnail(videoPath: String, timeMs: Long, outputFile: File): Boolean {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            val frame = retriever.getFrameAtTime(
                timeMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            retriever.release()
            if (frame != null) {
                FileOutputStream(outputFile).use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                frame.recycle()
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }
}

data class VideoImportMetadata(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 30,
    val exactFrameCount: Int = 0
)

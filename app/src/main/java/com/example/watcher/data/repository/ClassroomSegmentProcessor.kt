package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.data.local.VideoSegmentRunDao
import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.VideoSegmentRun
import com.example.watcher.data.remote.DoubaoApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val CLASSROOM_PROCESSOR_TAG = "Watcher.Classroom.Processor"

internal class ClassroomSegmentProcessor(
    private val apiService: DoubaoApiService,
    private val segmentRunDao: VideoSegmentRunDao,
    private val remoteFileResolver: VideoRemoteFileResolver,
    private val segmentAnalyzer: ClassroomSegmentAnalyzer,
    private val apiKey: String
) {
    fun requireApiKey() {
        check(apiKey.isNotBlank()) { "API_KEY is missing. Set it in local.properties first." }
    }

    suspend fun analyzeRecordedSegment(
        recordedSegment: RecordedSegment,
        task: VideoProcessTaskDraft,
        segmentCount: Int,
        runId: Long,
        traceId: String,
        streamingOutputEnabled: Boolean,
        recordedSegmentCount: AtomicInteger,
        analyzedSegmentCount: AtomicInteger,
        recordedDurationSeconds: AtomicInteger,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ): SegmentExecutionResult {
        var segment = recordedSegment.segment
        try {
            onStatus(uploadStatus(recordedSegment, task, segmentCount, runId, streamingOutputEnabled, recordedSegmentCount, analyzedSegmentCount, recordedDurationSeconds))
            segment = segment.copy(status = VideoRunStatus.Uploading, updatedAt = System.currentTimeMillis())
            segmentRunDao.upsert(segment)

            var analysisFile = recordedSegment.file
            var isMergedSegmentVideo = recordedSegment.hasAudio && recordedSegment.file.name.endsWith("_merged.mp4")
            if (!isMergedSegmentVideo && recordedSegment.audioAssetPath != null) {
                val audioFile = File(recordedSegment.audioAssetPath)
                if (audioFile.exists() && audioFile.length() > 0L) {
                    delay(500L)
                    val mergedFile = File(recordedSegment.file.parentFile, recordedSegment.file.nameWithoutExtension + "_merged.mp4")
                    val merged = runCatching {
                        withContext(Dispatchers.IO) {
                            AudioSegmentSlicer().mergeVideoAndAudio(recordedSegment.file, audioFile, mergedFile)
                        }
                    }.onFailure { Log.e(CLASSROOM_PROCESSOR_TAG, "Classroom segment merge failed", it) }
                        .getOrDefault(false)
                    if (merged && mergedFile.exists() && mergedFile.length() > 0L) {
                        remoteFileResolver.recordLocalFileBinding(
                            file = mergedFile,
                            runId = runId,
                            segmentRunId = segment.id,
                            assetKind = VideoRemoteAssetKind.MergedSegmentVideo,
                            mediaType = "video/mp4"
                        )
                        analysisFile = mergedFile
                        isMergedSegmentVideo = true
                    }
                }
            }

            val remoteFile = try {
                remoteFileResolver.resolveVideoFile(
                    file = analysisFile,
                    runId = runId,
                    segmentRunId = segment.id,
                    assetKind = if (isMergedSegmentVideo) VideoRemoteAssetKind.MergedSegmentVideo else VideoRemoteAssetKind.SegmentVideo,
                    samplingFps = task.plannedSamplingFps
                )
            } catch (error: Exception) {
                throw VideoProcessException(
                    stage = VideoRunStatus.Uploading,
                    userMessage = "Failed to upload classroom segment: ${error.toUserMessage("Check network access or API permissions.")}",
                    cause = error
                )
            }

            segment = segment.copy(
                status = VideoRunStatus.Preprocessing,
                arkFileId = remoteFile.fileId,
                updatedAt = System.currentTimeMillis()
            )
            segmentRunDao.upsert(segment)
            onStatus(preprocessingStatus(recordedSegment, task, segmentCount, runId, streamingOutputEnabled, recordedSegmentCount, analyzedSegmentCount, recordedDurationSeconds))
            waitForFileReady(remoteFile.fileId)

            val remoteAudioFileId: String?
            val audioResolutionFailed: Boolean
            val analysisInputMode: SegmentAnalysisInputMode
            if (isMergedSegmentVideo) {
                remoteAudioFileId = null
                audioResolutionFailed = false
                analysisInputMode = SegmentAnalysisInputMode.MergedSegmentVideo
            } else {
                val audioResult = runCatching {
                    recordedSegment.audioAssetPath
                        ?.takeIf(String::isNotBlank)
                        ?.let(::File)
                        ?.takeIf { it.exists() && it.length() > 0L }
                        ?.let { audioFile ->
                            val remoteAudio = remoteFileResolver.resolveAudioFile(
                                file = audioFile,
                                runId = runId,
                                segmentRunId = segment.id,
                                assetKind = VideoRemoteAssetKind.SegmentAudio
                            )
                            waitForFileReady(remoteAudio.fileId)
                            remoteAudio.fileId
                        }
                }
                remoteAudioFileId = audioResult.getOrNull()
                audioResolutionFailed = audioResult.isFailure
                analysisInputMode = if (remoteAudioFileId != null) {
                    SegmentAnalysisInputMode.SeparateVideoAudio
                } else {
                    SegmentAnalysisInputMode.VideoOnly
                }
            }

            onStatus(analysisStatus(recordedSegment, task, segmentCount, runId, streamingOutputEnabled, recordedSegmentCount, analyzedSegmentCount, recordedDurationSeconds))
            val traceInputMode = when (analysisInputMode) {
                SegmentAnalysisInputMode.MergedSegmentVideo -> "merged_video"
                SegmentAnalysisInputMode.SeparateVideoAudio -> "separate_video_audio"
                SegmentAnalysisInputMode.VideoOnly -> "video_only"
            }
            val analysisResult = try {
                segmentAnalyzer.analyze(
                    fileId = remoteFile.fileId,
                    audioFileId = remoteAudioFileId,
                    isMergedInput = isMergedSegmentVideo,
                    task = task,
                    segmentNumber = recordedSegment.segmentNumber,
                    segmentCount = segmentCount,
                    startOffsetSeconds = recordedSegment.startOffsetSeconds,
                    durationSeconds = recordedSegment.durationSeconds,
                    traceId = traceId,
                    runId = runId,
                    inputMode = traceInputMode
                )
            } catch (error: Exception) {
                throw VideoProcessException(
                    stage = VideoRunStatus.Analyzing,
                    userMessage = "Failed to analyze classroom segment: ${error.toUserMessage("Check the model configuration or output.")}",
                    cause = error
                )
            }

            segment = segment.copy(
                status = VideoRunStatus.Completed,
                summary = analysisResult.summary,
                conclusion = analysisResult.conclusion,
                evidenceJson = analysisResult.evidenceJson,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            segmentRunDao.upsert(segment)
            return SegmentExecutionResult(
                segment = segment,
                analysisResult = analysisResult,
                mergedAnalysisFilePath = if (isMergedSegmentVideo) analysisFile.absolutePath else null,
                hasAudio = recordedSegment.hasAudio,
                audioEnhancementInfo = recordedSegment.audioEnhancementInfo,
                audioAssetPath = recordedSegment.audioAssetPath,
                audioDiagnosticsJson = recordedSegment.audioDiagnosticsJson,
                analysisInputMode = analysisInputMode,
                audioResolutionFailed = audioResolutionFailed,
                coverageLimitation = when {
                    audioResolutionFailed -> "Audio preprocessing failed; classroom segment used video-only fallback."
                    analysisInputMode == SegmentAnalysisInputMode.VideoOnly && recordedSegment.hasAudio ->
                        "Audio available locally but could not be resolved remotely."
                    analysisInputMode == SegmentAnalysisInputMode.VideoOnly -> "No audio track."
                    analysisResult.conclusion.isNotBlank() -> analysisResult.conclusion
                    else -> null
                }
            )
        } catch (cancelled: CancellationException) {
            persistSegmentCancelled(segment)
            throw cancelled
        } catch (error: Exception) {
            persistSegmentFailure(segment, error)
            throw error
        }
    }

    suspend fun markRecordedSegmentAnalysisFailed(
        recordedSegment: RecordedSegment,
        error: Throwable
    ): SegmentExecutionResult {
        val message = error.toUserMessage("Classroom segment analysis failed.")
        val latestSegment = segmentRunDao.getById(recordedSegment.segment.id) ?: recordedSegment.segment
        val failedSegment = latestSegment.copy(
            status = VideoRunStatus.Failed,
            errorMessage = message,
            updatedAt = System.currentTimeMillis()
        )
        segmentRunDao.upsert(failedSegment)
        return SegmentExecutionResult(
            segment = failedSegment,
            analysisResult = VideoAnalysisResult(
                summary = "",
                conclusion = "课堂分片分析失败：$message",
                timelineEvents = emptyList(),
                rawResponse = message
            ),
            hasAudio = recordedSegment.hasAudio,
            audioEnhancementInfo = recordedSegment.audioEnhancementInfo,
            audioAssetPath = recordedSegment.audioAssetPath,
            audioDiagnosticsJson = recordedSegment.audioDiagnosticsJson,
            coverageLimitation = message
        )
    }

    private fun uploadStatus(
        recordedSegment: RecordedSegment,
        task: VideoProcessTaskDraft,
        segmentCount: Int,
        runId: Long,
        streamingOutputEnabled: Boolean,
        recordedSegmentCount: AtomicInteger,
        analyzedSegmentCount: AtomicInteger,
        recordedDurationSeconds: AtomicInteger
    ) = baseStatus(
        stage = VideoRunStatus.Uploading,
        message = "Uploading classroom segment ${recordedSegment.segmentNumber}/$segmentCount",
        recordedSegment = recordedSegment,
        task = task,
        segmentCount = segmentCount,
        runId = runId,
        streamingOutputEnabled = streamingOutputEnabled,
        recordedSegmentCount = recordedSegmentCount,
        analyzedSegmentCount = analyzedSegmentCount,
        recordedDurationSeconds = recordedDurationSeconds
    )

    private fun preprocessingStatus(
        recordedSegment: RecordedSegment,
        task: VideoProcessTaskDraft,
        segmentCount: Int,
        runId: Long,
        streamingOutputEnabled: Boolean,
        recordedSegmentCount: AtomicInteger,
        analyzedSegmentCount: AtomicInteger,
        recordedDurationSeconds: AtomicInteger
    ) = baseStatus(
        stage = VideoRunStatus.Preprocessing,
        message = "Waiting for classroom segment ${recordedSegment.segmentNumber}/$segmentCount preprocessing",
        recordedSegment = recordedSegment,
        task = task,
        segmentCount = segmentCount,
        runId = runId,
        streamingOutputEnabled = streamingOutputEnabled,
        recordedSegmentCount = recordedSegmentCount,
        analyzedSegmentCount = analyzedSegmentCount,
        recordedDurationSeconds = recordedDurationSeconds
    )

    private fun analysisStatus(
        recordedSegment: RecordedSegment,
        task: VideoProcessTaskDraft,
        segmentCount: Int,
        runId: Long,
        streamingOutputEnabled: Boolean,
        recordedSegmentCount: AtomicInteger,
        analyzedSegmentCount: AtomicInteger,
        recordedDurationSeconds: AtomicInteger
    ) = baseStatus(
        stage = VideoRunStatus.Analyzing,
        message = "Analyzing classroom facts ${recordedSegment.segmentNumber}/$segmentCount",
        recordedSegment = recordedSegment,
        task = task,
        segmentCount = segmentCount,
        runId = runId,
        streamingOutputEnabled = streamingOutputEnabled,
        recordedSegmentCount = recordedSegmentCount,
        analyzedSegmentCount = analyzedSegmentCount,
        recordedDurationSeconds = recordedDurationSeconds
    )

    private fun baseStatus(
        stage: VideoRunStatus,
        message: String,
        recordedSegment: RecordedSegment,
        task: VideoProcessTaskDraft,
        segmentCount: Int,
        runId: Long,
        streamingOutputEnabled: Boolean,
        recordedSegmentCount: AtomicInteger,
        analyzedSegmentCount: AtomicInteger,
        recordedDurationSeconds: AtomicInteger
    ): VideoExecutionStatusUpdate = VideoExecutionStatusUpdate(
        stage = stage,
        runId = runId,
        segmentIndex = recordedSegment.segmentNumber,
        segmentCount = segmentCount,
        message = message,
        templateLabel = task.templateLabel,
        segmentDurationSeconds = task.plannedSegmentDurationSeconds,
        captureIntervalSeconds = task.captureIntervalSeconds,
        streamingEnabled = streamingOutputEnabled,
        streamingBuffer = if (stage == VideoRunStatus.Analyzing) "" else null,
        isStreamingActive = stage == VideoRunStatus.Analyzing && streamingOutputEnabled,
        isRecordingActive = recordedSegmentCount.get() < segmentCount,
        isAnalysisActive = true,
        recordedSegmentCount = recordedSegmentCount.get(),
        analyzedSegmentCount = analyzedSegmentCount.get(),
        pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get()).coerceAtLeast(0),
        recordedDurationSeconds = recordedDurationSeconds.get(),
        remainingDurationSeconds = (task.plannedDurationSeconds - recordedSegment.startOffsetSeconds).coerceAtLeast(0),
        activeStreamingSegmentIndex = recordedSegment.segmentNumber
    )

    private suspend fun waitForFileReady(fileId: String) {
        repeat(FILE_POLL_ATTEMPTS) { attempt ->
            val file = retryRemoteCall { apiService.getFile("Bearer $apiKey", fileId) }
            val status = file.status?.lowercase()
            when {
                status in setOf("active", "processed", "ready", "succeeded") -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status ?: "ready")
                    return
                }
                status == "failed" -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status, "preprocessing failed")
                    error("Ark file preprocessing failed for file $fileId.")
                }
                attempt == FILE_POLL_ATTEMPTS - 1 -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status ?: "unknown", "preprocessing timed out")
                    error("Ark file preprocessing timed out (last status: $status) for file $fileId.")
                }
                else -> delay(FILE_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun <T> retryRemoteCall(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(REMOTE_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException || !error.isRetryableRemoteFailure() || attempt == REMOTE_RETRY_ATTEMPTS - 1) {
                    throw error
                }
                lastError = error
                delay(REMOTE_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Remote call failed.")
    }

    private fun Throwable.isRetryableRemoteFailure(): Boolean {
        val text = message.orEmpty()
        return this is IOException ||
            text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true)
    }

    private suspend fun persistSegmentFailure(segment: VideoSegmentRun, error: Throwable) {
        runCatching {
            segmentRunDao.upsert(
                segment.copy(
                    status = VideoRunStatus.Failed,
                    errorMessage = error.toUserMessage("Classroom execution failed."),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun persistSegmentCancelled(segment: VideoSegmentRun) {
        runCatching {
            segmentRunDao.upsert(
                segment.copy(
                    status = VideoRunStatus.Cancelled,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private companion object {
        private const val FILE_POLL_ATTEMPTS = 150
        private const val FILE_POLL_INTERVAL_MS = 2_000L
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}

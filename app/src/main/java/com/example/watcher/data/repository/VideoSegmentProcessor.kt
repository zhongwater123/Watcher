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
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Watcher.Video.Processor"

/**
 * Coordinates the lifecycle of a single recorded segment:
 * upload → wait for preprocessing → analyze via model → persist result.
 *
 * Delegates recording to [VideoSegmentRecorder], model calls to [VideoSegmentAnalyzer],
 * media assembly to [VideoMediaAssembler], and summarization to [VideoReportSummarizer].
 */
internal class VideoSegmentProcessor(
    private val apiService: DoubaoApiService,
    private val segmentRunDao: VideoSegmentRunDao,
    private val remoteFileResolver: VideoRemoteFileResolver,
    private val segmentAnalyzer: VideoSegmentAnalyzer,
    private val apiKey: String
) {

    fun requireApiKey() {
        check(apiKey.isNotBlank()) {
            "API_KEY is missing. Set it in local.properties first."
        }
    }

    suspend fun analyzeRecordedSegment(
        recordedSegment: RecordedSegment,
        task: VideoProcessTaskDraft,
        segmentCount: Int,
        runId: Long,
        streamingOutputEnabled: Boolean,
        recordedSegmentCount: AtomicInteger,
        analyzedSegmentCount: AtomicInteger,
        recordedDurationSeconds: AtomicInteger,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ): SegmentExecutionResult {
        var segment = recordedSegment.segment

        try {
            // --- Upload phase ---
            onStatus(
                VideoExecutionStatusUpdate(
                    stage = VideoRunStatus.Uploading,
                    runId = runId,
                    segmentIndex = recordedSegment.segmentNumber,
                    segmentCount = segmentCount,
                    message = "Uploading segment ${recordedSegment.segmentNumber}/$segmentCount",
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    streamingEnabled = streamingOutputEnabled,
                    isRecordingActive = recordedSegmentCount.get() < segmentCount,
                    isAnalysisActive = true,
                    recordedSegmentCount = recordedSegmentCount.get(),
                    analyzedSegmentCount = analyzedSegmentCount.get(),
                    pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get())
                        .coerceAtLeast(0),
                    recordedDurationSeconds = recordedDurationSeconds.get(),
                    remainingDurationSeconds = (task.plannedDurationSeconds - recordedSegment.startOffsetSeconds)
                        .coerceAtLeast(0),
                    activeStreamingSegmentIndex = recordedSegment.segmentNumber
                )
            )

            segment = segment.copy(
                status = VideoRunStatus.Uploading,
                updatedAt = System.currentTimeMillis()
            )
            segmentRunDao.upsert(segment)

            var isMergedSegmentVideo = recordedSegment.hasAudio &&
                recordedSegment.file.name.endsWith("_merged.mp4")

            // Fallback merge: if recording didn't produce a merged file but audio exists locally,
            // attempt on-the-fly merge before upload so analysis uses a single audio+video input.
            var analysisFile = recordedSegment.file
            if (!isMergedSegmentVideo && recordedSegment.audioAssetPath != null) {
                val audioFile = File(recordedSegment.audioAssetPath)
                if (audioFile.exists() && audioFile.length() > 0L) {
                    // Wait briefly for file I/O to settle after recording
                    delay(500L)
                    val videoFileReady = recordedSegment.file.exists() && recordedSegment.file.length() > 0L
                    Log.d(TAG, "Segment ${recordedSegment.segmentNumber} merge attempt: video=${recordedSegment.file.name}(${recordedSegment.file.length()}) audio=${audioFile.name}(${audioFile.length()}) videoReady=$videoFileReady")
                    val mergedFile = File(
                        recordedSegment.file.parentFile,
                        recordedSegment.file.nameWithoutExtension + "_merged.mp4"
                    )
                    val merged = runCatching {
                        AudioSegmentSlicer().mergeVideoAndAudio(recordedSegment.file, audioFile, mergedFile)
                    }.onFailure { error ->
                        Log.e(TAG, "Segment ${recordedSegment.segmentNumber} merge FAILED: ${error.message}", error)
                    }.getOrDefault(false)
                    if (merged && mergedFile.exists() && mergedFile.length() > 0L) {
                        Log.d(TAG, "Segment ${recordedSegment.segmentNumber} merge SUCCESS: ${mergedFile.name}(${mergedFile.length()})")
                        remoteFileResolver.recordLocalFileBinding(
                            file = mergedFile,
                            runId = runId,
                            segmentRunId = segment.id,
                            assetKind = VideoRemoteAssetKind.MergedSegmentVideo,
                            mediaType = "video/mp4"
                        )
                        analysisFile = mergedFile
                        isMergedSegmentVideo = true
                    } else {
                        Log.w(TAG, "Segment ${recordedSegment.segmentNumber} merge produced no valid file, using video-only. merged=$merged exists=${mergedFile.exists()} size=${mergedFile.length()}")
                    }
                } else {
                    Log.w(TAG, "Segment ${recordedSegment.segmentNumber} audio file missing or empty: path=${recordedSegment.audioAssetPath} exists=${audioFile.exists()} size=${audioFile.length()}")
                }
            }

            val effectiveAssetKind = if (isMergedSegmentVideo)
                VideoRemoteAssetKind.MergedSegmentVideo
            else
                VideoRemoteAssetKind.SegmentVideo

            Log.d(TAG, "Segment ${recordedSegment.segmentNumber} uploading file=${analysisFile.name} size=${analysisFile.length()} isMerged=$isMergedSegmentVideo kind=$effectiveAssetKind")

            val remoteFile = try {
                remoteFileResolver.resolveVideoFile(
                    file = analysisFile,
                    runId = runId,
                    segmentRunId = segment.id,
                    assetKind = effectiveAssetKind,
                    samplingFps = task.plannedSamplingFps
                )
            } catch (error: Exception) {
                throw VideoProcessException(
                    stage = VideoRunStatus.Uploading,
                    userMessage = "Failed to upload the video segment: ${
                        error.toUserMessage("Check network access or API permissions.")
                    }",
                    cause = error
                )
            }

            // --- Preprocessing phase ---
            Log.d(TAG, "Segment ${recordedSegment.segmentNumber} uploaded arkFileId=${remoteFile.fileId}")
            segment = segment.copy(
                status = VideoRunStatus.Preprocessing,
                arkFileId = remoteFile.fileId,
                updatedAt = System.currentTimeMillis()
            )
            segmentRunDao.upsert(segment)

            onStatus(
                VideoExecutionStatusUpdate(
                    stage = VideoRunStatus.Preprocessing,
                    runId = runId,
                    segmentIndex = recordedSegment.segmentNumber,
                    segmentCount = segmentCount,
                    message = "Waiting for segment ${recordedSegment.segmentNumber}/$segmentCount preprocessing",
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    streamingEnabled = streamingOutputEnabled,
                    isRecordingActive = recordedSegmentCount.get() < segmentCount,
                    isAnalysisActive = true,
                    recordedSegmentCount = recordedSegmentCount.get(),
                    analyzedSegmentCount = analyzedSegmentCount.get(),
                    pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get())
                        .coerceAtLeast(0),
                    recordedDurationSeconds = recordedDurationSeconds.get(),
                    remainingDurationSeconds = (task.plannedDurationSeconds - recordedSegment.startOffsetSeconds)
                        .coerceAtLeast(0),
                    activeStreamingSegmentIndex = recordedSegment.segmentNumber
                )
            )

            try {
                waitForFileReady(remoteFile.fileId)
            } catch (error: Exception) {
                throw VideoProcessException(
                    stage = VideoRunStatus.Preprocessing,
                    userMessage = "Failed to preprocess the video segment: ${
                        error.toUserMessage("Check the remote file status.")
                    }",
                    cause = error
                )
            }

            // --- Audio resolution ---
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
                analysisInputMode = if (remoteAudioFileId != null)
                    SegmentAnalysisInputMode.SeparateVideoAudio
                else
                    SegmentAnalysisInputMode.VideoOnly
            }

            // --- Analysis phase (delegated to VideoSegmentAnalyzer) ---
            onStatus(
                VideoExecutionStatusUpdate(
                    stage = VideoRunStatus.Analyzing,
                    runId = runId,
                    segmentIndex = recordedSegment.segmentNumber,
                    segmentCount = segmentCount,
                    message = "Analyzing segment ${recordedSegment.segmentNumber}/$segmentCount",
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    streamingEnabled = streamingOutputEnabled,
                    streamingBuffer = "",
                    isStreamingActive = streamingOutputEnabled,
                    isRecordingActive = recordedSegmentCount.get() < segmentCount,
                    isAnalysisActive = true,
                    recordedSegmentCount = recordedSegmentCount.get(),
                    analyzedSegmentCount = analyzedSegmentCount.get(),
                    pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get())
                        .coerceAtLeast(0),
                    recordedDurationSeconds = recordedDurationSeconds.get(),
                    remainingDurationSeconds = (task.plannedDurationSeconds - recordedSegment.startOffsetSeconds)
                        .coerceAtLeast(0),
                    activeStreamingSegmentIndex = recordedSegment.segmentNumber
                )
            )
            Log.d(TAG, "Segment ${recordedSegment.segmentNumber} analysis starting merged=$isMergedSegmentVideo audioId=$remoteAudioFileId")
            val analysisResult = try {
                segmentAnalyzer.analyze(
                    fileId = remoteFile.fileId,
                    audioFileId = remoteAudioFileId,
                    isMergedInput = isMergedSegmentVideo,
                    task = task,
                    segmentNumber = recordedSegment.segmentNumber,
                    segmentCount = segmentCount
                )
            } catch (error: Exception) {
                throw VideoProcessException(
                    stage = VideoRunStatus.Analyzing,
                    userMessage = "Failed to analyze the video segment: ${error.toUserMessage("Check the model configuration or output.")}",
                    cause = error
                )
            }

            Log.d(TAG, "Segment ${recordedSegment.segmentNumber} analysis complete summary=${analysisResult.summary.take(60)} evidenceLen=${analysisResult.evidenceJson.length}")
            // --- Persist result ---
            try {
                segment = segment.copy(
                    status = VideoRunStatus.Completed,
                    summary = analysisResult.summary,
                    conclusion = analysisResult.conclusion,
                    evidenceJson = analysisResult.evidenceJson,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
                segmentRunDao.upsert(segment)
            } catch (error: Exception) {
                throw VideoProcessException(
                    stage = VideoRunStatus.Completed,
                    userMessage = "Failed to save the segment result: ${error.toUserMessage("Check the local database state.")}",
                    cause = error
                )
            }

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
                    audioResolutionFailed -> "Audio preprocessing failed; video-only analysis."
                    analysisInputMode == SegmentAnalysisInputMode.VideoOnly && recordedSegment.hasAudio ->
                        "Audio available locally but could not be resolved remotely."
                    analysisInputMode == SegmentAnalysisInputMode.VideoOnly -> "No audio track."
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
        val message = error.toUserMessage("Segment analysis failed.")
        val latestSegment = segmentRunDao.getById(recordedSegment.segment.id)
            ?: recordedSegment.segment
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
                conclusion = "",
                timelineEvents = emptyList(),
                rawResponse = message
            ),
            hasAudio = recordedSegment.hasAudio,
            audioEnhancementInfo = recordedSegment.audioEnhancementInfo,
            audioAssetPath = recordedSegment.audioAssetPath,
            audioDiagnosticsJson = recordedSegment.audioDiagnosticsJson
        )
    }

    // region File polling

    private suspend fun waitForFileReady(fileId: String) {
        repeat(FILE_POLL_ATTEMPTS) { attempt ->
            val file = retryRemoteCall { apiService.getFile(bearerToken(), fileId) }
            val status = file.status?.lowercase()
            when {
                status == "active" || status == "processed" || status == "ready" || status == "succeeded" -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status)
                    return
                }
                status == "failed" -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status, "preprocessing failed")
                    error("Ark file preprocessing failed for file $fileId.")
                }
                else -> {
                    delay(FILE_POLL_INTERVAL_MS)
                    if (attempt == FILE_POLL_ATTEMPTS - 1) {
                        remoteFileResolver.recordRemoteFileStatus(
                            fileId = fileId,
                            status = status ?: "unknown",
                            message = "preprocessing timed out"
                        )
                        error("Ark file preprocessing timed out (last status: $status) for file $fileId.")
                    }
                }
            }
        }
    }

    // endregion

    // region Utilities

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

    private fun bearerToken(): String = "Bearer $apiKey"

    private suspend fun persistSegmentFailure(segment: VideoSegmentRun, error: Throwable) {
        runCatching {
            segmentRunDao.upsert(
                segment.copy(
                    status = VideoRunStatus.Failed,
                    errorMessage = error.toUserMessage("Execution failed."),
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

    // endregion

    private companion object {
        private const val FILE_POLL_ATTEMPTS = 150
        private const val FILE_POLL_INTERVAL_MS = 2_000L
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}

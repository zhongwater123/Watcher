package com.example.watcher.data.repository

import android.graphics.Bitmap
import com.example.watcher.data.local.VideoAudioAssetDao
import com.example.watcher.data.local.VideoSegmentRunDao
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.VideoSegmentRun
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles the recording lifecycle of a single video segment:
 * video capture, audio asset construction, local file binding.
 */
internal class VideoSegmentRecorder(
    private val recorder: MjpegVideoRecorder,
    private val audioAssetBuilder: VideoAudioAssetBuilder,
    private val remoteFileResolver: VideoRemoteFileResolver,
    private val segmentRunDao: VideoSegmentRunDao,
    private val audioAssetDao: VideoAudioAssetDao
) {

    suspend fun recordSegmentClip(
        runId: Long,
        task: VideoProcessTaskDraft,
        segmentNumber: Int,
        segmentCount: Int,
        actualDuration: Int,
        outputRoot: File,
        latestFrameProvider: () -> Bitmap?,
        startOffsetSeconds: Int,
        streamingOutputEnabled: Boolean,
        recordedSegmentCount: AtomicInteger,
        analyzedSegmentCount: AtomicInteger,
        recordedDurationSeconds: AtomicInteger,
        segmentWindowStartedAt: Long = System.currentTimeMillis(),
        mediaClockStartedAt: Long? = null,
        continuousAudioFile: File? = null,
        continuousAudioStartedAt: Long? = null,
        audioSlicer: AudioSegmentSlicer? = null,
        shouldStopRequested: () -> Boolean = { false },
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ): RecordedSegment {
        var segment = VideoSegmentRun(
            runId = runId,
            segmentIndex = segmentNumber,
            status = VideoRunStatus.Recording,
            durationSeconds = actualDuration
        )
        val segmentId = segmentRunDao.upsert(segment)
        segment = segment.copy(id = segmentId)

        try {
            onStatus(
                VideoExecutionStatusUpdate(
                    stage = VideoRunStatus.Recording,
                    runId = runId,
                    segmentIndex = segmentNumber,
                    segmentCount = segmentCount,
                    message = "Recording segment $segmentNumber/$segmentCount",
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    streamingEnabled = streamingOutputEnabled,
                    isRecordingActive = true,
                    isAnalysisActive = recordedSegmentCount.get() > analyzedSegmentCount.get(),
                    recordedSegmentCount = recordedSegmentCount.get(),
                    analyzedSegmentCount = analyzedSegmentCount.get(),
                    pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get())
                        .coerceAtLeast(0),
                    recordedDurationSeconds = recordedDurationSeconds.get(),
                    remainingDurationSeconds = (task.plannedDurationSeconds - startOffsetSeconds)
                        .coerceAtLeast(0),
                    recordingSegmentIndex = segmentNumber
                )
            )

            val useContinuousAudio = continuousAudioFile != null && continuousAudioStartedAt != null && audioSlicer != null
            val videoOnlyFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}.mp4")
            val segmentAudioFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}_audio.m4a")
            val segmentAudioRecorder = if (!useContinuousAudio) ContinuousAudioRecorder() else null
            val wallClockStartTime = segmentWindowStartedAt
            val audioStarted = segmentAudioRecorder?.start(segmentAudioFile) == true
            var audioResult: ContinuousAudioResult? = null
            val recording = try {
                recorder.recordSegment(
                    outputFile = videoOnlyFile,
                    durationSeconds = actualDuration,
                    frameProvider = latestFrameProvider,
                    audioEnabled = false,
                    shouldStopRequested = shouldStopRequested
                )
            } finally {
                if (audioStarted) {
                    audioResult = segmentAudioRecorder?.stop()
                } else {
                    segmentAudioRecorder?.release()
                }
            }
            val wallClockEndTime = System.currentTimeMillis()
            val mediaStartMs = mediaClockStartedAt?.let { (wallClockStartTime - it).coerceAtLeast(0L) }
            val mediaEndMs = mediaClockStartedAt?.let { (wallClockEndTime - it).coerceAtLeast(0L) }

            val finalFile: File
            var audioAssetPath: String? = null
            var audioDiagnosticsJson = ""
            var hasAudio = recording.hasAudio

            if (useContinuousAudio) {
                val audioStartMs = (wallClockStartTime - continuousAudioStartedAt!!).coerceAtLeast(0L)
                val audioEndMs = (wallClockEndTime - continuousAudioStartedAt).coerceAtLeast(audioStartMs + 1000L)
                val audioSliceFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}_audio.m4a")
                val sliced = audioSlicer!!.sliceAudio(continuousAudioFile!!, audioStartMs, audioEndMs, audioSliceFile)

                if (sliced) {
                    audioAssetPath = audioSliceFile.absolutePath
                    val mergedFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}_merged.mp4")
                    val merged = audioSlicer.mergeVideoAndAudio(videoOnlyFile, audioSliceFile, mergedFile)
                    if (merged) {
                        remoteFileResolver.recordLocalFileBinding(
                            file = mergedFile,
                            runId = runId,
                            segmentRunId = segment.id,
                            assetKind = VideoRemoteAssetKind.MergedSegmentVideo,
                            mediaType = "video/mp4"
                        )
                        finalFile = mergedFile
                        hasAudio = true
                    } else {
                        finalFile = videoOnlyFile
                    }
                } else {
                    finalFile = videoOnlyFile
                }
            } else {
                finalFile = videoOnlyFile
                if (audioResult?.hasAudio == true) {
                    val audioAsset = audioAssetBuilder.buildSegmentAudioAssetFromFile(
                        runId = runId,
                        segment = segment,
                        audioFile = audioResult.file,
                        expectedDurationMs = recording.durationMs.takeIf { it > 0L } ?: actualDuration * 1_000L,
                        diagnosticsExtras = mapOf(
                            "wallClockStartMs" to wallClockStartTime,
                            "wallClockEndMs" to wallClockEndTime,
                            "interrupted" to recording.interrupted,
                            "audioStartedAtMs" to audioResult.startedAtMs
                        )
                    )
                    audioAssetDao.upsert(audioAsset)
                    audioAssetPath = audioAsset.localFilePath
                    audioDiagnosticsJson = audioAsset.diagnosticsJson
                    hasAudio = true
                } else {
                    audioAssetPath = audioResult?.file?.absolutePath
                    hasAudio = false
                }
            }

            remoteFileResolver.recordLocalFileBinding(
                file = finalFile,
                runId = runId,
                segmentRunId = segment.id,
                assetKind = if (useContinuousAudio && finalFile.name.endsWith("_merged.mp4")) {
                    VideoRemoteAssetKind.MergedSegmentVideo
                } else {
                    VideoRemoteAssetKind.SegmentVideo
                },
                mediaType = "video/mp4"
            )
            audioAssetPath
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf { it.exists() && it.length() > 0L }
                ?.let { audioFile ->
                    remoteFileResolver.recordLocalFileBinding(
                        file = audioFile,
                        runId = runId,
                        segmentRunId = segment.id,
                        assetKind = VideoRemoteAssetKind.SegmentAudio,
                        mediaType = "audio/mp4"
                    )
                }

            segment = segment.copy(
                durationSeconds = recording.durationSeconds,
                durationMs = recording.durationMs,
                localFilePath = finalFile.absolutePath,
                mediaStartMs = mediaStartMs,
                mediaEndMs = mediaEndMs,
                wallClockStartMs = wallClockStartTime,
                wallClockEndMs = wallClockEndTime,
                interrupted = recording.interrupted,
                updatedAt = System.currentTimeMillis()
            )
            segmentRunDao.upsert(segment)

            return RecordedSegment(
                segment = segment,
                file = finalFile,
                segmentNumber = segmentNumber,
                durationSeconds = recording.durationSeconds,
                startOffsetSeconds = startOffsetSeconds,
                wallClockStartTime = wallClockStartTime,
                wallClockEndTime = wallClockEndTime,
                mediaStartMs = mediaStartMs,
                mediaEndMs = mediaEndMs,
                hasAudio = hasAudio,
                audioEnhancementInfo = audioResult?.enhancementInfo.orEmpty(),
                audioAssetPath = audioAssetPath,
                audioDiagnosticsJson = audioDiagnosticsJson,
                interrupted = recording.interrupted
            )
        } catch (cancelled: CancellationException) {
            persistSegmentCancelled(segment)
            throw cancelled
        } catch (error: Exception) {
            persistSegmentFailure(segment, error)
            throw VideoProcessException(
                stage = VideoRunStatus.Recording,
                userMessage = "Failed to record the video segment: ${error.toUserMessage("Check the live stream state.")}",
                cause = error
            )
        }
    }

    private suspend fun persistSegmentFailure(segment: VideoSegmentRun, error: Throwable) {
        runCatching {
            segmentRunDao.upsert(
                segment.copy(
                    status = VideoRunStatus.Failed,
                    errorMessage = error.toUserMessage("Segment recording failed."),
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
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }
}

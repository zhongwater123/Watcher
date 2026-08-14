package com.example.watcher.data.repository

import android.graphics.Bitmap
import com.example.watcher.data.local.VideoAudioAssetDao
import com.example.watcher.data.local.VideoSegmentRunDao
import com.example.watcher.data.model.ClassroomRecordingInput
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.VideoSegmentRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        recordingInput: ClassroomRecordingInput = ClassroomRecordingInput.LiveCamera,
        suppressSegmentAudioRecorder: Boolean = false,
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
            val testVideoInput = recordingInput as? ClassroomRecordingInput.TestVideo
            val videoOnlyFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}.mp4")
            val segmentAudioFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}_audio.m4a")
            val segmentAudioRecorder = if (!useContinuousAudio && testVideoInput == null && !suppressSegmentAudioRecorder) {
                ContinuousAudioRecorder()
            } else {
                null
            }
            val wallClockStartTime = segmentWindowStartedAt
            val audioStarted = segmentAudioRecorder?.start(segmentAudioFile) == true
            var audioResult: ContinuousAudioResult? = null
            val testFrameProvider = testVideoInput?.let {
                TestVideoFrameProvider(
                    videoFile = File(it.localPath),
                    startOffsetMs = startOffsetSeconds * 1_000L,
                    wallClockStartedAtMs = wallClockStartTime
                )
            }
            val frameProviderForRecording: () -> Bitmap? = testFrameProvider?.let { provider ->
                { provider.currentFrame() }
            } ?: latestFrameProvider
            val recording = try {
                recorder.recordSegment(
                    outputFile = videoOnlyFile,
                    durationSeconds = actualDuration,
                    frameProvider = frameProviderForRecording,
                    audioEnabled = false,
                    shouldStopRequested = shouldStopRequested
                )
            } finally {
                testFrameProvider?.close()
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

            if (testVideoInput != null) {
                val audioStartMs = startOffsetSeconds * 1_000L
                val effectiveRecordedDurationMs = recording.durationMs
                    .takeIf { it > 0L }
                    ?: (wallClockEndTime - wallClockStartTime).coerceAtLeast(1_000L)
                val audioEndMs = (audioStartMs + effectiveRecordedDurationMs)
                    .coerceAtLeast(audioStartMs + 1_000L)
                val slicer = AudioSegmentSlicer()
                val sourceSegmentFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}_original_merged.mp4")
                val sourceSegmentSliced = withContext(Dispatchers.IO) {
                    slicer.sliceMedia(
                        sourceFile = File(testVideoInput.localPath),
                        startMs = audioStartMs,
                        endMs = audioEndMs,
                        outputFile = sourceSegmentFile
                    )
                }
                val sliced = withContext(Dispatchers.IO) {
                    slicer.sliceAudio(
                        sourceFile = File(testVideoInput.localPath),
                        startMs = audioStartMs,
                        endMs = audioEndMs,
                        outputFile = segmentAudioFile
                    )
                }
                audioDiagnosticsJson = "source=test_video, displayName=${testVideoInput.displayName}, startMs=$audioStartMs, endMs=$audioEndMs, originalMediaSlice=$sourceSegmentSliced"
                if (sourceSegmentSliced && sourceSegmentFile.exists() && sourceSegmentFile.length() > 0L) {
                    finalFile = sourceSegmentFile
                    hasAudio = true
                    if (sliced) {
                        audioAssetPath = segmentAudioFile.absolutePath
                    }
                } else if (sliced) {
                    audioAssetPath = segmentAudioFile.absolutePath
                    val mergedFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}_merged.mp4")
                    val merged = withContext(Dispatchers.IO) {
                        slicer.mergeVideoAndAudio(videoOnlyFile, segmentAudioFile, mergedFile)
                    }
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
                    hasAudio = false
                }
            } else if (useContinuousAudio) {
                val audioStartMs = (wallClockStartTime - continuousAudioStartedAt!!).coerceAtLeast(0L)
                val audioEndMs = (wallClockEndTime - continuousAudioStartedAt).coerceAtLeast(audioStartMs + 1000L)
                val audioSliceFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}_audio.m4a")
                val slicer = audioSlicer!!
                val sliced = withContext(Dispatchers.IO) {
                    slicer.sliceAudio(continuousAudioFile!!, audioStartMs, audioEndMs, audioSliceFile)
                }

                if (sliced) {
                    audioAssetPath = audioSliceFile.absolutePath
                    val mergedFile = File(outputRoot, "video_runs/run_${runId}_segment_${segmentNumber}_merged.mp4")
                    val merged = withContext(Dispatchers.IO) {
                        slicer.mergeVideoAndAudio(videoOnlyFile, audioSliceFile, mergedFile)
                    }
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
                    val audioAsset = withContext(Dispatchers.IO) {
                        audioAssetBuilder.buildSegmentAudioAssetFromFile(
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
                    }
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
                assetKind = if (finalFile.name.endsWith("_merged.mp4")) {
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

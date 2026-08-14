package com.example.watcher.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.watcher.data.local.TimelineEventDao
import com.example.watcher.data.local.VideoProcessRunDao
import com.example.watcher.data.local.VideoProcessTaskDao
import com.example.watcher.data.model.ClassroomRecordingInput
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.VideoSegmentFeedback
import com.example.watcher.data.model.VideoTimelineEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

private const val TAG = "Watcher.Video.Orchestrator"

internal class VideoExecutionOrchestrator(
    private val taskDao: VideoProcessTaskDao,
    private val runDao: VideoProcessRunDao,
    private val timelineEventDao: TimelineEventDao,
    private val saveTask: suspend (VideoProcessTaskDraft) -> VideoProcessTaskDraft,
    private val segmentProcessor: VideoSegmentProcessor,
    private val segmentRecorder: VideoSegmentRecorder,
    private val mediaAssembler: VideoMediaAssembler,
    private val reportSummarizer: VideoReportSummarizer,
    private val chunkAnalyzer: VideoEvidenceChunkAnalyzer,
    private val reportRefiner: VideoReportRefiner,
    private val audioOutlineProcessor: AudioOutlineProcessor,
    private val remoteFileResolver: VideoRemoteFileResolver,
    private val traceLogger: VideoAiTraceLogger
) {
    suspend fun executeTask(
        draft: VideoProcessTaskDraft,
        streamingOutputEnabled: Boolean,
        latestFrameProvider: () -> Bitmap?,
        outputRoot: File,
        recordingInput: ClassroomRecordingInput = ClassroomRecordingInput.LiveCamera,
        shouldStopRequested: () -> Boolean = { false },
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ): VideoExecutionResult = coroutineScope {
        segmentProcessor.requireApiKey()
        Log.i(TAG, "Task starting: title=${draft.title} segments=${draft.plannedSegmentCount} duration=${draft.plannedDurationSeconds}s")
        val task = saveTask(draft)
        val taskEntity = task.taskId?.let { taskDao.getTaskById(it) }
            ?: error("Failed to save the video task before execution.")
        val scheduledSegmentCount = task.plannedSegmentCount
        val now = System.currentTimeMillis()
        var run = VideoProcessRun(
            taskId = taskEntity.id,
            templateId = task.templateId,
            templateLabel = task.templateLabel,
            taskTitle = taskEntity.title,
            taskRequirement = taskEntity.userRequirement,
            recordingScenario = task.recordingScenario,
            speechInputEnabled = false,
            status = VideoRunStatus.Recording,
            recordingStartedAt = now,
            totalDurationSeconds = task.plannedDurationSeconds,
            segmentDurationSeconds = task.plannedSegmentDurationSeconds,
            captureIntervalSeconds = task.captureIntervalSeconds,
            segmentCount = scheduledSegmentCount,
            fullMediaVideoSource = recordingInput.sourceId
        )
        val runId = runDao.upsert(run)
        val traceId = traceLogger.newTraceId()
        run = run.copy(id = runId, aiTraceId = traceId)
        runDao.upsert(run)
        val orchestrationTrace = VideoAiTraceContext(
            traceId = traceId,
            runId = runId,
            taskId = taskEntity.id,
            node = "VideoExecutionOrchestrator",
            requestKind = "video_process_run"
        )
        traceLogger.beginNode(
            orchestrationTrace,
            aiTracePayload(
                "taskTitle" to taskEntity.title,
                "recordingScenario" to task.recordingScenario,
                "recordingInput" to recordingInput.sourceId,
                "plannedSegmentCount" to scheduledSegmentCount,
                "plannedDurationSeconds" to task.plannedDurationSeconds
            )
        )

        taskDao.upsert(
            taskEntity.copy(
                updatedAt = now,
                lastUsedAt = now,
                runCount = taskEntity.runCount + 1
            )
        )

        onStatus(
            VideoExecutionStatusUpdate(
                stage = VideoRunStatus.Recording,
                runId = runId,
                segmentIndex = 0,
                segmentCount = scheduledSegmentCount,
                message = "Preparing video analysis",
                templateLabel = task.templateLabel,
                segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                captureIntervalSeconds = task.captureIntervalSeconds,
                streamingEnabled = streamingOutputEnabled,
                streamingBuffer = "",
                isRecordingActive = true,
                isAnalysisActive = false,
                recordedSegmentCount = 0,
                analyzedSegmentCount = 0,
                pendingSegmentCount = 0,
                recordedDurationSeconds = 0,
                remainingDurationSeconds = task.plannedDurationSeconds,
                nextCaptureInSeconds = 0,
                recordingSegmentIndex = 1
            )
        )

        val pipelineStages = mutableListOf<String>()
        val finalMediaSource = if (recordingInput is ClassroomRecordingInput.TestVideo) {
            recordingInput.sourceId
        } else {
            "split_audio_video_segments"
        }
        fun recordStage(stage: String) {
            pipelineStages.add("${System.currentTimeMillis()}:$stage")
        }

        val recordedSegmentCount = AtomicInteger(0)
        val analyzedSegmentCount = AtomicInteger(0)
        val recordedDurationSeconds = AtomicInteger(0)
        val nextCaptureOffsetSeconds = AtomicInteger(0)
        val recordedSegments = Channel<RecordedSegment>(Channel.UNLIMITED)
        val partialTimelineEvents = CopyOnWriteArrayList<VideoTimelineEvent>()
        val segmentFeedbacks = CopyOnWriteArrayList<VideoSegmentFeedback>()
        val segmentAudioResults = CopyOnWriteArrayList<Boolean>()
        val analyzerResults = CopyOnWriteArrayList<SegmentExecutionResult>()

        val analyzer = async {
            val workers = List(ANALYSIS_PARALLELISM) {
                async {
                    for (recordedSegment in recordedSegments) {
                ensureActive()
                val result = runCatching {
                    segmentProcessor.analyzeRecordedSegment(
                        recordedSegment = recordedSegment,
                        task = task,
                        segmentCount = scheduledSegmentCount,
                        runId = runId,
                        traceId = traceId,
                        streamingOutputEnabled = streamingOutputEnabled,
                        recordedSegmentCount = recordedSegmentCount,
                        analyzedSegmentCount = analyzedSegmentCount,
                        recordedDurationSeconds = recordedDurationSeconds,
                        onStatus = onStatus
                    )
                }.getOrElse { error ->
                    if (error is kotlinx.coroutines.CancellationException) {
                        throw error
                    }
                    segmentProcessor.markRecordedSegmentAnalysisFailed(recordedSegment, error)
                }
                analyzerResults += result

                val offsetEvents = result.analysisResult.timelineEvents.map { event ->
                    event.copy(timestampSeconds = event.timestampSeconds + recordedSegment.startOffsetSeconds)
                }
                partialTimelineEvents.addAll(offsetEvents)
                val completedCount = analyzedSegmentCount.incrementAndGet()
                segmentFeedbacks.add(VideoSegmentFeedback(
                    segmentIndex = recordedSegment.segmentNumber,
                    summary = result.analysisResult.summary,
                    conclusion = result.analysisResult.conclusion
                ))

                onStatus(
                    VideoExecutionStatusUpdate(
                        stage = VideoRunStatus.Analyzing,
                        runId = runId,
                        segmentIndex = recordedSegment.segmentNumber,
                        segmentCount = scheduledSegmentCount,
                        message = if (
                            completedCount < recordedSegmentCount.get() ||
                            (recordedSegmentCount.get() < scheduledSegmentCount && !shouldStopRequested())
                        ) {
                            "第 ${recordedSegment.segmentNumber}/$scheduledSegmentCount 段分析完成，继续处理后续片段"
                        } else {
                            "All recorded segments analyzed. Preparing final summary"
                        },
                        templateLabel = task.templateLabel,
                        segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                        captureIntervalSeconds = task.captureIntervalSeconds,
                        timelineEvents = partialTimelineEvents.sortedBy { it.timestampSeconds },
                        streamingEnabled = streamingOutputEnabled,
                        isStreamingActive = false,
                        isRecordingActive = recordedSegmentCount.get() < scheduledSegmentCount &&
                            !shouldStopRequested(),
                        isAnalysisActive = completedCount < recordedSegmentCount.get(),
                        recordedSegmentCount = recordedSegmentCount.get(),
                        analyzedSegmentCount = completedCount,
                        pendingSegmentCount = (recordedSegmentCount.get() - completedCount).coerceAtLeast(0),
                        recordedDurationSeconds = recordedDurationSeconds.get(),
                        remainingDurationSeconds = (task.plannedDurationSeconds - nextCaptureOffsetSeconds.get())
                            .coerceAtLeast(0),
                        stopRequested = shouldStopRequested(),
                        activeStreamingSegmentIndex = 0,
                        segmentFeedbacks = segmentFeedbacks.toList()
                    )
                )
                    }
                }
            }
            workers.awaitAll()
            analyzerResults.sortedBy { it.segment.segmentIndex }
        }

        val runStartedAt = System.currentTimeMillis()
        var producedSegments = 0
        try {
            while (nextCaptureOffsetSeconds.get() < task.plannedDurationSeconds) {
                ensureActive()
                if (shouldStopRequested()) {
                    break
                }

                val scheduledOffsetSeconds = nextCaptureOffsetSeconds.get()
                val scheduledStartAt = runStartedAt + (scheduledOffsetSeconds * 1_000L)
                val waitMs = (scheduledStartAt - System.currentTimeMillis()).coerceAtLeast(0L)
                if (waitMs > 0L) {
                    onStatus(
                        VideoExecutionStatusUpdate(
                            stage = VideoRunStatus.Recording,
                            runId = runId,
                            segmentIndex = producedSegments,
                            segmentCount = scheduledSegmentCount,
                            message = "Waiting for next capture window",
                            templateLabel = task.templateLabel,
                            segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                            captureIntervalSeconds = task.captureIntervalSeconds,
                            streamingEnabled = streamingOutputEnabled,
                            isRecordingActive = false,
                            isAnalysisActive = recordedSegmentCount.get() > analyzedSegmentCount.get(),
                            recordedSegmentCount = recordedSegmentCount.get(),
                            analyzedSegmentCount = analyzedSegmentCount.get(),
                            pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get())
                                .coerceAtLeast(0),
                            recordedDurationSeconds = recordedDurationSeconds.get(),
                            remainingDurationSeconds = (task.plannedDurationSeconds - scheduledOffsetSeconds)
                                .coerceAtLeast(0),
                            nextCaptureInSeconds = ceil(waitMs / 1000.0).toInt(),
                            recordingSegmentIndex = (producedSegments + 1).coerceAtMost(scheduledSegmentCount),
                            stopRequested = shouldStopRequested()
                        )
                    )
                    var remainingWaitMs = waitMs
                    while (remainingWaitMs > 0L && !shouldStopRequested()) {
                        val stepMs = remainingWaitMs.coerceAtMost(STOP_POLL_INTERVAL_MS)
                        delay(stepMs)
                        remainingWaitMs -= stepMs
                    }
                }

                ensureActive()
                if (shouldStopRequested()) {
                    break
                }

                val remainingDuration = task.plannedDurationSeconds - scheduledOffsetSeconds
                if (remainingDuration <= 0) {
                    break
                }

                val segmentNumber = producedSegments + 1
                val actualDuration = minOf(task.plannedSegmentDurationSeconds, remainingDuration)
                    .coerceAtLeast(1)
                val segmentWindowStartedAt = System.currentTimeMillis()

                val recordedSegment = segmentRecorder.recordSegmentClip(
                    runId = runId,
                    task = task,
                    segmentNumber = segmentNumber,
                    segmentCount = scheduledSegmentCount,
                    actualDuration = actualDuration,
                    outputRoot = outputRoot,
                    latestFrameProvider = latestFrameProvider,
                    startOffsetSeconds = scheduledOffsetSeconds,
                    streamingOutputEnabled = streamingOutputEnabled,
                    recordedSegmentCount = recordedSegmentCount,
                    analyzedSegmentCount = analyzedSegmentCount,
                    recordedDurationSeconds = recordedDurationSeconds,
                    segmentWindowStartedAt = segmentWindowStartedAt,
                    mediaClockStartedAt = runStartedAt,
                    recordingInput = recordingInput,
                    shouldStopRequested = shouldStopRequested,
                    onStatus = onStatus
                )

                producedSegments = segmentNumber
                val producedCount = recordedSegmentCount.incrementAndGet()
                val recordedSegmentDuration = recordedSegment.durationSeconds.coerceAtLeast(1)
                val totalRecordedDuration = recordedDurationSeconds.addAndGet(recordedSegmentDuration)
                val nextOffset = scheduledOffsetSeconds + task.captureIntervalSeconds
                nextCaptureOffsetSeconds.set(nextOffset)
                segmentAudioResults.add(recordedSegment.hasAudio)
                recordedSegments.send(recordedSegment)

                val hasMoreScheduledSegments =
                    nextOffset < task.plannedDurationSeconds && !shouldStopRequested()
                onStatus(
                    VideoExecutionStatusUpdate(
                        stage = VideoRunStatus.Recording,
                        runId = runId,
                        segmentIndex = segmentNumber,
                        segmentCount = scheduledSegmentCount,
                        message = when {
                            shouldStopRequested() -> "Stop requested. Waiting for pending segment analysis"
                            hasMoreScheduledSegments -> "Segment $segmentNumber/$scheduledSegmentCount recorded and queued"
                            else -> "Capture finished. Waiting for remaining segment analysis"
                        },
                        templateLabel = task.templateLabel,
                        segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                        captureIntervalSeconds = task.captureIntervalSeconds,
                        streamingEnabled = streamingOutputEnabled,
                        isRecordingActive = hasMoreScheduledSegments,
                        isAnalysisActive = producedCount > analyzedSegmentCount.get(),
                        recordedSegmentCount = producedCount,
                        analyzedSegmentCount = analyzedSegmentCount.get(),
                        pendingSegmentCount = (producedCount - analyzedSegmentCount.get()).coerceAtLeast(0),
                        recordedDurationSeconds = totalRecordedDuration,
                        remainingDurationSeconds = (task.plannedDurationSeconds - nextOffset).coerceAtLeast(0),
                        nextCaptureInSeconds = if (hasMoreScheduledSegments) {
                            task.captureIntervalSeconds
                        } else {
                            null
                        },
                        recordingSegmentIndex = (segmentNumber + 1).coerceAtMost(scheduledSegmentCount),
                        stopRequested = shouldStopRequested(),
                        currentSegmentHasAudio = recordedSegment.hasAudio,
                        segmentAudioResults = segmentAudioResults.toList()
                    )
                )
            }
        } finally {
            recordedSegments.close()
        }

        val captureEndedAt = System.currentTimeMillis()
        Log.d(TAG, "Recording ended: segments=${recordedSegmentCount.get()} duration=${recordedDurationSeconds.get()}s")
        run = run.copy(
            status = VideoRunStatus.Summarizing,
            recordingEndedAt = run.recordingEndedAt ?: captureEndedAt,
            updatedAt = captureEndedAt
        )
        runDao.upsert(run)

        // Phase 1: Build master audio from recorded audio segment files, then generate outline
        val segmentFiles = (1..recordedSegmentCount.get()).mapNotNull { idx ->
            File(outputRoot, "video_runs/run_${runId}_segment_${idx}_audio.m4a").takeIf { it.exists() }
        }
        val masterAudioFile = runCatching {
            audioOutlineProcessor.buildMasterAudioFromFiles(
                runId = runId,
                segmentFiles = segmentFiles,
                outputRoot = outputRoot,
                expectedDurationMs = recordedDurationSeconds.get() * 1_000L
            )
        }.getOrNull()

        // Audio outline processor handles m4a→mp3 conversion internally via FFmpegKit.

        if (masterAudioFile != null && masterAudioFile.exists() && masterAudioFile.length() > 0L) {
            run = run.copy(
                continuousAudioPath = masterAudioFile.absolutePath,
                continuousAudioDurationMs = recordedDurationSeconds.get() * 1_000L,
                updatedAt = System.currentTimeMillis()
            )
            runDao.upsert(run)

            onStatus(
                VideoExecutionStatusUpdate(
                    stage = VideoRunStatus.Summarizing,
                    runId = runId,
                    segmentIndex = recordedSegmentCount.get(),
                    segmentCount = scheduledSegmentCount,
                    message = "正在根据完整音频生成大纲...",
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    streamingEnabled = streamingOutputEnabled,
                    isRecordingActive = false,
                    isAnalysisActive = true,
                    recordedSegmentCount = recordedSegmentCount.get(),
                    analyzedSegmentCount = analyzedSegmentCount.get(),
                    pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get()).coerceAtLeast(0),
                    recordedDurationSeconds = recordedDurationSeconds.get(),
                    stopRequested = false
                )
            )

            recordStage("audio_outline_started")
            val outlineResult = runCatching {
                audioOutlineProcessor.generateAudioOutline(
                    runId = runId,
                    audioFile = masterAudioFile,
                    task = task,
                    durationSeconds = recordedDurationSeconds.get(),
                    traceId = traceId
                )
            }.getOrNull()

            if (outlineResult != null) {
                recordStage("audio_outline_completed")
                run = run.copy(
                    outlineMarkdown = outlineResult.markdownNote,
                    outlineGeneratedAt = System.currentTimeMillis(),
                    markdownNote = outlineResult.markdownNote,
                    finalSummary = outlineResult.summary,
                    reportVersion = 0,
                    updatedAt = System.currentTimeMillis()
                )
                runDao.upsert(run)
                onStatus(
                    VideoExecutionStatusUpdate(
                        stage = VideoRunStatus.Summarizing,
                        runId = runId,
                        segmentIndex = recordedSegmentCount.get(),
                        segmentCount = scheduledSegmentCount,
                        message = "大纲已生成，等待分片分析完成...",
                        templateLabel = task.templateLabel,
                        segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                        captureIntervalSeconds = task.captureIntervalSeconds,
                        finalSummary = outlineResult.summary,
                        streamingEnabled = streamingOutputEnabled,
                        streamingBuffer = outlineResult.markdownNote,
                        isRecordingActive = false,
                        isAnalysisActive = analyzedSegmentCount.get() < recordedSegmentCount.get(),
                        recordedSegmentCount = recordedSegmentCount.get(),
                        analyzedSegmentCount = analyzedSegmentCount.get(),
                        pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get()).coerceAtLeast(0),
                        recordedDurationSeconds = recordedDurationSeconds.get(),
                        stopRequested = false,
                        segmentFeedbacks = segmentFeedbacks.toList()
                    )
                )
            } else {
                recordStage("audio_outline_failed")
            }
        }

        recordStage("segment_analysis_started")
        val analyzerResult = runCatching { analyzer.await() }
        if (analyzerResult.isFailure) {
            val error = analyzerResult.exceptionOrNull()!!
            if (error is kotlinx.coroutines.CancellationException) throw error
            analyzer.cancel()
        }
        val segmentResults = analyzerResult.getOrDefault(emptyList())
        val analyzerError = analyzerResult.exceptionOrNull()
        recordStage(if (analyzerError == null) "segment_analysis_completed" else "segment_analysis_failed")
        val successfulSegmentResults = segmentResults.filter { it.segment.status == VideoRunStatus.Completed }
        val failedAnalysisCount = segmentResults.count { it.segment.status == VideoRunStatus.Failed }
        if (segmentResults.isEmpty()) {
            run = run.copy(
                status = if (analyzerError != null) VideoRunStatus.Failed else VideoRunStatus.Cancelled,
                recordingEndedAt = run.recordingEndedAt ?: System.currentTimeMillis(),
                fullMediaPath = "",
                fullMediaDurationMs = 0L,
                fullMediaHasAudio = false,
                fullMediaVideoSource = finalMediaSource,
                errorMessage = analyzerError?.message,
                updatedAt = System.currentTimeMillis()
            )
            runDao.upsert(run)
            traceLogger.finishNode(orchestrationTrace, System.currentTimeMillis() - now)
            onStatus(
                VideoExecutionStatusUpdate(
                    stage = VideoRunStatus.Cancelled,
                    runId = runId,
                    segmentIndex = 0,
                    segmentCount = scheduledSegmentCount,
                    message = "Task stopped before any valid segment was produced",
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    streamingEnabled = streamingOutputEnabled,
                    recordedSegmentCount = 0,
                    analyzedSegmentCount = 0,
                    pendingSegmentCount = 0,
                    recordedDurationSeconds = 0,
                    remainingDurationSeconds = task.plannedDurationSeconds,
                    stopRequested = shouldStopRequested()
                )
            )
            return@coroutineScope VideoExecutionResult(
                task = task,
                run = run,
                finalResult = VideoAnalysisResult(
                    summary = "",
                    conclusion = "",
                    timelineEvents = emptyList()
                )
            )
        }

        if (successfulSegmentResults.isEmpty()) {
            val fallbackSummary = "Recorded ${segmentResults.size} segment(s), but none could be analyzed. Check network/API connectivity and audio/video assets in debug details."
            val fallbackResult = VideoAnalysisResult(
                summary = fallbackSummary,
                conclusion = "",
                timelineEvents = emptyList(),
                rawResponse = segmentResults.joinToString("\n") { it.analysisResult.rawResponse }
            )
            val (mergedVideoPath, mergeError) = runCatching {
                mediaAssembler.mergeSegmentVideos(
                    runId = runId,
                    task = task,
                    results = segmentResults,
                    outputRoot = outputRoot
                )
            }.fold(
                onSuccess = { it to null },
                onFailure = { null to it.message }
            )
            mergedVideoPath?.let {
                mediaAssembler.recordDerivedVideoAssetBinding(
                    runId = runId,
                    filePath = it,
                    assetKind = VideoRemoteAssetKind.MasterVideo
                )
            }
            val fallbackMasterAudioPath = run.continuousAudioPath
                ?.takeIf(String::isNotBlank)
                ?.takeIf { File(it).exists() && File(it).length() > 0L }
                ?: runCatching {
                    audioOutlineProcessor.buildMasterAudioAsset(
                        runId = runId,
                        results = segmentResults,
                        outputRoot = outputRoot,
                        expectedDurationMs = recordedDurationSeconds.get() * 1_000L
                    )
                }.getOrNull()
            val fallbackFullMediaPath = mediaAssembler.mergeVideoWithMasterAudio(
                runId = runId,
                videoPath = mergedVideoPath,
                audioPath = fallbackMasterAudioPath ?: run.continuousAudioPath,
                outputRoot = outputRoot
            )
            val validation = mediaAssembler.validateMedia(fallbackFullMediaPath ?: mergedVideoPath)
            run = run.copy(
                status = VideoRunStatus.CompletedDegraded,
                recordingEndedAt = run.recordingEndedAt ?: System.currentTimeMillis(),
                continuousAudioPath = run.continuousAudioPath ?: fallbackMasterAudioPath,
                continuousAudioDurationMs = run.continuousAudioDurationMs
                    .takeIf { it > 0L }
                    ?: recordedDurationSeconds.get() * 1_000L,
                finalSummary = fallbackResult.summary,
                finalConclusion = fallbackResult.conclusion,
                rawModelSummary = fallbackResult.rawResponse,
                mergedVideoPath = mergedVideoPath,
                fullMediaPath = fallbackFullMediaPath ?: mergedVideoPath,
                fullMediaDurationMs = validation.durationMs.takeIf { it > 0L }
                    ?: recordedDurationSeconds.get() * 1_000L,
                fullMediaHasAudio = validation.hasAudio,
                fullMediaVideoSource = finalMediaSource,
                audioEnhancementInfo = segmentResults.firstOrNull { it.audioEnhancementInfo.isNotBlank() }
                    ?.audioEnhancementInfo.orEmpty(),
                errorMessage = null,
                degradedReason = listOfNotNull(
                    "All segment analyses failed; local media/audio assets were preserved.",
                    mergeError?.let { "Video merge failed: $it" },
                    validation.errorMessage?.takeIf { mergedVideoPath != null }?.let { "Media validation failed: $it" }
                ).joinToString("; "),
                updatedAt = System.currentTimeMillis()
            )
            runDao.upsert(run)
            traceLogger.finishNode(orchestrationTrace, System.currentTimeMillis() - now)
            onStatus(
                VideoExecutionStatusUpdate(
                    stage = VideoRunStatus.CompletedDegraded,
                    runId = runId,
                    segmentIndex = recordedSegmentCount.get(),
                    segmentCount = scheduledSegmentCount,
                    message = fallbackSummary,
                    degradedReason = run.degradedReason,
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    finalSummary = fallbackResult.summary,
                    finalConclusion = fallbackResult.conclusion,
                    streamingEnabled = streamingOutputEnabled,
                    isRecordingActive = false,
                    isAnalysisActive = false,
                    recordedSegmentCount = recordedSegmentCount.get(),
                    analyzedSegmentCount = analyzedSegmentCount.get(),
                    pendingSegmentCount = 0,
                    recordedDurationSeconds = recordedDurationSeconds.get(),
                    stopRequested = shouldStopRequested(),
                    segmentFeedbacks = segmentFeedbacks.toList()
                )
            )
            return@coroutineScope VideoExecutionResult(
                task = task,
                run = run,
                finalResult = fallbackResult
            )
        }

        val allTimelineEvents = partialTimelineEvents.sortedBy { it.timestampSeconds }
        val shouldSummarize = task.finalSummaryEnabled &&
            (
                successfulSegmentResults.size > 1 ||
                    task.recordingScenario != RecordingScenario.General.value
            )

        onStatus(
            VideoExecutionStatusUpdate(
                stage = VideoRunStatus.Summarizing,
                runId = runId,
                segmentIndex = recordedSegmentCount.get(),
                segmentCount = scheduledSegmentCount,
                message = if (shouldSummarize) {
                    "Generating final summary"
                } else {
                    "Compiling completed segment results"
                },
                templateLabel = task.templateLabel,
                segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                captureIntervalSeconds = task.captureIntervalSeconds,
                streamingEnabled = streamingOutputEnabled,
                streamingBuffer = "",
                isRecordingActive = false,
                isAnalysisActive = shouldSummarize,
                recordedSegmentCount = recordedSegmentCount.get(),
                analyzedSegmentCount = analyzedSegmentCount.get(),
                pendingSegmentCount = failedAnalysisCount,
                recordedDurationSeconds = recordedDurationSeconds.get(),
                remainingDurationSeconds = (task.plannedDurationSeconds - nextCaptureOffsetSeconds.get())
                    .coerceAtLeast(0),
                stopRequested = shouldStopRequested(),
                segmentFeedbacks = segmentFeedbacks.toList()
            )
        )

        val recordedForCoverage = recordedSegmentCount.get().coerceAtLeast(1)
        val segmentFactPacketCount = successfulSegmentResults.count { it.segment.evidenceJson.isNotBlank() }
        val hasEnoughSegmentFactPackets = segmentFactPacketCount * 4 >= recordedForCoverage * 3
        // Avoid uploading the same video twice. Merged chunk analysis is only a recovery path
        // when segment-level fact packets are too incomplete to support the final report.
        val shouldAnalyzeMergedChunks = shouldSummarize && !hasEnoughSegmentFactPackets

        val mergedChunkEvidence = if (shouldAnalyzeMergedChunks) {
            recordStage("segment_merge_started")
            val segmentFiles = successfulSegmentResults
                .sortedBy { it.segment.segmentIndex }
                .mapNotNull { result ->
                    // Prefer the merged analysis file (video+audio) over the raw segment file
                    val path = result.mergedAnalysisFilePath
                        ?: result.segment.localFilePath
                    path?.takeIf(String::isNotBlank)
                        ?.let(::File)
                        ?.takeIf { it.exists() && it.length() > 0L }
                }
            val chunkPlans = VideoChunkPlanner().planChunks(segmentFiles)
            val chunkFiles = runCatching {
                mediaAssembler.mergeVideoChunks(
                    runId = runId,
                    chunkPlans = chunkPlans,
                    outputRoot = outputRoot
                )
            }.getOrDefault(emptyList())
            val evidence = mutableListOf<VideoMergedChunkResult>()
            chunkFiles.forEachIndexed { index, chunkFile ->
                onStatus(
                    VideoExecutionStatusUpdate(
                        stage = VideoRunStatus.Summarizing,
                        runId = runId,
                        segmentIndex = recordedSegmentCount.get(),
                        segmentCount = scheduledSegmentCount,
                        message = "Analyzing merged video chunk ${index + 1}/${chunkFiles.size}",
                        templateLabel = task.templateLabel,
                        segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                        captureIntervalSeconds = task.captureIntervalSeconds,
                        streamingEnabled = streamingOutputEnabled,
                        isRecordingActive = false,
                        isAnalysisActive = true,
                        recordedSegmentCount = recordedSegmentCount.get(),
                        analyzedSegmentCount = analyzedSegmentCount.get(),
                        pendingSegmentCount = failedAnalysisCount,
                        recordedDurationSeconds = recordedDurationSeconds.get(),
                        remainingDurationSeconds = (task.plannedDurationSeconds - nextCaptureOffsetSeconds.get())
                            .coerceAtLeast(0),
                        stopRequested = shouldStopRequested(),
                        segmentFeedbacks = segmentFeedbacks.toList()
                    )
                )
                analyzeMergedVideoChunk(
                    runId = runId,
                    task = task,
                    chunkFile = chunkFile,
                    chunkIndex = index + 1,
                    chunkCount = chunkFiles.size,
                    traceId = traceId
                ).also(evidence::add)
            }
            recordStage("segment_merge_completed")
            evidence
        } else {
            emptyList()
        }

        val finalResult = try {
            when {
                successfulSegmentResults.size == 1 && !shouldSummarize -> {
                    successfulSegmentResults.single().analysisResult.copy(timelineEvents = allTimelineEvents)
                }

                !task.finalSummaryEnabled -> reportSummarizer.combineSegmentResults(successfulSegmentResults, allTimelineEvents)

                else -> {
                    var reportResult = reportSummarizer.summarize(
                        task = task,
                        results = successfulSegmentResults,
                        outlineMarkdown = run.outlineMarkdown,
                        mergedChunkEvidence = mergedChunkEvidence,
                        traceId = traceId,
                        runId = runId
                    ).let { result ->
                        if (result.timelineEvents.isNotEmpty()) result
                        else result.copy(timelineEvents = allTimelineEvents)
                    }

                    if (mergedChunkEvidence.isNotEmpty()) {
                        recordStage("video_refinement_started")
                        onStatus(
                            VideoExecutionStatusUpdate(
                                stage = VideoRunStatus.Summarizing,
                                runId = runId,
                                segmentIndex = recordedSegmentCount.get(),
                                segmentCount = scheduledSegmentCount,
                                message = "正在用视频证据校准报告...",
                                pipelinePhase = "video_refinement",
                                templateLabel = task.templateLabel,
                                segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                                captureIntervalSeconds = task.captureIntervalSeconds,
                                streamingEnabled = streamingOutputEnabled,
                                isRecordingActive = false,
                                isAnalysisActive = true,
                                recordedSegmentCount = recordedSegmentCount.get(),
                                analyzedSegmentCount = analyzedSegmentCount.get(),
                                pendingSegmentCount = 0,
                                recordedDurationSeconds = recordedDurationSeconds.get(),
                                remainingDurationSeconds = 0,
                                stopRequested = shouldStopRequested(),
                                segmentFeedbacks = segmentFeedbacks.toList()
                            )
                        )
                        val refinedMarkdown = runCatching {
                            val chunksWithFileIds = mergedChunkEvidence.filter { it.arkFileId != null }
                            var current = reportResult.markdownNote.ifBlank { reportResult.rawResponse }
                            chunksWithFileIds.forEachIndexed { idx, chunk ->
                                current = reportRefiner.refineWithVideo(
                                    currentReport = current,
                                    videoFileId = chunk.arkFileId!!,
                                    task = task,
                                    chunkIndex = idx + 1,
                                    chunkCount = chunksWithFileIds.size,
                                    traceId = traceId,
                                    runId = runId
                                )
                            }
                            current
                        }.getOrNull()

                        if (!refinedMarkdown.isNullOrBlank()) {
                            recordStage("video_refinement_completed")
                            reportResult = reportResult.copy(
                                markdownNote = refinedMarkdown,
                                rawResponse = refinedMarkdown
                            )
                            val chunksUsed = mergedChunkEvidence.count { it.arkFileId != null }
                            run = run.copy(
                                videoRefinementApplied = true,
                                videoRefinementInputMode = if (chunksUsed == 1) "single_master" else "chunked_master",
                                updatedAt = System.currentTimeMillis()
                            )
                            runDao.upsert(run)
                        } else {
                            recordStage("video_refinement_failed")
                        }
                    }

                    reportResult
                }
            }
        } catch (error: Exception) {
            throw VideoProcessException(
                stage = VideoRunStatus.Summarizing,
                userMessage = "Failed to summarize segment results: ${error.toUserMessage("Check the model output.")}",
                cause = error
            )
        }

        // Audio outline fallback: if final report has no markdown but audio outline exists, use it
        val finalResultWithFallback = if (finalResult.markdownNote.isBlank() && run.outlineMarkdown.isNotBlank()) {
            finalResult.copy(markdownNote = run.outlineMarkdown)
        } else {
            finalResult
        }

        val (mergedVideoPath, mergeError) = try {
            onStatus(
                VideoExecutionStatusUpdate(
                    stage = VideoRunStatus.Summarizing,
                    runId = runId,
                    segmentIndex = recordedSegmentCount.get(),
                    segmentCount = scheduledSegmentCount,
                    message = "Generating merged video",
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    finalSummary = finalResultWithFallback.summary,
                    finalConclusion = finalResultWithFallback.conclusion,
                    timelineEvents = finalResultWithFallback.timelineEvents,
                    streamingEnabled = streamingOutputEnabled,
                    streamingBuffer = finalResultWithFallback.rawResponse,
                    isRecordingActive = false,
                    isAnalysisActive = false,
                    recordedSegmentCount = recordedSegmentCount.get(),
                    analyzedSegmentCount = analyzedSegmentCount.get(),
                    pendingSegmentCount = 0,
                    recordedDurationSeconds = recordedDurationSeconds.get(),
                    remainingDurationSeconds = (task.plannedDurationSeconds - nextCaptureOffsetSeconds.get())
                        .coerceAtLeast(0),
                    stopRequested = shouldStopRequested(),
                    segmentFeedbacks = segmentFeedbacks.toList()
                )
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                mediaAssembler.mergeSegmentVideos(
                    runId = runId,
                    task = task,
                    results = segmentResults,
                    outputRoot = outputRoot
                )
            } to null
        } catch (e: Exception) {
            null to e.message
        }
        mergedVideoPath?.let {
            mediaAssembler.recordDerivedVideoAssetBinding(
                runId = runId,
                filePath = it,
                assetKind = VideoRemoteAssetKind.MasterVideo
            )
        }

        val masterAudioPath = run.continuousAudioPath
            ?.takeIf(String::isNotBlank)
            ?.takeIf { File(it).exists() && File(it).length() > 0L }
            ?: runCatching {
                audioOutlineProcessor.buildMasterAudioAsset(
                    runId = runId,
                    results = segmentResults,
                    outputRoot = outputRoot,
                    expectedDurationMs = recordedDurationSeconds.get() * 1_000L
                )
            }.getOrNull()
        val fullMediaPath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            mediaAssembler.mergeVideoWithMasterAudio(
                runId = runId,
                videoPath = mergedVideoPath,
                audioPath = masterAudioPath ?: run.continuousAudioPath,
                outputRoot = outputRoot
            )
        }

        val manualStopWithSegments = shouldStopRequested() && recordedSegmentCount.get() > 0
        val validation = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            mediaAssembler.validateMedia(fullMediaPath ?: mergedVideoPath)
        }
        try {
            val finalStatus = if (!manualStopWithSegments && mergedVideoPath != null && validation.errorMessage == null) {
                VideoRunStatus.Completed
            } else {
                VideoRunStatus.CompletedDegraded
            }
            val degradedReason = listOfNotNull(
                if (manualStopWithSegments) "用户手动停止，已基于已记录片段生成总结。" else null,
                mergeError?.let { "视频合并失败: $it" },
                validation.errorMessage?.takeIf { mergedVideoPath != null }?.let { "视频校验异常: $it" }
            ).joinToString("；").ifBlank { null }
            val robustDegradedReason = listOfNotNull(
                if (manualStopWithSegments) "Manual stop; generated a partial summary from recorded segments." else null,
                if (failedAnalysisCount > 0) "$failedAnalysisCount segment analysis failed; summary used successful segments only." else null,
                mergeError?.let { "Video merge failed: $it" },
                validation.errorMessage?.takeIf { mergedVideoPath != null }?.let { "Media validation failed: $it" }
            ).joinToString("; ").ifBlank { null }
            run = run.copy(
                status = finalStatus,
                recordingEndedAt = run.recordingEndedAt ?: System.currentTimeMillis(),
                continuousAudioPath = run.continuousAudioPath ?: masterAudioPath,
                continuousAudioDurationMs = run.continuousAudioDurationMs
                    .takeIf { it > 0L }
                    ?: recordedDurationSeconds.get() * 1_000L,
                finalSummary = finalResultWithFallback.summary,
                finalConclusion = finalResultWithFallback.conclusion,
                rawModelSummary = finalResultWithFallback.rawResponse,
                structuredNoteJson = finalResultWithFallback.structuredNoteJson,
                markdownNote = finalResultWithFallback.markdownNote,
                mergedVideoPath = mergedVideoPath,
                fullMediaPath = fullMediaPath ?: mergedVideoPath,
                fullMediaDurationMs = validation.durationMs.takeIf { it > 0L }
                    ?: recordedDurationSeconds.get() * 1_000L,
                fullMediaHasAudio = validation.hasAudio,
                fullMediaVideoSource = finalMediaSource,
                audioEnhancementInfo = segmentResults.firstOrNull { it.audioEnhancementInfo.isNotBlank() }
                    ?.audioEnhancementInfo.orEmpty(),
                errorMessage = null,
                degradedReason = robustDegradedReason ?: degradedReason,
                mergedSegmentCountActual = successfulSegmentResults.count {
                    it.analysisInputMode == SegmentAnalysisInputMode.MergedSegmentVideo
                },
                segmentsMissingMergedAnalysisAsset = successfulSegmentResults.count {
                    it.analysisInputMode == SegmentAnalysisInputMode.VideoOnly
                },
                audioOutlineAvailable = run.outlineMarkdown.isNotBlank(),
                reportPipelineStagesJson = pipelineStages.joinToString(",", "[", "]") { "\"$it\"" },
                updatedAt = System.currentTimeMillis()
            )
            runDao.upsert(run)
            traceLogger.finishNode(orchestrationTrace, System.currentTimeMillis() - now)
            timelineEventDao.deleteByRunId(runId)
            timelineEventDao.insertAll(
                finalResultWithFallback.timelineEvents.map { event ->
                    TimelineEventEntity(
                        runId = runId,
                        timestampSeconds = event.timestampSeconds,
                        title = event.title,
                        detail = event.detail,
                        confidence = event.confidence
                    )
                }
            )
        } catch (error: Exception) {
            throw VideoProcessException(
                stage = run.status,
                userMessage = "Failed to save the final video result: ${error.toUserMessage("Check the local database state.")}",
                cause = error
            )
        }

        onStatus(
            VideoExecutionStatusUpdate(
                stage = run.status,
                runId = runId,
                segmentIndex = recordedSegmentCount.get(),
                segmentCount = scheduledSegmentCount,
                message = when {
                    shouldStopRequested() && recordedSegmentCount.get() < scheduledSegmentCount ->
                        "Stopped manually, partial summary completed"
                    run.status == VideoRunStatus.CompletedDegraded ->
                        run.degradedReason ?: "视频处理完成，但部分产物降级"
                    else -> "视频处理完成"
                },
                degradedReason = run.degradedReason,
                templateLabel = task.templateLabel,
                segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                captureIntervalSeconds = task.captureIntervalSeconds,
                finalSummary = finalResultWithFallback.summary,
                finalConclusion = finalResultWithFallback.conclusion,
                timelineEvents = finalResultWithFallback.timelineEvents,
                streamingEnabled = streamingOutputEnabled,
                streamingBuffer = finalResultWithFallback.rawResponse,
                isRecordingActive = false,
                isAnalysisActive = false,
                recordedSegmentCount = recordedSegmentCount.get(),
                analyzedSegmentCount = analyzedSegmentCount.get(),
                pendingSegmentCount = 0,
                recordedDurationSeconds = recordedDurationSeconds.get(),
                remainingDurationSeconds = (task.plannedDurationSeconds - nextCaptureOffsetSeconds.get())
                    .coerceAtLeast(0),
                stopRequested = false,
                segmentFeedbacks = segmentFeedbacks.toList()
            )
        )

        VideoExecutionResult(
            task = task,
            run = run,
            finalResult = finalResult
        )
    }

    suspend fun markRunFailed(
        runId: Long,
        segmentIndex: Int,
        segmentCount: Int,
        streamingEnabled: Boolean,
        error: Throwable,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ) {
        val existingRun = runDao.getRunById(runId) ?: return
        if (existingRun.status in setOf(VideoRunStatus.Cancelled, VideoRunStatus.Completed, VideoRunStatus.CompletedDegraded)) {
            return
        }

        val failureMessage = error.toUserMessage("Execution failed.")
        existingRun.aiTraceId.takeIf(String::isNotBlank)?.let { traceId ->
            traceLogger.logError(
                VideoAiTraceContext(
                    traceId = traceId,
                    runId = runId,
                    taskId = existingRun.taskId,
                    node = "VideoExecutionOrchestrator",
                    segmentIndex = segmentIndex,
                    requestKind = "video_process_run"
                ),
                error
            )
        }
        runDao.upsert(
            existingRun.copy(
                status = VideoRunStatus.Failed,
                errorMessage = failureMessage,
                recordingEndedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        onStatus(
            VideoExecutionStatusUpdate(
                stage = VideoRunStatus.Failed,
                runId = runId,
                segmentIndex = segmentIndex,
                segmentCount = segmentCount,
                message = failureMessage,
                templateLabel = existingRun.templateLabel,
                segmentDurationSeconds = existingRun.segmentDurationSeconds,
                captureIntervalSeconds = existingRun.captureIntervalSeconds,
                streamingEnabled = streamingEnabled,
                errorMessage = failureMessage,
                isRecordingActive = false,
                isAnalysisActive = false,
                activeStreamingSegmentIndex = 0
            )
        )
    }

    suspend fun markRunCancelled(
        runId: Long,
        segmentIndex: Int,
        segmentCount: Int,
        streamingEnabled: Boolean,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ) {
        val existingRun = runDao.getRunById(runId) ?: return
        if (existingRun.status in setOf(VideoRunStatus.Completed, VideoRunStatus.CompletedDegraded, VideoRunStatus.Cancelled)) {
            return
        }

        existingRun.aiTraceId.takeIf(String::isNotBlank)?.let { traceId ->
            traceLogger.finishNode(
                VideoAiTraceContext(
                    traceId = traceId,
                    runId = runId,
                    taskId = existingRun.taskId,
                    node = "VideoExecutionOrchestrator",
                    segmentIndex = segmentIndex,
                    requestKind = "video_process_run"
                )
            )
        }
        runDao.upsert(
            existingRun.copy(
                status = VideoRunStatus.Cancelled,
                errorMessage = null,
                recordingEndedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        onStatus(
            VideoExecutionStatusUpdate(
                stage = VideoRunStatus.Cancelled,
                runId = runId,
                segmentIndex = segmentIndex,
                segmentCount = segmentCount,
                message = "Current video processing task cancelled",
                templateLabel = existingRun.templateLabel,
                segmentDurationSeconds = existingRun.segmentDurationSeconds,
                captureIntervalSeconds = existingRun.captureIntervalSeconds,
                streamingEnabled = streamingEnabled,
                isRecordingActive = false,
                isAnalysisActive = false,
                activeStreamingSegmentIndex = 0
            )
        )
    }

    private suspend fun analyzeMergedVideoChunk(
        runId: Long,
        task: VideoProcessTaskDraft,
        chunkFile: File,
        chunkIndex: Int,
        chunkCount: Int,
        traceId: String
    ): VideoMergedChunkResult {
        return try {
            val remoteFile = remoteFileResolver.resolveVideoFile(
                file = chunkFile,
                runId = runId,
                segmentRunId = null,
                assetKind = VideoRemoteAssetKind.MergedChunkVideo,
                samplingFps = task.plannedSamplingFps
            )
            // Poll until file is ready
            repeat(150) { attempt ->
                val status = remoteFileResolver.pollFileStatus(remoteFile.fileId)
                when {
                    status == "active" || status == "processed" || status == "ready" || status == "succeeded" -> {
                        return@repeat
                    }
                    status == "failed" -> error("Ark file preprocessing failed for chunk $chunkIndex.")
                    else -> {
                        kotlinx.coroutines.delay(2_000L)
                        if (attempt == 149) error("Ark file preprocessing timed out for chunk $chunkIndex.")
                    }
                }
            }
            val rawText = chunkAnalyzer.analyze(
                fileId = remoteFile.fileId,
                task = task,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
                traceId = traceId,
                runId = runId
            )
            val parsed = ModelOutputParser.parseVideoAnalysis(rawText)
            VideoMergedChunkResult(
                chunkIndex = chunkIndex,
                filePath = chunkFile.absolutePath,
                fileSizeBytes = chunkFile.length(),
                arkFileId = remoteFile.fileId,
                evidenceJson = parsed.evidenceJson.ifBlank { rawText },
                summary = parsed.summary.ifBlank { parsed.conclusion }
            )
        } catch (error: Exception) {
            VideoMergedChunkResult(
                chunkIndex = chunkIndex,
                filePath = chunkFile.absolutePath,
                fileSizeBytes = chunkFile.length(),
                arkFileId = null,
                evidenceJson = "",
                summary = "",
                errorMessage = error.toUserMessage("Merged video chunk analysis failed.")
            )
        }
    }

    private companion object {
        private const val ANALYSIS_PARALLELISM = 2
        private const val STOP_POLL_INTERVAL_MS = 250L
    }
}

package com.example.watcher.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.watcher.data.local.TimelineEventDao
import com.example.watcher.data.local.VideoProcessRunDao
import com.example.watcher.data.local.VideoProcessTaskDao
import com.example.watcher.data.local.VideoSpeechTranscriptDao
import com.example.watcher.data.model.ClassroomKnowledgeTreeProcessingStatus
import com.example.watcher.data.model.ClassroomRecordingInput
import com.example.watcher.data.model.ClassroomSpeechRecognitionConfig
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.VideoSegmentFeedback
import com.example.watcher.data.model.acceptsPreviewFrameSource
import com.example.watcher.data.model.longTermFrameSource
import com.example.watcher.data.model.shortTermFrameSource
import com.example.watcher.data.model.usesLiveAudioCapture
import com.example.watcher.data.model.usesLiveFrameProvider
import com.example.watcher.data.model.toPersistedClassroomKnowledgeFrameRefsJson
import com.example.watcher.data.model.toPersistedClassroomKnowledgeTreeJson
import com.example.watcher.data.remote.DoubaoApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

private const val CLASSROOM_ORCHESTRATOR_TAG = "Watcher.Classroom.Orch"
private const val CLASSROOM_VISUAL_TAG = "Watcher.Classroom.Visual"
private const val CLASSROOM_MEDIA_TAG = "Watcher.Classroom.Media"

internal class ClassroomRecordingOrchestrator(
    private val taskDao: VideoProcessTaskDao,
    private val runDao: VideoProcessRunDao,
    private val timelineEventDao: TimelineEventDao,
    private val saveTask: suspend (VideoProcessTaskDraft) -> VideoProcessTaskDraft,
    private val segmentProcessor: ClassroomSegmentProcessor,
    private val segmentRecorder: VideoSegmentRecorder,
    private val mediaAssembler: VideoMediaAssembler,
    private val audioOutlineProcessor: ClassroomAudioOutlineProcessor,
    private val noteSynthesizer: ClassroomNoteSynthesizer,
    private val visualEvidenceAnalyzer: ClassroomVisualEvidenceAnalyzer? = null,
    private val appContext: Context,
    private val speechTranscriptDao: VideoSpeechTranscriptDao,
    private val apiService: DoubaoApiService,
    private val realtimeInsightModel: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger,
    private val frameEvidenceCache: ClassroomFrameEvidenceCache? = null,
    private val astSourceSubtitleStore: ClassroomAstSourceSubtitleStore? = null
) {
    suspend fun executeClassroomRecording(
        draft: VideoProcessTaskDraft,
        streamingOutputEnabled: Boolean,
        latestFrameProvider: () -> Bitmap?,
        latestFrameSourceProvider: () -> String = { "" },
        outputRoot: File,
        recordingInput: ClassroomRecordingInput,
        speechRecognitionConfig: ClassroomSpeechRecognitionConfig = ClassroomSpeechRecognitionConfig.Default,
        shouldStopRequested: () -> Boolean = { false },
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ): VideoExecutionResult = coroutineScope {
        segmentProcessor.requireApiKey()
        val task = saveTask(draft)
        val taskEntity = task.taskId?.let { taskDao.getTaskById(it) }
            ?: error("Failed to save the classroom task before execution.")
        val now = System.currentTimeMillis()
        val scheduledSegmentCount = task.plannedSegmentCount
        var run = VideoProcessRun(
            taskId = taskEntity.id,
            templateId = task.templateId,
            templateLabel = task.templateLabel,
            taskTitle = taskEntity.title,
            taskRequirement = taskEntity.userRequirement,
            recordingScenario = task.recordingScenario,
            speechInputEnabled = recordingInput.usesLiveAudioCapture,
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
            node = "ClassroomRecordingOrchestrator",
            requestKind = "classroom_recording_run"
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
        taskDao.upsert(taskEntity.copy(updatedAt = now, lastUsedAt = now, runCount = taskEntity.runCount + 1))

        val pipelineStages = mutableListOf<String>()
        fun recordStage(stage: String) {
            pipelineStages.add("${System.currentTimeMillis()}:$stage")
        }

        val finalMediaSource = recordingInput.sourceId
        val recordedSegmentCount = AtomicInteger(0)
        val analyzedSegmentCount = AtomicInteger(0)
        val recordedDurationSeconds = AtomicInteger(0)
        val nextCaptureOffsetSeconds = AtomicInteger(0)
        val segmentFeedbacks = mutableListOf<VideoSegmentFeedback>()
        val segmentAudioResults = mutableListOf<Boolean>()
        val recordedSegments = mutableListOf<RecordedSegment>()
        val segmentResults = mutableListOf<SegmentExecutionResult>()
        var latestRealtimeState = ClassroomRealtimeFeedbackState()
        val runStartedAt = System.currentTimeMillis()
        if (recordingInput is ClassroomRecordingInput.TestVideo) {
            val testVideoFile = File(recordingInput.localPath)
            frameEvidenceCache?.registerTestVideo(runId, testVideoFile)
            frameEvidenceCache?.let { cache ->
                launch(Dispatchers.IO) {
                    cache.archiveTestVideoFrames(runId, testVideoFile)
                }
            }
            recordStage("classroom_test_video_frame_evidence_registered")
        }
        val firstPreviewSource = latestFrameSourceProvider()
        if (recordingInput.usesLiveFrameProvider && !recordingInput.acceptsPreviewFrameSource(firstPreviewSource)) {
            Log.w(
                CLASSROOM_VISUAL_TAG,
                "blocked classroom recording because preview source mismatched run=$runId " +
                    "expected=${recordingInput.sourceId} actual=$firstPreviewSource"
            )
            error("视频源不一致：课堂录制不会使用非预览画面。")
        }
        val firstPreviewFrame = if (recordingInput.usesLiveFrameProvider) latestFrameProvider() else null
        Log.i(
            CLASSROOM_VISUAL_TAG,
            "classroom visual source start run=$runId previewSource=${recordingInput.sourceId} " +
                "recordingSource=${recordingInput.sourceId} evidenceSource=${recordingInput.shortTermFrameSource} " +
                "currentFrameSource=$firstPreviewSource stream=${recordingInput.visualLogLabel} " +
                "firstFrameSize=${firstPreviewFrame?.width ?: 0}x${firstPreviewFrame?.height ?: 0}"
        )
        if (recordingInput.usesLiveFrameProvider && firstPreviewFrame == null) {
            Log.w(
                CLASSROOM_VISUAL_TAG,
                "blocked classroom recording because preview source has no frame run=$runId source=${recordingInput.sourceId}"
            )
            error("视频流未就绪：课堂录制必须使用当前预览画面作为处理源。")
        }

        var loggedFirstEvidenceFrame = false
        val frameProviderForRecording: () -> Bitmap? = if (recordingInput.usesLiveFrameProvider) {
            {
                val currentFrameSource = latestFrameSourceProvider()
                if (!recordingInput.acceptsPreviewFrameSource(currentFrameSource)) {
                    Log.w(
                        CLASSROOM_VISUAL_TAG,
                        "rejected mismatched frame run=$runId expected=${recordingInput.sourceId} actual=$currentFrameSource"
                    )
                    null
                } else latestFrameProvider()?.also { frame ->
                    val mediaTimeMs = (System.currentTimeMillis() - runStartedAt).coerceAtLeast(0L)
                    if (!loggedFirstEvidenceFrame) {
                        loggedFirstEvidenceFrame = true
                        Log.i(
                            CLASSROOM_VISUAL_TAG,
                            "first evidence frame run=$runId source=${recordingInput.shortTermFrameSource} " +
                                "mediaTimeMs=$mediaTimeMs size=${frame.width}x${frame.height}"
                        )
                    }
                    frameEvidenceCache?.offerFrameEvidence(
                        runId = runId,
                        mediaTimeMs = mediaTimeMs,
                        bitmap = frame,
                        shortTermSource = recordingInput.shortTermFrameSource,
                        longTermSource = recordingInput.longTermFrameSource
                    )
                }
            }
        } else {
            latestFrameProvider
        }

        fun realtimeStatusUpdate(state: ClassroomRealtimeFeedbackState): VideoExecutionStatusUpdate {
            latestRealtimeState = state
            return VideoExecutionStatusUpdate(
                stage = run.status,
                runId = runId,
                segmentIndex = recordedSegmentCount.get(),
                segmentCount = scheduledSegmentCount,
                message = when {
                    state.errorMessage != null -> "实时转写异常：${state.errorMessage}"
                    state.knowledgeTreeStatus == ClassroomKnowledgeTreeProcessingStatus.Updating ->
                        "正在整理课堂知识结构"
                    run.status != VideoRunStatus.Recording && state.changedKnowledgeNodeIds.isNotEmpty() ->
                        "课堂要点已根据完整字幕刷新"
                    run.status != VideoRunStatus.Recording -> "实时转写已收尾"
                    state.knowledgeTreeProgress.addedChars > 0 ->
                        "正在积累课堂要点：${state.knowledgeTreeProgress.addedChars.coerceAtMost(state.knowledgeTreeProgress.requiredChars)}/${state.knowledgeTreeProgress.requiredChars} 字"
                    state.currentTranscript.isNotBlank() -> "实时转写更新"
                    else -> "实时转写准备中"
                },
                templateLabel = task.templateLabel,
                segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                captureIntervalSeconds = task.captureIntervalSeconds,
                streamingEnabled = streamingOutputEnabled,
                isRecordingActive = run.status == VideoRunStatus.Recording,
                isAnalysisActive = run.status == VideoRunStatus.Analyzing,
                recordedSegmentCount = recordedSegmentCount.get(),
                analyzedSegmentCount = analyzedSegmentCount.get(),
                pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get()).coerceAtLeast(0),
                recordedDurationSeconds = recordedDurationSeconds.get(),
                remainingDurationSeconds = (task.plannedDurationSeconds - nextCaptureOffsetSeconds.get()).coerceAtLeast(0),
                segmentFeedbacks = segmentFeedbacks.toList(),
                realtimeTranscript = state.currentTranscript,
                stableTranscript = state.stableTranscript,
                realtimeInsights = state.liveInsights,
                realtimeKnowledgeTree = state.knowledgeTree,
                changedKnowledgeNodeIds = state.changedKnowledgeNodeIds,
                newKnowledgeNodeIds = state.newKnowledgeNodeIds,
                realtimeKnowledgeTreeStatus = state.knowledgeTreeStatus.value,
                realtimeKnowledgeTreeProgress = state.knowledgeTreeProgress,
                realtimeKnowledgeFrameRefs = state.knowledgeFrameRefs,
                realtimeConnectionState = state.connectionState.name,
                realtimeAudioLagMs = state.audioLagMs,
                realtimeDroppedFrameCount = state.droppedFrameCount,
                realtimeBackfillSegmentCount = state.backfillSegmentCount,
                realtimePendingFrameCount = state.pendingFrameCount,
                realtimeAsrLogId = state.asrLogId,
                realtimeSpeechProvider = state.speechProvider.value,
                realtimeSpeechFallbackReason = state.speechFallbackReason,
                realtimeSpeechSessionId = state.speechSessionId,
                errorMessage = null
            )
        }

        fun postCaptureStatus(
            message: String,
            streamingBuffer: String? = null,
            stage: VideoRunStatus = VideoRunStatus.Summarizing,
            analysisActive: Boolean = true
        ): VideoExecutionStatusUpdate = VideoExecutionStatusUpdate(
            stage = stage,
            runId = runId,
            segmentIndex = recordedSegmentCount.get(),
            segmentCount = scheduledSegmentCount,
            message = message,
            templateLabel = task.templateLabel,
            segmentDurationSeconds = task.plannedSegmentDurationSeconds,
            captureIntervalSeconds = task.captureIntervalSeconds,
            streamingBuffer = streamingBuffer,
            streamingEnabled = streamingOutputEnabled,
            isRecordingActive = false,
            isAnalysisActive = analysisActive,
            recordedSegmentCount = recordedSegmentCount.get(),
            analyzedSegmentCount = analyzedSegmentCount.get(),
            pendingSegmentCount = (recordedSegmentCount.get() - analyzedSegmentCount.get()).coerceAtLeast(0),
            recordedDurationSeconds = recordedDurationSeconds.get(),
            remainingDurationSeconds = 0,
            stopRequested = shouldStopRequested(),
            segmentFeedbacks = segmentFeedbacks.toList(),
            realtimeTranscript = latestRealtimeState.currentTranscript,
            stableTranscript = latestRealtimeState.stableTranscript,
            realtimeInsights = latestRealtimeState.liveInsights,
            realtimeKnowledgeTree = latestRealtimeState.knowledgeTree,
            changedKnowledgeNodeIds = latestRealtimeState.changedKnowledgeNodeIds,
            newKnowledgeNodeIds = latestRealtimeState.newKnowledgeNodeIds,
            realtimeKnowledgeTreeStatus = latestRealtimeState.knowledgeTreeStatus.value,
            realtimeKnowledgeTreeProgress = latestRealtimeState.knowledgeTreeProgress,
            realtimeKnowledgeFrameRefs = latestRealtimeState.knowledgeFrameRefs,
            realtimeConnectionState = latestRealtimeState.connectionState.name,
            realtimeAudioLagMs = latestRealtimeState.audioLagMs,
            realtimeDroppedFrameCount = latestRealtimeState.droppedFrameCount,
            realtimeBackfillSegmentCount = latestRealtimeState.backfillSegmentCount,
            realtimePendingFrameCount = latestRealtimeState.pendingFrameCount,
            realtimeAsrLogId = latestRealtimeState.asrLogId,
            realtimeSpeechProvider = latestRealtimeState.speechProvider.value,
            realtimeSpeechFallbackReason = latestRealtimeState.speechFallbackReason,
            realtimeSpeechSessionId = latestRealtimeState.speechSessionId
        )

        val realtimeCoordinator = ClassroomRealtimeFeedbackCoordinator(
            appContext = appContext,
            scope = this,
            transcriptDao = speechTranscriptDao,
            apiService = apiService,
            planningModel = realtimeInsightModel,
            apiKey = apiKey,
            traceLogger = traceLogger,
            frameEvidenceCache = frameEvidenceCache,
            astSourceSubtitleStore = astSourceSubtitleStore,
            onUpdate = { state -> onStatus(realtimeStatusUpdate(state)) }
        )
        val liveAudioSession = if (recordingInput.usesLiveAudioCapture) {
            ClassroomAudioCaptureSession(
                outputFile = File(outputRoot, "video_runs/run_${runId}_continuous_audio.m4a"),
                onFrame = realtimeCoordinator::offer
            )
        } else {
            null
        }
        var liveAudioResult: ContinuousAudioResult? = null
        var liveAudioStarted = false
        var liveAudioClosed = false
        var realtimeStarted = false
        var testAudioJob: Job? = null
        suspend fun closeRealtimeResourcesAfterAbort() = withContext(NonCancellable) {
            if (liveAudioClosed) return@withContext
            testAudioJob?.cancel()
            try {
                if (liveAudioStarted) {
                    liveAudioResult = withContext(Dispatchers.IO) { liveAudioSession?.stop() }
                } else {
                    liveAudioSession?.release()
                }
            } catch (_: Throwable) {
                runCatching { liveAudioSession?.release() }
            } finally {
                liveAudioClosed = true
            }
            if (realtimeStarted) {
                try {
                    realtimeCoordinator.stop()
                } catch (_: Throwable) {
                }
            }
        }

        liveAudioStarted = liveAudioSession?.start() == true
        if (liveAudioStarted) {
            realtimeCoordinator.start(
                runId = runId,
                traceId = traceId,
                task = task,
                sampleRate = 48_000,
                bitsPerSample = 16,
                channelCount = 1,
                speechConfig = speechRecognitionConfig
            )
            realtimeStarted = true
            run = run.copy(
                continuousAudioPath = liveAudioSession?.file?.absolutePath,
                continuousAudioStartedAt = liveAudioSession?.startedAtMs ?: 0L,
                updatedAt = System.currentTimeMillis()
            )
            runDao.upsert(run)
            recordStage("classroom_realtime_audio_started")
        } else if (liveAudioSession != null) {
            recordStage("classroom_realtime_audio_failed")
            latestRealtimeState = latestRealtimeState.copy(
                enabled = false,
                connectionState = ClassroomRealtimeConnectionState.Failed,
                errorMessage = "实时音频采集启动失败，课堂录制将使用分片音频兜底。"
            )
        } else if (recordingInput is ClassroomRecordingInput.TestVideo) {
            val player = TestVideoAudioFramePlayer(
                videoFile = File(recordingInput.localPath),
                startOffsetMs = 0L,
                endOffsetMs = task.plannedDurationSeconds * 1_000L,
                wallClockStartedAtMs = runStartedAt,
                onFrame = realtimeCoordinator::offer
            )
            val format = player.audioFormat()
            if (format != null) {
                realtimeCoordinator.start(
                    runId = runId,
                    traceId = traceId,
                    task = task,
                    sampleRate = format.sampleRate,
                    bitsPerSample = format.bitsPerSample,
                    channelCount = format.channelCount,
                    speechConfig = speechRecognitionConfig
                )
                realtimeStarted = true
                testAudioJob = launch(Dispatchers.IO) {
                    player.play(shouldStopRequested)
                }
                recordStage("classroom_test_video_realtime_audio_started")
            } else {
                recordStage("classroom_test_video_realtime_audio_unavailable")
            }
        }

        onStatus(
            VideoExecutionStatusUpdate(
                stage = VideoRunStatus.Recording,
                runId = runId,
                segmentIndex = 0,
                segmentCount = scheduledSegmentCount,
                message = "Preparing classroom recording",
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
        var producedSegments = 0
        try {
            while (nextCaptureOffsetSeconds.get() < task.plannedDurationSeconds && !shouldStopRequested()) {
                ensureActive()
                val scheduledOffsetSeconds = nextCaptureOffsetSeconds.get()
                val waitMs = (runStartedAt + scheduledOffsetSeconds * 1_000L - System.currentTimeMillis()).coerceAtLeast(0L)
                if (waitMs > 0L) {
                    onStatus(
                        VideoExecutionStatusUpdate(
                            stage = VideoRunStatus.Recording,
                            runId = runId,
                            segmentIndex = producedSegments,
                            segmentCount = scheduledSegmentCount,
                            message = "Waiting for next classroom capture window",
                            templateLabel = task.templateLabel,
                            segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                            captureIntervalSeconds = task.captureIntervalSeconds,
                            streamingEnabled = streamingOutputEnabled,
                            isRecordingActive = false,
                            isAnalysisActive = false,
                            recordedSegmentCount = recordedSegmentCount.get(),
                            analyzedSegmentCount = analyzedSegmentCount.get(),
                            pendingSegmentCount = 0,
                            recordedDurationSeconds = recordedDurationSeconds.get(),
                            remainingDurationSeconds = (task.plannedDurationSeconds - scheduledOffsetSeconds).coerceAtLeast(0),
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
                if (shouldStopRequested()) break

                val remainingDuration = task.plannedDurationSeconds - scheduledOffsetSeconds
                if (remainingDuration <= 0) break
                val segmentNumber = producedSegments + 1
                val actualDuration = minOf(task.plannedSegmentDurationSeconds, remainingDuration).coerceAtLeast(1)
                val recordedSegment = segmentRecorder.recordSegmentClip(
                    runId = runId,
                    task = task,
                    segmentNumber = segmentNumber,
                    segmentCount = scheduledSegmentCount,
                    actualDuration = actualDuration,
                    outputRoot = outputRoot,
                    latestFrameProvider = frameProviderForRecording,
                    startOffsetSeconds = scheduledOffsetSeconds,
                    streamingOutputEnabled = streamingOutputEnabled,
                    recordedSegmentCount = recordedSegmentCount,
                    analyzedSegmentCount = analyzedSegmentCount,
                    recordedDurationSeconds = recordedDurationSeconds,
                    segmentWindowStartedAt = System.currentTimeMillis(),
                    mediaClockStartedAt = runStartedAt,
                    recordingInput = recordingInput,
                    suppressSegmentAudioRecorder = liveAudioStarted,
                    shouldStopRequested = shouldStopRequested,
                    onStatus = onStatus
                )
                producedSegments = segmentNumber
                val producedCount = recordedSegmentCount.incrementAndGet()
                val totalRecordedDuration = recordedDurationSeconds.addAndGet(recordedSegment.durationSeconds.coerceAtLeast(1))
                segmentAudioResults += recordedSegment.hasAudio
                recordedSegments += recordedSegment
                nextCaptureOffsetSeconds.set(scheduledOffsetSeconds + task.captureIntervalSeconds)

                val hasMore = nextCaptureOffsetSeconds.get() < task.plannedDurationSeconds && !shouldStopRequested()
                onStatus(
                    VideoExecutionStatusUpdate(
                        stage = if (hasMore) VideoRunStatus.Recording else VideoRunStatus.Analyzing,
                        runId = runId,
                        segmentIndex = segmentNumber,
                        segmentCount = scheduledSegmentCount,
                        message = if (hasMore) {
                            "Classroom segment $segmentNumber/$scheduledSegmentCount recorded"
                        } else {
                            "Classroom capture finished. Preparing segment analysis"
                        },
                        templateLabel = task.templateLabel,
                        segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                        captureIntervalSeconds = task.captureIntervalSeconds,
                        streamingEnabled = streamingOutputEnabled,
                        isRecordingActive = hasMore,
                        isAnalysisActive = false,
                        recordedSegmentCount = producedCount,
                        analyzedSegmentCount = analyzedSegmentCount.get(),
                        pendingSegmentCount = (producedCount - analyzedSegmentCount.get()).coerceAtLeast(0),
                        recordedDurationSeconds = totalRecordedDuration,
                        remainingDurationSeconds = (task.plannedDurationSeconds - nextCaptureOffsetSeconds.get()).coerceAtLeast(0),
                        nextCaptureInSeconds = if (hasMore) task.captureIntervalSeconds else null,
                        recordingSegmentIndex = (segmentNumber + 1).coerceAtMost(scheduledSegmentCount),
                        stopRequested = shouldStopRequested(),
                        currentSegmentHasAudio = recordedSegment.hasAudio,
                        segmentAudioResults = segmentAudioResults.toList(),
                        segmentFeedbacks = segmentFeedbacks.toList()
                    )
                )
            }

            val captureEndedAt = System.currentTimeMillis()
            if (liveAudioStarted) {
                liveAudioResult = withContext(Dispatchers.IO) { liveAudioSession?.stop() }
                liveAudioClosed = true
                realtimeCoordinator.stop()
                val audioResult = liveAudioResult
                if (audioResult?.hasAudio == true) {
                    run = run.copy(
                        continuousAudioPath = audioResult.file.absolutePath,
                        continuousAudioDurationMs = audioResult.durationMs,
                        continuousAudioStartedAt = audioResult.startedAtMs,
                        audioEnhancementInfo = audioResult.enhancementInfo,
                        updatedAt = System.currentTimeMillis()
                    )
                    runDao.upsert(run)
                    val enrichedSegments = sliceContinuousAudioForSegments(
                        runId = runId,
                        sourceAudio = audioResult.file,
                        audioStartedAtMs = audioResult.startedAtMs,
                        outputRoot = outputRoot,
                        segments = recordedSegments
                    )
                    recordedSegments.clear()
                    recordedSegments.addAll(enrichedSegments)
                    segmentAudioResults.clear()
                    segmentAudioResults.addAll(recordedSegments.map { it.hasAudio })
                    recordStage("classroom_realtime_audio_finalized")
                } else {
                    recordStage("classroom_realtime_audio_empty")
                }
            } else {
                liveAudioSession?.release()
                liveAudioClosed = true
                if (realtimeStarted) {
                    testAudioJob?.cancel()
                    realtimeCoordinator.stop()
                    recordStage("classroom_test_video_realtime_audio_stopped")
                }
            }
            latestRealtimeState = realtimeCoordinator.latestState()
            run = run.copy(status = VideoRunStatus.Summarizing, recordingEndedAt = captureEndedAt, updatedAt = captureEndedAt)
            runDao.upsert(run)

            val transcriptDraft = buildClassroomTranscriptDraft(
                title = task.title.ifBlank { task.userRequirement },
                stableTranscript = latestRealtimeState.stableTranscript,
                realtimeInsights = latestRealtimeState.liveInsights
            )
            recordStage("classroom_transcript_draft_ready")
            onStatus(postCaptureStatus("临时草稿已生成，正在整理录制回放...", transcriptDraft))
            val finalKnowledgeTreeRefreshJob = launch(Dispatchers.IO) {
                runCatching {
                    ClassroomRealtimeDiagnostics.knowledgeTree(
                        "final_update_started run=$runId stableChars=${latestRealtimeState.stableTranscript.length} currentNodes=${ClassroomKnowledgeTreeParser.countNodes(latestRealtimeState.knowledgeTree)}"
                    )
                    val refreshed = realtimeCoordinator.flushKnowledgeTree(reason = "post_capture_final")
                    latestRealtimeState = realtimeCoordinator.latestState()
                    ClassroomRealtimeDiagnostics.knowledgeTree(
                        "final_update_finished run=$runId refreshed=$refreshed nodes=${ClassroomKnowledgeTreeParser.countNodes(latestRealtimeState.knowledgeTree)} active=${ClassroomKnowledgeTreeParser.countActiveNodes(latestRealtimeState.knowledgeTree)}"
                    )
                }.onFailure { error ->
                    ClassroomRealtimeDiagnostics.knowledgeTreeWarning(
                        "final_update_failed run=$runId message=${error.message.orEmpty().take(160)}"
                    )
                }
            }

            var archivedMergedVideoPath: String? = null
            var archivedFullMediaPath: String? = null
            var archivedValidation = MediaValidationResult()
            val masterAudioFile = liveAudioResult
                ?.takeIf { it.hasAudio && it.file.exists() && it.file.length() > 0L }
                ?.file
                ?: runCatching {
                    val segmentFiles = (1..recordedSegmentCount.get()).mapNotNull { index ->
                        File(outputRoot, "video_runs/run_${runId}_segment_${index}_audio.m4a")
                            .takeIf { it.exists() && it.length() > 0L }
                    }
                    audioOutlineProcessor.buildMasterAudioFromFiles(
                        runId = runId,
                        segmentFiles = segmentFiles,
                        outputRoot = outputRoot,
                        expectedDurationMs = recordedDurationSeconds.get() * 1_000L
                    )
                }.getOrNull()
            val masterAudioPath = masterAudioFile?.absolutePath
            if (masterAudioFile != null) {
                run = run.copy(
                    continuousAudioPath = masterAudioPath,
                    continuousAudioDurationMs = recordedDurationSeconds.get() * 1_000L,
                    updatedAt = System.currentTimeMillis()
                )
                runDao.upsert(run)
            }

            var earlyMediaMergeError: String? = null
            recordStage("classroom_early_media_archive_started")
            onStatus(postCaptureStatus("正在整理录制回放...", transcriptDraft))
            Log.i(
                CLASSROOM_MEDIA_TAG,
                "early archive started run=$runId segments=${recordedSegments.size} " +
                    "audio=${masterAudioFile?.exists() == true} source=${recordingInput.sourceId}"
            )
            val earlyMergedVideoPath = runCatching {
                withContext(Dispatchers.IO) {
                    mediaAssembler.mergeRecordedSegmentVideos(runId, recordedSegments, outputRoot)
                }
            }.onFailure { error ->
                earlyMediaMergeError = error.message ?: "early media archive failed"
                recordStage("classroom_early_media_archive_failed")
                Log.w(CLASSROOM_MEDIA_TAG, "early video merge failed run=$runId message=${error.message}")
            }.getOrNull()

            if (earlyMergedVideoPath != null) {
                val candidateFullMediaPath = withContext(Dispatchers.IO) {
                    mediaAssembler.mergeVideoWithMasterAudio(
                        runId = runId,
                        videoPath = earlyMergedVideoPath,
                        audioPath = masterAudioPath ?: run.continuousAudioPath,
                        outputRoot = outputRoot
                    )
                } ?: earlyMergedVideoPath
                val candidateValidation = withContext(Dispatchers.IO) {
                    mediaAssembler.validateMedia(candidateFullMediaPath)
                }
                if (candidateValidation.errorMessage == null) {
                    archivedMergedVideoPath = earlyMergedVideoPath
                    archivedFullMediaPath = candidateFullMediaPath
                    archivedValidation = candidateValidation
                    mediaAssembler.recordDerivedVideoAssetBinding(runId, earlyMergedVideoPath, VideoRemoteAssetKind.MasterVideo)
                    run = run.copy(
                        mergedVideoPath = archivedMergedVideoPath,
                        fullMediaPath = archivedFullMediaPath ?: archivedMergedVideoPath,
                        fullMediaDurationMs = archivedValidation.durationMs.takeIf { it > 0L }
                            ?: recordedDurationSeconds.get() * 1_000L,
                        fullMediaHasAudio = archivedValidation.hasAudio,
                        fullMediaVideoSource = finalMediaSource,
                        updatedAt = System.currentTimeMillis()
                    )
                    runDao.upsert(run)
                    recordStage("classroom_early_media_archive_completed")
                    Log.i(
                        CLASSROOM_MEDIA_TAG,
                        "early archive completed run=$runId merged=$archivedMergedVideoPath full=$archivedFullMediaPath " +
                            "durationMs=${archivedValidation.durationMs} hasAudio=${archivedValidation.hasAudio}"
                    )
                    onStatus(
                        postCaptureStatus("录制回放已生成，正在生成音频大纲...", transcriptDraft)
                            .copy(playbackPath = archivedFullMediaPath ?: archivedMergedVideoPath)
                    )
                } else {
                    earlyMediaMergeError = candidateValidation.errorMessage
                    recordStage("classroom_early_media_archive_failed_validation")
                    Log.w(
                        CLASSROOM_MEDIA_TAG,
                        "early archive validation failed run=$runId message=${candidateValidation.errorMessage}"
                    )
                    onStatus(postCaptureStatus("录制回放整理失败，正在生成音频大纲...", transcriptDraft))
                }
            } else {
                onStatus(postCaptureStatus("录制回放整理失败，正在生成音频大纲...", transcriptDraft))
            }

            var outlineMarkdown = ""
            if (masterAudioFile != null) {
                onStatus(postCaptureStatus("正在生成课堂音频大纲...", transcriptDraft))
                recordStage("classroom_audio_outline_started")
                runCatching {
                    audioOutlineProcessor.generateAudioOutline(
                        runId = runId,
                        audioFile = masterAudioFile,
                        task = task,
                        durationSeconds = recordedDurationSeconds.get(),
                        traceId = traceId
                    )
                }.onSuccess { outline ->
                    recordStage("classroom_audio_outline_completed")
                    outlineMarkdown = outline.markdownNote.ifBlank { outline.rawResponse }
                    run = run.copy(
                        outlineMarkdown = outlineMarkdown,
                        outlineGeneratedAt = System.currentTimeMillis(),
                        markdownNote = outlineMarkdown,
                        finalSummary = outline.summary,
                        reportVersion = 0,
                        updatedAt = System.currentTimeMillis()
                    )
                    runDao.upsert(run)
                    onStatus(postCaptureStatus("音频大纲已生成，正在补强视频分片证据...", outlineMarkdown))
                }.onFailure {
                    recordStage("classroom_audio_outline_failed")
                    Log.w(CLASSROOM_ORCHESTRATOR_TAG, "Classroom audio outline failed: ${it.message}")
                    onStatus(postCaptureStatus("音频大纲生成失败，保留临时草稿并继续补强视频分片证据...", transcriptDraft))
                }
            } else {
                recordStage("classroom_audio_outline_skipped")
                onStatus(postCaptureStatus("音频母带暂不可用，保留临时草稿并继续补强视频分片证据...", transcriptDraft))
            }

            run = run.copy(status = VideoRunStatus.Analyzing, updatedAt = System.currentTimeMillis())
            runDao.upsert(run)
            recordStage("classroom_segment_analysis_started")
            onStatus(postCaptureStatus("正在补强视频分片证据...", outlineMarkdown.ifBlank { transcriptDraft }, VideoRunStatus.Analyzing))

            recordedSegments.forEach { recordedSegment ->
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
                    if (error is CancellationException) throw error
                    segmentProcessor.markRecordedSegmentAnalysisFailed(recordedSegment, error)
                }
                segmentResults += result
                val analyzedCount = analyzedSegmentCount.incrementAndGet()
                segmentFeedbacks += VideoSegmentFeedback(
                    segmentIndex = recordedSegment.segmentNumber,
                    summary = result.analysisResult.summary,
                    conclusion = result.analysisResult.conclusion
                )
                onStatus(
                    VideoExecutionStatusUpdate(
                        stage = VideoRunStatus.Analyzing,
                        runId = runId,
                        segmentIndex = recordedSegment.segmentNumber,
                        segmentCount = scheduledSegmentCount,
                        message = "Classroom segment ${recordedSegment.segmentNumber}/$scheduledSegmentCount facts extracted",
                        templateLabel = task.templateLabel,
                        segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                        captureIntervalSeconds = task.captureIntervalSeconds,
                        streamingEnabled = streamingOutputEnabled,
                        isRecordingActive = false,
                        isAnalysisActive = analyzedCount < recordedSegmentCount.get(),
                        recordedSegmentCount = recordedSegmentCount.get(),
                        analyzedSegmentCount = analyzedCount,
                        pendingSegmentCount = (recordedSegmentCount.get() - analyzedCount).coerceAtLeast(0),
                        recordedDurationSeconds = recordedDurationSeconds.get(),
                        remainingDurationSeconds = 0,
                        segmentFeedbacks = segmentFeedbacks.toList()
                    )
                )
            }

            run = run.copy(status = VideoRunStatus.Summarizing, updatedAt = System.currentTimeMillis())
            runDao.upsert(run)
            recordStage("classroom_segment_analysis_completed")

            onStatus(summarizingStatus(runId, task, scheduledSegmentCount, recordedSegmentCount, analyzedSegmentCount, recordedDurationSeconds, "正在合成课堂笔记...", segmentFeedbacks, streamingOutputEnabled))
            val successfulSegmentResults = segmentResults.filter { it.segment.status == VideoRunStatus.Completed }
            val failedAnalysisCount = segmentResults.count { it.segment.status == VideoRunStatus.Failed }
            var visualEvidenceFailureCount = 0
            val visualEvidenceResults = visualEvidenceAnalyzer?.let { analyzer ->
                val targets = successfulSegmentResults
                    .filter(::needsVisualEvidenceSupplement)
                    .mapNotNull { result ->
                        result.segment.arkFileId
                            ?.takeIf(String::isNotBlank)
                            ?.let { fileId -> result to fileId }
                    }
                    .take(MAX_VISUAL_SUPPLEMENT_SEGMENTS)
                if (targets.isEmpty()) {
                    emptyList()
                } else {
                    recordStage("classroom_visual_evidence_started")
                    val supplements = targets.mapNotNull { (result, fileId) ->
                        val timeRange = result.segment.mediaStartMs
                            ?.let { startMs ->
                                val endMs = result.segment.mediaEndMs ?: (startMs + result.segment.durationMs)
                                "${startMs / 1000}-${endMs / 1000}s"
                            }
                            ?: "segment_${result.segment.segmentIndex}"
                        runCatching {
                            analyzer.analyze(
                                fileId = fileId,
                                task = task,
                                segmentNumber = result.segment.segmentIndex,
                                segmentCount = scheduledSegmentCount,
                                timeRange = timeRange,
                                traceId = traceId,
                                runId = runId
                            )
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            visualEvidenceFailureCount += 1
                            Log.w(CLASSROOM_ORCHESTRATOR_TAG, "Classroom visual evidence supplement failed: ${error.message}")
                        }.getOrNull()
                    }
                    recordStage("classroom_visual_evidence_completed")
                    supplements
                }
            }.orEmpty()
            finalKnowledgeTreeRefreshJob.join()
            latestRealtimeState = realtimeCoordinator.latestState()
            val coverageNotices = buildList {
                if (outlineMarkdown.isBlank()) {
                    add("完整音频大纲不可用，课堂结构主要来自分片事实包。")
                }
                if (latestRealtimeState.stableTranscript.isBlank()) {
                    add("实时转写不可用或未产出稳定文本。")
                }
                if (latestRealtimeState.backfillSegmentCount > 0 || latestRealtimeState.droppedFrameCount > 0) {
                    add("实时转写存在网络或队列缺口，最终笔记已优先使用本地音频证据。")
                }
                segmentResults.mapNotNullTo(this) { it.coverageLimitation }
                if (failedAnalysisCount > 0) {
                    add("$failedAnalysisCount 个课堂分片分析失败，笔记仅使用可用证据。")
                }
                if (visualEvidenceFailureCount > 0) {
                    add("$visualEvidenceFailureCount 个可选视觉证据补充失败。")
                }
                if (shouldStopRequested() && recordedSegmentCount.get() > 0) {
                    add("用户手动停止，笔记基于已录制片段生成。")
                }
            }
            var noteSynthesisFailed = false
            val finalResult = runCatching {
                noteSynthesizer.synthesize(
                    task = task,
                    results = successfulSegmentResults.ifEmpty { segmentResults },
                    outlineMarkdown = outlineMarkdown,
                    realtimeTranscript = latestRealtimeState.stableTranscript,
                    coverageNotices = coverageNotices,
                    visualEvidence = visualEvidenceResults,
                    traceId = traceId,
                    runId = runId
                )
            }.getOrElse { error ->
                noteSynthesisFailed = true
                recordStage("classroom_note_synthesis_failed")
                Log.w(CLASSROOM_ORCHESTRATOR_TAG, "Classroom note synthesis failed: ${error.message}")
                noteSynthesizer.fallbackFromAvailableEvidence(
                    task = task,
                    results = segmentResults,
                    outlineMarkdown = outlineMarkdown,
                    realtimeTranscript = latestRealtimeState.stableTranscript,
                    coverageNotices = coverageNotices + "课堂笔记合成模型调用失败，已使用可用证据降级生成。",
                    visualEvidence = visualEvidenceResults
                )
            }
            recordStage("classroom_note_synthesis_completed")

            val (mergedVideoPath, mergeError) = if (!archivedMergedVideoPath.isNullOrBlank()) {
                archivedMergedVideoPath to null
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        mediaAssembler.mergeRecordedSegmentVideos(runId, recordedSegments, outputRoot)
                    }
                }.fold(onSuccess = { it to null }, onFailure = { null to (it.message ?: earlyMediaMergeError) })
            }
            if (archivedMergedVideoPath.isNullOrBlank()) {
                mergedVideoPath?.let {
                    mediaAssembler.recordDerivedVideoAssetBinding(runId, it, VideoRemoteAssetKind.MasterVideo)
                }
            }
            val finalMasterAudioPath = masterAudioPath
                ?: run.continuousAudioPath
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
            val fullMediaPath = archivedFullMediaPath ?: withContext(Dispatchers.IO) {
                mediaAssembler.mergeVideoWithMasterAudio(
                    runId = runId,
                    videoPath = mergedVideoPath,
                    audioPath = finalMasterAudioPath,
                    outputRoot = outputRoot
                )
            }
            val validation = if (!archivedMergedVideoPath.isNullOrBlank()) {
                archivedValidation
            } else {
                withContext(Dispatchers.IO) {
                    mediaAssembler.validateMedia(fullMediaPath ?: mergedVideoPath)
                }
            }
            val manualStopWithSegments = shouldStopRequested() && recordedSegmentCount.get() > 0
            val degradedReason = listOfNotNull(
                if (manualStopWithSegments) "用户手动停止，已基于已录制片段生成课堂笔记。" else null,
                if (failedAnalysisCount > 0) "$failedAnalysisCount 个课堂分片分析失败。" else null,
                if (noteSynthesisFailed) "课堂笔记合成模型调用失败，已使用可用证据降级生成。" else null,
                mergeError?.let { "视频合并失败：$it" },
                validation.errorMessage?.takeIf { mergedVideoPath != null }?.let { "媒体校验失败：$it" }
            ).joinToString("; ").ifBlank { null }
            val finalStatus = if (degradedReason == null && mergedVideoPath != null && validation.errorMessage == null) {
                VideoRunStatus.Completed
            } else {
                VideoRunStatus.CompletedDegraded
            }
            run = run.copy(
                status = finalStatus,
                recordingEndedAt = run.recordingEndedAt ?: System.currentTimeMillis(),
                continuousAudioPath = run.continuousAudioPath ?: finalMasterAudioPath,
                continuousAudioDurationMs = run.continuousAudioDurationMs.takeIf { it > 0L } ?: recordedDurationSeconds.get() * 1_000L,
                finalSummary = finalResult.summary,
                finalConclusion = finalResult.conclusion,
                rawModelSummary = finalResult.rawResponse,
                structuredNoteJson = finalResult.structuredNoteJson,
                markdownNote = finalResult.markdownNote,
                mergedVideoPath = mergedVideoPath ?: run.mergedVideoPath,
                fullMediaPath = fullMediaPath ?: mergedVideoPath ?: run.fullMediaPath,
                fullMediaDurationMs = validation.durationMs.takeIf { it > 0L } ?: recordedDurationSeconds.get() * 1_000L,
                fullMediaHasAudio = validation.hasAudio,
                fullMediaVideoSource = finalMediaSource,
                audioEnhancementInfo = segmentResults.firstOrNull { it.audioEnhancementInfo.isNotBlank() }?.audioEnhancementInfo.orEmpty(),
                errorMessage = null,
                degradedReason = degradedReason,
                mergedSegmentCountActual = successfulSegmentResults.count { it.analysisInputMode == SegmentAnalysisInputMode.MergedSegmentVideo },
                segmentsMissingMergedAnalysisAsset = successfulSegmentResults.count { it.analysisInputMode == SegmentAnalysisInputMode.VideoOnly },
                audioOutlineAvailable = outlineMarkdown.isNotBlank(),
                reportPipelineStagesJson = pipelineStages.joinToString(",", "[", "]") { "\"$it\"" },
                classroomKnowledgeTreeJson = latestRealtimeState.knowledgeTree.toPersistedClassroomKnowledgeTreeJson(),
                classroomKnowledgeFrameRefsJson = latestRealtimeState.knowledgeFrameRefs.toPersistedClassroomKnowledgeFrameRefsJson(),
                classroomKnowledgeTreeStatus = latestRealtimeState.knowledgeTreeStatus.value,
                classroomKnowledgeTreeUpdatedAt = latestRealtimeState.knowledgeTree?.updatedAtMs ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            runDao.upsert(run)
            timelineEventDao.deleteByRunId(runId)
            timelineEventDao.insertAll(
                finalResult.timelineEvents.map { event ->
                    TimelineEventEntity(
                        runId = runId,
                        timestampSeconds = event.timestampSeconds,
                        title = event.title,
                        detail = event.detail,
                        confidence = event.confidence
                    )
                }
            )
            traceLogger.finishNode(orchestrationTrace, System.currentTimeMillis() - now)
            onStatus(
                VideoExecutionStatusUpdate(
                    stage = run.status,
                    runId = runId,
                    segmentIndex = recordedSegmentCount.get(),
                    segmentCount = scheduledSegmentCount,
                    message = if (run.status == VideoRunStatus.CompletedDegraded) {
                        run.degradedReason ?: "课堂记录完成，但部分证据降级"
                    } else {
                        "课堂记录完成"
                    },
                    degradedReason = run.degradedReason,
                    templateLabel = task.templateLabel,
                    segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = task.captureIntervalSeconds,
                    finalSummary = finalResult.summary,
                    finalConclusion = finalResult.conclusion,
                    streamingEnabled = streamingOutputEnabled,
                    streamingBuffer = finalResult.markdownNote.ifBlank { finalResult.rawResponse },
                    playbackPath = run.fullMediaPath ?: run.mergedVideoPath,
                    realtimeKnowledgeTree = latestRealtimeState.knowledgeTree,
                    changedKnowledgeNodeIds = latestRealtimeState.changedKnowledgeNodeIds,
                    newKnowledgeNodeIds = latestRealtimeState.newKnowledgeNodeIds,
                    realtimeKnowledgeTreeStatus = latestRealtimeState.knowledgeTreeStatus.value,
                    realtimeKnowledgeTreeProgress = latestRealtimeState.knowledgeTreeProgress,
                    realtimeKnowledgeFrameRefs = latestRealtimeState.knowledgeFrameRefs,
                    isRecordingActive = false,
                    isAnalysisActive = false,
                    recordedSegmentCount = recordedSegmentCount.get(),
                    analyzedSegmentCount = analyzedSegmentCount.get(),
                    pendingSegmentCount = 0,
                    recordedDurationSeconds = recordedDurationSeconds.get(),
                    remainingDurationSeconds = (task.plannedDurationSeconds - nextCaptureOffsetSeconds.get()).coerceAtLeast(0),
                    segmentFeedbacks = segmentFeedbacks.toList()
                )
            )
            VideoExecutionResult(task, run, finalResult)
        } catch (cancelled: CancellationException) {
            closeRealtimeResourcesAfterAbort()
            traceLogger.logError(orchestrationTrace, cancelled, System.currentTimeMillis() - now)
            throw cancelled
        } catch (error: Throwable) {
            closeRealtimeResourcesAfterAbort()
            traceLogger.logError(orchestrationTrace, error, System.currentTimeMillis() - now)
            throw error
        }
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

        val failureMessage = error.toUserMessage("Classroom recording failed.")
        existingRun.aiTraceId.takeIf(String::isNotBlank)?.let { traceId ->
            traceLogger.logError(
                VideoAiTraceContext(
                    traceId = traceId,
                    runId = runId,
                    taskId = existingRun.taskId,
                    node = "ClassroomRecordingOrchestrator",
                    segmentIndex = segmentIndex,
                    requestKind = "classroom_recording_run"
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
                finalSummary = existingRun.finalSummary,
                finalConclusion = existingRun.finalConclusion,
                streamingBuffer = existingRun.markdownNote.ifBlank { existingRun.rawModelSummary },
                streamingEnabled = streamingEnabled,
                errorMessage = failureMessage,
                isRecordingActive = false,
                isAnalysisActive = false,
                recordedSegmentCount = existingRun.segmentCount,
                recordedDurationSeconds = existingRun.totalDurationSeconds,
                activeStreamingSegmentIndex = 0,
                degradedReason = existingRun.degradedReason
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
                    node = "ClassroomRecordingOrchestrator",
                    segmentIndex = segmentIndex,
                    requestKind = "classroom_recording_run"
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
                message = "课堂录制已取消",
                templateLabel = existingRun.templateLabel,
                segmentDurationSeconds = existingRun.segmentDurationSeconds,
                captureIntervalSeconds = existingRun.captureIntervalSeconds,
                finalSummary = existingRun.finalSummary,
                finalConclusion = existingRun.finalConclusion,
                streamingBuffer = existingRun.markdownNote.ifBlank { existingRun.rawModelSummary },
                streamingEnabled = streamingEnabled,
                isRecordingActive = false,
                isAnalysisActive = false,
                recordedSegmentCount = existingRun.segmentCount,
                recordedDurationSeconds = existingRun.totalDurationSeconds,
                activeStreamingSegmentIndex = 0,
                degradedReason = existingRun.degradedReason
            )
        )
    }

    private fun summarizingStatus(
        runId: Long,
        task: VideoProcessTaskDraft,
        segmentCount: Int,
        recordedSegmentCount: AtomicInteger,
        analyzedSegmentCount: AtomicInteger,
        recordedDurationSeconds: AtomicInteger,
        message: String,
        segmentFeedbacks: List<VideoSegmentFeedback>,
        streamingOutputEnabled: Boolean
    ): VideoExecutionStatusUpdate = VideoExecutionStatusUpdate(
        stage = VideoRunStatus.Summarizing,
        runId = runId,
        segmentIndex = recordedSegmentCount.get(),
        segmentCount = segmentCount,
        message = message,
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
        segmentFeedbacks = segmentFeedbacks
    )

    private fun needsVisualEvidenceSupplement(result: SegmentExecutionResult): Boolean {
        if (result.analysisInputMode == SegmentAnalysisInputMode.VideoOnly) {
            return true
        }
        val evidence = result.analysisResult.evidenceJson
            .replace(" ", "")
            .replace("\n", "")
            .lowercase()
        return evidence.isBlank() ||
            !evidence.contains("boardorscreenevidence") ||
            evidence.contains("\"boardorscreenevidence\":[]") ||
            evidence.contains("\"visualevidence\":[]")
    }

    private suspend fun sliceContinuousAudioForSegments(
        runId: Long,
        sourceAudio: File,
        audioStartedAtMs: Long,
        outputRoot: File,
        segments: List<RecordedSegment>
    ): List<RecordedSegment> = withContext(Dispatchers.IO) {
        if (!sourceAudio.exists() || sourceAudio.length() <= 0L) {
            return@withContext segments
        }
        val slicer = AudioSegmentSlicer()
        segments.map { segment ->
            val startMs = (segment.wallClockStartTime - audioStartedAtMs).coerceAtLeast(0L)
            val endMs = (segment.wallClockEndTime - audioStartedAtMs).coerceAtLeast(startMs + 1_000L)
            val audioFile = File(outputRoot, "video_runs/run_${runId}_segment_${segment.segmentNumber}_audio.m4a")
            val sliced = slicer.sliceAudio(
                sourceFile = sourceAudio,
                startMs = startMs,
                endMs = endMs,
                outputFile = audioFile
            )
            if (sliced && audioFile.exists() && audioFile.length() > 0L) {
                segment.copy(
                    hasAudio = true,
                    audioAssetPath = audioFile.absolutePath,
                    audioDiagnosticsJson = "source=classroom_audio_tee,startMs=$startMs,endMs=$endMs"
                )
            } else {
                segment
            }
        }
    }

    private companion object {
        private const val STOP_POLL_INTERVAL_MS = 250L
        private const val MAX_VISUAL_SUPPLEMENT_SEGMENTS = 3
    }
}

package com.example.watcher.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.watcher.R
import com.example.watcher.WatcherForegroundService
import com.example.watcher.data.model.ClassroomInlineQuestionType
import com.example.watcher.data.model.ClassroomInlineQuestionUiState
import com.example.watcher.data.model.ClassroomNoteFollowupUiState
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.ClassroomRecordingDefaults
import com.example.watcher.data.model.ClassroomRecordingInput
import com.example.watcher.data.model.ClassroomSpeechRecognitionConfig
import com.example.watcher.data.model.ClassroomKnowledgeTreeProgress
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.acceptsPreviewFrameSource
import com.example.watcher.data.model.usesLiveFrameProvider
import com.example.watcher.data.repository.buildClassroomTranscriptDraft
import com.example.watcher.data.repository.ClassroomTestVideoImporter
import com.example.watcher.data.repository.VideoExecutionStatusUpdate
import com.example.watcher.data.repository.VideoProcessRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoWorkflowController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val videoRepository: VideoProcessRepository,
    private val latestFrameProvider: () -> Bitmap?,
    private val latestFrameSourceProvider: () -> String = { "" }
) {
    private val _videoPlanUiState = MutableStateFlow<VideoPlanUiState>(VideoPlanUiState.Idle)
    private val _currentVideoTask = MutableStateFlow<VideoProcessTaskDraft?>(null)
    private val _videoProcessingStatus = MutableStateFlow(VideoProcessingStatus())
    private val _classroomRecordingInput = MutableStateFlow<ClassroomRecordingInput>(ClassroomRecordingInput.LiveCamera)
    val selectedVideoRunIdState = MutableStateFlow<Long?>(null)
    val selectedVideoRunEventsState = MutableStateFlow<List<TimelineEventEntity>>(emptyList())

    private var videoProcessingJob: Job? = null
    private var videoStopRequested = AtomicBoolean(false)
    private var classroomTranscriptJob: Job? = null
    private var observedClassroomTranscriptRunId: Long? = null
    private var classroomNoteFollowupJob: Job? = null
    private var observedClassroomNoteFollowupRunId: Long? = null
    private var classroomNoteMaterialJob: Job? = null
    private var observedClassroomNoteMaterialRunId: Long? = null
    private var videoWorkflowSessionId: Long = 0L

    val videoPlanUiState: StateFlow<VideoPlanUiState> = _videoPlanUiState.asStateFlow()
    val currentVideoTask: StateFlow<VideoProcessTaskDraft?> = _currentVideoTask.asStateFlow()
    val videoProcessingStatus: StateFlow<VideoProcessingStatus> = _videoProcessingStatus.asStateFlow()
    val classroomRecordingInput: StateFlow<ClassroomRecordingInput> = _classroomRecordingInput.asStateFlow()
    val selectedVideoRunId: StateFlow<Long?> = selectedVideoRunIdState.asStateFlow()
    val selectedVideoRunEvents: StateFlow<List<TimelineEventEntity>> = selectedVideoRunEventsState.asStateFlow()

    fun analyzeVideoIntent(userInput: String) {
        if (userInput.isBlank()) {
            _videoPlanUiState.value = VideoPlanUiState.Error(
                appContext.getString(R.string.error_empty_request)
            )
            return
        }

        scope.launch {
            showVideoPlanningInProgress()
            videoRepository.planVideoTask(userInput, latestFrameProvider())
                .onSuccess { plan ->
                    val draft = plan.toDraft(userInput)
                    showVideoDraftReady(
                        draft = draft,
                        message = "Plan generated. Review it or tweak it before recording.",
                    )
                }
                .onFailure { error ->
                    _videoPlanUiState.value = VideoPlanUiState.Error(error.message ?: "瑙嗛瑙勫垝澶辫触")
                    _videoProcessingStatus.value = VideoProcessingStatus(
                        stage = VideoRunStatus.Failed,
                        message = "瑙嗛瑙勫垝澶辫触",
                        errorMessage = error.message,
                        isBusy = false
                    )
                }
        }
    }

    fun loadVideoTask(task: VideoProcessTask) {
        showVideoDraftReady(
            draft = VideoProcessTaskDraft.fromEntity(task),
            message = "Video task loaded. You can refine it or start recording now.",
        )
    }

    fun showVideoDraft(draft: VideoProcessTaskDraft, message: String) {
        showVideoDraftReady(draft = draft, message = message)
    }

    fun saveVideoTask(draft: VideoProcessTaskDraft) {
        scope.launch {
            val normalized = draft.normalized()
            _currentVideoTask.value = normalized
            _videoPlanUiState.value = VideoPlanUiState.Success(normalized)
            _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                stage = VideoRunStatus.AwaitingConfirmation,
                activeTask = normalized,
                message = "淇濆瓨涓?..",
                errorMessage = null,
                isTaskSaving = true,
                isBusy = false
            )
            runCatching { videoRepository.saveTask(normalized) }
                .onSuccess { saved ->
                    showVideoDraftReady(
                        draft = saved,
                        message = "Video task saved.",
                    )
                }
                .onFailure { error ->
                    _videoPlanUiState.value = VideoPlanUiState.Error(error.message ?: "瑙嗛浠诲姟淇濆瓨澶辫触")
                    _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                        activeTask = normalized,
                        message = "保存失败",
                        errorMessage = error.message,
                        isTaskSaving = false,
                        isBusy = false
                    )
                }
        }
    }

    fun deleteVideoTask(id: Long) {
        scope.launch {
            if (_currentVideoTask.value?.taskId == id) {
                clearSelectedVideoTaskState()
            }
            videoRepository.deleteTask(id)
        }
    }

    fun startVideoProcessing(
        task: VideoProcessTaskDraft? = _currentVideoTask.value,
        streamingOutputEnabled: Boolean = false
    ) = launchVideoProcessing(task = task, streamingOutputEnabled = streamingOutputEnabled)

    fun stopVideoProcessing() = requestStopVideoProcessing()

    fun resetClassroomRecording() {
        resetVideoProcessingJob()
        _classroomRecordingInput.value = ClassroomRecordingInput.LiveCamera
        clearSelectedVideoTaskState()
    }

    fun selectClassroomTestVideo(uri: Uri) {
        scope.launch {
            _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                message = "Importing test video...",
                errorMessage = null
            )
            runCatching { ClassroomTestVideoImporter(appContext).import(uri) }
                .onSuccess { input ->
                    _classroomRecordingInput.value = input
                    _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                        message = "Test video ready: ${input.displayName}",
                        errorMessage = null
                    )
                }
                .onFailure { error ->
                    _classroomRecordingInput.value = ClassroomRecordingInput.LiveCamera
                    _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                        message = "Test video import failed.",
                        errorMessage = error.message ?: "Unable to import selected video."
                    )
                }
        }
    }

    fun clearClassroomTestVideo() {
        _classroomRecordingInput.value = ClassroomRecordingInput.LiveCamera
    }

    fun cleanupClassroomTestVideoCache() {
        scope.launch {
            val keepPath = (_classroomRecordingInput.value as? ClassroomRecordingInput.TestVideo)?.localPath
            runCatching {
                ClassroomTestVideoImporter(appContext).cleanupCache(keepPath = keepPath)
            }.onSuccess { result ->
                _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                    message = "已清理 ${result.deletedCount} 个测试视频缓存，释放 ${formatStorageSize(result.bytesFreed)}。",
                    errorMessage = null
                )
            }.onFailure { error ->
                _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                    message = "测试视频缓存清理失败。",
                    errorMessage = error.message ?: "Unable to clean test video cache."
                )
            }
        }
    }

    fun startClassroomRecording(
        courseName: String,
        durationSeconds: Int,
        speechRecognitionConfig: ClassroomSpeechRecognitionConfig = ClassroomSpeechRecognitionConfig.Default,
        recordingInputOverride: ClassroomRecordingInput? = null
    ) {
        val input = recordingInputOverride ?: _classroomRecordingInput.value
        val currentFrameSource = latestFrameSourceProvider()
        if (input.usesLiveFrameProvider && !input.acceptsPreviewFrameSource(currentFrameSource)) {
            _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                stage = VideoRunStatus.AwaitingConfirmation,
                message = "视频源不一致，请重新连接预览画面后再开始录课。",
                errorMessage = "当前预览源为 $currentFrameSource，但本次录制源为 ${input.sourceId}。课堂录制不会使用非预览画面。",
                isBusy = false
            )
            return
        }
        if (input.usesLiveFrameProvider && latestFrameProvider() == null) {
            _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                stage = VideoRunStatus.AwaitingConfirmation,
                message = "视频流未就绪，请确认预览画面已连接后再开始录课。",
                errorMessage = "课堂录制需要使用当前预览画面作为唯一视觉源，但当前没有可用视频帧。",
                isBusy = false
            )
            return
        }
        _classroomRecordingInput.value = input
        val effectiveDurationSeconds = when (input) {
            is ClassroomRecordingInput.TestVideo -> {
                val sourceDurationSeconds = (input.durationMs / 1_000L).toInt().coerceAtLeast(1)
                minOf(durationSeconds, sourceDurationSeconds).coerceAtLeast(1)
            }
            else -> durationSeconds
        }
        val draft = ClassroomRecordingDefaults.buildDraft(courseName, effectiveDurationSeconds)
        _currentVideoTask.value = draft
        _videoPlanUiState.value = VideoPlanUiState.Success(draft)
        launchVideoProcessing(
            task = draft,
            streamingOutputEnabled = true,
            recordingInput = input,
            classroomPipeline = true,
            speechRecognitionConfig = speechRecognitionConfig
        )
    }

    fun selectVideoRun(runId: Long?) {
        observeClassroomTranscripts(null)
        observeClassroomNoteFollowups(runId)
        observeClassroomNoteMaterials(runId)
        selectedVideoRunIdState.value = runId
    }

    fun openClassroomRecordingRun(runId: Long) {
        val sessionId = advanceVideoWorkflowSession()
        scope.launch {
            val run = withContext(Dispatchers.IO) {
                videoRepository.getRunById(runId)
            }
            if (!isCurrentVideoWorkflowSession(sessionId)) {
                return@launch
            }
            if (run == null || run.recordingScenario != RecordingScenario.ClassLecture.value) {
                selectedVideoRunIdState.value = runId
                return@launch
            }
            selectedVideoRunIdState.value = runId
            observeClassroomNoteFollowups(runId)
            observeClassroomNoteMaterials(runId)
            hydrateClassroomCompletedRun(run, sessionId)
            // Defer transcript loading to avoid competing with initial UI render
            delay(300L)
            if (isCurrentVideoWorkflowSession(sessionId)) {
                observeClassroomTranscripts(runId)
            }
        }
    }

    fun toggleClassroomTranscriptSelection(transcriptId: Long) {
        val runId = _videoProcessingStatus.value.activeRunId ?: selectedVideoRunIdState.value ?: return
        scope.launch {
            runCatching { videoRepository.toggleClassroomTranscriptSelection(runId, transcriptId) }
                .onFailure { error ->
                    _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                        inlineQuestionState = _videoProcessingStatus.value.inlineQuestionState.copy(
                            visible = true,
                            isLoading = false,
                            errorMessage = error.message ?: "字幕选择失败"
                        )
                    )
                }
        }
    }

    fun answerClassroomInlineQuestion(questionType: ClassroomInlineQuestionType) {
        val status = _videoProcessingStatus.value
        val runId = status.activeRunId ?: selectedVideoRunIdState.value ?: return
        val selectedIds = status.realtimeTranscriptItems
            .filter { it.isSelected && !it.isAnswered }
            .mapNotNull { it.transcriptId }
        _videoProcessingStatus.value = status.copy(
            inlineQuestionState = ClassroomInlineQuestionUiState(
                visible = true,
                isLoading = true,
                questionType = questionType,
                selectedTranscriptIds = selectedIds
            )
        )
        scope.launch {
            runCatching {
                videoRepository.answerClassroomInlineQuestion(
                    runId = runId,
                    questionType = questionType,
                    realtimeInsights = _videoProcessingStatus.value.realtimeInsights
                )
            }.onSuccess { result ->
                _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                    inlineQuestionState = ClassroomInlineQuestionUiState(
                        visible = true,
                        isLoading = false,
                        questionType = questionType,
                        answerText = result.answerText,
                        selectedTranscriptIds = selectedIds,
                        visualFramePath = result.visualFramePath,
                        visualFrameTimestampMs = result.visualFrameTimestampMs,
                        visualFrameStatus = result.visualFrameStatus
                    )
                )
            }.onFailure { error ->
                _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                    inlineQuestionState = ClassroomInlineQuestionUiState(
                        visible = true,
                        isLoading = false,
                        questionType = questionType,
                        errorMessage = error.message ?: "课堂即时解释失败",
                        selectedTranscriptIds = selectedIds
                    )
                )
            }
        }
    }

    fun dismissClassroomInlineQuestion() {
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            inlineQuestionState = ClassroomInlineQuestionUiState()
        )
    }

    fun askClassroomNoteFollowup(question: String) {
        val status = _videoProcessingStatus.value
        val runId = status.activeRunId ?: status.completedRunId ?: selectedVideoRunIdState.value ?: return
        val trimmed = question.trim()
        if (trimmed.isBlank()) return
        _videoProcessingStatus.value = status.copy(
            classroomNoteFollowupState = status.classroomNoteFollowupState.copy(
                activeRunId = runId,
                isSubmitting = true,
                errorMessage = null
            )
        )
        scope.launch {
            runCatching {
                videoRepository.askClassroomNoteFollowup(
                    runId = runId,
                    question = trimmed,
                    streamingBuffer = _videoProcessingStatus.value.streamingBuffer
                )
            }.onSuccess {
                _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                    classroomNoteFollowupState = _videoProcessingStatus.value.classroomNoteFollowupState.copy(
                        activeRunId = runId,
                        isSubmitting = false,
                        errorMessage = null
                    )
                )
            }.onFailure { error ->
                _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                    classroomNoteFollowupState = _videoProcessingStatus.value.classroomNoteFollowupState.copy(
                        activeRunId = runId,
                        isSubmitting = false,
                        errorMessage = error.message ?: "课后追问失败"
                    )
                )
            }
        }
    }

    fun retryClassroomNoteFollowup(followupId: Long) {
        updateClassroomNoteFollowupSubmitting(true)
        scope.launch {
            runCatching {
                videoRepository.retryClassroomNoteFollowup(
                    followupId = followupId,
                    streamingBuffer = _videoProcessingStatus.value.streamingBuffer
                )
            }.onSuccess {
                updateClassroomNoteFollowupSubmitting(false)
            }.onFailure { error ->
                updateClassroomNoteFollowupSubmitting(false, error.message ?: "课后追问重试失败")
            }
        }
    }

    fun regenerateClassroomNoteFollowupWithFinalNote(followupId: Long) {
        updateClassroomNoteFollowupSubmitting(true)
        scope.launch {
            runCatching {
                videoRepository.regenerateClassroomNoteFollowupWithFinalNote(followupId)
            }.onSuccess {
                updateClassroomNoteFollowupSubmitting(false)
            }.onFailure { error ->
                updateClassroomNoteFollowupSubmitting(false, error.message ?: "用最终笔记重新回答失败")
            }
        }
    }

    fun deleteClassroomNoteFollowup(followupId: Long) {
        scope.launch {
            runCatching {
                videoRepository.deleteClassroomNoteFollowup(followupId)
            }.onFailure { error ->
                updateClassroomNoteFollowupSubmitting(false, error.message ?: "删除课后追问失败")
            }
        }
    }

    fun attachClassroomNoteMaterials(uris: List<Uri>) {
        val runId = _videoProcessingStatus.value.activeRunId
            ?: _videoProcessingStatus.value.completedRunId
            ?: selectedVideoRunIdState.value
            ?: return
        if (uris.isEmpty()) return
        scope.launch {
            uris.forEach { uri ->
                runCatching {
                    videoRepository.attachClassroomNoteMaterial(runId, uri)
                }.onFailure { error ->
                    _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                        errorMessage = error.message ?: "课堂资料上传失败"
                    )
                }
            }
        }
    }

    fun launchVideoProcessing(
        task: VideoProcessTaskDraft? = _currentVideoTask.value,
        streamingOutputEnabled: Boolean = false,
        recordingInput: ClassroomRecordingInput = ClassroomRecordingInput.LiveCamera,
        classroomPipeline: Boolean = false,
        speechRecognitionConfig: ClassroomSpeechRecognitionConfig = ClassroomSpeechRecognitionConfig.Default
    ) {
        val draft = task?.normalized() ?: run {
            _videoPlanUiState.value = VideoPlanUiState.Error("Create or load a video task first.")
            return
        }

        _currentVideoTask.value = draft
        resetVideoProcessingJob()
        val sessionId = videoWorkflowSessionId
        observeClassroomTranscripts(null)
        observeClassroomNoteFollowups(null)
        observeClassroomNoteMaterials(null)
        selectedVideoRunIdState.value = null
        val effectiveStreamingEnabled = streamingOutputEnabled || draft.autoStartStreamingOutput

        var launchedJob: Job? = null
        launchedJob = scope.launch {
            var activeRunId: Long? = null
            try {
                WatcherForegroundService.start(appContext, "视频分析进行中", WatcherForegroundService.REASON_VIDEO)
                showVideoExecutionStarting(draft, effectiveStreamingEnabled)
                val result = if (classroomPipeline) {
                    videoRepository.executeClassroomRecording(
                        draft = draft,
                        streamingOutputEnabled = effectiveStreamingEnabled,
                        latestFrameProvider = latestFrameProvider,
                        latestFrameSourceProvider = latestFrameSourceProvider,
                        outputRoot = appContext.filesDir,
                        recordingInput = recordingInput,
                        speechRecognitionConfig = speechRecognitionConfig,
                        shouldStopRequested = { videoStopRequested.get() },
                        onStatus = { update ->
                            if (isCurrentVideoWorkflowSession(sessionId)) {
                                  activeRunId = update.runId
                                  selectedVideoRunIdState.value = update.runId
                                  observeClassroomTranscripts(update.runId)
                                  observeClassroomNoteFollowups(update.runId)
                                  observeClassroomNoteMaterials(update.runId)
                                  applyVideoStatusUpdate(draft, update)
                            }
                        }
                    )
                } else {
                    videoRepository.executeTask(
                        draft = draft,
                        streamingOutputEnabled = effectiveStreamingEnabled,
                        latestFrameProvider = latestFrameProvider,
                        outputRoot = appContext.filesDir,
                        recordingInput = recordingInput,
                        shouldStopRequested = { videoStopRequested.get() },
                        onStatus = { update ->
                            if (isCurrentVideoWorkflowSession(sessionId)) {
                                activeRunId = update.runId
                                selectedVideoRunIdState.value = update.runId
                                applyVideoStatusUpdate(draft, update)
                            }
                        }
                    )
                }
                if (
                    isCurrentVideoWorkflowSession(sessionId) &&
                    shouldApplyVideoProcessingJobUpdate(videoProcessingJob, launchedJob)
                ) {
                    selectedVideoRunIdState.value = result.run.id
                    publishCompletedVideoRun(result.run)
                }
            } catch (_: CancellationException) {
                if (
                    isCurrentVideoWorkflowSession(sessionId) &&
                    shouldApplyVideoProcessingJobUpdate(videoProcessingJob, launchedJob)
                ) {
                    handleVideoProcessingCancellation(
                        runId = activeRunId,
                        draft = draft,
                        streamingEnabled = effectiveStreamingEnabled,
                        classroomPipeline = classroomPipeline,
                        sessionId = sessionId
                    )
                }
            } catch (error: Exception) {
                if (
                    isCurrentVideoWorkflowSession(sessionId) &&
                    shouldApplyVideoProcessingJobUpdate(videoProcessingJob, launchedJob)
                ) {
                    handleVideoProcessingFailure(
                        runId = activeRunId,
                        draft = draft,
                        streamingEnabled = effectiveStreamingEnabled,
                        error = error,
                        classroomPipeline = classroomPipeline,
                        sessionId = sessionId
                    )
                }
            } finally {
                WatcherForegroundService.stop(appContext, WatcherForegroundService.REASON_VIDEO)
                clearVideoProcessingJobIfMatches(launchedJob)
            }
        }
        videoProcessingJob = launchedJob
    }

    fun release() {
        advanceVideoWorkflowSession()
        videoStopRequested.set(true)
        videoProcessingJob?.cancel()
        observeClassroomTranscripts(null)
    }

    private fun requestStopVideoProcessing() {
        if (videoProcessingJob?.isActive != true) {
            return
        }
        videoStopRequested.set(true)
        val currentStatus = _videoProcessingStatus.value
        val isClassroomRecording = currentStatus.activeTask?.recordingScenario == RecordingScenario.ClassLecture.value
        val transcriptDraft = if (isClassroomRecording) {
            val draftTitle = currentStatus.activeTask?.title
                ?.takeIf(String::isNotBlank)
                ?: currentStatus.activeTask?.userRequirement.orEmpty()
            buildClassroomTranscriptDraft(
                title = draftTitle,
                stableTranscript = currentStatus.stableTranscript,
                realtimeInsights = currentStatus.realtimeInsights
            )
        } else {
            currentStatus.streamingBuffer
        }
        _videoProcessingStatus.value = currentStatus.copy(
            message = if (isClassroomRecording) {
                "录制已结束，正在生成音频大纲..."
            } else {
                "Stopping new captures and summarizing recorded segments."
            },
            streamingBuffer = transcriptDraft,
            errorMessage = null,
            stopRequested = true,
            isRecordingActive = false,
            isBusy = true
        )
    }

    private fun showVideoPlanningInProgress() {
        _videoPlanUiState.value = VideoPlanUiState.Loading
        selectedVideoRunIdState.value = null
        _videoProcessingStatus.value = VideoProcessingStatus(
            stage = VideoRunStatus.Planning,
            message = "姝ｅ湪鐢熸垚瑙嗛浠诲姟瑙勫垝",
            isBusy = true
        )
    }

    private fun showVideoDraftReady(draft: VideoProcessTaskDraft, message: String) {
        _currentVideoTask.value = draft
        _videoPlanUiState.value = VideoPlanUiState.Success(draft)
        showAwaitingVideoConfirmation(task = draft, message = message)
    }

    private fun showVideoExecutionStarting(
        draft: VideoProcessTaskDraft,
        streamingEnabled: Boolean
    ) {
        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        _videoProcessingStatus.value = VideoProcessingStatus(
            stage = VideoRunStatus.Recording,
            activeTask = draft,
            activeRunId = null,
            templateLabel = draft.templateLabel,
            currentSegmentIndex = 0,
            segmentCount = draft.plannedSegmentCount,
            segmentDurationSeconds = draft.plannedSegmentDurationSeconds,
            captureIntervalSeconds = draft.captureIntervalSeconds,
            message = "Preparing to record.",
            streamingEnabled = streamingEnabled,
            streamingBuffer = "",
            isRecordingActive = true,
            isAnalysisActive = false,
            recordingSegmentIndex = 1,
            remainingDurationSeconds = draft.plannedDurationSeconds,
            speechInputEnabled = false,
            isSpeechActive = false,
            isSpeechListening = false,
            speechErrorMessage = null,
            recentSpeech = emptyList(),
            isBusy = true,
            micPermissionGranted = hasMicPermission
        )
    }

    private fun handleVideoProcessingCancellation(
        runId: Long?,
        draft: VideoProcessTaskDraft,
        streamingEnabled: Boolean,
        classroomPipeline: Boolean = false,
        sessionId: Long = videoWorkflowSessionId
    ) {
        runId?.let {
            selectedVideoRunIdState.value = it
            scope.launch {
                if (classroomPipeline) {
                    videoRepository.markClassroomRunCancelled(
                        runId = it,
                        segmentIndex = _videoProcessingStatus.value.currentSegmentIndex,
                        segmentCount = draft.plannedSegmentCount,
                        streamingEnabled = streamingEnabled,
                        onStatus = { update ->
                            if (isCurrentVideoWorkflowSession(sessionId)) {
                                applyVideoStatusUpdate(draft, update)
                            }
                        }
                    )
                } else {
                    videoRepository.markRunCancelled(
                        runId = it,
                        segmentIndex = _videoProcessingStatus.value.currentSegmentIndex,
                        segmentCount = draft.plannedSegmentCount,
                        streamingEnabled = streamingEnabled,
                        onStatus = { update ->
                            if (isCurrentVideoWorkflowSession(sessionId)) {
                                applyVideoStatusUpdate(draft, update)
                            }
                        }
                    )
                }
            }
        }
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            stage = VideoRunStatus.Cancelled,
            message = "Current video processing task was cancelled.",
            isStreamingActive = false,
            isRecordingActive = false,
            isAnalysisActive = false,
            activeStreamingSegmentIndex = 0,
            isBusy = false,
            isTaskSaving = false
        )
    }

    private fun handleVideoProcessingFailure(
        runId: Long?,
        draft: VideoProcessTaskDraft,
        streamingEnabled: Boolean,
        error: Exception,
        classroomPipeline: Boolean = false,
        sessionId: Long = videoWorkflowSessionId
    ) {
        runId?.let {
            selectedVideoRunIdState.value = it
            scope.launch {
                if (classroomPipeline) {
                    videoRepository.markClassroomRunFailed(
                        runId = it,
                        segmentIndex = _videoProcessingStatus.value.currentSegmentIndex,
                        segmentCount = draft.plannedSegmentCount,
                        streamingEnabled = streamingEnabled,
                        error = error,
                        onStatus = { update ->
                            if (isCurrentVideoWorkflowSession(sessionId)) {
                                applyVideoStatusUpdate(draft, update)
                            }
                        }
                    )
                } else {
                    videoRepository.markRunFailed(
                        runId = it,
                        segmentIndex = _videoProcessingStatus.value.currentSegmentIndex,
                        segmentCount = draft.plannedSegmentCount,
                        streamingEnabled = streamingEnabled,
                        error = error,
                        onStatus = { update ->
                            if (isCurrentVideoWorkflowSession(sessionId)) {
                                applyVideoStatusUpdate(draft, update)
                            }
                        }
                    )
                }
            }
        }
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            stage = VideoRunStatus.Failed,
            message = error.message ?: "鎵ц澶辫触",
            errorMessage = error.message ?: "鎵ц澶辫触",
            isStreamingActive = false,
            isRecordingActive = false,
            isAnalysisActive = false,
            activeStreamingSegmentIndex = 0,
            isBusy = false
        )
    }

    private fun clearVideoProcessingJobIfMatches(job: Job?) {
        if (videoProcessingJob == job) {
            videoProcessingJob = null
        }
    }

    private fun resetVideoProcessingJob() {
        advanceVideoWorkflowSession()
        videoStopRequested.set(true)
        val jobToCancel = videoProcessingJob
        videoProcessingJob = null
        jobToCancel?.cancel()
        videoStopRequested.set(false)
    }

    private fun clearSelectedVideoTaskState() {
        observeClassroomTranscripts(null)
        observeClassroomNoteFollowups(null)
        observeClassroomNoteMaterials(null)
        _currentVideoTask.value = null
        _videoPlanUiState.value = VideoPlanUiState.Idle
        selectedVideoRunIdState.value = null
        _videoProcessingStatus.value = VideoProcessingStatus()
    }

    private fun advanceVideoWorkflowSession(): Long {
        videoWorkflowSessionId += 1
        return videoWorkflowSessionId
    }

    private fun isCurrentVideoWorkflowSession(sessionId: Long): Boolean =
        shouldApplyVideoWorkflowSessionUpdate(
            currentSessionId = videoWorkflowSessionId,
            updateSessionId = sessionId
        )

    private fun applyVideoStatusUpdate(task: VideoProcessTaskDraft, update: VideoExecutionStatusUpdate) {
        selectedVideoRunIdState.value = update.runId
        if (task.recordingScenario == RecordingScenario.ClassLecture.value) {
            observeClassroomTranscripts(update.runId)
            observeClassroomNoteFollowups(update.runId)
            observeClassroomNoteMaterials(update.runId)
        }
        val previousStatus = _videoProcessingStatus.value
        val effectiveRealtimeConnectionState = update.realtimeConnectionState.ifBlank {
            previousStatus.realtimeConnectionState
        }
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            stage = update.stage,
            activeTask = task,
            activeRunId = update.runId,
            templateLabel = update.templateLabel ?: task.templateLabel,
            currentSegmentIndex = update.segmentIndex,
            segmentCount = update.segmentCount,
            segmentDurationSeconds = update.segmentDurationSeconds,
            captureIntervalSeconds = update.captureIntervalSeconds,
            message = update.message,
            finalSummary = update.finalSummary.ifBlank { previousStatus.finalSummary },
            finalConclusion = update.finalConclusion.ifBlank { previousStatus.finalConclusion },
            timelineEvents = update.timelineEvents.takeIf(List<*>::isNotEmpty) ?: previousStatus.timelineEvents,
            streamingBuffer = if (task.recordingScenario == RecordingScenario.ClassLecture.value) {
                mergeClassroomCompletionDraftBuffer(
                    previous = previousStatus.streamingBuffer,
                    incoming = update.streamingBuffer
                )
            } else {
                when {
                    update.streamingBuffer != null -> update.streamingBuffer
                    else -> previousStatus.streamingBuffer
                }
            },
            playbackPath = update.playbackPath ?: previousStatus.playbackPath,
            markdownNote = previousStatus.markdownNote,
            structuredNoteJson = previousStatus.structuredNoteJson,
            rawModelSummary = previousStatus.rawModelSummary,
            completedRunId = previousStatus.completedRunId,
            degradedReason = update.degradedReason ?: previousStatus.degradedReason,
            streamingEnabled = update.streamingEnabled,
            isStreamingActive = update.isStreamingActive,
            isRecordingActive = update.isRecordingActive,
            isAnalysisActive = update.isAnalysisActive,
            recordingSegmentIndex = update.recordingSegmentIndex,
            activeStreamingSegmentIndex = update.activeStreamingSegmentIndex,
            recordedSegmentCount = update.recordedSegmentCount,
            analyzedSegmentCount = update.analyzedSegmentCount,
            pendingSegmentCount = update.pendingSegmentCount,
            recordedDurationSeconds = update.recordedDurationSeconds,
            remainingDurationSeconds = update.remainingDurationSeconds,
            nextCaptureInSeconds = update.nextCaptureInSeconds,
            stopRequested = if (update.stage in TERMINAL_VIDEO_STAGES) {
                false
            } else {
                previousStatus.stopRequested || update.stopRequested
            },
            segmentFeedbacks = update.segmentFeedbacks.takeIf(List<*>::isNotEmpty)
                ?: previousStatus.segmentFeedbacks,
            speechInputEnabled = effectiveRealtimeConnectionState.isNotBlank(),
            isSpeechActive = effectiveRealtimeConnectionState.isNotBlank() &&
                effectiveRealtimeConnectionState !in setOf("Closed", "Failed"),
            isSpeechListening = effectiveRealtimeConnectionState == "Connected",
            speechErrorMessage = update.errorMessage ?: previousStatus.speechErrorMessage,
            recentSpeech = emptyList(),
            realtimeTranscript = update.realtimeTranscript.ifBlank { previousStatus.realtimeTranscript },
            stableTranscript = update.stableTranscript.ifBlank { previousStatus.stableTranscript },
            realtimeInsights = update.realtimeInsights.takeIf(List<*>::isNotEmpty)
                ?: previousStatus.realtimeInsights,
            realtimeKnowledgeTree = update.realtimeKnowledgeTree ?: previousStatus.realtimeKnowledgeTree,
            changedKnowledgeNodeIds = update.changedKnowledgeNodeIds
                ?: previousStatus.changedKnowledgeNodeIds,
            newKnowledgeNodeIds = update.newKnowledgeNodeIds
                ?: previousStatus.newKnowledgeNodeIds,
            realtimeKnowledgeTreeStatus = update.realtimeKnowledgeTreeStatus.ifBlank {
                previousStatus.realtimeKnowledgeTreeStatus
            },
            realtimeKnowledgeTreeProgress = update.realtimeKnowledgeTreeProgress.takeIf {
                it.requiredChars > 0 || it.requiredIntervalMs > 0L || it.addedChars > 0 || it.jobActive
            } ?: previousStatus.realtimeKnowledgeTreeProgress,
            realtimeKnowledgeFrameRefs = update.realtimeKnowledgeFrameRefs.takeIf(List<*>::isNotEmpty)
                ?: previousStatus.realtimeKnowledgeFrameRefs,
            realtimeConnectionState = effectiveRealtimeConnectionState,
            realtimeAudioLagMs = update.realtimeAudioLagMs.takeIf { it > 0L }
                ?: previousStatus.realtimeAudioLagMs,
            realtimeDroppedFrameCount = maxOf(
                update.realtimeDroppedFrameCount,
                previousStatus.realtimeDroppedFrameCount
            ),
            realtimeBackfillSegmentCount = maxOf(
                update.realtimeBackfillSegmentCount,
                previousStatus.realtimeBackfillSegmentCount
            ),
            realtimePendingFrameCount = update.realtimePendingFrameCount,
            realtimeAsrLogId = update.realtimeAsrLogId.ifBlank { previousStatus.realtimeAsrLogId },
            realtimeSpeechProvider = update.realtimeSpeechProvider.ifBlank {
                previousStatus.realtimeSpeechProvider
            },
            realtimeSpeechFallbackReason = update.realtimeSpeechFallbackReason
                ?: previousStatus.realtimeSpeechFallbackReason,
            realtimeSpeechSessionId = update.realtimeSpeechSessionId.ifBlank {
                previousStatus.realtimeSpeechSessionId
            },
            realtimeTranscriptItems = previousStatus.realtimeTranscriptItems,
            inlineQuestionState = previousStatus.inlineQuestionState,
            classroomNoteMaterials = previousStatus.classroomNoteMaterials,
            errorMessage = update.errorMessage,
            isBusy = update.stage !in TERMINAL_VIDEO_STAGES,
            isTaskSaving = false,
            currentSegmentHasAudio = update.currentSegmentHasAudio ?: previousStatus.currentSegmentHasAudio,
            segmentAudioResults = update.segmentAudioResults ?: previousStatus.segmentAudioResults
        )
    }

    private fun showAwaitingVideoConfirmation(task: VideoProcessTaskDraft, message: String) {
        selectedVideoRunIdState.value = null
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            stage = VideoRunStatus.AwaitingConfirmation,
            activeTask = task,
            activeRunId = null,
            templateLabel = task.templateLabel,
            currentSegmentIndex = 0,
            segmentCount = task.plannedSegmentCount,
            segmentDurationSeconds = task.plannedSegmentDurationSeconds,
            captureIntervalSeconds = task.captureIntervalSeconds,
            finalSummary = "",
            finalConclusion = "",
            degradedReason = null,
            timelineEvents = emptyList(),
            streamingBuffer = "",
            streamingEnabled = task.autoStartStreamingOutput,
            isStreamingActive = false,
            isRecordingActive = false,
            isAnalysisActive = false,
            recordingSegmentIndex = 0,
            activeStreamingSegmentIndex = 0,
            recordedSegmentCount = 0,
            analyzedSegmentCount = 0,
            pendingSegmentCount = 0,
            recordedDurationSeconds = 0,
            remainingDurationSeconds = task.plannedDurationSeconds,
            nextCaptureInSeconds = 0,
            stopRequested = false,
            segmentFeedbacks = emptyList(),
            speechInputEnabled = false,
            isSpeechActive = false,
            isSpeechListening = false,
            speechErrorMessage = null,
            recentSpeech = emptyList(),
            realtimeTranscript = "",
            stableTranscript = "",
            realtimeInsights = emptyList(),
            realtimeKnowledgeTree = null,
            changedKnowledgeNodeIds = emptyList(),
            newKnowledgeNodeIds = emptyList(),
            realtimeKnowledgeTreeStatus = "",
            realtimeKnowledgeTreeProgress = ClassroomKnowledgeTreeProgress(),
            realtimeKnowledgeFrameRefs = emptyList(),
            realtimeConnectionState = "",
            realtimeAudioLagMs = 0L,
            realtimeDroppedFrameCount = 0,
            realtimeBackfillSegmentCount = 0,
            realtimePendingFrameCount = 0,
            realtimeAsrLogId = "",
            realtimeSpeechProvider = "",
            realtimeSpeechFallbackReason = null,
            realtimeSpeechSessionId = "",
            realtimeTranscriptItems = emptyList(),
            inlineQuestionState = ClassroomInlineQuestionUiState(),
            classroomNoteMaterials = emptyList(),
            message = message,
            errorMessage = null,
            isBusy = false
        )
    }

    private fun publishCompletedVideoRun(run: com.example.watcher.data.model.VideoProcessRun) {
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            activeRunId = run.id,
            completedRunId = run.id,
            finalSummary = run.finalSummary,
            finalConclusion = run.finalConclusion,
            markdownNote = run.markdownNote,
            structuredNoteJson = run.structuredNoteJson,
            rawModelSummary = run.rawModelSummary,
            degradedReason = run.degradedReason,
            streamingBuffer = run.markdownNote
                .ifBlank { run.rawModelSummary }
                .ifBlank { _videoProcessingStatus.value.streamingBuffer },
            isBusy = false
        )
    }

    private fun hydrateClassroomCompletedRun(run: VideoProcessRun, sessionId: Long) {
        if (!isCurrentVideoWorkflowSession(sessionId)) {
            return
        }
        _currentVideoTask.value = null
        // Phase 1: Set lightweight metadata immediately (fast recomposition for skeleton UI)
        _videoProcessingStatus.value = VideoProcessingStatus(
            stage = run.status,
            activeRunId = run.id,
            completedRunId = run.id,
            templateLabel = run.templateLabel,
            currentSegmentIndex = run.segmentCount,
            segmentCount = run.segmentCount,
            segmentDurationSeconds = run.segmentDurationSeconds,
            captureIntervalSeconds = run.captureIntervalSeconds,
            message = "课堂记录载入中...",
            finalSummary = run.finalSummary,
            finalConclusion = run.finalConclusion,
            streamingBuffer = "",
            markdownNote = "",
            structuredNoteJson = "",
            rawModelSummary = "",
            degradedReason = run.degradedReason,
            recordedSegmentCount = run.segmentCount,
            analyzedSegmentCount = run.segmentCount,
            pendingSegmentCount = 0,
            recordedDurationSeconds = run.totalDurationSeconds
                .takeIf { it > 0 }
                ?: run.fullMediaDurationMs.takeIf { it > 0L }?.let { (it / 1_000L).toInt() }
                ?: run.continuousAudioDurationMs.takeIf { it > 0L }?.let { (it / 1_000L).toInt() }
                ?: 0,
            remainingDurationSeconds = 0,
            stopRequested = false,
            speechInputEnabled = run.speechInputEnabled,
            errorMessage = run.errorMessage,
            isBusy = false,
            currentSegmentHasAudio = run.fullMediaHasAudio,
            segmentAudioResults = if (run.segmentCount > 0) {
                List(run.segmentCount) { run.fullMediaHasAudio }
            } else {
                emptyList()
            }
        )
        // Phase 2: Fill heavy markdown/note content after skeleton renders
        scope.launch {
            delay(150L)
            if (!isCurrentVideoWorkflowSession(sessionId)) {
                return@launch
            }
            _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                message = "课堂记录已载入",
                streamingBuffer = run.markdownNote.ifBlank { run.rawModelSummary },
                markdownNote = run.markdownNote,
                structuredNoteJson = run.structuredNoteJson,
                rawModelSummary = run.rawModelSummary
            )
        }
    }

    private fun observeClassroomTranscripts(runId: Long?) {
        if (observedClassroomTranscriptRunId == runId) {
            return
        }
        classroomTranscriptJob?.cancel()
        classroomTranscriptJob = null
        observedClassroomTranscriptRunId = runId
        if (runId == null) {
            return
        }
        classroomTranscriptJob = scope.launch {
            videoRepository.observeClassroomTranscriptItems(runId)
                .flowOn(Dispatchers.IO)
                .collect { items ->
                    if (observedClassroomTranscriptRunId == runId) {
                        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                            realtimeTranscriptItems = items
                        )
                    }
                }
        }
    }

    private fun observeClassroomNoteFollowups(runId: Long?) {
        if (observedClassroomNoteFollowupRunId == runId) {
            return
        }
        classroomNoteFollowupJob?.cancel()
        classroomNoteFollowupJob = null
        observedClassroomNoteFollowupRunId = runId
        if (runId == null) {
            _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                classroomNoteFollowupState = ClassroomNoteFollowupUiState()
            )
            return
        }
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            classroomNoteFollowupState = _videoProcessingStatus.value.classroomNoteFollowupState.copy(
                activeRunId = runId
            )
        )
        classroomNoteFollowupJob = scope.launch {
            videoRepository.observeClassroomNoteFollowups(runId)
                .flowOn(Dispatchers.IO)
                .collect { items ->
                    if (observedClassroomNoteFollowupRunId == runId) {
                        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                            classroomNoteFollowupState = _videoProcessingStatus.value.classroomNoteFollowupState.copy(
                                activeRunId = runId,
                                items = items
                            )
                        )
                    }
                }
        }
    }

    private fun observeClassroomNoteMaterials(runId: Long?) {
        if (observedClassroomNoteMaterialRunId == runId) {
            return
        }
        classroomNoteMaterialJob?.cancel()
        classroomNoteMaterialJob = null
        observedClassroomNoteMaterialRunId = runId
        if (runId == null) {
            _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                classroomNoteMaterials = emptyList()
            )
            return
        }
        classroomNoteMaterialJob = scope.launch {
            videoRepository.observeClassroomNoteMaterials(runId)
                .flowOn(Dispatchers.IO)
                .collect { items ->
                    if (observedClassroomNoteMaterialRunId == runId) {
                        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
                            classroomNoteMaterials = items
                        )
                    }
                }
        }
    }

    private fun updateClassroomNoteFollowupSubmitting(
        isSubmitting: Boolean,
        errorMessage: String? = null
    ) {
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            classroomNoteFollowupState = _videoProcessingStatus.value.classroomNoteFollowupState.copy(
                isSubmitting = isSubmitting,
                errorMessage = errorMessage
            )
        )
    }

    private companion object {
        fun formatStorageSize(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return when {
                mb >= 100 -> "${mb.toInt()} MB"
                mb >= 1 -> "%.1f MB".format(mb)
                bytes >= 1024 -> "${bytes / 1024} KB"
                else -> "$bytes B"
            }
        }

        val TERMINAL_VIDEO_STAGES = setOf(
            VideoRunStatus.Completed,
            VideoRunStatus.CompletedDegraded,
            VideoRunStatus.Failed,
            VideoRunStatus.Cancelled
        )
    }
}

internal fun shouldApplyVideoProcessingJobUpdate(currentJob: Job?, updateJob: Job?): Boolean {
    return currentJob != null && currentJob === updateJob
}

internal fun shouldApplyVideoWorkflowSessionUpdate(
    currentSessionId: Long,
    updateSessionId: Long
): Boolean = currentSessionId == updateSessionId

internal fun mergeClassroomCompletionDraftBuffer(previous: String, incoming: String?): String {
    return incoming
        ?.takeIf(String::isNotBlank)
        ?: previous
}

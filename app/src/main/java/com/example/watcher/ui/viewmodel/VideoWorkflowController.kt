package com.example.watcher.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import com.example.watcher.R
import com.example.watcher.WatcherForegroundService
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.repository.VideoExecutionStatusUpdate
import com.example.watcher.data.repository.VideoProcessRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoWorkflowController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val videoRepository: VideoProcessRepository,
    private val latestFrameProvider: () -> Bitmap?
) {
    private val _videoPlanUiState = MutableStateFlow<VideoPlanUiState>(VideoPlanUiState.Idle)
    private val _currentVideoTask = MutableStateFlow<VideoProcessTaskDraft?>(null)
    private val _videoProcessingStatus = MutableStateFlow(VideoProcessingStatus())
    val selectedVideoRunIdState = MutableStateFlow<Long?>(null)
    val selectedVideoRunEventsState = MutableStateFlow<List<TimelineEventEntity>>(emptyList())

    private var videoProcessingJob: Job? = null
    private var videoStopRequested = AtomicBoolean(false)

    val videoPlanUiState: StateFlow<VideoPlanUiState> = _videoPlanUiState.asStateFlow()
    val currentVideoTask: StateFlow<VideoProcessTaskDraft?> = _currentVideoTask.asStateFlow()
    val videoProcessingStatus: StateFlow<VideoProcessingStatus> = _videoProcessingStatus.asStateFlow()
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

    fun selectVideoRun(runId: Long?) {
        selectedVideoRunIdState.value = runId
    }

    fun launchVideoProcessing(
        task: VideoProcessTaskDraft? = _currentVideoTask.value,
        streamingOutputEnabled: Boolean = false
    ) {
        val draft = task?.normalized() ?: run {
            _videoPlanUiState.value = VideoPlanUiState.Error("Create or load a video task first.")
            return
        }

        _currentVideoTask.value = draft
        resetVideoProcessingJob()
        selectedVideoRunIdState.value = null
        val effectiveStreamingEnabled = streamingOutputEnabled || draft.autoStartStreamingOutput

        var launchedJob: Job? = null
        launchedJob = scope.launch {
            var activeRunId: Long? = null
            try {
                WatcherForegroundService.start(appContext, "视频分析进行中")
                showVideoExecutionStarting(draft, effectiveStreamingEnabled)
                val result = videoRepository.executeTask(
                    draft = draft,
                    streamingOutputEnabled = effectiveStreamingEnabled,
                    latestFrameProvider = latestFrameProvider,
                    outputRoot = appContext.filesDir,
                    shouldStopRequested = { videoStopRequested.get() },
                    onStatus = { update ->
                        activeRunId = update.runId
                        selectedVideoRunIdState.value = update.runId
                        applyVideoStatusUpdate(draft, update)
                    }
                )
                selectedVideoRunIdState.value = result.run.id
            } catch (_: CancellationException) {
                handleVideoProcessingCancellation(
                    runId = activeRunId,
                    draft = draft,
                    streamingEnabled = effectiveStreamingEnabled
                )
            } catch (error: Exception) {
                handleVideoProcessingFailure(
                    runId = activeRunId,
                    draft = draft,
                    streamingEnabled = effectiveStreamingEnabled,
                    error = error
                )
            } finally {
                WatcherForegroundService.stop(appContext)
                clearVideoProcessingJobIfMatches(launchedJob)
            }
        }
        videoProcessingJob = launchedJob
    }

    fun release() {
        videoStopRequested.set(true)
        videoProcessingJob?.cancel()
    }

    private fun requestStopVideoProcessing() {
        if (videoProcessingJob?.isActive != true) {
            return
        }
        videoStopRequested.set(true)
        _videoProcessingStatus.value = _videoProcessingStatus.value.copy(
            message = "Stopping new captures and summarizing recorded segments.",
            errorMessage = null,
            stopRequested = true,
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
        streamingEnabled: Boolean
    ) {
        runId?.let {
            selectedVideoRunIdState.value = it
            scope.launch {
                videoRepository.markRunCancelled(
                    runId = it,
                    segmentIndex = _videoProcessingStatus.value.currentSegmentIndex,
                    segmentCount = draft.plannedSegmentCount,
                    streamingEnabled = streamingEnabled,
                    onStatus = { update -> applyVideoStatusUpdate(draft, update) }
                )
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
        error: Exception
    ) {
        runId?.let {
            selectedVideoRunIdState.value = it
            scope.launch {
                videoRepository.markRunFailed(
                    runId = it,
                    segmentIndex = _videoProcessingStatus.value.currentSegmentIndex,
                    segmentCount = draft.plannedSegmentCount,
                    streamingEnabled = streamingEnabled,
                    error = error,
                    onStatus = { update -> applyVideoStatusUpdate(draft, update) }
                )
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
        videoStopRequested.set(true)
        videoProcessingJob?.cancel()
        videoStopRequested.set(false)
    }

    private fun clearSelectedVideoTaskState() {
        _currentVideoTask.value = null
        _videoPlanUiState.value = VideoPlanUiState.Idle
        selectedVideoRunIdState.value = null
        _videoProcessingStatus.value = VideoProcessingStatus()
    }

    private fun applyVideoStatusUpdate(task: VideoProcessTaskDraft, update: VideoExecutionStatusUpdate) {
        selectedVideoRunIdState.value = update.runId
        val previousStatus = _videoProcessingStatus.value
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
            streamingBuffer = when {
                update.streamingBuffer != null -> update.streamingBuffer
                update.streamingEnabled -> previousStatus.streamingBuffer
                else -> ""
            },
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
            speechInputEnabled = false,
            isSpeechActive = false,
            isSpeechListening = false,
            speechErrorMessage = null,
            recentSpeech = emptyList(),
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
            message = message,
            errorMessage = null,
            isBusy = false
        )
    }

    private companion object {
        val TERMINAL_VIDEO_STAGES = setOf(
            VideoRunStatus.Completed,
            VideoRunStatus.CompletedDegraded,
            VideoRunStatus.Failed,
            VideoRunStatus.Cancelled
        )
    }
}

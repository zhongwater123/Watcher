package com.example.watcher.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.ClassroomInlineQuestionType
import com.example.watcher.data.model.ClassroomPhoneCameraLens
import com.example.watcher.data.model.ClassroomRecordingDefaults
import com.example.watcher.data.model.ClassroomRecordingInput
import com.example.watcher.data.model.ClassroomSpeechRecognitionConfig
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.repository.ClassroomTranscriptSelectionPolicy
import com.example.watcher.ui.components.MjpegStreamUiState
import com.example.watcher.ui.components.MotionDepth
import com.example.watcher.ui.components.MotionStageSection
import com.example.watcher.ui.components.PageScaffold
import com.example.watcher.ui.components.StreamSource
import com.example.watcher.ui.components.WatcherTopBar
import kotlin.math.roundToInt
import kotlinx.coroutines.delay


@Composable
internal fun ClassroomRecordingPage(
    settings: VideoStreamSettings,
    streamState: MjpegStreamUiState,
    isStreamPlaying: Boolean,
    status: VideoProcessingStatus,
    recentRuns: List<VideoProcessRun>,
    selectedRunId: Long?,
    recordingInput: ClassroomRecordingInput,
    onPlayingChange: (Boolean) -> Unit,
    onReconnectStream: () -> Unit,
    onCaptureSnapshot: (Bitmap) -> Unit,
    onOpenSettings: () -> Unit,
    onPickTestVideo: () -> Unit,
    onClearTestVideo: () -> Unit,
    onCleanupTestVideoCache: () -> Unit,
    onStartClassroomRecording: (
        courseName: String,
        durationSeconds: Int,
        speechRecognitionConfig: ClassroomSpeechRecognitionConfig,
        recordingInput: ClassroomRecordingInput
    ) -> Unit,
    onStopProcessing: () -> Unit,
    onNewRecording: () -> Unit,
    onOpenClassroomRun: (Long) -> Unit,
    onToggleTranscriptSelection: (Long) -> Unit,
    onAnswerInlineQuestion: (ClassroomInlineQuestionType) -> Unit,
    onDismissInlineQuestion: () -> Unit,
    onAskClassroomNoteFollowup: (String) -> Unit,
    onRetryClassroomNoteFollowup: (Long) -> Unit,
    onRegenerateClassroomNoteFollowup: (Long) -> Unit,
    onDeleteClassroomNoteFollowup: (Long) -> Unit,
    onAppendClassroomNoteMaterials: () -> Unit,
    onOpenRunDetail: (Long) -> Unit,
    onCopyNote: (String) -> Unit,
    onOpenWalletConfig: (() -> Unit)? = null,
    onOpenAgentConfig: (() -> Unit)? = null,
    isConciseMode: Boolean,
    onConciseModeChange: (Boolean) -> Unit,
    rotaryRotationDegrees: Float,
    onRotaryRotationChange: (Float) -> Unit,
    currentPage: HubPage,
    pageOffset: Float
) {
    var courseName by remember { mutableStateOf("") }
    var selectedDuration by remember { mutableIntStateOf(ClassroomRecordingDefaults.DURATION_45_MIN) }
    var speechRecognitionConfig by remember { mutableStateOf(ClassroomSpeechRecognitionConfig.Default) }
    val resolvedPhase = resolveClassroomRecordingPhase(status)
    var showCompletedResult by remember { mutableStateOf(false) }
    val phase = resolveClassroomRecordingDisplayPhase(
        status = status,
        resultConfirmed = showCompletedResult
    )
    val result = remember(status, recentRuns, selectedRunId) {
        buildClassroomRecordingResultUiModel(status, recentRuns, selectedRunId)
    }
    fun resetClassroomStartDraft() {
        courseName = ""
        selectedDuration = ClassroomRecordingDefaults.DURATION_45_MIN
        speechRecognitionConfig = ClassroomSpeechRecognitionConfig.Default
    }
    BackHandler(enabled = phase == ClassroomRecordingPhase.Completed) {
        showCompletedResult = false
        resetClassroomStartDraft()
        onNewRecording()
    }

    val selectableTranscriptCount = status.realtimeTranscriptItems.count { it.isSelected && !it.isAnswered }
    var showInlineQuestionOverlay by remember { mutableStateOf(false) }
    var inlineQuestionExpansionReady by remember { mutableStateOf(false) }
    var inlineQuestionDragOffset by remember { mutableStateOf(Offset.Zero) }
    var inlineQuestionBaseTopPx by remember { mutableStateOf<Float?>(null) }
    var inlineFramePreview by remember { mutableStateOf<InlineQuestionFramePreviewState?>(null) }
    var transcriptInteractionAnchorY by remember { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    val defaultInlineQuestionTopPx = with(density) { 220.dp.toPx() }
    val inlineQuestionLiftPx = with(density) { 96.dp.toPx() }
    val pageScrollState = rememberScrollState()
    val isPageVisible = kotlin.math.abs(pageOffset) < 0.98f

    LaunchedEffect(status.stage, status.activeTask?.taskId, status.activeRunId, status.completedRunId) {
        when {
            resolvedPhase == ClassroomRecordingPhase.NotStarted -> showCompletedResult = false
            status.isRecordingActive &&
                !status.stopRequested &&
                resolvedPhase == ClassroomRecordingPhase.Recording -> showCompletedResult = false
            resolvedPhase == ClassroomRecordingPhase.Completed &&
                status.activeTask == null &&
                status.completedRunId != null -> showCompletedResult = true
        }
    }

    LaunchedEffect(phase, selectableTranscriptCount) {
        if (phase != ClassroomRecordingPhase.Recording) {
            showInlineQuestionOverlay = false
            inlineQuestionExpansionReady = false
            inlineQuestionDragOffset = Offset.Zero
            inlineQuestionBaseTopPx = null
            inlineFramePreview = null
            return@LaunchedEffect
        }
        if (selectableTranscriptCount <= 0 && !status.inlineQuestionState.visible) {
            showInlineQuestionOverlay = false
            inlineQuestionExpansionReady = false
            inlineQuestionBaseTopPx = null
            inlineFramePreview = null
            return@LaunchedEffect
        }
        if (inlineQuestionBaseTopPx == null) {
            inlineQuestionBaseTopPx = (transcriptInteractionAnchorY ?: defaultInlineQuestionTopPx) - inlineQuestionLiftPx
        }
        if (!showInlineQuestionOverlay && !status.inlineQuestionState.visible) {
            inlineQuestionDragOffset = Offset.Zero
        }
        showInlineQuestionOverlay = true
    }

    LaunchedEffect(
        phase,
        selectableTranscriptCount,
        status.inlineQuestionState.visible,
        status.inlineQuestionState.isLoading,
        status.inlineQuestionState.answerText,
        status.inlineQuestionState.errorMessage
    ) {
        if (phase != ClassroomRecordingPhase.Recording) {
            inlineQuestionExpansionReady = false
            return@LaunchedEffect
        }
        if (status.inlineQuestionState.visible || status.inlineQuestionState.isLoading) {
            inlineQuestionExpansionReady = true
            return@LaunchedEffect
        }
        if (selectableTranscriptCount < ClassroomTranscriptSelectionPolicy.MIN_SELECTIONS_TO_ASK) {
            inlineQuestionExpansionReady = false
            return@LaunchedEffect
        }
        if (!inlineQuestionExpansionReady) {
            // Debounced by selectableTranscriptCount: another subtitle pick cancels and restarts this wait.
            delay(2_000L)
            inlineQuestionExpansionReady = true
        }
    }

    LaunchedEffect(status.inlineQuestionState.isLoading) {
        if (status.inlineQuestionState.isLoading) {
            inlineFramePreview = null
        }
    }

    LaunchedEffect(
        phase,
        result.runId,
        result.playbackPath,
        result.hasAudioOutline,
        result.hasFinalNote,
        result.noteText.length,
        status.streamingBuffer.length,
        status.markdownNote.length,
        status.rawModelSummary.length,
        status.classroomNoteFollowupState.items.size,
        status.message
    ) {
        if (phase == ClassroomRecordingPhase.Completed) {
            Log.d(
                CLASSROOM_COMPLETION_LOG_TAG,
                "Completed render run=${result.runId} active=${status.activeRunId} completed=${status.completedRunId} " +
                    "playback=${result.playbackPath} " +
                    "audioOutline=${result.hasAudioOutline} finalNote=${result.hasFinalNote} " +
                    "noteLength=${result.noteText.length} bufferLength=${status.streamingBuffer.length} " +
                    "markdownLength=${status.markdownNote.length} rawLength=${status.rawModelSummary.length} " +
                    "followups=${status.classroomNoteFollowupState.items.size} " +
                    "stage=${status.stage} message=${status.message}"
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PageScaffold(page = currentPage, pageOffset = pageOffset, scrollState = pageScrollState) {
            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Header) {
                WatcherTopBar(
                    eyebrow = "Watcher",
                    title = "课堂助手",
                    subtitle = "让 Watcher 和你一起上一节课吧。",
                    currentPage = currentPage,
                    pageOffset = pageOffset,
                    showConciseModeToggle = true,
                    isConciseMode = isConciseMode,
                    onConciseModeChange = onConciseModeChange,
                    rotaryRotationDegrees = rotaryRotationDegrees,
                    onRotaryRotationChange = onRotaryRotationChange,
                    onOpenSettings = onOpenSettings,
                    onOpenWalletConfig = onOpenWalletConfig,
                    onOpenAgentConfig = onOpenAgentConfig
                )
            }

            when (phase) {
                ClassroomRecordingPhase.NotStarted -> {
                    MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Hero) {
                        ClassroomLivePreviewCard(
                            settings = settings,
                            streamState = streamState,
                            isStreamPlaying = isStreamPlaying,
                            recordingInput = recordingInput,
                            autoPlayTestVideo = false,
                            previewActive = isPageVisible,
                            onPlayingChange = onPlayingChange,
                            onReconnectStream = onReconnectStream,
                            onCaptureSnapshot = onCaptureSnapshot,
                            onOpenSettings = onOpenSettings
                        )
                    }
                    MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
                        ClassroomStartCard(
                            courseName = courseName,
                            onCourseNameChange = { courseName = it },
                            selectedDuration = selectedDuration,
                            onDurationChange = { selectedDuration = it },
                            recordingInput = recordingInput,
                            speechRecognitionConfig = speechRecognitionConfig,
                            onSpeechRecognitionConfigChange = { speechRecognitionConfig = it },
                            statusMessage = status.message,
                            errorMessage = status.errorMessage,
                            onPickTestVideo = onPickTestVideo,
                            onClearTestVideo = onClearTestVideo,
                            onCleanupTestVideoCache = onCleanupTestVideoCache,
                            onStart = {
                                onStartClassroomRecording(
                                    courseName,
                                    selectedDuration,
                                    speechRecognitionConfig,
                                    resolvePreviewBackedRecordingInput(
                                        settings = settings,
                                        streamState = streamState,
                                        currentInput = recordingInput
                                    )
                                )
                            }
                        )
                    }
                    MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                        ClassroomHistoryCard(
                            historyItems = buildClassroomHistoryItems(recentRuns),
                            selectedRunId = selectedRunId,
                            onOpenClassroomRun = onOpenClassroomRun
                        )
                    }
                }

                ClassroomRecordingPhase.Recording -> {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Hero) {
                            ClassroomLivePreviewCard(
                                settings = settings,
                                streamState = streamState,
                                isStreamPlaying = isStreamPlaying,
                                recordingInput = recordingInput,
                                autoPlayTestVideo = true,
                                previewActive = isPageVisible,
                                onPlayingChange = onPlayingChange,
                                onReconnectStream = onReconnectStream,
                                onCaptureSnapshot = onCaptureSnapshot,
                                onOpenSettings = onOpenSettings
                            )
                        }
                        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
                            ClassroomTranscriptCard(
                                status = status,
                                onToggleTranscriptSelection = onToggleTranscriptSelection,
                                onTranscriptInteractionAnchorChanged = { transcriptInteractionAnchorY = it }
                            )
                        }
                        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                            ClassroomInsightsCard(
                                insights = status.realtimeInsights,
                                knowledgeTree = status.realtimeKnowledgeTree,
                                changedNodeIds = status.changedKnowledgeNodeIds,
                                newNodeIds = status.newKnowledgeNodeIds,
                                knowledgeTreeStatus = status.realtimeKnowledgeTreeStatus,
                                knowledgeTreeProgress = status.realtimeKnowledgeTreeProgress,
                                knowledgeFrameRefs = status.realtimeKnowledgeFrameRefs
                            )
                        }
                        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                            ClassroomRecordingControlCard(
                                status = status,
                                onStop = onStopProcessing,
                                onOpenResult = if (resolvedPhase == ClassroomRecordingPhase.Completed) {
                                    { showCompletedResult = true }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }

                ClassroomRecordingPhase.Completed -> {
                    MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Hero) {
                        ClassroomCompletedPlaybackCard(playbackPath = result.playbackPath)
                    }
                    MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                        ClassroomInsightsCard(
                            insights = status.realtimeInsights,
                            knowledgeTree = result.knowledgeTree,
                            changedNodeIds = result.changedKnowledgeNodeIds,
                            newNodeIds = result.newKnowledgeNodeIds,
                            knowledgeTreeStatus = result.knowledgeTreeStatus,
                            knowledgeTreeProgress = result.knowledgeTreeProgress,
                            knowledgeFrameRefs = result.knowledgeFrameRefs,
                            emptyMessage = "本次录制暂无可展示的知识树"
                        )
                    }
                    MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                        ClassroomNoteCard(
                            status = status,
                            result = result,
                            onNewRecording = {
                                Log.d(CLASSROOM_COMPLETION_LOG_TAG, "New recording from completed run=${result.runId}")
                                showCompletedResult = false
                                resetClassroomStartDraft()
                                onNewRecording()
                            },
                            onCopyNote = { note ->
                                Log.d(
                                    CLASSROOM_COMPLETION_LOG_TAG,
                                    "Copy note run=${result.runId} length=${note.length}"
                                )
                                onCopyNote(note)
                            },
                            onAppendMaterials = {
                                Log.d(CLASSROOM_COMPLETION_LOG_TAG, "Append note materials run=${result.runId}")
                                onAppendClassroomNoteMaterials()
                            }
                        )
                    }
                    MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                        ClassroomNoteFollowupCard(
                            status = status,
                            result = result,
                            followupState = status.classroomNoteFollowupState,
                            onAsk = { question ->
                                Log.d(
                                    CLASSROOM_COMPLETION_LOG_TAG,
                                    "Followup ask run=${result.runId} questionLength=${question.length}"
                                )
                                onAskClassroomNoteFollowup(question)
                            },
                            onRetry = { followupId ->
                                Log.d(CLASSROOM_COMPLETION_LOG_TAG, "Followup retry id=$followupId run=${result.runId}")
                                onRetryClassroomNoteFollowup(followupId)
                            },
                            onRegenerateWithFinalNote = { followupId ->
                                Log.d(
                                    CLASSROOM_COMPLETION_LOG_TAG,
                                    "Followup regenerateWithFinal id=$followupId run=${result.runId}"
                                )
                                onRegenerateClassroomNoteFollowup(followupId)
                            },
                            onDelete = { followupId ->
                                Log.d(CLASSROOM_COMPLETION_LOG_TAG, "Followup delete id=$followupId run=${result.runId}")
                                onDeleteClassroomNoteFollowup(followupId)
                            },
                            onCopyAnswer = onCopyNote
                        )
                    }
                    MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                        ClassroomReportCard(
                            result = result,
                            onOpenRunDetail = {
                                Log.d(CLASSROOM_COMPLETION_LOG_TAG, "Open run detail run=$it")
                                onOpenRunDetail(it)
                            }
                        )
                    }
                }
            }
        }
        if (phase == ClassroomRecordingPhase.Recording && (showInlineQuestionOverlay || status.inlineQuestionState.visible)) {
            InlineQuestionGlassOverlay(
                status = status,
                selectedCount = selectableTranscriptCount,
                expansionReady = inlineQuestionExpansionReady,
                onAnswerInlineQuestion = onAnswerInlineQuestion,
                onDismissInlineQuestion = {
                    showInlineQuestionOverlay = false
                    inlineFramePreview = null
                    onDismissInlineQuestion()
                    inlineQuestionExpansionReady = false
                },
                onOpenFramePreview = { framePath, frameTimestampMs ->
                    inlineFramePreview = InlineQuestionFramePreviewState(
                        framePath = framePath,
                        frameTimestampMs = frameTimestampMs
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 14.dp)
                    .offset {
                        IntOffset(
                            inlineQuestionDragOffset.x.roundToInt(),
                            ((inlineQuestionBaseTopPx ?: defaultInlineQuestionTopPx) + inlineQuestionDragOffset.y).roundToInt()
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            inlineQuestionDragOffset += dragAmount
                        }
                    }
            )
        }
        if (phase == ClassroomRecordingPhase.Recording && (showInlineQuestionOverlay || status.inlineQuestionState.visible)) {
            inlineFramePreview?.let { preview ->
                InlineQuestionFramePreviewOverlay(
                    preview = preview,
                    onDismiss = { inlineFramePreview = null },
                    modifier = Modifier
                        .fillMaxSize()
                )
            }
        }
    }
}

private fun resolvePreviewBackedRecordingInput(
    settings: VideoStreamSettings,
    streamState: MjpegStreamUiState,
    currentInput: ClassroomRecordingInput
): ClassroomRecordingInput {
    if (currentInput is ClassroomRecordingInput.TestVideo) {
        return currentInput
    }
    return when (streamState.source) {
        StreamSource.RemoteMjpeg -> ClassroomRecordingInput.RemoteMjpegStream(
            streamUrl = streamState.activeStreamUrl ?: settings.streamDisplayUrl,
            sourceLabel = streamState.sourceLabel.ifBlank { settings.streamDisplayUrl }
        )
        StreamSource.FrontCameraFallback -> ClassroomRecordingInput.PhoneCameraFallback(
            lens = ClassroomPhoneCameraLens.Front,
            sourceLabel = streamState.sourceLabel
        )
        StreamSource.BackCameraFallback -> ClassroomRecordingInput.PhoneCameraFallback(
            lens = ClassroomPhoneCameraLens.Back,
            sourceLabel = streamState.sourceLabel
        )
        StreamSource.None -> ClassroomRecordingInput.LiveCamera
    }
}

package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorLogEntry
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.data.model.MonitorTask
import com.example.watcher.data.model.MonitorTemplateEntity
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.ui.components.CameraPreviewCard
import com.example.watcher.ui.components.MjpegStreamUiState
import com.example.watcher.ui.components.MotionDepth
import com.example.watcher.ui.components.MotionStageSection
import com.example.watcher.ui.components.PageScaffold
import com.example.watcher.ui.components.WatcherTopBar
import com.example.watcher.ui.viewmodel.UiState

@Composable
internal fun MonitorWorkbenchPage(
    settings: VideoStreamSettings,
    streamState: MjpegStreamUiState,
    isStreamPlaying: Boolean,
    monitorStatus: MonitorStatus,
    currentTask: IntentResult?,
    pendingBaselineImagePath: String?,
    pendingBaselineBase64: String?,
    monitorTemplates: List<MonitorTemplateEntity>,
    tasks: List<MonitorTask>,
    monitorLogs: List<MonitorLogEntry>,
    uiState: UiState,
    requestText: TextFieldValue,
    isListening: Boolean,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onStartListening: () -> Unit,
    onAnalyze: () -> Unit,
    onSaveTask: (IntentResult) -> Unit,
    onSaveAndStartConciseMonitoring: (IntentResult) -> Boolean,
    onStartMonitoring: (IntentResult) -> Unit,
    onPauseMonitoring: () -> Unit,
    onResumeMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onRefreshBaseline: () -> Unit,
    onClearPendingBaselineImage: () -> Unit,
    onPickBaselineImage: () -> Unit,
    onApplyMonitorTemplate: (String) -> Unit,
    onLoadTask: (MonitorTask) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onCopyJson: () -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onReconnectStream: () -> Unit,
    onCaptureSnapshot: (Bitmap) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgentConfig: () -> Unit,
    onOpenWalletConfig: () -> Unit,
    isConciseMode: Boolean,
    onConciseModeChange: (Boolean) -> Unit,
    rotaryRotationDegrees: Float,
    onRotaryRotationChange: (Float) -> Unit,
    currentPage: HubPage,
    pageOffset: Float
) {
    val header = workspaceHeaderFor(currentPage)
    val isPageVisible = kotlin.math.abs(pageOffset) < 0.98f
    var title by remember(currentTask?.taskId, currentTask?.title) {
        mutableStateOf(currentTask?.title.orEmpty())
    }
    var requirement by remember(currentTask?.taskId, currentTask?.userRequirement) {
        mutableStateOf(currentTask?.userRequirement.orEmpty())
    }
    var interval by remember(currentTask?.taskId, currentTask?.checkInterval) {
        mutableStateOf(currentTask?.checkInterval?.toString().orEmpty())
    }
    var prompt by remember(currentTask?.taskId, currentTask?.promptTemplate) {
        mutableStateOf(currentTask?.promptTemplate.orEmpty())
    }
    var conciseGuideState by remember { mutableStateOf(ConciseMonitorGuideState.Idle) }

    val editedTask = currentTask?.copy(
        title = title,
        userRequirement = requirement,
        checkInterval = interval.toIntOrNull() ?: currentTask.checkInterval,
        promptTemplate = prompt
    )?.normalized()

    PageScaffold(
        page = currentPage,
        pageOffset = pageOffset,
        verticalSpacing = if (isConciseMode) 8.dp else 20.dp
    ) {
        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Header) {
            WatcherTopBar(
                eyebrow = if (isConciseMode) "Watcher" else header.eyebrow,
                title = if (isConciseMode) "智能小监控" else header.title,
                subtitle = if (isConciseMode) "AI 智能监控\n磁吸，插眼，获取视野！" else header.subtitle,
                currentPage = currentPage,
                pageOffset = pageOffset,
                showConciseModeToggle = true,
                isConciseMode = isConciseMode,
                onConciseModeChange = onConciseModeChange,
                rotaryRotationDegrees = rotaryRotationDegrees,
                onRotaryRotationChange = onRotaryRotationChange,
                onOpenSettings = onOpenSettings,
                onOpenAgentConfig = onOpenAgentConfig,
                onOpenWalletConfig = onOpenWalletConfig
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Hero) {
            CameraPreviewCard(
                title = "实时监控画面",
                subtitle = settings.streamDisplayUrl,
                streamState = streamState,
                isPlaying = isStreamPlaying,
                onPlayingChange = onPlayingChange,
                onReconnect = onReconnectStream,
                onCaptureSnapshot = onCaptureSnapshot,
                onOpenSettings = onOpenSettings,
                compact = isConciseMode,
                showFooterText = !isConciseMode,
                showFrameRatePill = !isConciseMode,
                previewActive = isPageVisible
            )
        }

        if (!isConciseMode) {
            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                MonitorTemplateCard(
                    templates = monitorTemplates,
                    onApplyTemplate = onApplyMonitorTemplate
                )
            }

            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                MonitorHistoryCard(
                    tasks = tasks,
                    currentTaskId = currentTask?.taskId,
                    onLoadTask = onLoadTask,
                    onDeleteTask = onDeleteTask
                )
            }
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
            if (isConciseMode) {
                ConciseMonitorSetupCard(
                    uiState = uiState,
                    requestText = requestText,
                    currentTask = currentTask,
                    pendingBaselinePath = pendingBaselineImagePath,
                    pendingBaselineBase64 = pendingBaselineBase64,
                    monitorStatus = monitorStatus,
                    isPageFocused = isPageVisible,
                    onRequestTextChange = onRequestTextChange,
                    onAnalyze = onAnalyze,
                    onClearPendingBaselineImage = onClearPendingBaselineImage,
                    onPickBaselineImage = onPickBaselineImage,
                    onSaveAndStartMonitoring = onSaveAndStartConciseMonitoring,
                    onGuideStateChange = { conciseGuideState = it }
                )
            } else {
                MonitorGuideCard(
                    uiState = uiState,
                    requestText = requestText,
                    isListening = isListening,
                    currentTask = currentTask,
                    pendingBaselinePath = pendingBaselineImagePath,
                    pendingBaselineBase64 = pendingBaselineBase64,
                    title = title,
                    requirement = requirement,
                    interval = interval,
                    prompt = prompt,
                    onTitleChange = { title = it },
                    onRequirementChange = { requirement = it },
                    onIntervalChange = { interval = it.filter(Char::isDigit) },
                    onPromptChange = { prompt = it },
                    onRequestTextChange = onRequestTextChange,
                    onStartListening = onStartListening,
                    onAnalyze = onAnalyze,
                    onSaveTask = onSaveTask,
                    onRefreshBaseline = onRefreshBaseline,
                    onPickBaselineImage = onPickBaselineImage,
                    onCopyJson = onCopyJson,
                    editedTask = editedTask
                )
            }
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
            MonitorStatusCard(
                currentTask = currentTask,
                editedTask = editedTask,
                monitorStatus = monitorStatus,
                monitorLogs = monitorLogs,
                isConciseMode = isConciseMode,
                conciseGuideState = conciseGuideState,
                onStartMonitoring = onStartMonitoring,
                onSaveTask = onSaveTask,
                onPauseMonitoring = onPauseMonitoring,
                onResumeMonitoring = onResumeMonitoring,
                onStopMonitoring = onStopMonitoring
            )
        }
    }
}

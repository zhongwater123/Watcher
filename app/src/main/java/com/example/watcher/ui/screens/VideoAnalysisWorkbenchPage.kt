package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.model.VideoTemplateEntity
import com.example.watcher.ui.components.ActionRow
import com.example.watcher.ui.components.CameraPreviewCard
import com.example.watcher.ui.components.EmptyHint
import com.example.watcher.ui.components.FormField
import com.example.watcher.ui.components.HistoryTile
import com.example.watcher.ui.components.InfiniteTemplateTicker
import com.example.watcher.ui.components.MjpegStreamUiState
import com.example.watcher.ui.components.MotionDepth
import com.example.watcher.ui.components.MotionStageSection
import com.example.watcher.ui.components.PageScaffold
import com.example.watcher.ui.components.StepBlock
import com.example.watcher.ui.components.StepProgressRow
import com.example.watcher.ui.components.TemplateTickerItem
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.components.WatcherTopBar
import com.example.watcher.ui.components.formFieldColors
import com.example.watcher.ui.theme.LocalWatcherExtendedColors
import com.example.watcher.ui.viewmodel.VideoPlanUiState

@Composable
internal fun VideoAnalysisWorkbenchPage(
    settings: VideoStreamSettings,
    streamState: MjpegStreamUiState,
    isStreamPlaying: Boolean,
    currentTask: VideoProcessTaskDraft?,
    videoTemplates: List<VideoTemplateEntity>,
    tasks: List<VideoProcessTask>,
    recentRuns: List<VideoProcessRun>,
    status: VideoProcessingStatus,
    planUiState: VideoPlanUiState,
    selectedRunId: Long?,
    selectedRunEvents: List<TimelineEventEntity>,
    requestText: TextFieldValue,
    isListening: Boolean,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onStartListening: () -> Unit,
    onAnalyze: () -> Unit,
    onApplyTemplate: (String) -> Unit,
    onSaveTask: (VideoProcessTaskDraft) -> Unit,
    onStartProcessing: (VideoProcessTaskDraft) -> Unit,
    onStopProcessing: () -> Unit,
    onLoadTask: (VideoProcessTask) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onSelectRun: (Long?) -> Unit,
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
    val selectedRun = remember(recentRuns, selectedRunId) {
        recentRuns.firstOrNull { it.id == selectedRunId }
    }
    var title by remember(currentTask?.taskId, currentTask?.title) {
        mutableStateOf(currentTask?.title.orEmpty())
    }
    var requirement by remember(currentTask?.taskId, currentTask?.userRequirement) {
        mutableStateOf(currentTask?.userRequirement.orEmpty())
    }
    var duration by remember(currentTask?.taskId, currentTask?.plannedDurationSeconds) {
        mutableStateOf(currentTask?.plannedDurationSeconds?.toString().orEmpty())
    }
    var samplingFps by remember(currentTask?.taskId, currentTask?.plannedSamplingFps) {
        mutableStateOf(currentTask?.plannedSamplingFps?.toString().orEmpty())
    }
    var segmentDuration by remember(currentTask?.taskId, currentTask?.plannedSegmentDurationSeconds) {
        mutableStateOf(currentTask?.plannedSegmentDurationSeconds?.toString().orEmpty())
    }
    var captureInterval by remember(currentTask?.taskId, currentTask?.captureIntervalSeconds) {
        mutableStateOf(currentTask?.captureIntervalSeconds?.toString().orEmpty())
    }
    var segmentAnalysisPrompt by remember(currentTask?.taskId, currentTask?.segmentAnalysisPrompt) {
        mutableStateOf(currentTask?.segmentAnalysisPrompt.orEmpty())
    }
    var finalSummaryPrompt by remember(currentTask?.taskId, currentTask?.finalSummaryPrompt) {
        mutableStateOf(currentTask?.finalSummaryPrompt.orEmpty())
    }
    var recordingScenario by remember(currentTask?.taskId, currentTask?.recordingScenario) {
        mutableStateOf(currentTask?.recordingScenario ?: RecordingScenario.General.value)
    }

    val editedTask = currentTask?.copy(
        title = title,
        userRequirement = requirement,
        recordingScenario = recordingScenario,
        speechInputEnabled = false,
        plannedDurationSeconds = duration.toIntOrNull() ?: currentTask.plannedDurationSeconds,
        plannedSamplingFps = samplingFps.toIntOrNull() ?: currentTask.plannedSamplingFps,
        plannedSegmentDurationSeconds = segmentDuration.toIntOrNull()
            ?: currentTask.plannedSegmentDurationSeconds,
        captureIntervalSeconds = captureInterval.toIntOrNull() ?: currentTask.captureIntervalSeconds,
        segmentAnalysisPrompt = segmentAnalysisPrompt,
        finalSummaryPrompt = finalSummaryPrompt
    )?.normalized()

    PageScaffold(page = currentPage, pageOffset = pageOffset) {
        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Header) {
            WatcherTopBar(
                eyebrow = header.eyebrow,
                title = header.title,
                subtitle = header.subtitle,
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
                title = "视频分析预览",
                subtitle = settings.streamDisplayUrl,
                streamState = streamState,
                isPlaying = isStreamPlaying,
                onPlayingChange = onPlayingChange,
                onReconnect = onReconnectStream,
                onCaptureSnapshot = onCaptureSnapshot,
                onOpenSettings = onOpenSettings,
                compact = true,
                previewActive = isPageVisible
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            TemplateCard(templates = videoTemplates, onApplyTemplate = onApplyTemplate)
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            VideoHistoryCard(
                tasks = tasks,
                currentTaskId = currentTask?.taskId,
                recentRuns = recentRuns,
                selectedRunId = selectedRunId,
                onLoadTask = onLoadTask,
                onDeleteTask = onDeleteTask,
                onSelectRun = onSelectRun
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
            VideoGuideCard(
                planUiState = planUiState,
                requestText = requestText,
                isListening = isListening,
                currentTask = currentTask,
                editedTask = editedTask,
                status = status,
                title = title,
                requirement = requirement,
                duration = duration,
                samplingFps = samplingFps,
                segmentDuration = segmentDuration,
                captureInterval = captureInterval,
                segmentAnalysisPrompt = segmentAnalysisPrompt,
                finalSummaryPrompt = finalSummaryPrompt,
                recordingScenario = recordingScenario,
                onTitleChange = { title = it },
                onRequirementChange = { requirement = it },
                onDurationChange = { duration = it.filter(Char::isDigit) },
                onSamplingFpsChange = { samplingFps = it.filter(Char::isDigit) },
                onSegmentDurationChange = { segmentDuration = it.filter(Char::isDigit) },
                onCaptureIntervalChange = { captureInterval = it.filter(Char::isDigit) },
                onSegmentAnalysisPromptChange = { segmentAnalysisPrompt = it },
                onFinalSummaryPromptChange = { finalSummaryPrompt = it },
                onRecordingScenarioChange = { recordingScenario = it },
                onResetSegmentAnalysisPrompt = {
                    segmentAnalysisPrompt = VideoProcessTaskDraft.buildFallbackSegmentAnalysisPrompt(
                        userRequirement = requirement,
                        sceneContext = editedTask?.sceneContext ?: currentTask?.sceneContext.orEmpty()
                    )
                },
                onResetFinalSummaryPrompt = {
                    finalSummaryPrompt = VideoProcessTaskDraft.buildFallbackFinalSummaryPrompt(
                        userRequirement = requirement,
                        sceneContext = editedTask?.sceneContext ?: currentTask?.sceneContext.orEmpty()
                    )
                },
                onRequestTextChange = onRequestTextChange,
                onStartListening = onStartListening,
                onAnalyze = onAnalyze,
                onSaveTask = { editedTask?.let(onSaveTask) },
                onCopyJson = onCopyJson
            )
        }

        if (status.streamingEnabled && (status.isBusy || status.streamingBuffer.isNotBlank())) {
            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
                VideoStreamingCard(status = status)
            }
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
            VideoResultCard(
                currentTask = currentTask,
                editedTask = editedTask,
                status = status,
                selectedRun = selectedRun,
                selectedRunEvents = selectedRunEvents,
                onSaveTask = onSaveTask,
                onStartProcessing = onStartProcessing,
                onStopProcessing = onStopProcessing
            )
        }
    }
}

@Composable
private fun TemplateCard(
    templates: List<VideoTemplateEntity>,
    onApplyTemplate: (String) -> Unit
) {
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("模板任务", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "点选模板快速填充分析参数，后续仍可手动微调。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            InfiniteTemplateTicker(
                items = templates.map { template ->
                    TemplateTickerItem(
                        id = template.templateId,
                        label = template.label
                    )
                },
                onItemClick = onApplyTemplate,
                modifier = Modifier.height(96.dp)
            )
        }
    }
}

@Composable
private fun VideoHistoryCard(
    tasks: List<VideoProcessTask>,
    currentTaskId: Long?,
    recentRuns: List<VideoProcessRun>,
    selectedRunId: Long?,
    onLoadTask: (VideoProcessTask) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onSelectRun: (Long?) -> Unit
) {
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("任务与运行记录", style = MaterialTheme.typography.titleLarge)
            LazyColumn(
                modifier = Modifier.height(240.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tasks, key = { "task-${it.id}" }) { task ->
                    HistoryTile(
                        title = task.title,
                        subtitle = task.userRequirement,
                        supporting = buildVideoRhythmSummary(
                            segmentDurationSeconds = task.plannedSegmentDurationSeconds,
                            captureIntervalSeconds = task.captureIntervalSeconds,
                            segmentCount = task.plannedSegmentCount
                        ),
                        selected = task.id == currentTaskId,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { onLoadTask(task) },
                        onDelete = { onDeleteTask(task.id) }
                    )
                }
                items(recentRuns, key = { "run-${it.id}" }) { run ->
                    HistoryTile(
                        title = "${videoStageLabel(run.status)} / ${formatDateTime(run.updatedAt)}",
                        subtitle = run.finalSummary.ifBlank { run.errorMessage ?: "暂无摘要" },
                        supporting = buildVideoRhythmSummary(
                            segmentDurationSeconds = run.segmentDurationSeconds,
                            captureIntervalSeconds = run.captureIntervalSeconds,
                            segmentCount = run.segmentCount
                        ),
                        selected = run.id == selectedRunId,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onClick = { onSelectRun(run.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoGuideCard(
    planUiState: VideoPlanUiState,
    requestText: TextFieldValue,
    isListening: Boolean,
    currentTask: VideoProcessTaskDraft?,
    editedTask: VideoProcessTaskDraft?,
    status: VideoProcessingStatus,
    title: String,
    requirement: String,
    duration: String,
    samplingFps: String,
    segmentDuration: String,
    captureInterval: String,
    segmentAnalysisPrompt: String,
    finalSummaryPrompt: String,
    recordingScenario: String,
    onTitleChange: (String) -> Unit,
    onRequirementChange: (String) -> Unit,
    onDurationChange: (String) -> Unit,
    onSamplingFpsChange: (String) -> Unit,
    onSegmentDurationChange: (String) -> Unit,
    onCaptureIntervalChange: (String) -> Unit,
    onSegmentAnalysisPromptChange: (String) -> Unit,
    onFinalSummaryPromptChange: (String) -> Unit,
    onRecordingScenarioChange: (String) -> Unit,
    onResetSegmentAnalysisPrompt: () -> Unit,
    onResetFinalSummaryPrompt: () -> Unit,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onStartListening: () -> Unit,
    onAnalyze: () -> Unit,
    onSaveTask: () -> Unit,
    onCopyJson: () -> Unit
) {
    val displayTask = editedTask ?: currentTask

    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("新建视频分析任务", style = MaterialTheme.typography.titleLarge)
            StepProgressRow(
                steps = listOf(
                    StepState("输入意图", requestText.text.isNotBlank(), currentTask == null),
                    StepState("确认参数", currentTask != null, currentTask != null)
                )
            )

            StepBlock(number = 1, title = "告诉模型这次要分析什么") {
                OutlinedTextField(
                    value = requestText,
                    onValueChange = onRequestTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("视频分析意图") },
                    placeholder = {
                        Text("例如：看看这一分钟会发生什么，重点记录人物动作和异常变化。")
                    },
                    colors = formFieldColors()
                )
                if (planUiState is VideoPlanUiState.Error) {
                    Text(
                        text = planUiState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                ActionRow(
                    primaryLabel = if (planUiState is VideoPlanUiState.Loading) "正在生成计划" else "生成分析计划",
                    onPrimaryClick = onAnalyze,
                    primaryEnabled = requestText.text.isNotBlank() && planUiState !is VideoPlanUiState.Loading,
                    secondaryLabel = if (isListening) "正在录入" else "语音输入",
                    onSecondaryClick = onStartListening,
                    secondaryEnabled = !isListening && planUiState !is VideoPlanUiState.Loading,
                    secondaryIcon = Icons.Default.Mic
                )
            }

            StepBlock(number = 2, title = "确认执行参数") {
                if (currentTask == null || displayTask == null) {
                    EmptyHint(text = "先生成分析计划，或直接选择上方模板任务。")
                } else {
                    Text(
                        text = videoTaskCategoryLabel(displayTask.taskCategory),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (displayTask.strategyReason.isNotBlank()) {
                        Text(
                            text = displayTask.strategyReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (displayTask.confirmationNotes.isNotBlank()) {
                        Text(
                            text = displayTask.confirmationNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "记录场景",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RecordingScenario.entries.forEach { scenario ->
                            TextButton(onClick = { onRecordingScenarioChange(scenario.value) }) {
                                Text(
                                    text = if (recordingScenario == scenario.value) {
                                        "✓ ${scenario.label} · ${scenario.outputFocus}"
                                    } else {
                                        "${scenario.label} · ${scenario.outputFocus}"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("全帧率音画录制", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "录制器以源帧率(≈30fps)写入完整音画分片；上传分析时按抽帧密度取样，音轨完整保留供模型多模态理解。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (status.isBusy || status.segmentAudioResults.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                val micColor = if (status.micPermissionGranted)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                                val micLabel = if (status.micPermissionGranted) "麦克风已授权" else "麦克风未授权"
                                Text(
                                    text = micLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = micColor
                                )
                                if (status.isRecordingActive) {
                                    val audioLabel = if (status.currentSegmentHasAudio) "音频: 录入中" else "音频: 未录入"
                                    Text(
                                        text = audioLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (status.segmentAudioResults.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    status.segmentAudioResults.forEachIndexed { index, hasAudio ->
                                        Text(
                                            text = if (hasAudio) "S${index + 1}:有音频" else "S${index + 1}:无音频",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (hasAudio)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    FormField(label = "任务标题", value = title, onValueChange = onTitleChange)
                    FormField(label = "分析目标", value = requirement, onValueChange = onRequirementChange)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormField(
                            label = "总时长(秒)",
                            value = duration,
                            onValueChange = onDurationChange,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                        FormField(
                            label = "抽帧密度(帧/秒)",
                            value = samplingFps,
                            onValueChange = onSamplingFpsChange,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormField(
                            label = "每次录制时长(秒)",
                            value = segmentDuration,
                            onValueChange = onSegmentDurationChange,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                        FormField(
                            label = "录制起点间隔(秒)",
                            value = captureInterval,
                            onValueChange = onCaptureIntervalChange,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = buildVideoRhythmSummary(
                            segmentDurationSeconds = displayTask.plannedSegmentDurationSeconds,
                            captureIntervalSeconds = displayTask.captureIntervalSeconds,
                            segmentCount = displayTask.plannedSegmentCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "抽帧密度仅影响模型理解视频时的取样频率，不影响本地录制帧率。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FormField(
                        label = "分析提示词",
                        value = segmentAnalysisPrompt,
                        onValueChange = onSegmentAnalysisPromptChange,
                        minLines = 4
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onResetSegmentAnalysisPrompt) {
                            Text("恢复推荐分片提示词")
                        }
                    }
                    FormField(
                        label = "最终汇总提示词",
                        value = finalSummaryPrompt,
                        onValueChange = onFinalSummaryPromptChange,
                        minLines = 4
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onResetFinalSummaryPrompt) {
                            Text("恢复推荐汇总提示词")
                        }
                    }
                    ActionRow(
                        primaryLabel = if (status.isTaskSaving) "保存中" else "保存任务",
                        onPrimaryClick = onSaveTask,
                        primaryEnabled = !status.isTaskSaving,
                        secondaryLabel = "复制 JSON",
                        onSecondaryClick = onCopyJson,
                        secondaryEnabled = true,
                        secondaryIcon = Icons.Default.ContentCopy
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoStreamingCard(status: VideoProcessingStatus) {
    val extendedColors = LocalWatcherExtendedColors.current
    val streamingText = status.streamingBuffer.trim()
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("流式处理过程", style = MaterialTheme.typography.titleLarge)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = extendedColors.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "${videoStageLabel(status.stage)} / ${status.recordedSegmentCount}/${status.segmentCount} 段",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "片段 ${status.segmentDurationSeconds}s / 间隔 ${status.captureIntervalSeconds}s / 剩余 ${status.remainingDurationSeconds}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    status.nextCaptureInSeconds?.let {
                        Text(
                            text = "下一次采样约在 ${it}s 后",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(text = status.message, style = MaterialTheme.typography.bodySmall)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        if (streamingText.isBlank()) {
                            Text(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                text = "等待流式返回…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .padding(14.dp)
                            ) {
                                item { Text(text = streamingText, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoResultCard(
    currentTask: VideoProcessTaskDraft?,
    editedTask: VideoProcessTaskDraft?,
    status: VideoProcessingStatus,
    selectedRun: VideoProcessRun?,
    selectedRunEvents: List<TimelineEventEntity>,
    onSaveTask: (VideoProcessTaskDraft) -> Unit,
    onStartProcessing: (VideoProcessTaskDraft) -> Unit,
    onStopProcessing: () -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val showingHistoricalRun = !status.isBusy && selectedRun != null && selectedRun.id != status.activeRunId
    val summary = if (showingHistoricalRun) selectedRun!!.finalSummary else status.finalSummary
    val conclusion = if (showingHistoricalRun) selectedRun!!.finalConclusion else status.finalConclusion
    val timelineEvents = if (showingHistoricalRun) {
        selectedRunEvents.take(5)
    } else {
        visibleTimelineEvents(selectedRunEvents, status)
    }

    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("分析结果与执行状态", style = MaterialTheme.typography.titleLarge)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = extendedColors.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text = videoStageLabel(if (showingHistoricalRun) selectedRun!!.status else status.stage))
                    Text(
                        text = if (showingHistoricalRun) {
                            selectedRun!!.templateLabel ?: selectedRun.taskTitle
                        } else {
                            status.templateLabel ?: currentTask?.title ?: "当前任务"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = videoHeadlineStatus(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (status.speechInputEnabled) {
                        Text(
                            text = status.speechErrorMessage
                                ?: if (status.isSpeechListening) "语音识别中，最近转写会参与分片分析。"
                                else "语音输入已开启，等待识别结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.speechErrorMessage != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        status.recentSpeech.take(3).forEach { transcript ->
                            Text(
                                text = transcript.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if ((editedTask ?: currentTask) != null) {
                        Text(
                            text = buildVideoRhythmSummary(
                                segmentDurationSeconds = (editedTask ?: currentTask)!!.plannedSegmentDurationSeconds,
                                captureIntervalSeconds = (editedTask ?: currentTask)!!.captureIntervalSeconds,
                                segmentCount = (editedTask ?: currentTask)!!.plannedSegmentCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (summary.isNotBlank()) {
                        Text("结果摘要", color = MaterialTheme.colorScheme.primary)
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (conclusion.isNotBlank()) {
                        Text("最终结论", color = MaterialTheme.colorScheme.tertiary)
                        Text(conclusion, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (timelineEvents.isNotEmpty()) {
                        Text("关键时间线", color = MaterialTheme.colorScheme.primary)
                        timelineEvents.forEach { event ->
                            Text(
                                text = "• ${formatTimelineSeconds(event.timestampSeconds)} ${event.title}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            if (status.isBusy) {
                ActionRow(
                    primaryLabel = if (status.stopRequested) "停止中" else "停止当前分析",
                    onPrimaryClick = onStopProcessing,
                    primaryEnabled = !status.stopRequested,
                    secondaryLabel = "处理中",
                    onSecondaryClick = {},
                    secondaryEnabled = false,
                    secondaryIcon = Icons.Default.AutoAwesome
                )
            } else {
                ActionRow(
                    primaryLabel = "启动视频分析",
                    onPrimaryClick = { editedTask?.let(onStartProcessing) },
                    primaryEnabled = editedTask != null,
                    secondaryLabel = if (status.isTaskSaving) "保存中" else "仅保存计划",
                    onSecondaryClick = { editedTask?.let(onSaveTask) },
                    secondaryEnabled = editedTask != null && !status.isTaskSaving,
                    secondaryIcon = Icons.Default.Tune
                )
            }
        }
    }
}

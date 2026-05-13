package com.example.watcher.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorLogEntry
import com.example.watcher.data.model.MonitorMode
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.data.model.MonitorTask
import com.example.watcher.data.model.MonitorTemplateEntity
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.model.baselineSourceLabel
import com.example.watcher.data.model.monitorModeLabel
import com.example.watcher.data.model.targetTriggerLabel
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
import com.example.watcher.ui.components.StatusPill
import com.example.watcher.ui.components.StepBlock
import com.example.watcher.ui.components.StepProgressRow
import com.example.watcher.ui.components.TemplateTickerItem
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.components.WatcherTopBar
import com.example.watcher.ui.components.formFieldColors
import com.example.watcher.ui.theme.LocalWatcherExtendedColors
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
    onStartMonitoring: (IntentResult) -> Unit,
    onPauseMonitoring: () -> Unit,
    onResumeMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onRefreshBaseline: () -> Unit,
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

    val editedTask = currentTask?.copy(
        title = title,
        userRequirement = requirement,
        checkInterval = interval.toIntOrNull() ?: currentTask.checkInterval,
        promptTemplate = prompt
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
                title = "实时监控画面",
                subtitle = settings.streamDisplayUrl,
                streamState = streamState,
                isPlaying = isStreamPlaying,
                onPlayingChange = onPlayingChange,
                onReconnect = onReconnectStream,
                onCaptureSnapshot = onCaptureSnapshot,
                onOpenSettings = onOpenSettings,
                compact = true
            )
        }

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

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
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

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
            MonitorStatusCard(
                currentTask = currentTask,
                editedTask = editedTask,
                monitorStatus = monitorStatus,
                monitorLogs = monitorLogs,
                onStartMonitoring = onStartMonitoring,
                onSaveTask = onSaveTask,
                onPauseMonitoring = onPauseMonitoring,
                onResumeMonitoring = onResumeMonitoring,
                onStopMonitoring = onStopMonitoring
            )
        }
    }
}

@Composable
private fun MonitorTemplateCard(
    templates: List<MonitorTemplateEntity>,
    onApplyTemplate: (String) -> Unit
) {
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("模板任务", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "点选模板快速填充监控参数，后续仍可手动微调。",
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
private fun MonitorHistoryCard(
    tasks: List<MonitorTask>,
    currentTaskId: Long?,
    onLoadTask: (MonitorTask) -> Unit,
    onDeleteTask: (Long) -> Unit
) {
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("历史监控任务", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "在卡片内部上下滚动，直接复用已有监控配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tasks.isEmpty()) {
                EmptyHint(text = "还没有历史监控任务，先生成一条新的监控需求。")
            } else {
                LazyColumn(
                    modifier = Modifier.height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        HistoryTile(
                            title = task.title,
                            subtitle = task.userRequirement,
                            supporting = task.lastSummary ?: "每 ${task.checkInterval} 秒巡检一次",
                            selected = task.id == currentTaskId,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { onLoadTask(task) },
                            onDelete = { onDeleteTask(task.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorGuideCard(
    uiState: UiState,
    requestText: TextFieldValue,
    isListening: Boolean,
    currentTask: IntentResult?,
    pendingBaselinePath: String?,
    pendingBaselineBase64: String?,
    title: String,
    requirement: String,
    interval: String,
    prompt: String,
    onTitleChange: (String) -> Unit,
    onRequirementChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onStartListening: () -> Unit,
    onAnalyze: () -> Unit,
    onSaveTask: (IntentResult) -> Unit,
    onRefreshBaseline: () -> Unit,
    onPickBaselineImage: () -> Unit,
    onCopyJson: () -> Unit,
    editedTask: IntentResult?
) {
    WatcherCard {
        val baselinePreview = remember(
            currentTask?.baselineImagePath,
            currentTask?.baseFrameBase64,
            pendingBaselinePath,
            pendingBaselineBase64
        ) {
            currentTask?.baselineImagePath?.let(BitmapFactory::decodeFile)
                ?: currentTask?.baseFrameBase64?.let(::decodeBase64Bitmap)
                ?: pendingBaselinePath?.let(BitmapFactory::decodeFile)
                ?: pendingBaselineBase64?.let(::decodeBase64Bitmap)
        }
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("新建监控任务", style = MaterialTheme.typography.titleLarge)
            StepProgressRow(
                steps = listOf(
                    StepState("输入需求", requestText.text.isNotBlank(), currentTask == null),
                    StepState("确认配置", currentTask != null, currentTask != null)
                )
            )

            StepBlock(number = 1, title = "描述你想监控什么") {
                OutlinedTextField(
                    value = requestText,
                    onValueChange = onRequestTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("监控需求") },
                    placeholder = { Text("例如：有人靠近床铺并停留超过 10 秒时提醒我。") },
                    colors = formFieldColors()
                )
                if (uiState is UiState.Error) {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onPickBaselineImage,
                        enabled = uiState !is UiState.Loading,
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "上传图片",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                    FilledTonalButton(
                        onClick = onStartListening,
                        enabled = !isListening && uiState !is UiState.Loading,
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isListening) "正在录入…" else "语音输入",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                Button(
                    onClick = onAnalyze,
                    enabled = requestText.text.isNotBlank() && uiState !is UiState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                ) {
                    Text(if (uiState is UiState.Loading) "正在生成任务规划…" else "生成任务规划")
                }
                Text(
                    text = "如果你要盯的是某个具体的人或物，先上传参考图，效果通常会更好。\n如果你只关心某类特征或动作，可以直接用文字描述。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                baselinePreview?.let {
                    FrameTile(
                        title = "待分析参考图片",
                        bitmap = it,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            StepBlock(number = 2, title = "检查并微调任务参数") {
                if (currentTask == null) {
                    EmptyHint(text = "先生成监控任务，这里才会解锁配置。")
                } else {
                    FormField(label = "任务标题", value = title, onValueChange = onTitleChange)
                    FormField(label = "监控目标", value = requirement, onValueChange = onRequirementChange)
                    FormField(
                        label = "巡检间隔（秒）",
                        value = interval,
                        onValueChange = onIntervalChange,
                        keyboardType = KeyboardType.Number
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("监控模式", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = monitorModeLabel(currentTask.monitorMode),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (currentTask.monitorMode == MonitorMode.ReferenceTarget) {
                                Text(
                                    text = targetTriggerLabel(currentTask.targetTrigger),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "基准图来源：${baselineSourceLabel(currentTask.baselineSource)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FormField(label = "提示词", value = prompt, onValueChange = onPromptChange, minLines = 4)
                    ActionRow(
                        primaryLabel = "保存任务",
                        onPrimaryClick = { editedTask?.let(onSaveTask) },
                        primaryEnabled = editedTask != null,
                        secondaryLabel = "刷新基准帧",
                        onSecondaryClick = onRefreshBaseline,
                        secondaryEnabled = true,
                        secondaryIcon = Icons.Default.Refresh
                    )
                    TextButton(onClick = onPickBaselineImage) {
                        Text("重新上传图片")
                    }
                    TextButton(onClick = onCopyJson) {
                        androidx.compose.material3.Icon(Icons.Default.ContentCopy, contentDescription = null)
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                        Text("复制 JSON")
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorStatusCard(
    currentTask: IntentResult?,
    editedTask: IntentResult?,
    monitorStatus: MonitorStatus,
    monitorLogs: List<MonitorLogEntry>,
    onStartMonitoring: (IntentResult) -> Unit,
    onSaveTask: (IntentResult) -> Unit,
    onPauseMonitoring: () -> Unit,
    onResumeMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val statusAccent = monitorStatusAccent(monitorStatus)
    val latestLog = monitorLogs.firstOrNull()
    val summaryText = monitorStatus.lastSummary.ifBlank {
        monitorStatus.lastReason.ifBlank {
            currentTask?.userRequirement ?: "任务启动后，这里会持续显示实时监控状态与最近日志。"
        }
    }

    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusPill(
                    text = monitorActivityLabel(monitorStatus, currentTask),
                    accent = statusAccent
                )
                StatusPill(
                    text = checkResultLabel(monitorStatus.lastResult),
                    accent = monitorResultAccent(monitorStatus.lastResult)
                )
            }

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
                    Text("监控运行状态", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (monitorStatus.lastReason.isNotBlank() && monitorStatus.lastReason != monitorStatus.lastSummary) {
                        Text(
                            text = "判定依据：${monitorStatus.lastReason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = buildMonitorStats(monitorStatus),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (monitorStatus.lastCheckTime > 0) {
                        Text(
                            text = "最近巡检：${formatDateTime(monitorStatus.lastCheckTime)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (latestLog != null) {
                        Text(
                            text = "最近日志：${latestLog.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    currentTask?.let { task ->
                        Text(
                            text = buildString {
                                append("模式：")
                                append(monitorModeLabel(task.monitorMode))
                                if (task.monitorMode == MonitorMode.ReferenceTarget) {
                                    append(" · ")
                                    append(targetTriggerLabel(task.targetTrigger))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    MonitorFrameComparison(
                        monitorMode = currentTask?.monitorMode ?: MonitorMode.SceneBaseline,
                        baselinePath = currentTask?.baselineImagePath
                            ?: monitorStatus.effectiveBaselineImagePath,
                        baselineBase64 = currentTask?.baseFrameBase64,
                        analyzedPath = monitorStatus.lastAnalyzedImagePath
                            ?: latestLog?.imagePath
                    )
                }
            }

            when {
                !monitorStatus.isRunning -> ActionRow(
                    primaryLabel = "启动实时监控",
                    onPrimaryClick = { editedTask?.let(onStartMonitoring) },
                    primaryEnabled = editedTask != null,
                    secondaryLabel = "仅保存稍后启动",
                    onSecondaryClick = { editedTask?.let(onSaveTask) },
                    secondaryEnabled = editedTask != null,
                    secondaryIcon = Icons.Default.Tune
                )

                monitorStatus.isPaused -> ActionRow(
                    primaryLabel = "继续监控",
                    onPrimaryClick = onResumeMonitoring,
                    primaryEnabled = true,
                    secondaryLabel = "停止",
                    onSecondaryClick = onStopMonitoring,
                    secondaryEnabled = true,
                    secondaryIcon = Icons.Default.Stop
                )

                else -> ActionRow(
                    primaryLabel = "暂停监控",
                    onPrimaryClick = onPauseMonitoring,
                    primaryEnabled = true,
                    secondaryLabel = "停止",
                    onSecondaryClick = onStopMonitoring,
                    secondaryEnabled = true,
                    secondaryIcon = Icons.Default.Stop
                )
            }
        }
    }
}

@Composable
private fun MonitorFrameComparison(
    monitorMode: MonitorMode,
    baselinePath: String?,
    baselineBase64: String?,
    analyzedPath: String?
) {
    val baselineBitmap = remember(baselinePath, baselineBase64) {
        baselinePath?.let(BitmapFactory::decodeFile)
            ?: baselineBase64?.let(::decodeBase64Bitmap)
    }
    val analyzedBitmap = remember(analyzedPath) {
        analyzedPath?.let(BitmapFactory::decodeFile)
    }

    if (baselineBitmap == null && analyzedBitmap == null) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val baselineTitle = if (monitorMode == MonitorMode.ReferenceTarget) "目标参考图" else "基准图片"
        Text(
            text = "图片对比",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = baselineTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FrameTile(
                title = "基准图片",
                bitmap = baselineBitmap,
                modifier = Modifier.weight(1f)
            )
            FrameTile(
                title = "本轮分析图片",
                bitmap = analyzedBitmap,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FrameTile(
    title: String,
    bitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (bitmap == null) {
            EmptyHint(text = "暂无图片")
            return
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

private fun decodeBase64Bitmap(base64: String): Bitmap? {
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

private fun monitorActivityLabel(status: MonitorStatus, currentTask: IntentResult?): String {
    return when {
        status.isRunning && status.isPaused -> "监控已暂停"
        status.isRunning -> "实时监控运行中"
        currentTask != null -> "任务已就绪"
        else -> "等待启动任务"
    }
}

@Composable
private fun monitorStatusAccent(status: MonitorStatus): Color {
    return when {
        status.isRunning && status.isPaused -> MaterialTheme.colorScheme.tertiary
        status.isRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
}

@Composable
private fun monitorResultAccent(result: CheckResult): Color {
    return when (result) {
        CheckResult.ALERT -> Color(0xFFC9485B)
        CheckResult.WARNING -> Color(0xFFE9A23B)
        CheckResult.NORMAL -> Color(0xFF0E8B65)
        CheckResult.UNKNOWN -> Color(0xFF5B6C8F)
        CheckResult.NONE -> MaterialTheme.colorScheme.outline
    }
}

private fun buildMonitorStats(status: MonitorStatus): String {
    return buildString {
        append("累计巡检 ${status.totalCheckCount} 次")
        append(" · 告警 ${status.alertCount}")
        append(" · 预警 ${status.warningCount}")
        append(" · 正常 ${status.normalCount}")
        if (status.failureCount > 0) {
            append(" · 失败 ${status.failureCount}")
        }
        if (status.skippedCount > 0) {
            append(" · 跳过 ${status.skippedCount}")
        }
    }
}

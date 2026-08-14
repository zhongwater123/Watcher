package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.BaselineSource
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorMode
import com.example.watcher.data.model.MonitorLogEntry
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.data.model.monitorModeLabel
import com.example.watcher.data.model.targetTriggerLabel
import com.example.watcher.ui.components.ActionRow
import com.example.watcher.ui.components.EmptyHint
import com.example.watcher.ui.components.StatusPill
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.theme.LocalWatcherExtendedColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MonitorStatusCard(
    currentTask: IntentResult?,
    editedTask: IntentResult?,
    monitorStatus: MonitorStatus,
    monitorLogs: List<MonitorLogEntry>,
    isConciseMode: Boolean = false,
    conciseGuideState: ConciseMonitorGuideState = ConciseMonitorGuideState.Idle,
    onStartMonitoring: (IntentResult) -> Unit,
    onSaveTask: (IntentResult) -> Unit,
    onPauseMonitoring: () -> Unit,
    onResumeMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    if (isConciseMode && !monitorStatus.isRunning) {
        ConciseMonitorIdleGuideCard(guideState = conciseGuideState)
        return
    }

    if (isConciseMode && monitorStatus.isRunning) {
        ConciseRunningMonitorStatusCard(
            currentTask = currentTask,
            monitorStatus = monitorStatus,
            onPauseMonitoring = onPauseMonitoring,
            onResumeMonitoring = onResumeMonitoring,
            onStopMonitoring = onStopMonitoring
        )
        return
    }

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

                    if (monitorStatus.lastRemark.isNotBlank()) {
                        Text(
                            text = "监控旁白：${monitorStatus.lastRemark}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

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
private fun ConciseRunningMonitorStatusCard(
    currentTask: IntentResult?,
    monitorStatus: MonitorStatus,
    onPauseMonitoring: () -> Unit,
    onResumeMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    val taskTarget = currentTask?.userRequirement?.trim().orEmpty()
    val summaryText = monitorStatus.lastSummary.trim()
    val hasReferenceImage = currentTask?.baselineSource == BaselineSource.UploadedImage ||
        currentTask?.baselineImagePath != null
    val referenceBitmap = if (hasReferenceImage) {
        rememberDecodedBitmap(
            path = currentTask?.baselineImagePath,
            base64 = currentTask?.baseFrameBase64
        )
    } else {
        null
    }
    val statusChipText = if (monitorStatus.isPaused) {
        "已暂停"
    } else {
        checkResultLabel(monitorStatus.lastResult)
    }
    val statusChipAccent = if (monitorStatus.isPaused) {
        MaterialTheme.colorScheme.tertiary
    } else {
        monitorResultAccent(monitorStatus.lastResult)
    }
    val runningPath = if (hasReferenceImage) {
        ConciseMonitorSetupPath.Reference
    } else {
        ConciseMonitorSetupPath.Direct
    }
    val runningShape = RoundedCornerShape(32.dp)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = runningShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.86f),
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
                    statusChipAccent.copy(alpha = 0.16f)
                )
            )
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            ConciseWaveDividerBackground(
                selectedPath = runningPath,
                isPageFocused = true,
                modifier = Modifier.matchParentSize()
            )
            ConciseFrostedGlassLayer(
                selectedPath = runningPath,
                modifier = Modifier.matchParentSize()
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatusPill(
                            text = statusChipText,
                            accent = statusChipAccent
                        )
                        if (monitorStatus.lastCheckTime > 0) {
                            StatusPill(
                                text = "Watcher在${formatTimeOfDay(monitorStatus.lastCheckTime)}看了一眼",
                                accent = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    if (hasReferenceImage) {
                        ConciseReferenceRunningCore(
                            referenceBitmap = referenceBitmap,
                            summaryText = summaryText
                        )
                    } else {
                        ConciseDirectRunningCore(
                            taskTarget = taskTarget,
                            summaryText = summaryText
                        )
                    }

                    ConciseRunningActionRow(
                        primaryLabel = if (monitorStatus.isPaused) "继续监控" else "暂停监控",
                        onPrimaryClick = if (monitorStatus.isPaused) onResumeMonitoring else onPauseMonitoring,
                        onStopMonitoring = onStopMonitoring
                    )
                }
            }
        }
    }
}

@Composable
private fun ConciseReferenceRunningCore(
    referenceBitmap: Bitmap?,
    summaryText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        ConciseReferencePreview(bitmap = referenceBitmap)
        if (summaryText.isNotBlank()) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ConciseStatusTextPanel(
                    text = summaryText,
                    emphasized = true,
                    modifier = Modifier.height(134.dp)
                )
            }
        }
    }
}

@Composable
private fun ConciseDirectRunningCore(
    taskTarget: String,
    summaryText: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (taskTarget.isNotBlank()) {
            ConciseStatusTextPanel(
                text = taskTarget,
                emphasized = true
            )
        }
        if (summaryText.isNotBlank()) {
            ConciseStatusTextPanel(text = summaryText)
        }
    }
}

@Composable
private fun ConciseReferencePreview(
    bitmap: Bitmap?
) {
    Column(
        modifier = Modifier.width(104.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(104.dp),
            shape = RoundedCornerShape(20.dp),
            color = LocalWatcherExtendedColors.current.surfaceContainerLow
        ) {
            if (bitmap == null) {
                EmptyHint(text = "暂无参考图")
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Text(
            text = "您让我注意TA",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConciseStatusTextPanel(
    text: String,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.78f),
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (emphasized) 72.dp else 64.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.30f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.16f)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = text,
                style = if (emphasized) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConciseRunningActionRow(
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onPrimaryClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = primaryLabel,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
        FilledTonalButton(
            onClick = onStopMonitoring,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "停止任务",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun ConciseMonitorIdleGuideCard(guideState: ConciseMonitorGuideState) {
    val activeStep = when (guideState) {
        ConciseMonitorGuideState.Idle -> 1
        ConciseMonitorGuideState.ReferenceEditing,
        ConciseMonitorGuideState.DirectEditing -> 2
        ConciseMonitorGuideState.Generated -> 3
    }
    val title = when (guideState) {
        ConciseMonitorGuideState.Idle -> "先选择一种看管方式"
        ConciseMonitorGuideState.ReferenceEditing -> "用一张照片锁定 TA"
        ConciseMonitorGuideState.DirectEditing -> "直接说出你想看住什么"
        ConciseMonitorGuideState.Generated -> "看管方案已经准备好了"
    }
    val body = when (guideState) {
        ConciseMonitorGuideState.Idle -> "点击上方左侧或右侧入口，Watcher 会按你的选择展开配置。"
        ConciseMonitorGuideState.ReferenceEditing -> "上传 TA 的照片，再补一句你想让我留意什么；图片和描述都完成后就能生成方案。"
        ConciseMonitorGuideState.DirectEditing -> "用一句话描述现场变化或物品状态，我会整理成可以执行的看管方案。"
        ConciseMonitorGuideState.Generated -> "确认无误后，点击上方「开始看管」，我就开始盯住这件事。"
    }
    val accent = when (guideState) {
        ConciseMonitorGuideState.Idle -> MaterialTheme.colorScheme.primary
        ConciseMonitorGuideState.ReferenceEditing -> MaterialTheme.colorScheme.tertiary
        ConciseMonitorGuideState.DirectEditing -> MaterialTheme.colorScheme.secondary
        ConciseMonitorGuideState.Generated -> MaterialTheme.colorScheme.primary
    }

    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(text = "空闲引导模式", accent = accent)
                StatusPill(text = "步骤 $activeStep / 3", accent = accent)
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = accent.copy(alpha = 0.14f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
    val baselineBitmap = rememberDecodedBitmap(path = baselinePath, base64 = baselineBase64)
    val analyzedBitmap = rememberDecodedBitmap(path = analyzedPath, base64 = null)

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
internal fun FrameTile(
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

private fun formatTimeOfDay(timestamp: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

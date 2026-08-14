package com.example.watcher.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.watcher.data.model.HistoryRecordDetail
import com.example.watcher.data.model.HistoryRecordItem
import com.example.watcher.data.model.HistoryRecordSelection
import com.example.watcher.data.model.HistoryRecordType
import com.example.watcher.data.model.MonitorHistoryDetail
import com.example.watcher.data.model.StorageSummary
import com.example.watcher.data.model.VideoHistoryDetail
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.model.isStaleMonitorRun
import com.example.watcher.data.model.isStalePostCaptureVideoRun
import com.example.watcher.ui.components.EmptyHint
import com.example.watcher.ui.components.HistoryTile
import com.example.watcher.ui.components.MotionDepth
import com.example.watcher.ui.components.MotionStageSection
import com.example.watcher.ui.components.PageScaffold
import com.example.watcher.ui.components.StatusPill
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.components.WatcherTopBar
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun HistoryWorkbenchPage(
    historyRecords: List<HistoryRecordItem>,
    storageSummary: StorageSummary,
    selectedRecord: HistoryRecordSelection?,
    selectedDetail: HistoryRecordDetail?,
    activeVideoReportDetail: VideoHistoryDetail?,
    onSelectRecord: (HistoryRecordSelection?) -> Unit,
    onDeleteRecord: (HistoryRecordSelection) -> Unit,
    onSaveAsTemplate: (HistoryRecordDetail) -> Unit,
    onShareAsTemplate: (HistoryRecordDetail) -> Unit,
    onOpenVideoReport: (HistoryRecordSelection) -> Unit,
    onCloseVideoReport: () -> Unit,
    onLoadFullHistoryDetail: (HistoryRecordSelection, (HistoryRecordDetail?) -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgentConfig: () -> Unit,
    onOpenWalletConfig: () -> Unit,
    isConciseMode: Boolean,
    onConciseModeChange: (Boolean) -> Unit,
    rotaryRotationDegrees: Float,
    onRotaryRotationChange: (Float) -> Unit,
    currentPage: HubPage,
    isVisible: Boolean,
    pageOffset: Float
) {
    val header = workspaceHeaderFor(currentPage)

    BackHandler(enabled = activeVideoReportDetail != null) {
        onCloseVideoReport()
    }

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

        val reportDetail = activeVideoReportDetail
        if (reportDetail != null) {
            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
                VideoAnalysisReportPage(
                    detail = reportDetail,
                    onBack = onCloseVideoReport
                )
            }
        } else {
            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    HistoryStorageCard(storageSummary = storageSummary)
                    HistoryTimelineCard(
                        historyRecords = historyRecords,
                        selectedRecord = selectedRecord,
                        onSelectRecord = onSelectRecord,
                        onOpenVideoReport = onOpenVideoReport
                    )
                }
            }
            HistoryDetailCard(
                detail = selectedDetail,
                onDeleteRecord = onDeleteRecord,
                onSaveAsTemplate = onSaveAsTemplate,
                onShareAsTemplate = onShareAsTemplate,
                onOpenVideoReport = { onOpenVideoReport(it.selection) },
                onLoadFullHistoryDetail = onLoadFullHistoryDetail,
                isVisible = isVisible
            )
        }
    }
}

@Composable
private fun HistoryStorageCard(storageSummary: StorageSummary) {
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("历史存储概览", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "统一保存视频分析记录、实时监控运行记录和关联媒体文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(text = "记录 ${storageSummary.recordCount}", accent = MaterialTheme.colorScheme.primary)
                StatusPill(text = "媒体 ${storageSummary.mediaCount}", accent = MaterialTheme.colorScheme.tertiary)
                StatusPill(text = formatBytes(storageSummary.totalBytes), accent = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun HistoryTimelineCard(
    historyRecords: List<HistoryRecordItem>,
    selectedRecord: HistoryRecordSelection?,
    onSelectRecord: (HistoryRecordSelection?) -> Unit,
    onOpenVideoReport: (HistoryRecordSelection) -> Unit
) {
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("统一时间流", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "按最近更新时间倒序排列，实时监控与视频分析混合展示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (historyRecords.isEmpty()) {
                EmptyHint("还没有可回看的历史记录。")
            } else {
                LazyColumn(
                    modifier = Modifier.height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(historyRecords, key = { "${it.selection.type}-${it.selection.recordId}" }) { item ->
                        HistoryTile(
                            title = "${item.typeLabel} · ${item.title}",
                            subtitle = item.summary,
                            supporting = buildString {
                                append(item.statusLabel)
                                append(" · ")
                                append(formatDateTime(item.updatedAt))
                                if (item.hasMedia) {
                                    append(" · 媒体 ${item.mediaCount}")
                                }
                            },
                            selected = item.selection == selectedRecord,
                            accent = historyAccent(item.selection.type),
                            onClick = {
                                onSelectRecord(
                                    if (item.selection == selectedRecord) null else item.selection
                                )
                            },
                            actionLabel = if (item.selection.type == HistoryRecordType.VideoAnalysis) {
                                "查看分析报告"
                            } else {
                                null
                            },
                            onAction = if (item.selection.type == HistoryRecordType.VideoAnalysis) {
                                { onOpenVideoReport(item.selection) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailCard(
    detail: HistoryRecordDetail?,
    onDeleteRecord: (HistoryRecordSelection) -> Unit,
    onSaveAsTemplate: (HistoryRecordDetail) -> Unit,
    onShareAsTemplate: (HistoryRecordDetail) -> Unit,
    onOpenVideoReport: (VideoHistoryDetail) -> Unit,
    onLoadFullHistoryDetail: (HistoryRecordSelection, (HistoryRecordDetail?) -> Unit) -> Unit,
    isVisible: Boolean
) {
    val context = LocalContext.current
    var exportStatus by rememberSaveable(detail?.selection?.recordId, detail?.selection?.type) {
        mutableStateOf<String?>(null)
    }

    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("记录详情", style = MaterialTheme.typography.titleLarge)
            if (detail == null) {
                EmptyHint("从上方时间流选择一条记录后，这里会展示任务结果、事件和媒体内容。")
                return@WatcherCard
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = detail.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = detail.requirement.ifBlank { "未记录任务要求" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    text = detail.statusLabel,
                    accent = historyAccent(detail.selection.type)
                )
            }

            Text(
                text = "开始 ${formatDateTime(detail.startedAt)} · 更新 ${formatDateTime(detail.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = detail.summary.ifBlank { "暂无摘要" },
                style = MaterialTheme.typography.bodyMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        exportStatus = "正在加载完整调试数据..."
                        onLoadFullHistoryDetail(detail.selection) { fullDetail ->
                            exportStatus = if (fullDetail == null) {
                                "源记录已不存在，无法导出调试包"
                            } else {
                                shareHistoryDebugExport(context = context, detail = fullDetail)
                            }
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("导出调试包")
                }
                if (detail is VideoHistoryDetail) {
                    Button(
                        onClick = { onOpenVideoReport(detail) },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("打开分析报告")
                    }
                }
            }

            exportStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (detail) {
                is VideoHistoryDetail -> VideoHistoryRecordContent(
                    detail = detail,
                    isVisible = isVisible
                )

                is MonitorHistoryDetail -> MonitorHistoryDetailContent(detail = detail)
            }

            val canConvert = when (detail) {
                is MonitorHistoryDetail -> detail.run.taskId != null && detail.canDelete
                is VideoHistoryDetail -> detail.canDelete
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSaveAsTemplate(detail) },
                    enabled = canConvert,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    androidx.compose.material3.Icon(Icons.Default.Bookmark, contentDescription = null)
                    Text(modifier = Modifier.padding(start = 6.dp), text = "存为模板")
                }
                Button(
                    onClick = { onShareAsTemplate(detail) },
                    enabled = canConvert,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    androidx.compose.material3.Icon(Icons.Default.Share, contentDescription = null)
                    Text(modifier = Modifier.padding(start = 6.dp), text = "分享配置")
                }
            }

            Button(
                onClick = { onDeleteRecord(detail.selection) },
                enabled = detail.canDelete,
                shape = RoundedCornerShape(18.dp)
            ) {
                androidx.compose.material3.Icon(Icons.Default.Delete, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = when {
                        detail is VideoHistoryDetail && isStalePostCaptureVideoRun(detail.run) ->
                            "清理这条中断记录"
                        detail is MonitorHistoryDetail && isStaleMonitorRun(detail.run) ->
                            "清理这条中断监控记录"
                        detail.canDelete -> "删除这条记录"
                        else -> "运行中的记录不可删除"
                    }
                )
            }
        }
    }
}

@Composable
private fun VideoHistoryRecordContent(
    detail: VideoHistoryDetail,
    isVisible: Boolean
) {
    var showDebugDetails by rememberSaveable(detail.selection.recordId) { mutableStateOf(false) }

    SectionCard(title = "报告入口", accent = MaterialTheme.colorScheme.primary) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "完整阅读体验已移至分析报告页；这里仅保留历史索引、记录状态和调试入口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(text = "分片 ${detail.totalSegmentCount}", accent = MaterialTheme.colorScheme.primary)
                StatusPill(text = "事件 ${detail.totalEventCount}", accent = MaterialTheme.colorScheme.secondary)
                StatusPill(text = "语音 ${detail.totalSpeechTranscriptCount}", accent = MaterialTheme.colorScheme.tertiary)
            }
            Button(
                onClick = { showDebugDetails = !showDebugDetails },
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (showDebugDetails) "收起调试详情" else "查看调试详情")
            }
        }
    }

    if (showDebugDetails) {
        VideoDebugDetailContent(detail = detail)
    }
}

@Composable
private fun VideoDebugDetailContent(detail: VideoHistoryDetail) {
    SectionCard(title = "调试详情", accent = MaterialTheme.colorScheme.tertiary) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DebugLine("Run ID", detail.run.id.toString())
            DebugLine("任务 ID", detail.run.taskId.toString())
            DebugLine("录制场景", detail.run.recordingScenario)
            DebugLine("完整母带", detail.fullMediaPath ?: detail.mergedVideoPath ?: "未生成")
            DebugLine("音轨", if (detail.run.fullMediaHasAudio) "包含音轨" else "未检测到音轨")
            DebugLine("音频链路", detail.run.audioEnhancementInfo.ifBlank { "未记录" })
            DebugLine("原始摘要", detail.run.rawModelSummary.ifBlank { "无" })
            DebugBlock("Task 完整字段", debugExportGson.toJson(detail.task))
            DebugBlock("Run 完整字段", debugExportGson.toJson(detail.run))
            DebugBlock("Segments 完整字段", debugExportGson.toJson(detail.segments))
            DebugBlock("Events 完整字段", debugExportGson.toJson(detail.events))
            DebugBlock("SpeechTranscripts 完整字段", debugExportGson.toJson(detail.speechTranscripts))

            if (detail.run.structuredNoteJson.isNotBlank()) {
                DebugBlock("结构化 JSON", detail.run.structuredNoteJson)
            }
            if (detail.run.markdownNote.isNotBlank()) {
                DebugBlock("Markdown 原文", detail.run.markdownNote)
            }
            if (detail.speechTranscripts.isNotEmpty()) {
                DebugBlock(
                    title = "保存的语音文本",
                    body = detail.speechTranscripts
                        .take(20)
                        .joinToString("\n") { "${it.displayTimestamp} ${it.text}" }
                )
            }
            if (detail.segments.isNotEmpty()) {
                DebugBlock(
                    title = "分片概览",
                    body = detail.segments.joinToString("\n") { segment ->
                        "第 ${segment.segmentIndex} 段 · ${segment.durationSeconds}s · ${segment.summary.ifBlank { segment.conclusion }}"
                    }
                )
            }
        }
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            modifier = Modifier.weight(1f),
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DebugBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonitorHistoryDetailContent(detail: MonitorHistoryDetail) {
    var selectedEventImagePath by rememberSaveable(detail.selection.recordId) {
        mutableStateOf<String?>(null)
    }

    SectionCard(title = "运行统计", accent = MaterialTheme.colorScheme.primary) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(text = "巡检 ${detail.run.totalCheckCount}", accent = MaterialTheme.colorScheme.primary)
                StatusPill(text = "警报 ${detail.run.alertCount}", accent = Color(0xFFC9485B))
                StatusPill(text = "预警 ${detail.run.warningCount}", accent = Color(0xFFE9A23B))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(text = "事件 ${detail.totalEventCount}", accent = MaterialTheme.colorScheme.secondary)
                StatusPill(text = "媒体 ${detail.totalMediaCount}", accent = MaterialTheme.colorScheme.tertiary)
            }
        }
    }

    ExpandableSectionCard(
        title = "完整视频记录",
        accent = MaterialTheme.colorScheme.tertiary,
        stateKey = "${detail.selection.recordId}-session-video",
        summary = if (detail.run.sessionVideoPath.isNullOrBlank()) {
            "这次监控还没有归档完整视频。"
        } else {
            "点击后加载视频播放器。"
        }
    ) {
        val sessionVideoPath = detail.run.sessionVideoPath
        if (sessionVideoPath.isNullOrBlank()) {
            EmptyHint("这次监控还没有归档完整视频。")
        } else {
            VideoPreview(path = sessionVideoPath)
            Text(
                text = sessionVideoPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    ExpandableSectionCard(
        title = "基准图片",
        accent = MaterialTheme.colorScheme.secondary,
        stateKey = "${detail.selection.recordId}-baseline",
        summary = if (detail.run.baselineImagePath.isNullOrBlank()) {
            "启动时没有成功保存基准图片。"
        } else {
            "点击后加载基准缩略图。"
        }
    ) {
        val baselinePath = detail.run.baselineImagePath
        if (baselinePath.isNullOrBlank()) {
            EmptyHint("启动时没有成功保存基准图片。")
        } else {
            SnapshotPreview(path = baselinePath)
            Text(
                text = baselinePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    ExpandableSectionCard(
        title = "关键事件",
        accent = MaterialTheme.colorScheme.secondary,
        stateKey = "${detail.selection.recordId}-events",
        summary = previewCountLabel(detail.events.size, detail.totalEventCount)
    ) {
        selectedEventImagePath?.let { SnapshotPreview(path = it) }
        if (detail.events.isEmpty()) {
            EmptyHint("还没有记录到监控事件。")
        } else {
            LazyColumn(
                modifier = Modifier.height(280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(detail.events, key = { it.id }) { event ->
                    val isSelected = selectedEventImagePath == event.frameImagePath &&
                        !event.frameImagePath.isNullOrBlank()
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !event.frameImagePath.isNullOrBlank()) {
                                selectedEventImagePath = event.frameImagePath
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "${formatDateTime(event.timestamp)} · ${event.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            event.frameImagePath?.let { eventPath ->
                                Text(
                                    text = eventPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ExpandableSectionCard(
        title = "附加快照",
        accent = MaterialTheme.colorScheme.tertiary,
        stateKey = "${detail.selection.recordId}-media",
        summary = previewCountLabel(detail.media.size, detail.totalMediaCount)
    ) {
        if (detail.media.isEmpty()) {
            EmptyHint("这次监控没有额外保存快照文件。")
        } else {
            LazyColumn(
                modifier = Modifier.height(240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(detail.media, key = { it.id }) { media ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedEventImagePath = media.localFilePath },
                        shape = RoundedCornerShape(18.dp),
                        color = if (selectedEventImagePath == media.localFilePath) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Text(
                            modifier = Modifier.padding(12.dp),
                            text = media.localFilePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    ExpandableSectionCard(
        title = "调试详情",
        accent = MaterialTheme.colorScheme.tertiary,
        stateKey = "${detail.selection.recordId}-debug",
        summary = "默认不渲染完整调试文本；导出调试包会单独读取完整数据。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DebugLine("Run ID", detail.run.id.toString())
            DebugLine("任务 ID", detail.run.taskId?.toString() ?: "无")
            DebugLine("监控模式", detail.run.monitorMode.name)
            DebugLine("触发方式", detail.run.targetTrigger.name)
            DebugLine("基准来源", detail.run.baselineSource.name)
            DebugLine("状态", detail.run.status.name)
            DebugBlock("Task 完整字段", debugExportGson.toJson(detail.task))
            DebugBlock("Run 完整字段", debugExportGson.toJson(detail.run))
            DebugBlock("Events 预览字段", debugExportGson.toJson(detail.events))
            DebugBlock("Media 预览字段", debugExportGson.toJson(detail.media))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    accent: Color,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = accent)
            content()
        }
    }
}

@Composable
private fun ExpandableSectionCard(
    title: String,
    accent: Color,
    stateKey: String = title,
    summary: String,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    SectionCard(title = title, accent = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (expanded) "收起" else "展开")
            }
            if (expanded) {
                content()
            }
        }
    }
}

@Composable
private fun VideoPreview(
    path: String,
    modifier: Modifier = Modifier
) {
    val file = remember(path) { File(path) }
    if (!file.exists()) {
        EmptyHint("视频文件不存在或无法读取。")
        return
    }

    var videoView by remember(path) {
        mutableStateOf<VideoView?>(null)
    }

    DisposableEffect(path) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
        factory = { context ->
            VideoView(context).apply {
                videoView = this
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                tag = path
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
                setVideoURI(Uri.fromFile(file))
            }
        },
        update = { view ->
            videoView = view
            if (view.tag != path) {
                view.stopPlayback()
                view.tag = path
                view.setVideoURI(Uri.fromFile(file))
            }
        }
    )
}

@Composable
private fun SnapshotPreview(path: String) {
    val bitmap = remember(path) { decodePreviewBitmap(path) }
    if (bitmap == null) {
        EmptyHint("图片文件不存在或无法读取。")
        return
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
        contentScale = ContentScale.Crop
    )
}

private fun decodePreviewBitmap(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds, reqWidth = 720, reqHeight = 720)
    }
    return BitmapFactory.decodeFile(path, options)
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun shareHistoryDebugExport(
    context: Context,
    detail: HistoryRecordDetail
): String {
    return runCatching {
        val exportJson = buildHistoryDebugExportJson(context, detail)
        val exportDir = File(context.filesDir, "debug/export").apply { mkdirs() }
        val file = File(exportDir, buildHistoryDebugExportFileName(detail))
        file.writeText(exportJson, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Watcher 调试包 ${detail.title}")
            putExtra(Intent.EXTRA_TEXT, "Watcher 历史记录调试包：${detail.title}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "分享调试包")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        "已生成调试包，可选择分享方式：${file.name}"
    }.getOrElse { error ->
        when (error) {
            is ActivityNotFoundException -> "已生成调试包，但未找到可分享的应用。"
            else -> "导出调试包失败：${error.message ?: error::class.java.simpleName}"
        }
    }
}

private fun buildHistoryDebugExportJson(context: Context, detail: HistoryRecordDetail): String {
    val payload = when (detail) {
        is VideoHistoryDetail -> {
            val mediaPaths = buildVideoDebugMediaPaths(detail)
            mapOf(
            "debug" to buildHistoryDebugMetadata(context, detail, "video"),
            "common" to buildCommonDebugFields(detail),
            "video" to mapOf(
                "taskSnapshot" to buildVideoTaskSnapshot(detail),
                "task" to detail.task,
                "run" to detail.run,
                "segments" to detail.segments,
                "audioAssets" to detail.audioAssets,
                "remoteFileBindings" to detail.remoteFileBindings,
                "uploadEvents" to buildRemoteUploadEvents(detail),
                "mergedVideoChunks" to buildMergedChunkDebug(detail),
                "analysisConcurrency" to mapOf(
                    "segmentAnalyzerParallelism" to 2,
                    "mergedChunkMaxBytes" to 400L * 1024L * 1024L
                ),
                "reportPipelineStagesSchema" to listOf(
                    "record_segments",
                    "generate_master_audio_outline",
                    "analyze_segment_fact_packets_concurrently",
                    "optionally_merge_and_analyze_video_chunks",
                    "generate_initial_report",
                    "optionally_refine_report_with_video_evidence"
                ),
                "reportPipelineStagesActual" to parseDebugJsonOrRaw(detail.run.reportPipelineStagesJson),
                "segmentFactPackets" to detail.segments.map { segment ->
                    mapOf(
                        "segmentIndex" to segment.segmentIndex,
                        "format" to if (isNarrativeEvidence(segment.evidenceJson)) "narrative" else "json",
                        "packet" to parseDebugJsonOrRaw(segment.evidenceJson)
                    )
                },
                "events" to detail.events,
                "speechTranscripts" to detail.speechTranscripts,
                "finalReportParse" to buildFinalReportDebugParse(detail),
                "dataContract" to buildVideoDataContractDebug(detail),
                "mediaPaths" to mediaPaths,
                "mediaDiagnostics" to buildMediaDiagnostics(mediaPaths.values.flattenPaths()),
                "perSegmentAssetDiagnostics" to buildPerSegmentAssetDiagnostics(detail),
                "integrity" to buildVideoIntegrity(detail)
            )
        )
        }

        is MonitorHistoryDetail -> {
            val mediaPaths = buildMonitorDebugMediaPaths(detail)
            mapOf(
            "debug" to buildHistoryDebugMetadata(context, detail, "monitor"),
            "common" to buildCommonDebugFields(detail),
            "monitor" to mapOf(
                "task" to detail.task,
                "run" to detail.run,
                "events" to detail.events,
                "media" to detail.media,
                "mediaPaths" to mediaPaths,
                "mediaDiagnostics" to buildMediaDiagnostics(mediaPaths.values.flattenPaths()),
                "integrity" to buildMonitorIntegrity(detail)
            )
        )
        }
    }
    return debugExportGson.toJson(payload)
}

private fun buildHistoryDebugMetadata(
    context: Context,
    detail: HistoryRecordDetail,
    recordType: String
): Map<String, Any?> {
    return mapOf(
        "exportVersion" to 3,
        "exportedAt" to System.currentTimeMillis(),
        "exportedAtLabel" to formatDateTime(System.currentTimeMillis()),
        "timezone" to TimeZone.getDefault().id,
        "recordType" to recordType,
        "recordId" to detail.selection.recordId,
        "packageName" to context.packageName,
        "appVersion" to buildAppVersionMetadata(context)
    )
}

private fun buildCommonDebugFields(detail: HistoryRecordDetail): Map<String, Any?> {
    return mapOf(
        "selection" to detail.selection,
        "title" to detail.title,
        "requirement" to detail.requirement,
        "statusLabel" to detail.statusLabel,
        "summary" to detail.summary,
        "startedAt" to detail.startedAt,
        "updatedAt" to detail.updatedAt,
        "canDelete" to detail.canDelete
    )
}

private fun buildVideoTaskSnapshot(detail: VideoHistoryDetail): Map<String, Any?> {
    return mapOf(
        "taskId" to detail.run.taskId,
        "taskTitle" to detail.run.taskTitle,
        "taskRequirement" to detail.run.taskRequirement,
        "templateId" to detail.run.templateId,
        "templateLabel" to detail.run.templateLabel,
        "recordingScenario" to detail.run.recordingScenario,
        "speechInputEnabled" to detail.run.speechInputEnabled,
        "totalDurationSeconds" to detail.run.totalDurationSeconds,
        "segmentDurationSeconds" to detail.run.segmentDurationSeconds,
        "captureIntervalSeconds" to detail.run.captureIntervalSeconds,
        "segmentCount" to detail.run.segmentCount
    )
}

private fun buildVideoDataContractDebug(detail: VideoHistoryDetail): Map<String, Any?> {
    return mapOf(
        "segmentEvidenceFormat" to if (detail.segments.any { isNarrativeEvidence(it.evidenceJson) })
            "markdown_narrative" else "json_fact_packet",
        "segmentFactPacketSchema" to "video_segment_fact_packet_v1",
        "finalReportSchema" to "video_final_report_v1",
        "segmentFactPacketCount" to detail.segments.count { it.evidenceJson.isNotBlank() },
        "finalReportStructuredJsonPresent" to detail.run.structuredNoteJson.isNotBlank(),
        "finalReportMarkdownPresent" to detail.run.markdownNote.isNotBlank(),
        "rawFinalResponseDebugOnly" to detail.run.rawModelSummary.isNotBlank(),
        "audioTrackPresent" to detail.run.fullMediaHasAudio,
        "audioAssetCount" to detail.audioAssets.size,
        "masterAudioAssetPresent" to detail.audioAssets.any { it.assetType == "masterAudio" },
        "segmentAudioAssetCount" to detail.audioAssets.count { it.assetType == "segmentAudio" },
        "remoteFileBindingCount" to detail.remoteFileBindings.size,
        "remoteSegmentVideoBindingCount" to detail.remoteFileBindings.count { it.assetKind == "segment_video" },
        "remoteMergedChunkBindingCount" to detail.remoteFileBindings.count { it.assetKind == "merged_chunk_video" },
        "remoteMergedSegmentBindingCount" to detail.remoteFileBindings.count { it.assetKind == "merged_segment_video" },
        "remoteMasterVideoBindingCount" to detail.remoteFileBindings.count { it.assetKind == "master_video" },
        "remoteFullMediaBindingCount" to detail.remoteFileBindings.count { it.assetKind == "full_media_video" },
        "remoteSegmentAudioBindingCount" to detail.remoteFileBindings.count { it.assetKind == "segment_audio" },
        "remoteMasterAudioBindingCount" to detail.remoteFileBindings.count { it.assetKind == "master_audio" },
        "speechTranscriptCount" to detail.speechTranscripts.size
    )
}

private fun buildFinalReportDebugParse(detail: VideoHistoryDetail): Map<String, Any?> {
    return mapOf(
        "summary" to detail.run.finalSummary,
        "conclusion" to detail.run.finalConclusion,
        "structuredNoteJson" to parseDebugJsonOrRaw(detail.run.structuredNoteJson),
        "markdownNote" to detail.run.markdownNote,
        "rawModelSummary" to detail.run.rawModelSummary
    )
}

private fun buildRemoteUploadEvents(detail: VideoHistoryDetail): List<Map<String, Any?>> {
    return detail.remoteFileBindings.flatMap { binding ->
        val parsed = parseDebugJsonOrRaw(binding.diagnosticsJson)
        val events = parsed as? List<*> ?: return@flatMap emptyList()
        events.mapNotNull { event ->
            val eventMap = event as? Map<*, *> ?: return@mapNotNull null
            buildMap<String, Any?> {
                eventMap.forEach { (key, value) -> put(key.toString(), value) }
                put("bindingId", binding.id)
                put("assetKind", binding.assetKind)
                put("localPath", binding.localPath)
                put("currentFileId", binding.arkFileId)
            }
        }
    }
}

private fun buildMergedChunkDebug(detail: VideoHistoryDetail): List<Map<String, Any?>> {
    return detail.remoteFileBindings
        .filter { it.assetKind == "merged_chunk_video" }
        .map { binding ->
            mapOf(
                "bindingId" to binding.id,
                "localPath" to binding.localPath,
                "lengthBytes" to binding.lengthBytes,
                "lastModified" to binding.lastModified,
                "arkFileId" to binding.arkFileId,
                "status" to binding.status,
                "uploadAttemptCount" to binding.uploadAttemptCount,
                "lastCheckedAt" to binding.lastCheckedAt,
                "diagnostics" to parseDebugJsonOrRaw(binding.diagnosticsJson)
            )
        }
}

private fun buildVideoDebugMediaPaths(detail: VideoHistoryDetail): Map<String, Any?> {
    return mapOf(
        "previewPath" to detail.previewPath,
        "fullMediaPath" to detail.fullMediaPath,
        "mergedVideoPath" to detail.mergedVideoPath,
        "masterAudioPaths" to detail.audioAssets
            .filter { it.assetType == "masterAudio" }
            .map { it.localFilePath },
        "segmentAudioPaths" to detail.audioAssets
            .filter { it.assetType == "segmentAudio" }
            .map { it.localFilePath },
        "mergedSegmentVideoPaths" to detail.remoteFileBindings
            .filter { it.assetKind == "merged_segment_video" }
            .map { it.localPath },
        "masterVideoPaths" to detail.remoteFileBindings
            .filter { it.assetKind == "master_video" }
            .map { it.localPath },
        "fullMediaVideoPaths" to detail.remoteFileBindings
            .filter { it.assetKind == "full_media_video" }
            .map { it.localPath },
        "mergedChunkVideoPaths" to detail.remoteFileBindings
            .filter { it.assetKind == "merged_chunk_video" }
            .map { it.localPath },
        "segmentLocalFilePaths" to detail.segments.mapNotNull { it.localFilePath }
    )
}

private fun buildMonitorDebugMediaPaths(detail: MonitorHistoryDetail): Map<String, Any?> {
    return mapOf(
        "previewPath" to detail.previewPath,
        "sessionVideoPath" to detail.run.sessionVideoPath,
        "baselineImagePath" to detail.run.baselineImagePath,
        "eventFramePaths" to detail.events.mapNotNull { it.frameImagePath },
        "mediaLocalFilePaths" to detail.media.map { it.localFilePath }
    )
}

private fun buildPerSegmentAssetDiagnostics(detail: VideoHistoryDetail): List<Map<String, Any?>> {
    return detail.segments.sortedBy { it.segmentIndex }.map { segment ->
        val bindings = detail.remoteFileBindings.filter { it.segmentRunId == segment.id }
        val segmentVideoBinding = bindings.firstOrNull { it.assetKind == "segment_video" }
        val segmentAudioBinding = bindings.firstOrNull { it.assetKind == "segment_audio" }
        val mergedSegmentBinding = bindings.firstOrNull { it.assetKind == "merged_segment_video" }

        fun fileInfo(path: String?): Map<String, Any?> {
            if (path.isNullOrBlank()) return mapOf("exists" to false)
            val file = java.io.File(path)
            return mapOf(
                "path" to path,
                "exists" to file.exists(),
                "sizeBytes" to if (file.exists()) file.length() else 0L,
                "readable" to file.canRead()
            )
        }

        fun bindingInfo(binding: VideoRemoteFileBindingEntity?): Map<String, Any?> {
            if (binding == null) return mapOf("present" to false)
            return mapOf(
                "present" to true,
                "localPath" to binding.localPath,
                "sizeBytes" to binding.lengthBytes,
                "arkFileId" to binding.arkFileId,
                "status" to binding.status,
                "uploadAttempts" to binding.uploadAttemptCount
            )
        }

        mapOf(
            "segmentIndex" to segment.segmentIndex,
            "segmentRunId" to segment.id,
            "status" to segment.status.name,
            "durationMs" to segment.durationMs,
            "interrupted" to segment.interrupted,
            "wallClockStartMs" to segment.wallClockStartMs,
            "wallClockEndMs" to segment.wallClockEndMs,
            "localFile" to fileInfo(segment.localFilePath),
            "segmentVideoBinding" to bindingInfo(segmentVideoBinding),
            "segmentAudioBinding" to bindingInfo(segmentAudioBinding),
            "mergedSegmentVideoBinding" to bindingInfo(mergedSegmentBinding),
            "analysisUsedMergedVideo" to (mergedSegmentBinding?.arkFileId != null &&
                segment.arkFileId == mergedSegmentBinding.arkFileId)
        )
    }
}

private fun buildVideoIntegrity(detail: VideoHistoryDetail): Map<String, Any?> {
    val warnings = mutableListOf<String>()
    if (detail.run.segmentCount > 0 && detail.segments.size != detail.run.segmentCount) {
        warnings += "run.segmentCount=${detail.run.segmentCount}, actualSegments=${detail.segments.size}"
    }
    if (detail.speechTranscripts.isEmpty()) {
        warnings += "speechTranscripts is empty"
    }
    if (detail.audioAssets.none { it.assetType == "masterAudio" }) {
        warnings += "masterAudio asset is missing"
    }
    if (detail.segments.isNotEmpty() && detail.audioAssets.count { it.assetType == "segmentAudio" } < detail.segments.size) {
        warnings += "some segmentAudio assets are missing"
    }
    detail.run.degradedReason?.takeIf(String::isNotBlank)?.let { warnings += "degradedReason=$it" }
    detail.run.errorMessage?.takeIf(String::isNotBlank)?.let { warnings += "errorMessage=$it" }
    if (detail.fullMediaPath.isNullOrBlank() && detail.mergedVideoPath.isNullOrBlank()) {
        warnings += "full/merged media path is missing"
    }
    return mapOf(
        "segmentCountDeclared" to detail.run.segmentCount,
        "segmentCountActual" to detail.segments.size,
        "eventCount" to detail.events.size,
        "speechTranscriptCount" to detail.speechTranscripts.size,
        "audioAssetCount" to detail.audioAssets.size,
        "masterAudioAssetCount" to detail.audioAssets.count { it.assetType == "masterAudio" },
        "segmentAudioAssetCount" to detail.audioAssets.count { it.assetType == "segmentAudio" },
        "remoteFileBindingCount" to detail.remoteFileBindings.size,
        "hasFullMediaPath" to !detail.fullMediaPath.isNullOrBlank(),
        "hasMergedVideoPath" to !detail.mergedVideoPath.isNullOrBlank(),
        "hasAudio" to detail.run.fullMediaHasAudio,
        "mergedSegmentCountActual" to detail.run.mergedSegmentCountActual,
        "segmentsMissingMergedAnalysisAsset" to detail.run.segmentsMissingMergedAnalysisAsset,
        "audioOutlineAvailable" to detail.run.audioOutlineAvailable,
        "videoRefinementApplied" to detail.run.videoRefinementApplied,
        "videoRefinementInputMode" to detail.run.videoRefinementInputMode,
        "reportPipelineStages" to parseDebugJsonOrRaw(detail.run.reportPipelineStagesJson),
        "warnings" to warnings
    )
}

private fun buildMonitorIntegrity(detail: MonitorHistoryDetail): Map<String, Any?> {
    val warnings = mutableListOf<String>()
    if (detail.events.isEmpty()) warnings += "events is empty"
    if (detail.media.isEmpty()) warnings += "media is empty"
    if (detail.run.sessionVideoPath.isNullOrBlank()) warnings += "sessionVideoPath is missing"
    return mapOf(
        "eventCount" to detail.events.size,
        "mediaCount" to detail.media.size,
        "totalCheckCount" to detail.run.totalCheckCount,
        "alertCount" to detail.run.alertCount,
        "warningCount" to detail.run.warningCount,
        "unknownCount" to detail.run.unknownCount,
        "normalCount" to detail.run.normalCount,
        "skippedCount" to detail.run.skippedCount,
        "failureCount" to detail.run.failureCount,
        "warnings" to warnings
    )
}

private fun buildMediaDiagnostics(paths: List<String>): List<Map<String, Any?>> {
    return paths.distinct().map { path ->
        val file = File(path)
        mapOf(
            "path" to path,
            "exists" to file.exists(),
            "isFile" to file.isFile,
            "lengthBytes" to if (file.exists()) file.length() else null,
            "lastModified" to if (file.exists()) file.lastModified() else null,
            "readable" to file.canRead()
        )
    }
}

private fun Iterable<Any?>.flattenPaths(): List<String> {
    return flatMap { value ->
        when (value) {
            is String -> listOf(value)
            is Iterable<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
    }.filter(String::isNotBlank)
}

private fun parseDebugJsonOrRaw(text: String): Any? {
    if (text.isBlank()) return null
    return runCatching {
        debugExportGson.fromJson(text, Any::class.java)
    }.getOrElse { text }
}

@Suppress("DEPRECATION")
private fun buildAppVersionMetadata(context: Context): Map<String, Any?> {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        mapOf(
            "versionName" to info.versionName,
            "versionCode" to info.versionCode
        )
    }.getOrElse {
        mapOf(
            "versionName" to null,
            "versionCode" to null,
            "error" to (it.message ?: it::class.java.simpleName)
        )
    }
}

private fun buildHistoryDebugExportFileName(detail: HistoryRecordDetail): String {
    val typeLabel = when (detail.selection.type) {
        HistoryRecordType.VideoAnalysis -> "video"
        HistoryRecordType.LiveMonitor -> "monitor"
    }
    val timestamp = debugExportTimestampFormat.format(Date())
    return "watcher-$typeLabel-${detail.selection.recordId}-debug-$timestamp.json"
}

private fun historyAccent(type: HistoryRecordType): Color {
    return when (type) {
        HistoryRecordType.VideoAnalysis -> Color(0xFF9A5B00)
        HistoryRecordType.LiveMonitor -> Color(0xFF0E8B65)
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}

private fun previewCountLabel(visibleCount: Int, totalCount: Int): String {
    return if (totalCount > visibleCount) {
        "已展示最近 $visibleCount 条 / 共 $totalCount 条。"
    } else {
        "共 $totalCount 条。"
    }
}

private val debugExportGson = GsonBuilder()
    .serializeNulls()
    .setPrettyPrinting()
    .create()

private val debugExportTimestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

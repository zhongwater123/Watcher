package com.example.watcher.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.watcher.data.model.ACTIVE_VIDEO_RUN_STATUSES
import com.example.watcher.data.model.HistoryRecordDetail
import com.example.watcher.data.model.HistoryRecordItem
import com.example.watcher.data.model.HistoryRecordSelection
import com.example.watcher.data.model.HistoryRecordType
import com.example.watcher.data.model.MonitorHistoryDetail
import com.example.watcher.data.model.StorageSummary
import com.example.watcher.data.model.VideoHistoryDetail
import com.example.watcher.data.model.videoRunStatusLabel
import com.example.watcher.ui.components.EmptyHint
import com.example.watcher.ui.components.HistoryTile
import com.example.watcher.ui.components.MotionDepth
import com.example.watcher.ui.components.MotionStageSection
import com.example.watcher.ui.components.PageScaffold
import com.example.watcher.ui.components.StatusPill
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.components.WatcherTopBar
import java.io.File

@Composable
internal fun HistoryWorkbenchPage(
    historyRecords: List<HistoryRecordItem>,
    storageSummary: StorageSummary,
    selectedRecord: HistoryRecordSelection?,
    selectedDetail: HistoryRecordDetail?,
    onSelectRecord: (HistoryRecordSelection?) -> Unit,
    onDeleteRecord: (HistoryRecordSelection) -> Unit,
    onSaveAsTemplate: (HistoryRecordDetail) -> Unit,
    onShareAsTemplate: (HistoryRecordDetail) -> Unit,
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

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            HistoryStorageCard(storageSummary = storageSummary)
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
            HistoryTimelineCard(
                historyRecords = historyRecords,
                selectedRecord = selectedRecord,
                onSelectRecord = onSelectRecord
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
            HistoryDetailCard(
                detail = selectedDetail,
                onDeleteRecord = onDeleteRecord,
                onSaveAsTemplate = onSaveAsTemplate,
                onShareAsTemplate = onShareAsTemplate,
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
    onSelectRecord: (HistoryRecordSelection?) -> Unit
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
    isVisible: Boolean
) {
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

            when (detail) {
                is VideoHistoryDetail -> VideoHistoryDetailContent(
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
                    text = if (detail.canDelete) "删除这条记录" else "运行中的记录不可删除"
                )
            }
        }
    }
}

@Composable
private fun VideoHistoryDetailContent(
    detail: VideoHistoryDetail,
    isVisible: Boolean
) {
    var expandedSegmentId by rememberSaveable(detail.selection.recordId) { mutableStateOf<Long?>(null) }
    var showMergedVideo by rememberSaveable(detail.selection.recordId) { mutableStateOf(false) }

    LaunchedEffect(isVisible, detail.selection.recordId) {
        if (!isVisible) {
            expandedSegmentId = null
            showMergedVideo = false
        }
    }

    LaunchedEffect(detail.selection.recordId, detail.segments.size) {
        if (expandedSegmentId != null && detail.segments.none { it.id == expandedSegmentId }) {
            expandedSegmentId = null
        }
    }

    SectionCard(title = "最终结论", accent = MaterialTheme.colorScheme.tertiary) {
        val mergedVideoPath = detail.mergedVideoPath
        val hasMergedVideo = !mergedVideoPath.isNullOrBlank()
        val toggleInteractionSource = remember(detail.selection.recordId) { MutableInteractionSource() }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (detail.run.finalConclusion.isNotBlank()) {
                Text(text = detail.run.finalConclusion, style = MaterialTheme.typography.bodyMedium)
            } else {
                EmptyHint("暂无最终结论。")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = hasMergedVideo,
                        interactionSource = toggleInteractionSource,
                        indication = null
                    ) {
                        showMergedVideo = !showMergedVideo
                        if (showMergedVideo) {
                            expandedSegmentId = null
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = when {
                        hasMergedVideo && showMergedVideo -> "再次点击此区域可收起完整视频"
                        hasMergedVideo -> "点击查看完整视频"
                        detail.run.status in ACTIVE_VIDEO_RUN_STATUSES -> "完整视频会在任务结束后生成"
                        else -> "未生成可回看的完整视频"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                mergedVideoPath?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (showMergedVideo && mergedVideoPath != null) {
                VideoPreview(
                    path = mergedVideoPath,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    SectionCard(title = "视频片段", accent = MaterialTheme.colorScheme.primary) {
        if (detail.segments.isEmpty()) {
            EmptyHint("没有找到可回放的视频片段。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.segments.forEach { segment ->
                    val path = segment.localFilePath
                    val isExpandable = !path.isNullOrBlank()
                    val isExpanded = isVisible && expandedSegmentId == segment.id && isExpandable
                    val toggleInteractionSource = remember(segment.id) { MutableInteractionSource() }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = if (isExpanded) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = isExpandable,
                                        interactionSource = toggleInteractionSource,
                                        indication = null
                                    ) {
                                        expandedSegmentId = if (expandedSegmentId == segment.id) {
                                            null
                                        } else {
                                            showMergedVideo = false
                                            segment.id
                                        }
                                    },
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "第 ${segment.segmentIndex} 段 · ${videoRunStatusLabel(detail.run.status)}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = buildString {
                                        append("时长 ${segment.durationSeconds}s")
                                        append(" · ")
                                        append(segment.summary.ifBlank {
                                            segment.conclusion.ifBlank { "暂无阶段摘要" }
                                        })
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when {
                                        isExpanded -> "再次点击上方信息区可收起播放器"
                                        isExpandable -> "点击上方信息区展开并播放该片段"
                                        else -> "该片段未保存本地视频"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                path?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (isExpanded && path != null) {
                                VideoPreview(
                                    path = path,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    SectionCard(title = "关键时间线", accent = MaterialTheme.colorScheme.secondary) {
        if (detail.events.isEmpty()) {
            EmptyHint("这次分析没有记录时间线事件。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.events.forEach { event ->
                    Text(
                        text = "${formatTimelineSeconds(event.timestampSeconds)} ${event.title} · ${event.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitorHistoryDetailContent(detail: MonitorHistoryDetail) {
    var selectedEventImagePath by rememberSaveable(detail.selection.recordId) {
        mutableStateOf(detail.events.firstNotNullOfOrNull { it.frameImagePath })
    }

    SectionCard(title = "运行统计", accent = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusPill(text = "巡检 ${detail.run.totalCheckCount}", accent = MaterialTheme.colorScheme.primary)
            StatusPill(text = "警报 ${detail.run.alertCount}", accent = Color(0xFFC9485B))
            StatusPill(text = "预警 ${detail.run.warningCount}", accent = Color(0xFFE9A23B))
        }
    }

    SectionCard(title = "完整视频记录", accent = MaterialTheme.colorScheme.tertiary) {
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

    SectionCard(title = "基准图片", accent = MaterialTheme.colorScheme.secondary) {
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

    SectionCard(title = "关键事件", accent = MaterialTheme.colorScheme.secondary) {
        selectedEventImagePath?.let { SnapshotPreview(path = it) }
        if (detail.events.isEmpty()) {
            EmptyHint("还没有记录到监控事件。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.events.forEach { event ->
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

    SectionCard(title = "附加快照", accent = MaterialTheme.colorScheme.tertiary) {
        if (detail.media.isEmpty()) {
            EmptyHint("这次监控没有额外保存快照文件。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.media.forEach { media ->
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
            if (!view.isPlaying) {
                view.start()
            }
        }
    )
}

@Composable
private fun SnapshotPreview(path: String) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
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

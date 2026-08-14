package com.example.watcher.ui.screens

import android.net.Uri
import android.util.Log
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.watcher.data.model.ClassroomNoteFollowupContextStage
import com.example.watcher.data.model.ClassroomNoteFollowupEntity
import com.example.watcher.data.model.ClassroomNoteFollowupStatus
import com.example.watcher.data.model.ClassroomNoteFollowupUiState
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.repository.ClassroomNoteFollowupResultParser
import com.example.watcher.ui.components.RoseFourLoader
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.theme.LocalWatcherExtendedColors
import kotlinx.coroutines.delay
import java.io.File

internal const val CLASSROOM_COMPLETION_LOG_TAG = "ClassroomCompletion"

@Composable
internal fun ClassroomCompletedPlaybackCard(playbackPath: String?) {
    LaunchedEffect(playbackPath) {
        val file = playbackPath?.let(::File)
        Log.d(
            CLASSROOM_COMPLETION_LOG_TAG,
            "PlaybackCard path=$playbackPath exists=${file?.exists()} length=${file?.length() ?: 0L}"
        )
    }
    ClassroomVideoFileCard(
        path = playbackPath,
        emptyText = "正在处理中",
        autoPlay = true
    )
}

@Composable
internal fun ClassroomReportCard(
    result: ClassroomRecordingResultUiModel,
    onOpenRunDetail: (Long) -> Unit
) {
    WatcherCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("录制报告", style = MaterialTheme.typography.titleLarge)
            ReportLine("Run", result.runId?.toString() ?: "-")
            ReportLine("课程", result.title)
            ReportLine("状态", result.statusLabel)
            ReportLine("时长", result.durationLabel)
            ReportLine("分片", result.segmentLabel)
            ReportLine("输入", if (result.inputSource == "test_video") "测试视频" else "实时视频流")
            ReportLine("音频", if (result.hasAudio) "包含音频" else "未检测到音频")
            ReportLine("完成", result.updatedAtLabel)
            result.degradedReason?.takeIf(String::isNotBlank)?.let { reason ->
                ReportLine("降级原因", reason)
            }
            OutlinedButton(
                onClick = { result.runId?.let(onOpenRunDetail) },
                enabled = result.runId != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("查看详情")
            }
        }
    }
}

@Composable
internal fun ClassroomNoteCard(
    status: VideoProcessingStatus,
    result: ClassroomRecordingResultUiModel,
    onNewRecording: () -> Unit,
    onCopyNote: (String) -> Unit,
    onAppendMaterials: () -> Unit
) {
    WatcherCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "课堂笔记",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onAppendMaterials,
                    enabled = result.runId != null,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("追加资料")
                }
            }
            ClassroomNoteProgressTimeline(steps = buildClassroomNoteProgressSteps(status, result))
            ClassroomNoteMaterialStatusRow(materials = status.classroomNoteMaterials)
            when {
                result.noteText.isNotBlank() -> VideoReportMarkdown(
                    markdown = result.noteText,
                    modifier = Modifier.fillMaxWidth()
                )
                status.errorMessage?.isNotBlank() == true -> Text(
                    text = status.errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
                else -> Text(
                    text = "本次记录没有生成可展示的课堂笔记。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onNewRecording,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("开始新录制")
            }
            OutlinedButton(
                onClick = { onCopyNote(result.copyText) },
                enabled = result.copyText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("复制笔记")
            }
        }
    }
}

@Composable
private fun ClassroomNoteMaterialStatusRow(materials: List<VideoRemoteFileBindingEntity>) {
    val uploading = materials.count { it.status == "uploading" }
    val failed = materials.count { it.status == "upload_failed" }
    val stored = materials.count { it.arkFileId?.isNotBlank() == true && it.status != "upload_failed" }
    val text = when {
        materials.isEmpty() -> "未追加课堂资料"
        failed > 0 || uploading > 0 -> "资料 $stored 份已存储，$uploading 份上传中，$failed 份失败"
        else -> "资料 $stored 份已存储成功"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ClassroomNoteProgressTimeline(steps: List<ClassroomNoteProgressStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val color = when (step.state) {
                    ClassroomNoteProgressState.Done -> MaterialTheme.colorScheme.primary
                    ClassroomNoteProgressState.Active -> MaterialTheme.colorScheme.tertiary
                    ClassroomNoteProgressState.Pending -> MaterialTheme.colorScheme.outline
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(color.copy(alpha = if (step.state == ClassroomNoteProgressState.Pending) 0.12f else 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (step.state == ClassroomNoteProgressState.Done) "✓" else (index + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun ClassroomNoteFollowupCard(
    status: VideoProcessingStatus,
    result: ClassroomRecordingResultUiModel,
    followupState: ClassroomNoteFollowupUiState,
    onAsk: (String) -> Unit,
    onRetry: (Long) -> Unit,
    onRegenerateWithFinalNote: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCopyAnswer: (String) -> Unit
) {
    var input by remember(result.runId) { mutableStateOf("") }
    WatcherCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("课后追问", style = MaterialTheme.typography.titleLarge)
            Text(
                text = followupStageHint(status, result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (followupState.items.isEmpty()) {
                Text(
                    text = "问这节课里的概念、例子或知识点。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    followupState.items.forEach { item ->
                        ClassroomNoteFollowupItem(
                            item = item,
                            finalNoteAvailable = result.hasFinalNote,
                            onRetry = onRetry,
                            onRegenerateWithFinalNote = onRegenerateWithFinalNote,
                            onDelete = onDelete,
                            onCopyAnswer = onCopyAnswer
                        )
                    }
                }
            }
            followupState.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    enabled = result.runId != null && !followupState.isSubmitting,
                    placeholder = { Text("继续追问本节课") },
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        val question = input.trim()
                        if (question.isNotBlank()) {
                            input = ""
                            onAsk(question)
                        }
                    },
                    enabled = result.runId != null && input.isNotBlank() && !followupState.isSubmitting,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (followupState.isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassroomNoteFollowupItem(
    item: ClassroomNoteFollowupEntity,
    finalNoteAvailable: Boolean,
    onRetry: (Long) -> Unit,
    onRegenerateWithFinalNote: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCopyAnswer: (String) -> Unit
) {
    val parsed = remember(item.rawResponse) {
        item.rawResponse.takeIf(String::isNotBlank)?.let(ClassroomNoteFollowupResultParser::parse)
    }
    val refs = remember(item.sourceRefsJson) {
        ClassroomNoteFollowupResultParser.sourceRefsFromJson(item.sourceRefsJson)
    }
    val status = ClassroomNoteFollowupStatus.fromValue(item.status)
    val stage = ClassroomNoteFollowupContextStage.fromValue(item.contextStage)
    var expanded by remember(item.id) { mutableStateOf(true) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("问：${item.question}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${status.followupLabel} · 基于：${stage.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开"
                    )
                }
                IconButton(onClick = { onDelete(item.id) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (!expanded) {
                val previewText = when (status) {
                    ClassroomNoteFollowupStatus.Completed -> item.answer.ifBlank { parsed?.answer.orEmpty() }
                    ClassroomNoteFollowupStatus.Failed -> item.errorMessage.ifBlank { "回答失败" }
                    ClassroomNoteFollowupStatus.Pending,
                    ClassroomNoteFollowupStatus.Running -> "正在回答..."
                }
                Text(
                    text = previewText.ifBlank { "暂无回答内容" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else when (status) {
                ClassroomNoteFollowupStatus.Running,
                ClassroomNoteFollowupStatus.Pending -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在回答...", style = MaterialTheme.typography.bodySmall)
                }
                ClassroomNoteFollowupStatus.Failed -> {
                    Text(
                        text = item.errorMessage.ifBlank { "回答失败" },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = { onRetry(item.id) }, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("重试")
                    }
                }
                ClassroomNoteFollowupStatus.Completed -> {
                    Text("回答", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = item.answer.ifBlank { parsed?.answer.orEmpty() },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val displayRefs = refs.ifEmpty { parsed?.sourceRefs.orEmpty() }
                    Text("本节课依据", style = MaterialTheme.typography.labelLarge)
                    if (displayRefs.isEmpty()) {
                        Text(
                            text = "本节课材料中未找到直接依据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        displayRefs.take(4).forEach { ref ->
                            Text(
                                text = "${formatFollowupRefTime(ref.startMs, ref.endMs)}${ref.text}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    parsed?.supplement?.takeIf(String::isNotBlank)?.let { supplement ->
                        Text("补充解释", style = MaterialTheme.typography.labelLarge)
                        Text(supplement, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onCopyAnswer(item.answer.ifBlank { parsed?.answer.orEmpty() }) },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("复制")
                        }
                        if (finalNoteAvailable && stage != ClassroomNoteFollowupContextStage.Final) {
                            OutlinedButton(
                                onClick = { onRegenerateWithFinalNote(item.id) },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("用最终笔记重新回答")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun followupStageHint(
    status: VideoProcessingStatus,
    result: ClassroomRecordingResultUiModel
): String {
    return when {
        result.hasFinalNote ->
            "当前基于最终笔记回答"
        result.hasAudioOutline -> "当前基于音频大纲回答，最终笔记完成后可重新回答"
        status.streamingBuffer.isNotBlank() -> "当前基于临时草稿回答，最终笔记完成后可重新回答"
        else -> "当前会优先使用本节课已保存的材料回答"
    }
}

private val ClassroomNoteFollowupStatus.followupLabel: String
    get() = when (this) {
        ClassroomNoteFollowupStatus.Pending -> "等待中"
        ClassroomNoteFollowupStatus.Running -> "回答中"
        ClassroomNoteFollowupStatus.Completed -> "已回答"
        ClassroomNoteFollowupStatus.Failed -> "失败"
    }

private fun formatFollowupRefTime(startMs: Long?, endMs: Long?): String {
    return when {
        startMs != null && endMs != null -> "[${formatFollowupMs(startMs)}-${formatFollowupMs(endMs)}] "
        startMs != null -> "[${formatFollowupMs(startMs)}] "
        else -> ""
    }
}

private fun formatFollowupMs(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1_000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
internal fun ClassroomVideoFileCard(
    path: String?,
    emptyText: String,
    autoPlay: Boolean = false
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val cardShape = RoundedCornerShape(28.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        color = extendedColors.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
        shadowElevation = 12.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(cardShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            extendedColors.surfaceContainerHighest,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.26f)
                        )
                    )
                )
        ) {
            VideoFilePreview(path = path, emptyText = emptyText, autoPlay = autoPlay)
        }
    }
}

@Composable
private fun VideoFilePreview(path: String?, emptyText: String, autoPlay: Boolean) {
    val file = remember(path) { path?.let(::File) }
    if (file == null || !file.exists()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.06f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RoseFourLoader(modifier = Modifier.size(72.dp))
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    var videoView by remember(path) { mutableStateOf<VideoView?>(null) }
    var durationMs by remember(path) { mutableStateOf(0) }
    var currentPositionMs by remember(path) { mutableStateOf(0) }
    var isSeeking by remember(path) { mutableStateOf(false) }
    var userPaused by remember(path) { mutableStateOf(false) }
    var playbackStarted by remember(path) { mutableStateOf(false) }
    var isPlaying by remember(path) { mutableStateOf(false) }
    DisposableEffect(path) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }
    LaunchedEffect(path, videoView, isSeeking) {
        while (true) {
            val view = videoView
            if (view != null && !isSeeking) {
                durationMs = view.duration.coerceAtLeast(0)
                currentPositionMs = view.currentPosition.coerceIn(0, durationMs.coerceAtLeast(0))
                isPlaying = view.isPlaying
            }
            delay(500L)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    videoView = this
                    tag = path
                    setOnPreparedListener { player ->
                        player.isLooping = autoPlay
                        player.setVolume(1f, 1f)
                        durationMs = duration.coerceAtLeast(0)
                        currentPositionMs = currentPosition.coerceIn(0, durationMs.coerceAtLeast(0))
                        Log.d(
                            CLASSROOM_COMPLETION_LOG_TAG,
                            "VideoView prepared path=$path durationMs=$durationMs " +
                                "video=${player.videoWidth}x${player.videoHeight} autoPlay=$autoPlay"
                        )
                        if (shouldAutoStartClassroomVideo(autoPlay, userPaused, playbackStarted)) {
                            start()
                            playbackStarted = true
                            isPlaying = true
                        }
                    }
                    setVideoURI(Uri.fromFile(file))
                }
            },
            update = { view ->
                videoView = view
                view.setOnPreparedListener { player ->
                    player.isLooping = autoPlay
                    player.setVolume(1f, 1f)
                    durationMs = view.duration.coerceAtLeast(0)
                    currentPositionMs = view.currentPosition.coerceIn(0, durationMs.coerceAtLeast(0))
                    Log.d(
                        CLASSROOM_COMPLETION_LOG_TAG,
                        "VideoView update prepared path=$path durationMs=$durationMs " +
                            "video=${player.videoWidth}x${player.videoHeight} autoPlay=$autoPlay"
                    )
                    if (shouldAutoStartClassroomVideo(autoPlay, userPaused, playbackStarted)) {
                        view.start()
                        playbackStarted = true
                        isPlaying = true
                    }
                }
                if (view.tag != path) {
                    view.stopPlayback()
                    view.tag = path
                    userPaused = false
                    playbackStarted = false
                    isPlaying = false
                    view.setVideoURI(Uri.fromFile(file))
                } else if (shouldAutoStartClassroomVideo(autoPlay, userPaused, playbackStarted)) {
                    view.start()
                    playbackStarted = true
                    isPlaying = true
                }
            }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.02f),
                            Color.Black.copy(alpha = 0.18f)
                        )
                    )
                )
        )
        if (durationMs > 0) {
            VideoPlaybackProgressBar(
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                onTogglePlayback = {
                    val view = videoView
                    if (view != null) {
                        if (view.isPlaying) {
                            userPaused = true
                            view.pause()
                            isPlaying = false
                        } else {
                            if (durationMs > 0 && currentPositionMs >= durationMs - 500) {
                                view.seekTo(0)
                                currentPositionMs = 0
                            }
                            userPaused = false
                            view.start()
                            playbackStarted = true
                            isPlaying = true
                        }
                    }
                },
                onSeekStart = { isSeeking = true },
                onSeek = { position ->
                    currentPositionMs = position.coerceIn(0, durationMs)
                },
                onSeekFinished = {
                    videoView?.seekTo(currentPositionMs.coerceIn(0, durationMs))
                    if (!userPaused && autoPlay && videoView?.isPlaying != true) {
                        videoView?.start()
                        playbackStarted = true
                        isPlaying = true
                    }
                    isSeeking = false
                }
            )
        }
    }
}

@Composable
private fun BoxScope.VideoPlaybackProgressBar(
    currentPositionMs: Int,
    durationMs: Int,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onSeekStart: () -> Unit,
    onSeek: (Int) -> Unit,
    onSeekFinished: () -> Unit
) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(
                onClick = onTogglePlayback,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White.copy(alpha = 0.92f)
                )
            }
            Text(
                text = formatDuration(currentPositionMs / 1_000),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f)
            )
            Slider(
                value = currentPositionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                onValueChange = { value ->
                    onSeekStart()
                    onSeek(value.toInt())
                },
                onValueChangeFinished = onSeekFinished,
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDuration(durationMs / 1_000),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.76f)
            )
        }
    }
}

internal fun shouldAutoStartClassroomVideo(
    autoPlay: Boolean,
    userPaused: Boolean,
    playbackStarted: Boolean
): Boolean = autoPlay && !userPaused && !playbackStarted

@Composable
private fun ReportLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

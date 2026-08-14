package com.example.watcher.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.watcher.data.model.ClassroomRecordingInput
import com.example.watcher.data.model.ClassroomSpeechProvider
import com.example.watcher.data.model.ClassroomSpeechRecognitionConfig
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.ui.components.CameraPreviewCard
import com.example.watcher.ui.components.MjpegStreamUiState
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.theme.LocalWatcherExtendedColors
import java.io.File


@Composable
internal fun ClassroomLivePreviewCard(
    settings: VideoStreamSettings,
    streamState: MjpegStreamUiState,
    isStreamPlaying: Boolean,
    recordingInput: ClassroomRecordingInput,
    autoPlayTestVideo: Boolean,
    previewActive: Boolean = true,
    onPlayingChange: (Boolean) -> Unit,
    onReconnectStream: () -> Unit,
    onCaptureSnapshot: (Bitmap) -> Unit,
    onOpenSettings: () -> Unit
) {
    when (recordingInput) {
        ClassroomRecordingInput.LiveCamera,
        is ClassroomRecordingInput.RemoteMjpegStream,
        is ClassroomRecordingInput.PhoneCameraFallback -> CameraPreviewCard(
            title = "视频流预览",
            subtitle = settings.streamDisplayUrl,
            streamState = streamState,
            isPlaying = isStreamPlaying,
            onPlayingChange = onPlayingChange,
            onReconnect = onReconnectStream,
            onCaptureSnapshot = onCaptureSnapshot,
            onOpenSettings = onOpenSettings,
            compact = true,
            showFooterText = false,
            showFrameRatePill = false,
            previewActive = previewActive
        )

        is ClassroomRecordingInput.TestVideo -> ClassroomImportedCoursePreviewCard(
            path = recordingInput.localPath,
            emptyText = "课程视频暂不可预览",
            autoPlay = autoPlayTestVideo
        )
    }
}

@Composable
private fun ClassroomImportedCoursePreviewCard(
    path: String?,
    emptyText: String,
    autoPlay: Boolean
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val cardShape = RoundedCornerShape(28.dp)
    val file = remember(path) { path?.let(::File) }
    var videoView by remember(path) { mutableStateOf<VideoView?>(null) }

    DisposableEffect(path) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }

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
            if (file != null && file.exists()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        VideoView(context).apply {
                            videoView = this
                            tag = path
                            setOnPreparedListener { player ->
                                player.isLooping = autoPlay
                                player.setVolume(1f, 1f)
                                if (autoPlay) start()
                            }
                            setVideoURI(Uri.fromFile(file))
                        }
                    },
                    update = { view ->
                        videoView = view
                        view.setOnPreparedListener { player ->
                            player.isLooping = autoPlay
                            player.setVolume(1f, 1f)
                            if (autoPlay) view.start()
                        }
                        if (view.tag != path) {
                            view.stopPlayback()
                            view.tag = path
                            view.setVideoURI(Uri.fromFile(file))
                        } else if (autoPlay && !view.isPlaying) {
                            view.start()
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
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun ClassroomStartCard(
    courseName: String,
    onCourseNameChange: (String) -> Unit,
    selectedDuration: Int,
    onDurationChange: (Int) -> Unit,
    recordingInput: ClassroomRecordingInput,
    speechRecognitionConfig: ClassroomSpeechRecognitionConfig,
    onSpeechRecognitionConfigChange: (ClassroomSpeechRecognitionConfig) -> Unit,
    statusMessage: String,
    errorMessage: String?,
    onPickTestVideo: () -> Unit,
    onClearTestVideo: () -> Unit,
    onCleanupTestVideoCache: () -> Unit,
    onStart: () -> Unit
) {
    WatcherCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("准备上课", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = courseName,
                onValueChange = onCourseNameChange,
                label = { Text("请输入课程名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            if (recordingInput is ClassroomRecordingInput.TestVideo) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = recordingInput.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearTestVideo) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清除")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("课堂翻译", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "当课堂需要翻译时开启此按钮",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = speechRecognitionConfig.provider == ClassroomSpeechProvider.AST,
                    onCheckedChange = { enabled ->
                        onSpeechRecognitionConfigChange(
                            speechRecognitionConfig.copy(
                                provider = if (enabled) {
                                    ClassroomSpeechProvider.AST
                                } else {
                                    ClassroomSpeechProvider.ASR
                                },
                                fallbackEnabled = true
                            )
                        )
                    }
                )
            }
            val notice = errorMessage?.takeIf(String::isNotBlank) ?: statusMessage.takeIf(String::isNotBlank)
            if (!notice.isNullOrBlank()) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!errorMessage.isNullOrBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            val breatheTransition = rememberInfiniteTransition(label = "startBtn")
            val breatheScale by breatheTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.02f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breatheScale"
            )
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .graphicsLayer {
                        scaleX = breatheScale
                        scaleY = breatheScale
                    },
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("开始上课", style = MaterialTheme.typography.titleLarge)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "可选择导入本地课程视频文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        onCleanupTestVideoCache()
                        onPickTestVideo()
                    }
                ) {
                    Text("选择视频文件")
                }
            }
        }
    }
}

@Composable
internal fun ClassroomHistoryCard(
    historyItems: List<ClassroomHistoryItemUiModel>,
    selectedRunId: Long?,
    onOpenClassroomRun: (Long) -> Unit
) {
    WatcherCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("最近课程记录", style = MaterialTheme.typography.titleLarge)
            if (historyItems.isEmpty()) {
                Text(
                    text = "暂无课堂记录，完成一次录课后会出现在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    historyItems.forEach { item ->
                        ClassroomHistoryRow(
                            item = item,
                            selected = item.runId == selectedRunId,
                            onClick = { onOpenClassroomRun(item.runId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassroomHistoryRow(
    item: ClassroomHistoryItemUiModel,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.statusLabel} · ${formatDateTime(item.updatedAt)} · ${item.rhythmLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

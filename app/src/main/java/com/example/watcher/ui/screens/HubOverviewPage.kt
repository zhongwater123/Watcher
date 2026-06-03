package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.ui.components.CameraPreviewCard
import com.example.watcher.ui.components.ConnectionConfigCard
import com.example.watcher.ui.components.MjpegStreamUiState
import com.example.watcher.ui.components.MotionDepth
import com.example.watcher.ui.components.MotionStageSection
import com.example.watcher.ui.components.PageScaffold
import com.example.watcher.ui.components.StatusPill
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.components.WatcherTopBar
import com.example.watcher.ui.theme.LocalWatcherExtendedColors

@Composable
internal fun HubOverviewPage(
    settings: VideoStreamSettings,
    streamState: MjpegStreamUiState,
    isStreamPlaying: Boolean,
    monitorStatus: MonitorStatus,
    currentTask: IntentResult?,
    currentVideoTask: VideoProcessTaskDraft?,
    videoProcessingStatus: VideoProcessingStatus,
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
    onNavigateMonitor: () -> Unit,
    onNavigateAnalysis: () -> Unit,
    onNavigateDigitalLifeCard: () -> Unit,
    onNavigateLiteRt: () -> Unit,
    onNavigatePoseEstimation: () -> Unit,
    currentPage: HubPage,
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

        if (!isConciseMode) {
            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
                ConnectionConfigCard(
                    label = "摄像头实时流",
                    value = settings.streamDisplayUrl,
                    detail = if (isStreamPlaying) {
                        "应用启动后会自动连接，修改地址后会自动重连。"
                    } else {
                        "当前连接已暂停，可点击编辑地址或恢复连接。"
                    },
                    onClick = onOpenSettings
                )
            }
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Hero) {
            CameraPreviewCard(
                title = "共享实时画面",
                subtitle = settings.ipAddress,
                streamState = streamState,
                isPlaying = isStreamPlaying,
                onPlayingChange = onPlayingChange,
                onReconnect = onReconnectStream,
                onCaptureSnapshot = onCaptureSnapshot,
                onOpenSettings = onOpenSettings,
                compact = false,
                showFooterText = !isConciseMode
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
            CurrentTaskStatusCard(
                currentTask = currentTask,
                monitorStatus = monitorStatus,
                currentVideoTask = currentVideoTask,
                videoProcessingStatus = videoProcessingStatus,
                onNavigateMonitor = onNavigateMonitor,
                onNavigateAnalysis = onNavigateAnalysis
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
            DigitalLifeCardEntryCard(
                onClick = onNavigateDigitalLifeCard
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
            LiteRtEntryCard(onClick = onNavigateLiteRt)
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
            PoseEstimationEntryCard(onClick = onNavigatePoseEstimation)
        }
    }
}

@Composable
private fun CurrentTaskStatusCard(
    currentTask: IntentResult?,
    monitorStatus: MonitorStatus,
    currentVideoTask: VideoProcessTaskDraft?,
    videoProcessingStatus: VideoProcessingStatus,
    onNavigateMonitor: () -> Unit,
    onNavigateAnalysis: () -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val summary = buildHubSummary(currentTask, monitorStatus, currentVideoTask, videoProcessingStatus)

    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = summary.eyebrow,
                        style = MaterialTheme.typography.labelMedium,
                        color = summary.accent
                    )
                    Text(text = summary.title, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = summary.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = extendedColors.surfaceContainer
                ) {
                    Icon(
                        imageVector = summary.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(14.dp),
                        tint = summary.accent
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(
                        color = extendedColors.surfaceContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(summary.progress)
                        .height(10.dp)
                        .background(
                            brush = extendedColors.primaryGradient,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
                        )
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                summary.tags.forEach { tag ->
                    StatusPill(text = tag, accent = summary.accent)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onNavigateMonitor,
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.Sensors, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("进入实时监控")
                }
                Button(
                    onClick = onNavigateAnalysis,
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("进入视频分析")
                }
            }
        }
    }
}

@Composable
private fun DigitalLifeCardEntryCard(
    onClick: () -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current

    WatcherCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "独立工作区 / 数字画像底座",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = "用户行为模型工作台",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "持续沉淀观察流，围绕习惯、环境、作息三维建立可演化的用户行为模型。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = extendedColors.surfaceContainer
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(text = "独立页面", accent = MaterialTheme.colorScheme.tertiary)
            StatusPill(text = "行为模型", accent = MaterialTheme.colorScheme.primary)
            StatusPill(text = "主动补证据", accent = Color(0xFF0E8B65))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "进入工作区",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "进入用户行为模型工作台",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LiteRtEntryCard(
    onClick: () -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current

    WatcherCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "独立工作区 / 本地推理引擎",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "本地大模型工作台",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "基于 LiteRT-LM 框架，支持 GPU/NPU 加速的端侧大模型推理，实现离线智能分析。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = extendedColors.surfaceContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(text = "独立页面", accent = MaterialTheme.colorScheme.secondary)
            StatusPill(text = "本地推理", accent = MaterialTheme.colorScheme.primary)
            StatusPill(text = "GPU 加速", accent = Color(0xFF0E8B65))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "进入工作区",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "进入本地大模型工作台",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PoseEstimationEntryCard(
    onClick: () -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current

    WatcherCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "独立工作区 / 姿态识别",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = "Pose Estimation",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "基于 MediaPipe BlazePose 的实时全身关节追踪，支持多人检测与 3D 坐标。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = extendedColors.surfaceContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Accessibility,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(text = "33 关键点", accent = MaterialTheme.colorScheme.tertiary)
            StatusPill(text = "3D 坐标", accent = MaterialTheme.colorScheme.primary)
            StatusPill(text = "多人支持", accent = Color(0xFF0E8B65))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "进入验证",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "进入姿态识别验证",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

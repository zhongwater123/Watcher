package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import com.example.watcher.ui.components.RoseFourLoader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
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

private class QuickNavigationTooltipPositionProvider(
    private val spacingPx: Int,
    private val tailInsetPx: Int,
    private val windowPaddingPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val anchorCenterX = (anchorBounds.left + anchorBounds.right) / 2
        val preferredX = anchorCenterX - popupContentSize.width + tailInsetPx
        val maxX = (windowSize.width - popupContentSize.width - windowPaddingPx)
            .coerceAtLeast(windowPaddingPx)
        val x = preferredX.coerceIn(windowPaddingPx, maxX)

        val aboveY = anchorBounds.top - popupContentSize.height - spacingPx
        val belowY = anchorBounds.bottom + spacingPx
        val maxY = (windowSize.height - popupContentSize.height - windowPaddingPx)
            .coerceAtLeast(windowPaddingPx)
        val y = if (aboveY >= windowPaddingPx) {
            aboveY
        } else {
            belowY.coerceIn(windowPaddingPx, maxY)
        }

        return IntOffset(x, y)
    }
}

@Composable
private fun QuickNavigationTooltipContent() {
    val bubbleColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

    Column(
        modifier = Modifier.requiredWidth(156.dp),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = bubbleColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 8.dp
            ),
            tonalElevation = 1.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Text(
                text = "点我可以快速导航",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
        Box(
            modifier = Modifier
                .offset(x = (-20).dp, y = (-1).dp)
                .size(10.dp)
                .rotate(45f)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(2.dp)
                )
        )
    }
}

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
    onNavigateMultiDevice: () -> Unit,
    isGatewayRunning: Boolean,
    pairedAgentCount: Int,
    pendingPairingCount: Int,
    onNavigateDigitalLifeCard: () -> Unit,
    onNavigateFitnessCompanion: () -> Unit,
    onNavigateLiteRt: () -> Unit,
    onNavigateLocalAgent: () -> Unit,
    onNavigatePoseEstimation: () -> Unit,
    onNavigateBackScreenPush: () -> Unit,
    onOpenQuickNavigation: () -> Unit,
    onQuickNavigationAnchorBoundsChanged: (Rect) -> Unit,
    showQuickNavigationHint: Boolean,
    currentPage: HubPage,
    pageOffset: Float
) {
    val header = workspaceHeaderFor(currentPage)
    val isPageVisible = kotlin.math.abs(pageOffset) < 0.98f

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
                compact = isConciseMode,
                showFooterText = !isConciseMode,
                showFrameRatePill = !isConciseMode,
                previewActive = isPageVisible
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Focus) {
            CurrentTaskStatusCard(
                currentTask = currentTask,
                monitorStatus = monitorStatus,
                currentVideoTask = currentVideoTask,
                videoProcessingStatus = videoProcessingStatus,
                onNavigateMonitor = onNavigateMonitor,
                onNavigateAnalysis = onNavigateAnalysis,
                onOpenQuickNavigation = onOpenQuickNavigation,
                onQuickNavigationAnchorBoundsChanged = onQuickNavigationAnchorBoundsChanged,
                showQuickNavigationHint = showQuickNavigationHint,
                isConciseMode = isConciseMode,
                pageOffset = pageOffset
            )
        }

        if (!isConciseMode) {
            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
                MultiDeviceEntryCard(
                    isGatewayRunning = isGatewayRunning,
                    pairedAgentCount = pairedAgentCount,
                    pendingPairingCount = pendingPairingCount,
                    onClick = onNavigateMultiDevice
                )
            }

            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
                DigitalLifeCardEntryCard(
                    onClick = onNavigateDigitalLifeCard
                )
            }

            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
                FitnessCompanionEntryCard(
                    onClick = onNavigateFitnessCompanion
                )
            }

            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
                LiteRtEntryCard(onClick = onNavigateLiteRt)
            }

            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
                LocalAgentEntryCard(onClick = onNavigateLocalAgent)
            }

            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
                PoseEstimationEntryCard(onClick = onNavigatePoseEstimation)
            }

            MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Footer) {
                BackScreenPushEntryCard(onClick = onNavigateBackScreenPush)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrentTaskStatusCard(
    currentTask: IntentResult?,
    monitorStatus: MonitorStatus,
    currentVideoTask: VideoProcessTaskDraft?,
    videoProcessingStatus: VideoProcessingStatus,
    onNavigateMonitor: () -> Unit,
    onNavigateAnalysis: () -> Unit,
    onOpenQuickNavigation: () -> Unit,
    onQuickNavigationAnchorBoundsChanged: (Rect) -> Unit,
    showQuickNavigationHint: Boolean,
    isConciseMode: Boolean = false,
    pageOffset: Float = 0f
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val summary = buildHubSummary(currentTask, monitorStatus, currentVideoTask, videoProcessingStatus)
    val isPageVisible = kotlin.math.abs(pageOffset) < 0.5f
    val quickNavigationTooltipState = rememberTooltipState(isPersistent = true)
    val density = LocalDensity.current
    val quickNavigationTooltipPositionProvider = remember(density) {
        QuickNavigationTooltipPositionProvider(
            spacingPx = with(density) { (-5).dp.roundToPx() },
            tailInsetPx = with(density) { 20.dp.roundToPx() },
            windowPaddingPx = with(density) { 8.dp.roundToPx() }
        )
    }

    LaunchedEffect(showQuickNavigationHint, isPageVisible) {
        if (showQuickNavigationHint && isPageVisible) {
            quickNavigationTooltipState.show()
        } else {
            quickNavigationTooltipState.dismiss()
        }
    }

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
                    if (summary.progress <= 0.1f) {
                        TypewriterCarousel(isPageVisible = isPageVisible)
                    } else {
                        Text(text = summary.title, style = MaterialTheme.typography.headlineMedium)
                    }
                    if (!isConciseMode) {
                        Text(
                            text = summary.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TooltipBox(
                        positionProvider = quickNavigationTooltipPositionProvider,
                        tooltip = {
                            QuickNavigationTooltipContent()
                        },
                        state = quickNavigationTooltipState,
                        focusable = false,
                        enableUserInput = false
                    ) {
                        IconButton(
                            onClick = onOpenQuickNavigation,
                            modifier = Modifier
                                .size(56.dp)
                                .onGloballyPositioned { coordinates ->
                                    onQuickNavigationAnchorBoundsChanged(coordinates.boundsInRoot())
                                }
                                .semantics { contentDescription = "\u5FEB\u901F\u5BFC\u822A" }
                        ) {
                            RoseFourLoader(modifier = Modifier.size(52.dp), active = isPageVisible)
                        }
                    }
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
                    Text(if (isConciseMode) "物品守护" else "进入实时监控")
                }
                Button(
                    onClick = onNavigateAnalysis,
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isConciseMode) "课堂助手" else "进入视频分析")
                }
            }
        }
    }
}

private data class CarouselLine(val line1: String, val line2: String)

private val carouselItems = listOf(
    CarouselLine("今天要和 Watcher", "一起做什么？"),
    CarouselLine("今天要和 Watcher", "一起上课吗？"),
    CarouselLine("今天或许可以...", "试试探索模式？"),
    CarouselLine("我也可以连接你的电脑", "试试“多端聚合”吧"),
    CarouselLine("想扒韩舞吗？", "试试“舞蹈学习”吧！"),
    CarouselLine("想测自己手机算力吗？", "试试“本地模型“吧！"),
    CarouselLine("探索模式有很多功能", "但还在开发中 orz"),
    CarouselLine("物品守护...", "其实有很多的玩法！"),
    CarouselLine("如果无聊的话...", "把手机横屏试试？")
)

@Composable
private fun TypewriterCarousel(isPageVisible: Boolean = true) {
    var itemIndex by remember { mutableIntStateOf(0) }
    var displayedLine1 by remember { mutableStateOf(carouselItems[0].line1) }
    var displayedLine2 by remember { mutableStateOf(carouselItems[0].line2) }
    var showCursor by remember { mutableStateOf(true) }

    LaunchedEffect(isPageVisible) {
        if (!isPageVisible) return@LaunchedEffect
        while (true) {
            // Display current text with blinking cursor for ~15s
            repeat(12) {
                showCursor = true
                delay(1_000L)
                showCursor = false
                delay(250L)
            }
            // Delete line2 character by character
            val currentLine2 = displayedLine2
            for (i in currentLine2.length downTo 0) {
                displayedLine2 = currentLine2.substring(0, i)
                showCursor = true
                delay(40L)
            }
            // Delete line1 character by character
            val currentLine1 = displayedLine1
            for (i in currentLine1.length downTo 0) {
                displayedLine1 = currentLine1.substring(0, i)
                delay(40L)
            }
            delay(250L)
            // Advance to next item
            itemIndex = (itemIndex + 1) % carouselItems.size
            val next = carouselItems[itemIndex]
            // Type line1 character by character
            for (i in 1..next.line1.length) {
                displayedLine1 = next.line1.substring(0, i)
                delay(55L)
            }
            delay(120L)
            // Type line2 character by character
            for (i in 1..next.line2.length) {
                displayedLine2 = next.line2.substring(0, i)
                delay(55L)
            }
            showCursor = true
        }
    }

    val cursorChar = if (showCursor) "▌" else ""
    val style = MaterialTheme.typography.headlineMedium
    val color = MaterialTheme.colorScheme.onSurface
    val cursorColor = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = buildAnnotatedString {
                append(displayedLine1)
                if (displayedLine2.isEmpty()) {
                    withStyle(SpanStyle(color = cursorColor)) { append(cursorChar) }
                }
            },
            style = style,
            color = color
        )
        Text(
            text = buildAnnotatedString {
                append(displayedLine2)
                if (displayedLine2.isNotEmpty()) {
                    withStyle(SpanStyle(color = cursorColor)) { append(cursorChar) }
                }
            },
            style = style,
            color = color
        )
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
private fun FitnessCompanionEntryCard(
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
                    text = "独立工作区 / 健身陪伴",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF0E8B65)
                )
                Text(
                    text = "健身陪伴助手",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "先用简单选项建立个人资料，再生成阶段目标和本次训练计划，首轮聚焦陪你练。",
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
                    tint = Color(0xFF0E8B65)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(text = "独立页面", accent = Color(0xFF0E8B65))
            StatusPill(text = "资料建档", accent = MaterialTheme.colorScheme.primary)
            StatusPill(text = "陪你练", accent = MaterialTheme.colorScheme.tertiary)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "进入助手",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "进入健身陪伴助手",
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
private fun MultiDeviceEntryCard(
    isGatewayRunning: Boolean,
    pairedAgentCount: Int,
    pendingPairingCount: Int,
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
                    text = "独立工作区 / 开放能力",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "多端聚合",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "对外开放 Gateway API，支持 mDNS 自动发现与多设备远程能力调用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = extendedColors.surfaceContainer
            ) {
                Icon(
                    imageVector = Icons.Default.DeviceHub,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(
                text = if (isGatewayRunning) "网关运行中" else "网关已关闭",
                accent = if (isGatewayRunning) Color(0xFF0E8B65) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusPill(text = "已连接 $pairedAgentCount", accent = MaterialTheme.colorScheme.primary)
            if (pendingPairingCount > 0) {
                StatusPill(text = "待确认 $pendingPairingCount", accent = MaterialTheme.colorScheme.tertiary)
            } else {
                StatusPill(text = "首次确认绑定", accent = MaterialTheme.colorScheme.secondary)
            }
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
                contentDescription = "进入多端聚合工作区",
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

@Composable
private fun BackScreenPushEntryCard(
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
                    text = "独立工作区 / 外设联动",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "背屏图片推送",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "将摄像头画面或自定义图片推送到设备背屏显示。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = extendedColors.surfaceContainer
            ) {
                Icon(
                    imageVector = Icons.Default.DeviceHub,
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
            StatusPill(text = "背屏推送", accent = MaterialTheme.colorScheme.primary)
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
                contentDescription = "进入背屏图片推送",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LocalAgentEntryCard(
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
                    text = "独立工作区 / 端侧智能体",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "本地 Agent",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "基于 Google ADK-Kotlin 的端侧 AI Agent 框架，支持 Gemini Nano 本地推理与工具调用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = extendedColors.surfaceContainer
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(text = "ADK 验证", accent = MaterialTheme.colorScheme.primary)
            StatusPill(text = "端侧推理", accent = MaterialTheme.colorScheme.secondary)
            StatusPill(text = "工具注册", accent = Color(0xFF0E8B65))
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
                contentDescription = "进入本地Agent验证",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

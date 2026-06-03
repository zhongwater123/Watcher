package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.watcher.R
import com.example.watcher.data.model.AiAudienceEntity
import com.example.watcher.data.model.AiAudienceLiveState
import com.example.watcher.data.model.AudienceAction
import com.example.watcher.data.model.GiftType
import com.example.watcher.data.model.AiAudienceMessageEntity
import com.example.watcher.data.model.LiveSpeechState
import com.example.watcher.data.model.DanmakuItem
import com.example.watcher.data.model.LiveCommentaryState
import com.example.watcher.data.model.VideoStreamSettings
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.example.watcher.ui.components.ConnectionStatus
import com.example.watcher.ui.components.MjpegStreamUiState
import com.example.watcher.ui.components.StreamSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val liveCommentaryTextScheme = CommentaryTextScheme(
    recording = "录制中…",
    uploading = "上传中…",
    processing = "处理中…",
    analyzing = "分析中…",
    streamingPlaceholder = "…",
    skippedFallback = "已跳过（积压保护）",
    failedFallback = "分析失败"
)

private val liveMemoryLabelScheme = MemoryLabelScheme(
    builderTag = "建设者",
    sceneLabel = "场景",
    entitiesLabel = "实体",
    actionLabel = "动态",
    asksLabel = "ASK",
    askPrefix = "? ",
    memoryALabel = "记忆A",
    memoryBLabel = "记忆B"
)

private val liveTranscriptTextScheme = TranscriptTextScheme(
    listeningText = "🎤 聆听中…",
    waitingText = "🎤 等待语音…",
    mutedText = "🎤 已静音",
    showMicIcon = true
)

@Composable
internal fun LiveRoomScreen(
    streamState: MjpegStreamUiState,
    isPlaying: Boolean,
    settings: VideoStreamSettings,
    commentaryState: LiveCommentaryState,
    aiAudienceState: AiAudienceLiveState,
    danmakuFlow: SharedFlow<DanmakuItem>,
    speechState: LiveSpeechState,
    audiences: List<AiAudienceEntity>,
    onPlayingChange: (Boolean) -> Unit,
    onMicToggle: (Boolean) -> Unit,
    onResetLiveRoom: () -> Unit,
    onReconnectStream: () -> Unit,
    onCaptureSnapshot: (Bitmap) -> Unit,
    onSaveAudience: (AiAudienceEntity) -> Unit,
    onExitLiveRoom: () -> Unit,
) {
    var showOverlay by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showAudienceConfig by remember { mutableStateOf(false) }
    var rightPanelExpanded by remember { mutableStateOf(true) }

    // Highlight message (醒目留言) state
    var highlightMsg by remember { mutableStateOf<DanmakuItem?>(null) }
    // NOTE: danmaku collection is done in DanmakuOverlay to avoid duplicate collectors

    LaunchedEffect(lastInteraction) {
        delay(4000)
        showOverlay = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    showOverlay = !showOverlay
                    lastInteraction = System.currentTimeMillis()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Video layer
        val frame = streamState.currentFrame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = stringResource(R.string.stream_live_content_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = when (streamState.connectionStatus) {
                        ConnectionStatus.Connecting -> stringResource(R.string.stream_status_connecting)
                        is ConnectionStatus.Error -> (streamState.connectionStatus as ConnectionStatus.Error).message
                        ConnectionStatus.Connected -> if (streamState.source.isCameraFallback) {
                            val cameraName = if (streamState.source == StreamSource.FrontCameraFallback) "前置" else "后置"
                            "ESP32 不可用，已切到手机${cameraName}摄像头"
                        } else {
                            stringResource(R.string.stream_status_receiving)
                        }
                        else -> stringResource(R.string.live_room_no_signal)
                    },
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }
        }

        // Leaderboard (top right)
        if (aiAudienceState.likeBoard.isNotEmpty() || aiAudienceState.giftBoard.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Like board
                if (aiAudienceState.likeBoard.isNotEmpty()) {
                    Text("❤️ 点赞榜", color = Color(0xFFFF6B6B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    aiAudienceState.likeBoard.forEachIndexed { i, e ->
                        val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "" }
                        Text(
                            "$medal${e.audienceName} ×${e.count}",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp
                        )
                    }
                    val liker = aiAudienceState.lastLiker
                    if (liker != null) {
                        Text("最新: $liker ❤️", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
                    }
                }
                // Gift board
                if (aiAudienceState.giftBoard.isNotEmpty()) {
                    Text("🎁 礼物榜", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    aiAudienceState.giftBoard.forEachIndexed { i, e ->
                        val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "" }
                        Text(
                            "$medal${e.audienceName} ${e.totalSpent}币",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp
                        )
                    }
                    for (g in aiAudienceState.recentGifts.reversed()) {
                        Text(
                            "${g.gift.emoji} ${g.audienceName}",
                            color = Color(0xFFFFD700).copy(alpha = 0.7f), fontSize = 9.sp
                        )
                    }
                }
            }
        }

        // Highlight message (醒目留言) — pinned center with countdown
        val currentHighlight = highlightMsg
        if (currentHighlight != null) {
            val nameColor = audienceColor(currentHighlight.audienceName)
            var remainSec by remember(currentHighlight.id) { mutableIntStateOf(30) }
            LaunchedEffect(currentHighlight.id) {
                while (remainSec > 0) {
                    delay(1000)
                    remainSec--
                }
                highlightMsg = null
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp)
            ) {
                // Glow border
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(1.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFFFD700),
                                    Color(0xFFFF6B35),
                                    Color(0xFFFFD700)
                                )
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                )
                // Inner content
                Column(
                    modifier = Modifier
                        .padding(2.dp)
                        .background(
                            Color(0xFF0D0D1A).copy(alpha = 0.95f),
                            RoundedCornerShape(15.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header: icon + label + countdown
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("📢", fontSize = 14.sp)
                        Text(
                            text = "醒目留言",
                            color = Color(0xFFFFD700),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${remainSec}s",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // Content
                    Text(
                        text = currentHighlight.content,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    // Sender
                    Text(
                        text = "—— ${currentHighlight.audienceName}",
                        color = nameColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Danmaku overlay (floating text across video)
        DanmakuOverlay(
            danmakuFlow = danmakuFlow,
            onHighlight = { highlightMsg = it }
        )

        // Left panel: AI audience chat
        if (aiAudienceState.messages.isNotEmpty()) {
            AiAudienceChatPanel(
                messages = aiAudienceState.messages,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(280.dp)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
            )
        }

        // Right panel: memory status + commentary feed (collapsible)
        if (commentaryState.isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 4.dp, top = 8.dp, bottom = 8.dp)
            ) {
                if (rightPanelExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(320.dp)
                            .padding(end = 8.dp)
                    ) {
                        MemoryStatusPanel(
                            state = commentaryState,
                            modifier = Modifier.weight(0.45f),
                            labels = liveMemoryLabelScheme
                        )
                        if (commentaryState.entries.isNotEmpty()) {
                            CommentaryFeedPanel(
                                entries = commentaryState.entries,
                                modifier = Modifier.weight(0.55f),
                                textScheme = liveCommentaryTextScheme
                            )
                        }
                    }
                }
                // Toggle button
                FilledTonalIconButton(
                    onClick = { rightPanelExpanded = !rightPanelExpanded },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(28.dp)
                ) {
                    Icon(
                        imageVector = if (rightPanelExpanded) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                        contentDescription = if (rightPanelExpanded) "收起" else "展开",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Overlay controls
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (statusText, statusColor) = when (streamState.connectionStatus) {
                        ConnectionStatus.Connected -> stringResource(R.string.stream_connection_connected) to Color(0xFF4CAF50)
                        ConnectionStatus.Connecting -> stringResource(R.string.stream_connection_connecting) to Color(0xFFFFA726)
                        is ConnectionStatus.Error -> stringResource(R.string.stream_connection_error) to Color(0xFFEF5350)
                        ConnectionStatus.Disconnected -> stringResource(R.string.stream_connection_disconnected) to Color.White.copy(alpha = 0.5f)
                    }
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )

                    if (streamState.connectionStatus is ConnectionStatus.Connected) {
                        Text(
                            text = buildString {
                                append(stringResource(R.string.stream_fps, streamState.fps))
                                if (streamState.source.isCameraFallback) {
                                    val badge = if (streamState.source == StreamSource.FrontCameraFallback) "前摄降级" else "后摄降级"
                                    append(" · $badge")
                                }
                            },
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = 0.5f),
                                    RoundedCornerShape(999.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // LIVE badge (top right)
                if (streamState.connectionStatus is ConnectionStatus.Connected) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(
                                Color(0xCCE53935),
                                RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Text(
                            text = stringResource(R.string.live_room_live_badge),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Bottom controls
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .background(
                            Color.Black.copy(alpha = 0.4f),
                            RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            streamState.currentFrame?.let(onCaptureSnapshot)
                            lastInteraction = System.currentTimeMillis()
                        },
                        enabled = streamState.currentFrame != null,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = 0.08f),
                            disabledContentColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.stream_capture_snapshot),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = {
                            onPlayingChange(!isPlaying)
                            lastInteraction = System.currentTimeMillis()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.stream_stop) else stringResource(R.string.stream_start),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (!isPlaying || streamState.connectionStatus is ConnectionStatus.Error || streamState.connectionStatus is ConnectionStatus.Disconnected) {
                        FilledTonalIconButton(
                            onClick = {
                                onReconnectStream()
                                lastInteraction = System.currentTimeMillis()
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.18f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.live_room_reconnect),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Mic toggle button
                    FilledTonalIconButton(
                        onClick = {
                            onMicToggle(!speechState.isMicEnabled)
                            lastInteraction = System.currentTimeMillis()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (speechState.isMicEnabled && speechState.isActive)
                                Color(0xFF66BB6A).copy(alpha = 0.3f)
                            else
                                Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (speechState.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "语音识别开关",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Reset live room button
                    FilledTonalIconButton(
                        onClick = {
                            onResetLiveRoom()
                            lastInteraction = System.currentTimeMillis()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFFEF5350).copy(alpha = 0.25f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "重置直播间",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // AI Audience config button
                    FilledTonalIconButton(
                        onClick = {
                            showAudienceConfig = true
                            lastInteraction = System.currentTimeMillis()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF42A5F5).copy(alpha = 0.3f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = "AI 观众配置",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Exit live room button
                    FilledTonalIconButton(
                        onClick = { onExitLiveRoom() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFFEF5350).copy(alpha = 0.4f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "退出直播",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Stream URL (bottom left)
                val displayUrl = streamState.activeStreamUrl ?: settings.streamUrl
                if (displayUrl.isNotBlank()) {
                    Text(
                        text = if (streamState.source.isCameraFallback) {
                            "当前视频源：$displayUrl"
                        } else {
                            displayUrl
                        },
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 28.dp)
                    )
                }
            }
        }

        // Speech transcript strip (center-bottom, prominent)
        SpeechTranscriptStripPanel(
            state = speechState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.6f)
                .padding(bottom = 76.dp),
            textScheme = liveTranscriptTextScheme
        )

        // AI Audience quick panel (lightweight, landscape-friendly)
        if (showAudienceConfig) {
            AudienceQuickPanel(
                audiences = audiences,
                onToggle = { audience, enabled ->
                    onSaveAudience(audience.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
                },
                onDismiss = { showAudienceConfig = false }
            )
        }
    }
}

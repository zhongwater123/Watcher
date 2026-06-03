package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watcher.data.model.CouncilAnalysisPhase
import com.example.watcher.data.model.CouncilDiscussionKind
import com.example.watcher.data.model.CouncilDiscussionSummary
import com.example.watcher.data.model.CouncilDiscussionTurn
import com.example.watcher.data.model.CouncilExpertConsoleState
import com.example.watcher.data.model.CouncilExpertKind
import com.example.watcher.data.model.CouncilExpertOpinion
import com.example.watcher.data.model.CouncilExpertRole
import com.example.watcher.data.model.CouncilUiState
import com.example.watcher.data.model.CouncilVoteLevel
import com.example.watcher.data.model.CouncilExpertStage
import com.example.watcher.data.model.LiveCommentaryState
import com.example.watcher.data.model.LiveSpeechState
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.ui.components.ConnectionStatus
import com.example.watcher.ui.components.MjpegStreamUiState
import com.example.watcher.ui.components.StreamSource
import kotlinx.coroutines.delay

private val councilCommentaryTextScheme = CommentaryTextScheme(
    recording = "Recording...",
    uploading = "Uploading...",
    processing = "Processing...",
    analyzing = "Analyzing...",
    streamingPlaceholder = "...",
    skippedFallback = "Skipped",
    failedFallback = "Failed"
)

private val councilMemoryLabelScheme = MemoryLabelScheme(
    builderTag = "Builder",
    sceneLabel = "Scene",
    entitiesLabel = "Entities",
    actionLabel = "Action",
    asksLabel = "ASK",
    askPrefix = "> ",
    expertRequestsLabel = "专家需求",
    expertRequestPrefix = "> ",
    memoryALabel = "Memory A",
    memoryBLabel = "Memory B"
)

private val councilTranscriptTextScheme = TranscriptTextScheme(
    listeningText = "Listening...",
    waitingText = "Waiting for speech",
    showMicIcon = false
)

@Composable
internal fun CouncilModeScreen(
    streamState: MjpegStreamUiState,
    isPlaying: Boolean,
    settings: VideoStreamSettings,
    commentaryState: LiveCommentaryState,
    speechState: LiveSpeechState,
    councilState: CouncilUiState,
    onPlayingChange: (Boolean) -> Unit,
    onReconnectStream: () -> Unit,
    onCaptureSnapshot: (Bitmap) -> Unit,
    onMicToggle: (Boolean) -> Unit,
    onTriggerAnalysis: () -> Unit,
    onExit: () -> Unit
) {
    var showOverlay by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var leftPanelExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(lastInteraction) {
        delay(4_000)
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
            }
    ) {
        val frame = streamState.currentFrame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "Council stream",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = when (streamState.connectionStatus) {
                        ConnectionStatus.Connecting -> "Connecting stream"
                        is ConnectionStatus.Error -> (streamState.connectionStatus as ConnectionStatus.Error).message
                        ConnectionStatus.Connected -> if (streamState.source.isCameraFallback) {
                            val cameraName = if (streamState.source == StreamSource.FrontCameraFallback) "前置" else "后置"
                            "ESP32 不可用，已切到手机${cameraName}摄像头"
                        } else {
                            "Receiving live frame"
                        }
                        else -> "No live frame"
                    },
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }
        }

        councilState.latestAlert?.let {
            CouncilAlertBanner(
                state = councilState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(start = 4.dp, top = 24.dp, bottom = 112.dp)
        ) {
            if (leftPanelExpanded) {
                CouncilInsightPanel(
                    councilState = councilState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(320.dp)
                        .padding(start = 8.dp)
                )
            } else {
                CollapsedCouncilPeek(
                    councilState = councilState,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(92.dp)
                        .padding(start = 8.dp)
                )
            }

            FilledTonalIconButton(
                onClick = { leftPanelExpanded = !leftPanelExpanded },
                colors = councilButtonColors(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = if (leftPanelExpanded) {
                        Icons.Default.ChevronLeft
                    } else {
                        Icons.Default.ChevronRight
                    },
                    contentDescription = if (leftPanelExpanded) "Collapse council panel" else "Expand council panel",
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        LiveStyleAnalysisPanel(
            commentaryState = commentaryState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(320.dp)
                .padding(end = 8.dp, top = 24.dp, bottom = 112.dp)
        )

        SpeechTranscriptStripPanel(
            state = speechState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.6f)
                .padding(bottom = 76.dp),
            textScheme = councilTranscriptTextScheme
        )

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { onPlayingChange(!isPlaying) },
                        colors = councilButtonColors()
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause stream" else "Play stream"
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onReconnectStream,
                        colors = councilButtonColors()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reconnect")
                    }
                    FilledTonalIconButton(
                        onClick = { streamState.currentFrame?.let(onCaptureSnapshot) },
                        colors = councilButtonColors()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Snapshot")
                    }
                    FilledTonalIconButton(
                        onClick = { onMicToggle(!speechState.isMicEnabled) },
                        colors = councilButtonColors()
                    ) {
                        Icon(
                            imageVector = if (speechState.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (speechState.isMicEnabled) "Mute mic" else "Enable mic"
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onTriggerAnalysis,
                        colors = councilButtonColors()
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Analyze now")
                    }
                    FilledTonalIconButton(
                        onClick = onExit,
                        colors = councilButtonColors()
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Exit council")
                    }
                }

                if (streamState.source.isCameraFallback) {
                    Text(
                        text = "当前视频源：${streamState.sourceLabel}",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CouncilAlertBanner(
    state: CouncilUiState,
    modifier: Modifier = Modifier
) {
    val alert = state.latestAlert ?: return
    val accent = voteColor(alert.level)
    Row(
        modifier = modifier
            .background(accent.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = accent)
        Text(
            text = "${alert.level.label}: ${alert.message}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CouncilInsightPanel(
    councilState: CouncilUiState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            CouncilControlHeader(councilState = councilState)
        }

        items(councilState.console, key = { it.expertId }) { entry ->
            CouncilConsoleSeat(entry = entry)
        }

        if (councilState.discussionTurns.isNotEmpty()) {
            item {
                CouncilDiscussionFeed(
                    turns = councilState.discussionTurns
                )
            }
        }

        councilState.discussionSummary?.let { summary ->
            item {
                DiscussionSummaryBlock(summary = summary)
            }
        }

        councilState.synthesis?.let { synthesis ->
            item {
                InfoBlock(
                    title = "Synthesis",
                    body = buildString {
                        appendLine(synthesis.finalAdvice)
                        if (synthesis.topFindings.isNotEmpty()) {
                            appendLine()
                            appendLine("Findings:")
                            synthesis.topFindings.take(3).forEach { appendLine("- $it") }
                        }
                        if (synthesis.topRisks.isNotEmpty()) {
                            appendLine()
                            appendLine("Risks:")
                            synthesis.topRisks.take(3).forEach { appendLine("- $it") }
                        }
                    }.trim()
                )
            }
        }

        councilState.errorMessage?.let { error ->
            item { InfoBlock(title = "Error", body = error) }
        }
    }
}

@Composable
private fun InfoBlock(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Color(0xFF81D4FA), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            text = body,
            color = Color.White.copy(alpha = 0.86f),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun DiscussionSummaryBlock(summary: CouncilDiscussionSummary) {
    InfoBlock(
        title = "Discussion Summary",
        body = buildString {
            appendLine(summary.headline)
            if (summary.agreements.isNotEmpty()) {
                appendLine()
                appendLine("Agreements:")
                summary.agreements.forEach { appendLine("- $it") }
            }
            if (summary.disagreements.isNotEmpty()) {
                appendLine()
                appendLine("Disagreements:")
                summary.disagreements.forEach { appendLine("- $it") }
            }
            if (summary.nextFocus.isNotEmpty()) {
                appendLine()
                appendLine("Next Focus:")
                summary.nextFocus.forEach { appendLine("- $it") }
            }
        }.trim()
    )
}

@Composable
private fun CouncilDiscussionFeed(
    turns: List<CouncilDiscussionTurn>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Expert Discussion",
            color = Color(0xFFCE93D8),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        turns.takeLast(16).forEach { turn ->
            DiscussionTurnRow(turn = turn)
        }
    }
}

@Composable
private fun DiscussionTurnRow(turn: CouncilDiscussionTurn) {
    val accent = when (turn.kind) {
        CouncilDiscussionKind.Ask -> Color(0xFFFFCA28)
        CouncilDiscussionKind.Reply -> Color(0xFF81C784)
        CouncilDiscussionKind.Summary -> Color(0xFF4FC3F7)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = buildString {
                append("R${turn.round} ")
                append(turn.kind.label)
                append(" ")
                append(turn.fromExpertName)
                if (turn.toExpertName.isNotBlank()) {
                    append(" -> @")
                    append(turn.toExpertName)
                }
            },
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = turn.message,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        if (turn.detail.isNotBlank()) {
            Text(
                text = turn.detail,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun CouncilControlHeader(councilState: CouncilUiState) {
    val progress = when (councilState.analysisPhase) {
        CouncilAnalysisPhase.Idle -> 0.08f
        CouncilAnalysisPhase.Gathering -> 0.35f
        CouncilAnalysisPhase.Discussing -> 0.68f
        CouncilAnalysisPhase.Reviewing -> 0.68f
        CouncilAnalysisPhase.Synthesizing -> 0.9f
        CouncilAnalysisPhase.Complete -> 1f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Council Control",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Scene: ${councilState.config.sceneType.name}",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 11.sp
        )
        Text(
            text = "Phase: ${councilState.analysisPhase.label}",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 11.sp
        )
        if (councilState.discussionRound > 0) {
            Text(
                text = "Discussion round: ${councilState.discussionRound} | turns ${councilState.discussionTurns.size}",
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 10.sp
            )
        }
        if (councilState.activeProviderName != null) {
            Text(
                text = "Model: ${councilState.activeProviderName}",
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 10.sp
            )
        }
        if (councilState.config.objective.isNotBlank()) {
            Text(
                text = councilState.config.objective,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
                .padding(1.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .background(Color(0xFF81D4FA), RoundedCornerShape(999.dp))
                    .padding(vertical = 4.dp)
            )
        }
        councilState.latestAlert?.let { alert ->
            Text(
                text = "Alert: ${alert.level.label}",
                color = voteColor(alert.level),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CollapsedCouncilPeek(
    councilState: CouncilUiState,
    modifier: Modifier = Modifier
) {
    val lead = councilState.console.firstOrNull { it.isLead } ?: councilState.console.firstOrNull()
    val accent = lead?.let(::seatAccent) ?: Color.White.copy(alpha = 0.6f)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "CTRL",
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = councilState.analysisPhase.label,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (lead != null) {
                Text(
                    text = lead.name,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            councilState.latestAlert?.let {
                Text(
                    text = it.level.label,
                    color = voteColor(it.level),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CouncilConsoleSeat(entry: CouncilExpertConsoleState) {
    val accent = seatAccent(entry)
    val badge = badgeFor(entry)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (entry.stage == CouncilExpertStage.Standby) 0.74f else 1f),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = if (entry.isLead) 0.22f else 0.12f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = badge,
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Text(
                        text = entry.name,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = entry.stage.label,
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (entry.headline.isNotBlank()) {
                Text(
                    text = entry.headline,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (entry.note.isNotBlank()) {
                Text(
                    text = entry.note,
                    color = Color.White.copy(alpha = 0.64f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.voteLevel?.label ?: "No vote yet",
                    color = entry.voteLevel?.let(::voteColor) ?: Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp
                )
                Text(
                    text = entry.confidence?.let { "Confidence $it%" } ?: "",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun badgeFor(entry: CouncilExpertConsoleState): String {
    val legacy = runCatching { CouncilExpertRole.valueOf(entry.legacyRole) }.getOrNull()
    return when (legacy) {
        CouncilExpertRole.Observer -> "锚"
        CouncilExpertRole.Delivery -> "表"
        CouncilExpertRole.Psychology -> "意"
        CouncilExpertRole.Risk -> "险"
        CouncilExpertRole.Strategy -> "策"
        CouncilExpertRole.Synthesizer -> "综"
        null -> when (entry.expertKind) {
            CouncilExpertKind.Synthesizer -> "综"
            CouncilExpertKind.Specialist -> entry.name.take(1).ifBlank { "专" }
        }
    }
}

@Composable
private fun councilButtonColors() = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = Color.Black.copy(alpha = 0.42f),
    contentColor = Color.White
)

private fun voteColor(level: CouncilVoteLevel): Color = when (level) {
    CouncilVoteLevel.Pass -> Color(0xFF66BB6A)
    CouncilVoteLevel.Watch -> Color(0xFFFFCA28)
    CouncilVoteLevel.Warn -> Color(0xFFFF7043)
    CouncilVoteLevel.Alert -> Color(0xFFEF5350)
}

private fun seatAccent(entry: CouncilExpertConsoleState): Color {
    entry.voteLevel?.let { return voteColor(it) }
    return when (entry.stage) {
        CouncilExpertStage.Observing -> Color(0xFF4FC3F7)
        CouncilExpertStage.Speaking -> Color(0xFFBA68C8)
        CouncilExpertStage.Discussing -> Color(0xFFFFB74D)
        CouncilExpertStage.Reviewing -> Color(0xFFFFCA28)
        CouncilExpertStage.Synthesizing -> Color(0xFF81C784)
        CouncilExpertStage.WaitingContext -> Color(0xFF90A4AE)
        CouncilExpertStage.Blocked -> Color(0xFFE57373)
        CouncilExpertStage.Voted -> Color(0xFF81C784)
        CouncilExpertStage.Standby -> Color.White.copy(alpha = 0.55f)
    }
}

@Composable
private fun LiveStyleAnalysisPanel(
    commentaryState: LiveCommentaryState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        MemoryStatusPanel(
            state = commentaryState,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .fillMaxHeight(0.45f),
            labels = councilMemoryLabelScheme
        )
        if (commentaryState.entries.isNotEmpty()) {
            CommentaryFeedPanel(
                entries = commentaryState.entries,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f),
                textScheme = councilCommentaryTextScheme
            )
        }
    }
}

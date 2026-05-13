package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.BlackboardDay
import com.example.watcher.data.model.BlackboardEntry
import com.example.watcher.data.model.BehaviorClaim
import com.example.watcher.data.model.BehaviorReasoningLog
import com.example.watcher.data.model.LiveCommentaryState
import com.example.watcher.data.model.MatchBreakdown
import com.example.watcher.data.model.ObservationGoal
import com.example.watcher.data.model.SceneProfile
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.repository.PortraitCuratorActivityEntry
import com.example.watcher.data.repository.PortraitCuratorMemoryDebugState
import com.example.watcher.data.repository.PortraitCuratorStatus
import com.example.watcher.ui.components.ConciseModeToggleChip
import com.example.watcher.ui.components.MjpegStreamUiState
import com.example.watcher.ui.components.RotaryActionCluster
import com.example.watcher.ui.components.RotaryActionItem
import com.example.watcher.ui.components.WorkspaceBackdrop
import com.example.watcher.ui.viewmodel.BlackboardDebugUiState
import com.example.watcher.ui.viewmodel.ClaimConsolidationUiState
import com.example.watcher.ui.viewmodel.ObservationControlState

@Composable
internal fun DigitalLifeCardWorkspacePage(
    settings: VideoStreamSettings,
    streamState: MjpegStreamUiState,
    isStreamPlaying: Boolean,
    commentaryState: LiveCommentaryState,
    isConciseMode: Boolean,
    rotaryRotationDegrees: Float,
    onConciseModeChange: (Boolean) -> Unit,
    onRotaryRotationChange: (Float) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onReconnectStream: () -> Unit,
    onCaptureSnapshot: (Bitmap) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgentConfig: () -> Unit,
    onOpenWalletConfig: () -> Unit,
    onStartCommentary: () -> Unit,
    onStopCommentary: () -> Unit,
    onStopAgent: () -> Unit,
    onResetCommentary: () -> Unit,
    observationControlState: ObservationControlState,
    blackboardDays: List<BlackboardDay> = emptyList(),
    allSceneProfiles: List<SceneProfile> = emptyList(),
    currentSceneId: String? = null,
    currentSceneLabel: String = "未识别场景",
    behaviorClaims: List<BehaviorClaim> = emptyList(),
    observationGoals: List<ObservationGoal> = emptyList(),
    allObservationGoals: List<ObservationGoal> = emptyList(),
    allReasoningLogs: List<BehaviorReasoningLog> = emptyList(),
    lastMatchedSceneId: String? = null,
    lastMatchBreakdown: MatchBreakdown? = null,
    onRegeneratePortrait: (String) -> Unit = { _ -> },
    claimConsolidationUiState: ClaimConsolidationUiState = ClaimConsolidationUiState(),
    onRunClaimConsolidation: (String) -> Unit = {},
    onExportClaims: (String, String) -> Unit = { _, _ -> },
    onRenameScene: (String, String) -> Unit = { _, _ -> },
    onMergeScenes: (String, String) -> Unit = { _, _ -> },
    selectedDayEntries: List<BlackboardEntry> = emptyList(),
    onLoadDayEntries: (String) -> Unit = {},
    reasoningLogs: List<BehaviorReasoningLog> = emptyList(),
    blackboardDebugState: BlackboardDebugUiState = BlackboardDebugUiState(),
    agentActivityLog: List<PortraitCuratorActivityEntry> = emptyList(),
    onExportActivityLog: (List<PortraitCuratorActivityEntry>) -> Unit = {},
    agentMemoryDebugState: PortraitCuratorMemoryDebugState = PortraitCuratorMemoryDebugState(),
    agentStatus: PortraitCuratorStatus = PortraitCuratorStatus.Idle
) {
    val rotaryItems = listOf(
        RotaryActionItem(
            icon = Icons.Default.VpnKey,
            contentDescription = "Open API wallet",
            onClick = onOpenWalletConfig
        ),
        RotaryActionItem(
            icon = Icons.Default.Psychology,
            contentDescription = "Open agent settings",
            onClick = onOpenAgentConfig
        ),
        RotaryActionItem(
            icon = Icons.Default.Settings,
            contentDescription = "Open camera settings",
            onClick = onOpenSettings
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        WorkspaceBackdrop(
            pagerPosition = 1.6f,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "独立功能模块 / Digital Life Card",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "用户行为模型工作台",
                        style = MaterialTheme.typography.displaySmall
                    )
                    Text(
                        text = "以 Blackboard 承接观察事实与 Agent 信息流转，再围绕具体场景沉淀行为模型，并逐步提升跨场景通用模式。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ConciseModeToggleChip(
                        isConciseMode = isConciseMode,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onToggle = { onConciseModeChange(!isConciseMode) }
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(176.dp),
                    contentAlignment = Alignment.Center
                ) {
                    RotaryActionCluster(
                        items = rotaryItems,
                        accent = MaterialTheme.colorScheme.tertiary,
                        glassOverlay = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        rotationDegrees = rotaryRotationDegrees,
                        onRotationChange = onRotaryRotationChange
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                DigitalLifeMissionCard(
                    currentSceneLabel = currentSceneLabel,
                    universalClaimCount = behaviorClaims.count { it.sceneId == null }
                )
            }

            DlcSectionIntro(
                eyebrow = "01 / 观察输入",
                title = "实时视频和观察流入口",
                description = "先采集当前画面，再把解说生成的客观观察持续写入 Blackboard。"
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val useStackedLayout = maxWidth < 900.dp

                if (useStackedLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        DigitalLifeVideoAnchorCard(
                            settings = settings,
                            streamState = streamState,
                            isStreamPlaying = isStreamPlaying,
                            onPlayingChange = onPlayingChange,
                            onReconnectStream = onReconnectStream,
                            onCaptureSnapshot = onCaptureSnapshot,
                            onOpenSettings = onOpenSettings
                        )
                        DlcCommentaryCard(
                            state = commentaryState,
                            controlState = observationControlState,
                            onStart = onStartCommentary,
                            onStop = onStopCommentary,
                            onStopAgent = onStopAgent,
                            onReset = onResetCommentary
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1.15f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            DigitalLifeVideoAnchorCard(
                                settings = settings,
                                streamState = streamState,
                                isStreamPlaying = isStreamPlaying,
                                onPlayingChange = onPlayingChange,
                                onReconnectStream = onReconnectStream,
                                onCaptureSnapshot = onCaptureSnapshot,
                                onOpenSettings = onOpenSettings
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.95f)
                                .widthIn(min = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            DlcCommentaryCard(
                                state = commentaryState,
                                controlState = observationControlState,
                                onStart = onStartCommentary,
                                onStop = onStopCommentary,
                                onStopAgent = onStopAgent,
                                onReset = onResetCommentary
                            )
                        }
                    }
                }
            }

            DlcSectionIntro(
                eyebrow = "02 / Blackboard",
                title = "共享工作台",
                description = "这里承接当前会话的观察事实、场景记忆、实体记忆和 Agent 的共享上下文，不等于最终行为模型。"
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val useStackedLayout = maxWidth < 900.dp

                if (useStackedLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        DlcAgentStatusCard(status = agentStatus)
                        DlcAgentMemoryCard(state = agentMemoryDebugState)
                        DlcAgentActivityCard(entries = agentActivityLog, onExport = onExportActivityLog)
                        DlcMemoryStatusCard(state = commentaryState)
                        DlcBlackboardDebugCard(state = blackboardDebugState)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(0.9f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            DlcAgentStatusCard(status = agentStatus)
                            DlcAgentMemoryCard(state = agentMemoryDebugState)
                            DlcAgentActivityCard(entries = agentActivityLog, onExport = onExportActivityLog)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1.2f)
                                .widthIn(min = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            DlcMemoryStatusCard(state = commentaryState)
                            DlcBlackboardDebugCard(state = blackboardDebugState)
                        }
                    }
                }
            }

            DlcSectionIntro(
                eyebrow = "03 / 行为模型产出",
                title = "结论、推理和待补证据",
                description = "这一组内容是 Blackboard 的下游产物，用来表达当前判断、推理轨迹和下一步需要补充的观察证据。"
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val useStackedLayout = maxWidth < 900.dp

                if (useStackedLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        DlcPortraitCard(
                            sceneProfiles = allSceneProfiles,
                            claims = behaviorClaims,
                            allGoals = allObservationGoals,
                            allReasoningLogs = allReasoningLogs,
                            currentSceneId = currentSceneId,
                            currentSceneLabel = currentSceneLabel,
                            lastMatchedSceneId = lastMatchedSceneId,
                            lastMatchBreakdown = lastMatchBreakdown,
                            onRegenerate = onRegeneratePortrait,
                            claimConsolidationUiState = claimConsolidationUiState,
                            onRunClaimConsolidation = onRunClaimConsolidation,
                            onExportClaims = onExportClaims,
                            onRenameScene = onRenameScene,
                            onMergeScenes = onMergeScenes
                        )
                        DlcBehaviorReasoningCard(reasoningLogs = reasoningLogs)
                        DlcObservationGoalsCard(
                            goals = observationGoals,
                            currentSceneLabel = currentSceneLabel
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1.15f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            DlcPortraitCard(
                                sceneProfiles = allSceneProfiles,
                                claims = behaviorClaims,
                                allGoals = allObservationGoals,
                                allReasoningLogs = allReasoningLogs,
                                currentSceneId = currentSceneId,
                                currentSceneLabel = currentSceneLabel,
                                lastMatchedSceneId = lastMatchedSceneId,
                                lastMatchBreakdown = lastMatchBreakdown,
                                onRegenerate = onRegeneratePortrait,
                                claimConsolidationUiState = claimConsolidationUiState,
                                onRunClaimConsolidation = onRunClaimConsolidation,
                                onExportClaims = onExportClaims,
                                onRenameScene = onRenameScene,
                                onMergeScenes = onMergeScenes
                            )
                            DlcBehaviorReasoningCard(reasoningLogs = reasoningLogs)
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.95f)
                                .widthIn(min = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            DlcObservationGoalsCard(
                                goals = observationGoals,
                                currentSceneLabel = currentSceneLabel
                            )
                        }
                    }
                }
            }

            DlcSectionIntro(
                eyebrow = "04 / 历史归档",
                title = "按天回看 Blackboard 快照",
                description = "这里展示的是已经落库的会话归档，方便回看某一天的场景记忆、实体摘要和原始观察条目。"
            )

            DlcBlackboardCard(
                days = blackboardDays,
                selectedDayEntries = selectedDayEntries,
                onLoadDayEntries = onLoadDayEntries
            )
        }
    }
}

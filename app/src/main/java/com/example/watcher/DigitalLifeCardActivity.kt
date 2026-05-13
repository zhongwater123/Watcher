package com.example.watcher

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watcher.ui.components.VideoStreamSettingsDialog
import com.example.watcher.ui.components.rememberMjpegStreamState
import com.example.watcher.ui.screens.DigitalLifeCardWorkspacePage
import com.example.watcher.ui.theme.WatcherTheme
import com.example.watcher.ui.util.DigitalLifeConciseModeStore
import com.example.watcher.ui.viewmodel.BlackboardDebugUiState
import com.example.watcher.ui.viewmodel.ClaimConsolidationUiState
import com.example.watcher.ui.viewmodel.DigitalLifeCardViewModel
import com.example.watcher.ui.viewmodel.ObservationControlState
import com.example.watcher.data.model.BlackboardDay
import com.example.watcher.data.model.BehaviorClaim
import com.example.watcher.data.model.BehaviorReasoningLog
import com.example.watcher.data.model.DeviceProvisionUiState
import com.example.watcher.data.repository.PortraitCuratorActivityEntry
import com.example.watcher.data.repository.PortraitCuratorStatus
import com.example.watcher.data.model.ObservationGoal
import com.example.watcher.data.model.StreamScanUiState
import com.example.watcher.data.model.VideoStreamSettings

class DigitalLifeCardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatcherTheme {
                DigitalLifeCardRoute(onClose = ::finish)
            }
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, DigitalLifeCardActivity::class.java)
        }
    }
}

@Composable
private fun DigitalLifeCardRoute(
    onClose: () -> Unit,
    viewModel: DigitalLifeCardViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val context = LocalContext.current
    val conciseModeStore = remember(context) { DigitalLifeConciseModeStore(context) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isConciseMode by remember { mutableStateOf(conciseModeStore.isConciseMode()) }
    var rotaryRotationDegrees by rememberSaveable { mutableStateOf(0f) }
    val streamSettings by viewModel.videoStreamSettings.collectAsStateWithLifecycle(initialValue = null)
    val isStreamPlaying by viewModel.isStreamPlaying.collectAsStateWithLifecycle()
    val streamReconnectToken by viewModel.streamReconnectToken.collectAsStateWithLifecycle()
    val commentaryState by viewModel.commentaryState.collectAsStateWithLifecycle()
    val blackboardDays by viewModel.blackboardDays.collectAsStateWithLifecycle(initialValue = emptyList())
    val behaviorClaims by viewModel.behaviorClaims.collectAsStateWithLifecycle(initialValue = emptyList())
    val observationGoals by viewModel.observationGoals.collectAsStateWithLifecycle(initialValue = emptyList())
    val allObservationGoals by viewModel.allObservationGoals.collectAsStateWithLifecycle(initialValue = emptyList())
    val allReasoningLogs by viewModel.allBehaviorReasoningLogs.collectAsStateWithLifecycle(initialValue = emptyList())
    val allSceneProfiles by viewModel.allSceneProfiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentSceneId by viewModel.currentSceneId.collectAsStateWithLifecycle()
    val currentSceneLabel by viewModel.currentSceneLabel.collectAsStateWithLifecycle()
    val lastMatchedSceneId by viewModel.lastMatchedSceneId.collectAsStateWithLifecycle()
    val lastMatchBreakdown by viewModel.lastMatchBreakdown.collectAsStateWithLifecycle()
    val selectedDayEntries by viewModel.selectedDayEntries.collectAsStateWithLifecycle()
    val reasoningLogs by viewModel.behaviorReasoningLogs.collectAsStateWithLifecycle(initialValue = emptyList())
    val agentStatus by viewModel.agentStatus.collectAsStateWithLifecycle()
    val observationControlState by viewModel.observationControlState.collectAsStateWithLifecycle()
    val claimConsolidationUiState by viewModel.claimConsolidationUiState.collectAsStateWithLifecycle()
    val blackboardDebugState by viewModel.blackboardDebugState.collectAsStateWithLifecycle()
    val agentActivityLog by viewModel.agentActivityLog.collectAsStateWithLifecycle()
    val agentMemoryDebugState by viewModel.agentMemoryDebugState.collectAsStateWithLifecycle()
    var pendingStartAfterPermission by remember { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        if (pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            viewModel.startCommentary()
        }
    }
    val settings = streamSettings ?: VideoStreamSettings()
    val streamState = rememberMjpegStreamState(
        settings = settings,
        isPlaying = isStreamPlaying,
        reconnectToken = streamReconnectToken,
        onFrameUpdate = viewModel::updateVideoFrame
    )

    DisposableEffect(viewModel) {
        viewModel.setStreamPlaying(true)
        onDispose {
            viewModel.setStreamPlaying(false)
            viewModel.updateVideoFrame(null)
        }
    }

    val startCommentaryWithLocationGate: () -> Unit = {
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCoarseLocation) {
            viewModel.startCommentary()
        } else {
            pendingStartAfterPermission = true
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    DigitalLifeCardWorkspacePage(
        settings = settings,
        streamState = streamState,
        isStreamPlaying = isStreamPlaying,
        commentaryState = commentaryState,
        isConciseMode = isConciseMode,
        rotaryRotationDegrees = rotaryRotationDegrees,
        onConciseModeChange = {
            isConciseMode = it
            conciseModeStore.setConciseMode(it)
        },
        onRotaryRotationChange = { rotaryRotationDegrees = it },
        onPlayingChange = viewModel::setStreamPlaying,
        onReconnectStream = viewModel::reconnectStream,
        onCaptureSnapshot = { bitmap ->
            Toast.makeText(context, "Snapshot captured", Toast.LENGTH_SHORT).show()
        },
        onOpenSettings = { showSettingsDialog = true },
        onOpenAgentConfig = { context.startActivity(AgentConfigActivity.createIntent(context)) },
        onOpenWalletConfig = { context.startActivity(ApiWalletActivity.createIntent(context)) },
        onStartCommentary = startCommentaryWithLocationGate,
        onStopCommentary = viewModel::stopCommentary,
        onStopAgent = viewModel::stopAgent,
        onResetCommentary = viewModel::resetCommentary,
        observationControlState = observationControlState,
        blackboardDays = blackboardDays,
        allSceneProfiles = allSceneProfiles,
        currentSceneId = currentSceneId,
        currentSceneLabel = currentSceneLabel,
        behaviorClaims = behaviorClaims,
        observationGoals = observationGoals,
        allObservationGoals = allObservationGoals,
        allReasoningLogs = allReasoningLogs,
        lastMatchedSceneId = lastMatchedSceneId,
        lastMatchBreakdown = lastMatchBreakdown,
        onRegeneratePortrait = viewModel::regeneratePortrait,
        claimConsolidationUiState = claimConsolidationUiState,
        onRunClaimConsolidation = viewModel::runClaimConsolidation,
        onExportClaims = { title, payload ->
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(ClipData.newPlainText(title, payload))
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, payload)
            }
            Toast.makeText(context, "已复制导出内容到剪贴板", Toast.LENGTH_SHORT).show()
            context.startActivity(Intent.createChooser(shareIntent, title))
        },
        onRenameScene = viewModel::renameScene,
        onMergeScenes = viewModel::mergeScenes,
        selectedDayEntries = selectedDayEntries,
        onLoadDayEntries = viewModel::loadBlackboardEntries,
        reasoningLogs = reasoningLogs,
        blackboardDebugState = blackboardDebugState,
        agentActivityLog = agentActivityLog,
        onExportActivityLog = { entries ->
            val text = com.example.watcher.ui.screens.formatActivityLogForExport(entries)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "Agent Activity Log")
            }
            context.startActivity(Intent.createChooser(sendIntent, "导出 Agent 日志"))
        },
        agentMemoryDebugState = agentMemoryDebugState,
        agentStatus = agentStatus
    )

    if (showSettingsDialog) {
        VideoStreamSettingsDialog(
            settings = streamSettings,
            scanState = StreamScanUiState(),
            provisionState = DeviceProvisionUiState(),
            onDismiss = { showSettingsDialog = false },
            onScanDevices = {},
            onLoadDeviceInfo = {},
            onScanProvisionWifi = {},
            onSubmitProvisionWifi = { _, _ -> },
            onClearProvisionedWifi = {},
            onSave = {
                viewModel.saveVideoStreamSettings(it)
                showSettingsDialog = false
            }
        )
    }
}

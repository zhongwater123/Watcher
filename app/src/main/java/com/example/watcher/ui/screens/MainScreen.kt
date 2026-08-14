package com.example.watcher.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watcher.AgentConfigActivity
import com.example.watcher.ApiWalletActivity
import com.example.watcher.DigitalLifeCardActivity
import com.example.watcher.FitnessCompanionActivity
import com.example.watcher.LiteRtActivity
import com.example.watcher.LocalAgentActivity
import com.example.watcher.MultiDeviceActivity
import com.example.watcher.BackScreenPushActivity
import com.example.watcher.PoseEstimationActivity
import com.example.watcher.data.model.AiAudienceEntity
import com.example.watcher.data.model.AiAudienceLiveState
import com.example.watcher.data.model.DanmakuItem
import com.example.watcher.data.gateway.GatewayPairingRequestStatus
import com.example.watcher.data.model.InteractionMode
import com.example.watcher.data.model.LiveCommentaryState
import com.example.watcher.data.model.LiveSpeechState
import com.example.watcher.data.model.LlmProviderEntity
import com.example.watcher.data.model.StorageSummary
import kotlinx.coroutines.flow.SharedFlow
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.intentrouter.IntentRouterLog
import com.example.watcher.ui.components.BottomGlassScrim
import com.example.watcher.ui.components.CameraFallbackLens
import com.example.watcher.ui.components.ConnectionStatus
import com.example.watcher.ui.components.StreamSource
import com.example.watcher.ui.components.SharedWorkspaceHeader
import com.example.watcher.ui.components.StartupMainContentPolicy
import com.example.watcher.ui.components.SwipeCoachmarkOverlay
import com.example.watcher.ui.components.VideoStreamSettingsDialog
import com.example.watcher.ui.components.WorkspaceBackdrop
import com.example.watcher.ui.components.calculatePageOffset
import com.example.watcher.ui.components.calculatePagerPosition
import com.example.watcher.ui.components.rememberMjpegStreamState
import com.example.watcher.ui.intentrouter.IntentRouterViewModel
import com.example.watcher.ui.intentrouter.QuickNavigationDialog
import com.example.watcher.ui.intentrouter.toHubPage
import com.example.watcher.ui.util.PageConciseModeController
import com.example.watcher.ui.util.PageConciseModeStore
import com.example.watcher.ui.viewmodel.IntentViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private const val UI_HINT_PREFS = "watcher_ui_hints"
private const val KEY_PAGER_COACHMARK_SEEN = "pager_coachmark_seen_v1"
private const val QUICK_NAVIGATION_HINT_VISIBLE_MS = 5_000L
private val CLASSROOM_NOTE_MATERIAL_MIME_TYPES = arrayOf(
    "image/*",
    "video/*",
    "audio/*",
    "application/pdf",
    "text/*",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)

@Composable
fun MainScreen(
    manageSystemBars: Boolean = true,
    viewModel: IntentViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    ),
    intentRouterViewModel: IntentRouterViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val hintPreferences = remember {
        context.getSharedPreferences(UI_HINT_PREFS, Activity.MODE_PRIVATE)
    }
    val conciseModeController = remember(context) {
        PageConciseModeController(PageConciseModeStore(context))
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tasks by viewModel.tasksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val videoTasks by viewModel.videoTasksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentVideoRuns by viewModel.recentVideoRunsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentClassroomRuns by viewModel.recentClassroomRunsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val historyRecords by viewModel.historyRecordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val storageSummary by viewModel.storageSummaryFlow.collectAsStateWithLifecycle(initialValue = StorageSummary())
    val streamSettings by viewModel.videoStreamSettings.collectAsStateWithLifecycle(initialValue = null)
    val isStreamPlaying by viewModel.isStreamPlaying.collectAsStateWithLifecycle()
    val streamReconnectToken by viewModel.streamReconnectToken.collectAsStateWithLifecycle()
    val monitorStatus by viewModel.monitorStatus.collectAsStateWithLifecycle()
    val monitorLogs by viewModel.monitorLogs.collectAsStateWithLifecycle()
    val currentTask by viewModel.currentIntentResult.collectAsStateWithLifecycle()
    val pendingBaselineImagePath by viewModel.pendingBaselineImagePath.collectAsStateWithLifecycle()
    val pendingBaselineBase64 by viewModel.pendingBaselineBase64.collectAsStateWithLifecycle()
    val streamScanUiState by viewModel.streamScanUiState.collectAsStateWithLifecycle()
    val deviceProvisionUiState by viewModel.deviceProvisionUiState.collectAsStateWithLifecycle()
    val settingsNotice by viewModel.settingsNotice.collectAsStateWithLifecycle(initialValue = null)
    val videoPlanUiState by viewModel.videoPlanUiState.collectAsStateWithLifecycle()
    val currentVideoTask by viewModel.currentVideoTask.collectAsStateWithLifecycle()
    val videoProcessingStatus by viewModel.videoProcessingStatus.collectAsStateWithLifecycle()
    val classroomRecordingInput by viewModel.classroomRecordingInput.collectAsStateWithLifecycle()
    val selectedVideoRunId by viewModel.selectedVideoRunId.collectAsStateWithLifecycle()
    val selectedVideoRunEvents by viewModel.selectedVideoRunEvents.collectAsStateWithLifecycle()
    val selectedHistoryRecord by viewModel.selectedHistoryRecord.collectAsStateWithLifecycle()
    val selectedHistoryDetail by viewModel.selectedHistoryDetail.collectAsStateWithLifecycle()
    val activeVideoHistoryReportDetail by viewModel.activeVideoHistoryReportDetail.collectAsStateWithLifecycle()
    val monitorTemplates by viewModel.monitorTemplatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val videoTemplates by viewModel.videoTemplatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val councilTemplates by viewModel.councilTemplatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val councilExperts by viewModel.councilExpertsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val liveCommentaryState by viewModel.liveCommentaryState.collectAsStateWithLifecycle()
    val aiAudienceState by viewModel.aiAudienceLiveState.collectAsStateWithLifecycle()
    val llmProviders by viewModel.llmProvidersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val aiAudiences by viewModel.aiAudiencesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val liveSpeechState by viewModel.liveSpeechState.collectAsStateWithLifecycle()
    val interactionMode by viewModel.interactionMode.collectAsStateWithLifecycle()
    val councilState by viewModel.councilState.collectAsStateWithLifecycle()
    val councilEntryUiState by viewModel.councilEntryUiState.collectAsStateWithLifecycle()
    val gatewayRunning by viewModel.gatewayRunning.collectAsStateWithLifecycle()
    val gatewayStatus by viewModel.gatewayStatus.collectAsStateWithLifecycle()
    val gatewayPairingRequests by viewModel.gatewayPairingRequests.collectAsStateWithLifecycle()
    val intentRouterState by intentRouterViewModel.uiState.collectAsStateWithLifecycle()
    val gatewayPairingBindings by viewModel.gatewayPairingBindings.collectAsStateWithLifecycle()
    val appUpdatePrompt by viewModel.appUpdatePrompt.collectAsStateWithLifecycle()

    var monitorRequestText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var videoRequestText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var previewRotationDegrees by remember { mutableStateOf<Int?>(null) }
    var previewMirrorHorizontally by remember { mutableStateOf<Boolean?>(null) }
    var quickNavigationAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var showQuickNavigationHint by remember { mutableStateOf(false) }
    var voiceTarget by remember { mutableStateOf(HubPage.Monitor) }
    var isListening by remember { mutableStateOf(false) }
    var showPagerCoachmark by remember {
        mutableStateOf(!hintPreferences.getBoolean(KEY_PAGER_COACHMARK_SEEN, false))
    }

    val pagerState = rememberPagerState(
        initialPage = HubPage.Hub.pageIndex,
        pageCount = { HubPage.entries.size }
    )
    var pendingNavigationPage by rememberSaveable {
        mutableStateOf<Int?>(null)
    }
    var sharedRotaryRotationDegrees by rememberSaveable {
        mutableStateOf(0f)
    }
    var conciseModes by remember {
        mutableStateOf(conciseModeController.initialModes())
    }
    val pagerPosition by remember(pagerState) {
        derivedStateOf {
            if (pagerState.isScrollInProgress) {
                calculatePagerPosition(
                    currentPage = pagerState.currentPage,
                    currentPageOffsetFraction = pagerState.currentPageOffsetFraction
                )
            } else {
                pendingNavigationPage?.toFloat() ?: pagerState.currentPage.toFloat()
            }
        }
    }
    val currentPage by remember(pagerState) {
        derivedStateOf { HubPage.fromPage(pagerState.currentPage) }
    }
    val quickNavigationHintVisible by remember {
        derivedStateOf {
            showQuickNavigationHint &&
                !intentRouterState.visible &&
                currentPage == HubPage.Hub &&
                quickNavigationAnchorBounds != null
        }
    }
    LaunchedEffect(quickNavigationHintVisible, quickNavigationAnchorBounds) {
        if (quickNavigationHintVisible) {
            delay(QUICK_NAVIGATION_HINT_VISIBLE_MS)
            showQuickNavigationHint = false
        }
    }
    val openQuickNavigation = remember(intentRouterViewModel) {
        {
            showQuickNavigationHint = false
            intentRouterViewModel.show()
        }
    }
    val dismissQuickNavigation = remember(intentRouterViewModel, currentPage) {
        {
            intentRouterViewModel.dismiss()
            showQuickNavigationHint = currentPage == HubPage.Hub
        }
    }
    val streamPreviewActive by remember {
        derivedStateOf { isStreamPreviewPageVisible(pagerPosition) }
    }

    val hasSavedStreamSettings = VideoStreamSettings.shouldAutoConnect(streamSettings)
    val persistedSettings = streamSettings ?: VideoStreamSettings()
    val settings = persistedSettings.copy(
        rotationDegrees = previewRotationDegrees ?: persistedSettings.rotationDegrees,
        mirrorHorizontally = previewMirrorHorizontally ?: persistedSettings.mirrorHorizontally
    ).normalized()
    LaunchedEffect(
        showSettingsDialog,
        streamSettings?.rotationDegrees,
        streamSettings?.mirrorHorizontally
    ) {
        val savedSettings = streamSettings?.normalized() ?: return@LaunchedEffect
        if (
            !showSettingsDialog &&
            previewRotationDegrees == savedSettings.rotationDegrees &&
            previewMirrorHorizontally == savedSettings.mirrorHorizontally
        ) {
            previewRotationDegrees = null
            previewMirrorHorizontally = null
        }
    }
    val streamState = rememberMjpegStreamState(
        settings = settings,
        isPlaying = isStreamPlaying && hasSavedStreamSettings,
        reconnectToken = streamReconnectToken,
        previewActive = streamPreviewActive,
        onFrameUpdate = viewModel::updateVideoFrame,
        onStreamSourceChanged = viewModel::updateStreamSource,
        onRemoteStreamUnavailable = viewModel::recoverProvisionedDeviceAfterRuntimeDisconnect
    )
    val startupBlockingDialogsAllowed = StartupMainContentPolicy.canShowBlockingDialogs(
        mainContentInteractive = manageSystemBars
    )

    LaunchedEffect(startupBlockingDialogsAllowed, streamState.showCameraChooser) {
        if (!startupBlockingDialogsAllowed && streamState.showCameraChooser) {
            Log.d("MjpegStream", "camera chooser pending until startup overlay finished")
        }
    }

    if (startupBlockingDialogsAllowed && streamState.showCameraChooser) {
        AlertDialog(
            onDismissRequest = { streamState.chooseCameraLens(CameraFallbackLens.Front) },
            title = { Text("选择摄像头") },
            text = { Text("ESP32 视频流不可用，请选择降级画面来源：") },
            confirmButton = {
                TextButton(onClick = { streamState.chooseCameraLens(CameraFallbackLens.Front) }) {
                    Text("前置摄像头")
                }
            },
            dismissButton = {
                TextButton(onClick = { streamState.chooseCameraLens(CameraFallbackLens.Back) }) {
                    Text("后置摄像头")
                }
            }
        )
    }

    // Orientation detection — landscape triggers immersive live room
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Hide system bars in landscape, restore in portrait
    val view = LocalView.current
    DisposableEffect(isLandscape, manageSystemBars) {
        if (!manageSystemBars) return@DisposableEffect onDispose {}
        val window = (view.context as? Activity)?.window
            ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)
        if (isLandscape) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var immersiveSessionActive by remember { mutableStateOf(false) }
    var showImmersiveEntryDialog by remember { mutableStateOf(false) }

    val lockLandscape: () -> Unit = remember(context) {
        {
            (context as? Activity)?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    val unlockOrientation: () -> Unit = remember(context) {
        {
            (context as? Activity)?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                (context as? Activity)?.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }, 1000)
        }
    }

    val stopCurrentImmersiveMode: () -> Unit = remember(viewModel, interactionMode) {
        {
            when (interactionMode) {
                InteractionMode.Live -> {
                    viewModel.stopLiveCommentary()
                    viewModel.stopAiAudience()
                    viewModel.stopLiveSpeech()
                }
                InteractionMode.Council -> viewModel.stopCouncilMode()
                InteractionMode.Off -> Unit
            }
        }
    }

    // Show entry dialog when user rotates to landscape (only if not already in a session)
    LaunchedEffect(isLandscape) {
        if (isLandscape && !immersiveSessionActive && interactionMode == InteractionMode.Off) {
            showImmersiveEntryDialog = true
        }
    }

    val navigateTo = rememberPagerNavigator(
        pagerState = pagerState,
        coroutineScope = coroutineScope,
        onNavigationRequested = { page -> pendingNavigationPage = page.pageIndex }
    )

    LaunchedEffect(intentRouterViewModel, navigateTo) {
        intentRouterViewModel.navigationEvents.collect { event ->
            val targetPage = event.routeId.toHubPage()
            Log.d(
                IntentRouterLog.TAG,
                "traceId=${event.traceId} MainScreen navigation consumed routeId=${event.routeId.wireId} targetPage=${targetPage.name} source=${event.sourceLabel}"
            )
            navigateTo(targetPage)
        }
    }

    val quickNavigationAutoTrigger = remember(
        startupBlockingDialogsAllowed,
        streamState.currentFrame,
        streamState.connectionStatus,
        streamState.source,
        streamState.showCameraChooser
    ) {
        if (!startupBlockingDialogsAllowed || streamState.currentFrame == null || streamState.showCameraChooser) {
            return@remember null
        }
        when {
            streamState.source == StreamSource.RemoteMjpeg &&
                streamState.connectionStatus is ConnectionStatus.Connected -> "remote_mjpeg_first_frame"
            streamState.source.isCameraFallback -> "camera_fallback_first_frame"
            else -> null
        }
    }

    LaunchedEffect(quickNavigationAutoTrigger) {
        val trigger = quickNavigationAutoTrigger ?: return@LaunchedEffect
        Log.d(
            IntentRouterLog.TAG,
            "MainScreen first frame ready for auto dialog trigger=$trigger source=${streamState.source} status=${streamState.connectionStatus::class.java.simpleName} dialogsAllowed=$startupBlockingDialogsAllowed"
        )
        intentRouterViewModel.showAutomaticallyAfterFirstFrameReady(trigger)
    }

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pendingNavigationPage == pagerState.currentPage) {
            pendingNavigationPage = null
        }
    }

    val captureSnapshot = rememberSnapshotCapturer(
        viewModel = viewModel,
        toast = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    )

    val dismissCoachmark = remember(hintPreferences) {
        {
            showPagerCoachmark = false
            hintPreferences.edit().putBoolean(KEY_PAGER_COACHMARK_SEEN, true).apply()
        }
    }
    val updateConciseMode = remember(conciseModeController) {
        { page: HubPage, enabled: Boolean ->
            conciseModes = conciseModeController.updateMode(conciseModes, page, enabled)
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = matches?.firstOrNull().orEmpty()
            if (recognizedText.isBlank()) {
                return@rememberLauncherForActivityResult
            }
            when (voiceTarget) {
                HubPage.Monitor -> monitorRequestText = TextFieldValue(recognizedText)
                HubPage.Analysis -> videoRequestText = TextFieldValue(recognizedText)
                HubPage.Hub,
                HubPage.History,
                HubPage.Templates -> Unit
            }
        }
    }

    val digitalLifeCardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (settings.streamUrl.isNotBlank()) {
            viewModel.setStreamPlaying(true)
            viewModel.reconnectStream()
        }
    }

    val fitnessCompanionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (settings.streamUrl.isNotBlank()) {
            viewModel.setStreamPlaying(true)
            viewModel.reconnectStream()
        }
    }

    val liteRtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (settings.streamUrl.isNotBlank()) {
            viewModel.setStreamPlaying(true)
            viewModel.reconnectStream()
        }
    }

    val poseEstimationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (settings.streamUrl.isNotBlank()) {
            viewModel.setStreamPlaying(true)
            viewModel.reconnectStream()
        }
    }

    val backScreenPushLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasSavedStreamSettings) {
            viewModel.setStreamPlaying(true)
            viewModel.reconnectStream()
        }
    }

    val localAgentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* No stream state to restore */ }

    val multiDeviceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Gateway doesn't use camera stream, no reconnect needed */ }

    val baselineImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let(viewModel::setBaselineFromPickedImage)
    }

    val classroomTestVideoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let(viewModel::selectClassroomTestVideo)
    }

    val classroomNoteMaterialPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.attachClassroomNoteMaterials(uris)
            Toast.makeText(context, "课堂资料已加入上传队列", Toast.LENGTH_SHORT).show()
        }
    }

    val startListening = remember(speechLauncher, context) {
        { target: HubPage ->
            voiceTarget = target
            isListening = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE)
            }
            try {
                speechLauncher.launch(intent)
            } catch (_: Exception) {
                isListening = false
                Toast.makeText(context, "Speech recognition is not available on this device.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(settings.streamUrl) {
        if (settings.streamUrl.isNotBlank()) {
            viewModel.setStreamPlaying(true)
        }
    }

    LaunchedEffect(settingsNotice) {
        settingsNotice?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeSettingsNotice()
        }
    }

    if (showImmersiveEntryDialog) {
        LandscapeModeEntryDialog(
            councilTemplates = councilTemplates,
            entryState = councilEntryUiState,
            onGenerate = viewModel::generateCouncilEntryConfig,
            onSaveGeneratedTemplate = viewModel::saveGeneratedCouncilTemplate,
            onDismiss = {
                showImmersiveEntryDialog = false
                unlockOrientation()
            },
            onStartLive = {
                showImmersiveEntryDialog = false
                immersiveSessionActive = true
                lockLandscape()
                viewModel.startLiveCommentary()
                viewModel.startAiAudience()
                viewModel.startLiveSpeech()
            },
            onStartCouncil = { config ->
                showImmersiveEntryDialog = false
                immersiveSessionActive = true
                lockLandscape()
                viewModel.startCouncilMode(config)
            }
        )
    }

    appUpdatePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAppUpdatePrompt,
            title = { Text("发现新版本") },
            text = {
                val fingerprint = remember(prompt.apkSha256) {
                    prompt.apkSha256.take(12)
                }
                Text(
                    buildString {
                        appendLine("当前版本 ${prompt.currentVersion}")
                        appendLine("最新版本 ${prompt.latestVersion}")
                        if (prompt.isVerified) {
                            appendLine("更新信息已验签")
                        }
                        appendLine("APK 指纹 ${fingerprint}...")
                        prompt.releaseNotes?.takeIf { it.isNotBlank() }?.let {
                            appendLine()
                            appendLine(it.trim())
                        }
                    }.trim()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetUrl = prompt.downloadUrl ?: prompt.downloadPageUrl
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                Toast.makeText(context, "无法打开下载页面。", Toast.LENGTH_SHORT).show()
                            }
                        viewModel.dismissAppUpdatePrompt()
                    }
                ) {
                    Text("立即更新")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAppUpdatePrompt) {
                    Text("稍后")
                }
            }
        )
    }

    val pendingGatewayPairingRequest = gatewayPairingRequests.firstOrNull {
        it.status == GatewayPairingRequestStatus.Pending
    }
    if (pendingGatewayPairingRequest != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("允许跨端 Agent 连接？") },
            text = {
                Text(
                    listOf(
                        "Agent：${pendingGatewayPairingRequest.bridgeName}",
                        "来源：${pendingGatewayPairingRequest.sourceHost ?: "同一局域网设备"}",
                        "批准后，它将通过 Watcher MCP 调用本机视觉与任务能力。"
                    ).joinToString("\n")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.approveGatewayPairingRequest(pendingGatewayPairingRequest.id)
                    }
                ) {
                    Text("允许")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.rejectGatewayPairingRequest(pendingGatewayPairingRequest.id)
                    }
                ) {
                    Text("拒绝")
                }
            }
        )
    }

    val exitImmersiveRoom: () -> Unit = remember(context, stopCurrentImmersiveMode) {
        {
            immersiveSessionActive = false
            stopCurrentImmersiveMode()
            unlockOrientation()
            Unit
        }
    }

    if (immersiveSessionActive) {
        when (interactionMode) {
            InteractionMode.Live -> {
                LiveRoomScreen(
                    streamState = streamState,
                    isPlaying = isStreamPlaying,
                    settings = settings,
                    commentaryState = liveCommentaryState,
                    aiAudienceState = aiAudienceState,
                    danmakuFlow = viewModel.danmakuFlow,
                    audiences = aiAudiences,
                    onPlayingChange = viewModel::setStreamPlaying,
                    onReconnectStream = viewModel::reconnectStream,
                    onCaptureSnapshot = captureSnapshot,
                    speechState = liveSpeechState,
                    onMicToggle = viewModel::setLiveSpeechMicEnabled,
                    onResetLiveRoom = viewModel::resetLiveRoom,
                    onSaveAudience = viewModel::saveAudience,
                    onExitLiveRoom = exitImmersiveRoom,
                )
                return
            }

            InteractionMode.Council -> {
                CouncilModeScreen(
                    streamState = streamState,
                    isPlaying = isStreamPlaying,
                    settings = settings,
                    commentaryState = liveCommentaryState,
                    speechState = liveSpeechState,
                    councilState = councilState,
                    onPlayingChange = viewModel::setStreamPlaying,
                    onReconnectStream = viewModel::reconnectStream,
                    onCaptureSnapshot = captureSnapshot,
                    onMicToggle = viewModel::setLiveSpeechMicEnabled,
                    onTriggerAnalysis = { viewModel.triggerCouncilAnalysis("manual_ui") },
                    onExit = exitImmersiveRoom
                )
                return
            }

            InteractionMode.Off -> Unit
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            WorkspaceBackdrop(
                pagerPosition = pagerPosition,
                modifier = Modifier.fillMaxSize()
            )
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val pageOffset = calculatePageOffset(
                    pagerPosition = pagerPosition,
                    page = page
                )
                when (HubPage.fromPage(page)) {
                    HubPage.Monitor -> MonitorWorkbenchPage(
                        settings = settings,
                        streamState = streamState,
                        isStreamPlaying = isStreamPlaying,
                        monitorStatus = monitorStatus,
                        currentTask = currentTask,
                        pendingBaselineImagePath = pendingBaselineImagePath,
                        pendingBaselineBase64 = pendingBaselineBase64,
                        monitorTemplates = monitorTemplates,
                        tasks = tasks,
                        monitorLogs = monitorLogs,
                        uiState = uiState,
                        requestText = monitorRequestText,
                        isListening = isListening && voiceTarget == HubPage.Monitor,
                        onRequestTextChange = { monitorRequestText = it },
                        onStartListening = { startListening(HubPage.Monitor) },
                        onAnalyze = { viewModel.analyzeIntent(monitorRequestText.text) },
                        onSaveTask = viewModel::saveCurrentTask,
                        onSaveAndStartConciseMonitoring = viewModel::saveAndStartConciseMonitoring,
                        onStartMonitoring = {
                            if (viewModel.startMonitoring(it)) {
                                navigateTo(HubPage.Hub)
                            }
                        },
                        onPauseMonitoring = viewModel::pauseMonitoring,
                        onResumeMonitoring = viewModel::resumeMonitoring,
                        onStopMonitoring = viewModel::stopMonitoring,
                        onRefreshBaseline = viewModel::refreshBaselineFromCurrentFrame,
                        onClearPendingBaselineImage = viewModel::clearPendingMonitorBaselineImage,
                        onPickBaselineImage = {
                            baselineImagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onApplyMonitorTemplate = { templateId ->
                            viewModel.applyMonitorTemplate(templateId)
                            monitorRequestText = TextFieldValue()
                        },
                        onLoadTask = {
                            monitorRequestText = TextFieldValue(it.userInput)
                            viewModel.loadTask(it)
                        },
                        onDeleteTask = viewModel::deleteTask,
                        onCopyJson = {
                            currentTask?.let {
                                clipboardManager.setText(AnnotatedString(buildMonitorTaskJson(it)))
                                Toast.makeText(context, "Monitor task JSON copied.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onPlayingChange = viewModel::setStreamPlaying,
                        onReconnectStream = viewModel::reconnectStream,
                        onCaptureSnapshot = captureSnapshot,
                        onOpenSettings = { showSettingsDialog = true },
                        onOpenAgentConfig = { context.startActivity(AgentConfigActivity.createIntent(context)) },
                        onOpenWalletConfig = { context.startActivity(ApiWalletActivity.createIntent(context)) },
                        isConciseMode = conciseModeController.isConciseMode(conciseModes, HubPage.Monitor),
                        onConciseModeChange = { updateConciseMode(HubPage.Monitor, it) },
                        rotaryRotationDegrees = sharedRotaryRotationDegrees,
                        onRotaryRotationChange = { sharedRotaryRotationDegrees = it },
                        currentPage = HubPage.Monitor,
                        pageOffset = pageOffset
                    )

                    HubPage.Hub -> HubOverviewPage(
                        settings = settings,
                        streamState = streamState,
                        isStreamPlaying = isStreamPlaying,
                        monitorStatus = monitorStatus,
                        currentTask = currentTask,
                        currentVideoTask = currentVideoTask,
                        videoProcessingStatus = videoProcessingStatus,
                        onPlayingChange = viewModel::setStreamPlaying,
                        onReconnectStream = viewModel::reconnectStream,
                        onCaptureSnapshot = captureSnapshot,
                        onOpenSettings = { showSettingsDialog = true },
                        onOpenAgentConfig = { context.startActivity(AgentConfigActivity.createIntent(context)) },
                        onOpenWalletConfig = { context.startActivity(ApiWalletActivity.createIntent(context)) },
                        isConciseMode = conciseModeController.isConciseMode(conciseModes, HubPage.Hub),
                        onConciseModeChange = { updateConciseMode(HubPage.Hub, it) },
                        rotaryRotationDegrees = sharedRotaryRotationDegrees,
                        onRotaryRotationChange = { sharedRotaryRotationDegrees = it },
                        onNavigateMonitor = { navigateTo(HubPage.Monitor) },
                        onNavigateAnalysis = { navigateTo(HubPage.Analysis) },
                        onNavigateMultiDevice = {
                            multiDeviceLauncher.launch(MultiDeviceActivity.createIntent(context))
                        },
                        isGatewayRunning = gatewayRunning,
                        pairedAgentCount = gatewayPairingBindings.size,
                        pendingPairingCount = gatewayPairingRequests.count {
                            it.status == GatewayPairingRequestStatus.Pending
                        },
                        onNavigateDigitalLifeCard = {
                            viewModel.setStreamPlaying(false)
                            viewModel.updateVideoFrame(null)
                            viewModel.updateStreamSource(StreamSource.None)
                            digitalLifeCardLauncher.launch(DigitalLifeCardActivity.createIntent(context))
                        },
                        onNavigateFitnessCompanion = {
                            viewModel.setStreamPlaying(false)
                            viewModel.updateVideoFrame(null)
                            viewModel.updateStreamSource(StreamSource.None)
                            fitnessCompanionLauncher.launch(FitnessCompanionActivity.createIntent(context))
                        },
                        onNavigateLiteRt = {
                            liteRtLauncher.launch(LiteRtActivity.createIntent(context))
                        },
                        onNavigateLocalAgent = {
                            localAgentLauncher.launch(LocalAgentActivity.createIntent(context))
                        },
                        onNavigatePoseEstimation = {
                            poseEstimationLauncher.launch(PoseEstimationActivity.createIntent(context))
                        },
                        onNavigateBackScreenPush = {
                            viewModel.setStreamPlaying(false)
                            backScreenPushLauncher.launch(BackScreenPushActivity.createIntent(context))
                        },
                        onOpenQuickNavigation = openQuickNavigation,
                        onQuickNavigationAnchorBoundsChanged = { bounds ->
                            quickNavigationAnchorBounds = bounds
                        },
                        showQuickNavigationHint = quickNavigationHintVisible,
                        currentPage = HubPage.Hub,
                        pageOffset = pageOffset
                    )

                    HubPage.Analysis -> {
                        val analysisIsConcise = conciseModeController.isConciseMode(conciseModes, HubPage.Analysis)
                        Crossfade(
                            targetState = analysisIsConcise,
                            animationSpec = tween(360),
                            label = "analysisModeCrossfade"
                        ) { concise ->
                            if (concise) {
                                ClassroomRecordingPage(
                                    settings = settings,
                                    streamState = streamState,
                                    isStreamPlaying = isStreamPlaying,
                                    status = videoProcessingStatus,
                                    recentRuns = recentClassroomRuns,
                                    selectedRunId = selectedVideoRunId,
                                    recordingInput = classroomRecordingInput,
                                    onPlayingChange = viewModel::setStreamPlaying,
                                    onReconnectStream = viewModel::reconnectStream,
                                    onCaptureSnapshot = captureSnapshot,
                                    onOpenSettings = { showSettingsDialog = true },
                                    onOpenWalletConfig = { context.startActivity(ApiWalletActivity.createIntent(context)) },
                                    onOpenAgentConfig = { context.startActivity(AgentConfigActivity.createIntent(context)) },
                                    onPickTestVideo = { classroomTestVideoPicker.launch("video/*") },
                                    onClearTestVideo = viewModel::clearClassroomTestVideo,
                                    onCleanupTestVideoCache = viewModel::cleanupClassroomTestVideoCache,
                                    onStartClassroomRecording = { courseName, duration, speechConfig, recordingInput ->
                                        viewModel.updateStreamSource(streamState.source)
                                        viewModel.startClassroomRecording(courseName, duration, speechConfig, recordingInput)
                                    },
                                    onStopProcessing = viewModel::stopVideoProcessing,
                                    onNewRecording = viewModel::resetClassroomRecording,
                                    onOpenClassroomRun = { runId ->
                                        viewModel.openClassroomRecordingRun(runId)
                                        updateConciseMode(HubPage.Analysis, true)
                                        navigateTo(HubPage.Analysis)
                                    },
                                    onToggleTranscriptSelection = viewModel::toggleClassroomTranscriptSelection,
                                    onAnswerInlineQuestion = viewModel::answerClassroomInlineQuestion,
                                    onDismissInlineQuestion = viewModel::dismissClassroomInlineQuestion,
                                    onAskClassroomNoteFollowup = viewModel::askClassroomNoteFollowup,
                                    onRetryClassroomNoteFollowup = viewModel::retryClassroomNoteFollowup,
                                    onRegenerateClassroomNoteFollowup = viewModel::regenerateClassroomNoteFollowupWithFinalNote,
                                      onDeleteClassroomNoteFollowup = viewModel::deleteClassroomNoteFollowup,
                                      onAppendClassroomNoteMaterials = {
                                          classroomNoteMaterialPicker.launch(CLASSROOM_NOTE_MATERIAL_MIME_TYPES)
                                      },
                                      onOpenRunDetail = { runId ->
                                          viewModel.selectVideoRun(runId)
                                        updateConciseMode(HubPage.Analysis, false)
                                        navigateTo(HubPage.Analysis)
                                    },
                                    onCopyNote = { note ->
                                        clipboardManager.setText(AnnotatedString(note))
                                        Toast.makeText(context, "课堂笔记已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    isConciseMode = true,
                                    onConciseModeChange = { updateConciseMode(HubPage.Analysis, it) },
                                    rotaryRotationDegrees = sharedRotaryRotationDegrees,
                                    onRotaryRotationChange = { sharedRotaryRotationDegrees = it },
                                    currentPage = HubPage.Analysis,
                                    pageOffset = pageOffset
                                )
                            } else {
                                VideoAnalysisWorkbenchPage(
                                    settings = settings,
                                    streamState = streamState,
                                    isStreamPlaying = isStreamPlaying,
                                    currentTask = currentVideoTask,
                                    videoTemplates = videoTemplates,
                                    tasks = videoTasks,
                                    recentRuns = recentVideoRuns,
                                    status = videoProcessingStatus,
                                    planUiState = videoPlanUiState,
                                    selectedRunId = selectedVideoRunId,
                                    selectedRunEvents = selectedVideoRunEvents,
                                    requestText = videoRequestText,
                                    isListening = isListening && voiceTarget == HubPage.Analysis,
                                    onRequestTextChange = { videoRequestText = it },
                                    onStartListening = { startListening(HubPage.Analysis) },
                                    onAnalyze = { viewModel.analyzeVideoIntent(videoRequestText.text) },
                                    onApplyTemplate = { templateId ->
                                        viewModel.applyVideoTemplate(templateId)
                                        videoRequestText = TextFieldValue()
                                    },
                                    onSaveTask = viewModel::saveVideoTask,
                                    onStartProcessing = {
                                        viewModel.launchVideoProcessing(
                                            task = it,
                                            streamingOutputEnabled = settings.videoAnalysisStreamingEnabled
                                        )
                                        navigateTo(HubPage.Hub)
                                    },
                                    onStopProcessing = viewModel::stopVideoProcessing,
                                    onLoadTask = {
                                        videoRequestText = TextFieldValue(it.userInput)
                                        viewModel.loadVideoTask(it)
                                    },
                                    onDeleteTask = viewModel::deleteVideoTask,
                                    onSelectRun = viewModel::selectVideoRun,
                                    onCopyJson = {
                                        currentVideoTask?.let {
                                            clipboardManager.setText(AnnotatedString(buildVideoTaskJson(it)))
                                            Toast.makeText(context, "Video task JSON copied.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onPlayingChange = viewModel::setStreamPlaying,
                                    onReconnectStream = viewModel::reconnectStream,
                                    onCaptureSnapshot = captureSnapshot,
                                    onOpenSettings = { showSettingsDialog = true },
                                    onOpenAgentConfig = { context.startActivity(AgentConfigActivity.createIntent(context)) },
                                    onOpenWalletConfig = { context.startActivity(ApiWalletActivity.createIntent(context)) },
                                    isConciseMode = false,
                                    onConciseModeChange = { updateConciseMode(HubPage.Analysis, it) },
                                    rotaryRotationDegrees = sharedRotaryRotationDegrees,
                                    onRotaryRotationChange = { sharedRotaryRotationDegrees = it },
                                    currentPage = HubPage.Analysis,
                                    pageOffset = pageOffset
                                )
                            }
                        }
                    }

                    HubPage.History -> HistoryWorkbenchPage(
                        historyRecords = historyRecords,
                        storageSummary = storageSummary,
                        selectedRecord = selectedHistoryRecord,
                        selectedDetail = selectedHistoryDetail,
                        activeVideoReportDetail = activeVideoHistoryReportDetail,
                        onSelectRecord = viewModel::selectHistoryRecord,
                        onDeleteRecord = viewModel::deleteHistoryRecord,
                        onSaveAsTemplate = { detail ->
                            viewModel.saveHistoryAsTemplate(detail) { _, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onShareAsTemplate = { detail ->
                            viewModel.shareHistoryAsTemplate(detail) { shareText ->
                                if (shareText != null) {
                                    clipboardManager.setText(AnnotatedString(shareText))
                                    Toast.makeText(context, "已复制到剪贴板，可粘贴分享", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "源任务已被删除，无法分享", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onOpenVideoReport = viewModel::openVideoHistoryReport,
                        onCloseVideoReport = viewModel::closeVideoHistoryReport,
                        onLoadFullHistoryDetail = viewModel::loadFullHistoryDetail,
                        onOpenSettings = { showSettingsDialog = true },
                        onOpenAgentConfig = { context.startActivity(AgentConfigActivity.createIntent(context)) },
                        onOpenWalletConfig = { context.startActivity(ApiWalletActivity.createIntent(context)) },
                        isConciseMode = conciseModeController.isConciseMode(conciseModes, HubPage.History),
                        onConciseModeChange = { updateConciseMode(HubPage.History, it) },
                        rotaryRotationDegrees = sharedRotaryRotationDegrees,
                        onRotaryRotationChange = { sharedRotaryRotationDegrees = it },
                        currentPage = HubPage.History,
                        isVisible = currentPage == HubPage.History,
                        pageOffset = pageOffset
                    )

                    HubPage.Templates -> TemplateManagementPage(
                        monitorTemplates = monitorTemplates,
                        videoTemplates = videoTemplates,
                        councilTemplates = councilTemplates,
                        councilExperts = councilExperts,
                        providers = llmProviders,
                        audiences = aiAudiences,
                        onUpdateMonitorTemplate = viewModel::updateMonitorTemplate,
                        onUpdateVideoTemplate = viewModel::updateVideoTemplate,
                        onUpdateCouncilTemplate = viewModel::updateCouncilTemplate,
                        onResetMonitorTemplate = viewModel::resetMonitorTemplate,
                        onResetVideoTemplate = viewModel::resetVideoTemplate,
                        onResetCouncilTemplate = viewModel::resetCouncilTemplate,
                        onCreateCouncilExpert = viewModel::addCouncilExpert,
                        onSaveCouncilExpert = viewModel::saveCouncilExpert,
                        onDuplicateCouncilExpert = viewModel::duplicateCouncilExpert,
                        onResetCouncilExpert = viewModel::resetCouncilExpert,
                        onDeleteCouncilExpert = viewModel::deleteCouncilExpert,
                        onRestoreMissingCouncilExperts = viewModel::restoreMissingCouncilExperts,
                        onDeleteMonitorTemplate = viewModel::deleteMonitorTemplate,
                        onDeleteVideoTemplate = viewModel::deleteVideoTemplate,
                        onDeleteCouncilTemplate = viewModel::deleteCouncilTemplate,
                        onOpenSettings = { showSettingsDialog = true },
                        onOpenAgentConfig = { context.startActivity(AgentConfigActivity.createIntent(context)) },
                        onOpenWalletConfig = { context.startActivity(ApiWalletActivity.createIntent(context)) },
                        isConciseMode = conciseModeController.isConciseMode(conciseModes, HubPage.Templates),
                        onConciseModeChange = { updateConciseMode(HubPage.Templates, it) },
                        rotaryRotationDegrees = sharedRotaryRotationDegrees,
                        onRotaryRotationChange = { sharedRotaryRotationDegrees = it },
                        onSaveProvider = viewModel::saveProvider,
                        onDeleteProvider = viewModel::deleteProvider,
                        onSaveAudience = viewModel::saveAudience,
                        onDeleteAudience = viewModel::deleteAudience,
                        getLastPost = viewModel::getAudienceLastPost,
                        getLastResponse = viewModel::getAudienceLastResponse,
                        getAgentDebugSnapshot = viewModel::getAgentAudienceDebugSnapshot,
                        getWallet = viewModel::getAudienceWallet,
                        setWallet = viewModel::setAudienceWallet,
                        getCouncilExpertLastPrompt = viewModel::getCouncilExpertLastPrompt,
                        getCouncilExpertLastResponse = viewModel::getCouncilExpertLastResponse,
                        getExpertSessionMemory = viewModel::getCouncilExpertSessionMemory,
                        getExpertKnowledge = viewModel::getExpertKnowledge,
                        onDeleteKnowledge = viewModel::deleteKnowledgeEntry,
                        getMemorySnapshot = viewModel::getMemorySnapshot,
                        commentaryState = liveCommentaryState,
                        onExportMonitor = viewModel::exportMonitorTemplate,
                        onExportVideo = viewModel::exportVideoTemplate,
                        onExportCouncil = viewModel::exportCouncilTemplate,
                        onExportCouncilExpert = viewModel::exportCouncilExpertTemplate,
                        onImportTemplate = viewModel::importTemplate,
                        currentPage = HubPage.Templates,
                        pageOffset = pageOffset
                    )
                }
            }

            BottomGlassScrim(
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            SharedWorkspaceHeader(
                pagerPosition = pagerPosition,
                onNavigate = navigateTo,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (showPagerCoachmark) {
                SwipeCoachmarkOverlay(onDismiss = dismissCoachmark)
            }

        }
    }

    if (startupBlockingDialogsAllowed) {
        QuickNavigationDialog(
            state = intentRouterState,
            onInputChange = intentRouterViewModel::updateInput,
            onSubmit = intentRouterViewModel::submit,
            onExampleSelected = intentRouterViewModel::selectExamplePrompt,
            onShortcutSelected = intentRouterViewModel::selectShortcut,
            anchorBounds = quickNavigationAnchorBounds,
            onDismiss = dismissQuickNavigation
        )
    }

    if (showSettingsDialog) {
        VideoStreamSettingsDialog(
            settings = streamSettings,
            scanState = streamScanUiState,
            provisionState = deviceProvisionUiState,
            onDismiss = {
                previewRotationDegrees = null
                previewMirrorHorizontally = null
                viewModel.clearStreamDeviceScan()
                viewModel.clearDeviceProvisionState()
                showSettingsDialog = false
            },
            onScanDevices = viewModel::scanVideoStreamDevices,
            onLoadDeviceInfo = viewModel::refreshDeviceProvisionInfo,
            onScanProvisionWifi = viewModel::scanProvisioningWifi,
            onSubmitProvisionWifi = viewModel::submitProvisioningWifi,
            onClearProvisionedWifi = viewModel::clearProvisionedWifi,
            onPreviewFrameOrientation = { rotationDegrees, mirrorHorizontally ->
                previewRotationDegrees = rotationDegrees
                previewMirrorHorizontally = mirrorHorizontally
            },
            onSave = {
                val savedSettings = it.normalized()
                previewRotationDegrees = savedSettings.rotationDegrees
                previewMirrorHorizontally = savedSettings.mirrorHorizontally
                viewModel.saveVideoStreamSettings(savedSettings)
                viewModel.clearStreamDeviceScan()
                viewModel.clearDeviceProvisionState()
                showSettingsDialog = false
                Toast.makeText(context, "视频与监控设置已保存", Toast.LENGTH_SHORT).show()
            }
        )
    }

}

@Composable
private fun rememberPagerNavigator(
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    onNavigationRequested: (HubPage) -> Unit
): (HubPage) -> Unit {
    return remember(pagerState, coroutineScope, onNavigationRequested) {
        var navigationJob: Job? = null
        var lastNavigationAtMillis = 0L
        { page ->
            onNavigationRequested(page)
            navigationJob?.cancel()
            navigationJob = coroutineScope.launch {
                val now = System.currentTimeMillis()
                val currentPosition = if (pagerState.isScrollInProgress) {
                    calculatePagerPosition(
                        currentPage = pagerState.currentPage,
                        currentPageOffsetFraction = pagerState.currentPageOffsetFraction
                    )
                } else {
                    pagerState.currentPage.toFloat()
                }
                val targetPosition = page.pageIndex.toFloat()
                val pageDistance = abs(targetPosition - currentPosition)
                if (pageDistance < 0.01f) return@launch

                val isRapidRetap = now - lastNavigationAtMillis < 180L
                val shouldSnap = pagerState.isScrollInProgress || isRapidRetap
                lastNavigationAtMillis = now

                if (shouldSnap) {
                    pagerState.scrollToPage(page.pageIndex)
                } else {
                    pagerState.animateScrollToPage(
                        page = page.pageIndex,
                        animationSpec = tween(
                            durationMillis = 220 + (((pageDistance.toInt()) - 1).coerceAtLeast(0) * 70),
                            easing = LinearOutSlowInEasing
                        )
                    )
                }
            }
        }
    }
}

private fun isStreamPreviewPageVisible(pagerPosition: Float): Boolean {
    return listOf(HubPage.Monitor, HubPage.Hub, HubPage.Analysis).any { page ->
        kotlin.math.abs(pagerPosition - page.pageIndex) < 0.98f
    }
}

@Composable
private fun rememberSnapshotCapturer(
    viewModel: IntentViewModel,
    toast: (String) -> Unit
): (Bitmap) -> Unit {
    return remember(viewModel, toast) {
        { bitmap ->
            val path = viewModel.saveSnapshot(bitmap)
            val message = if (path != null) {
                "截图已保存至相册"
            } else {
                "保存截图失败"
            }
            toast(message)
        }
    }
}

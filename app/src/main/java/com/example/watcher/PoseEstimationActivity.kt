package com.example.watcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watcher.data.local.pose.PoseVideoSession
import com.example.watcher.ui.screens.DanceFramePickerScreen
import com.example.watcher.ui.screens.DancePracticeScreen
import com.example.watcher.ui.screens.FitnessScreen
import com.example.watcher.ui.screens.DanceLearningScreen
import com.example.watcher.ui.screens.DancePosePlaybackScreen
import com.example.watcher.ui.screens.DanceProcessingScreen
import com.example.watcher.ui.screens.PoseEstimationScreen
import com.example.watcher.ui.screens.PoseScenarioSelectScreen
import com.example.watcher.ui.theme.WatcherTheme
import com.example.watcher.ui.viewmodel.DanceLearningViewModel
import com.example.watcher.ui.viewmodel.PoseEstimationViewModel

class PoseEstimationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatcherTheme {
                PoseEstimationRouter(onClose = ::finish)
            }
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, PoseEstimationActivity::class.java)
        }
    }
}

private enum class PoseScreen {
    ScenarioSelect, Realtime, DanceLearning, DanceFramePicker, DanceProcessing, DancePlayback, DancePractice, Fitness
}

@Composable
private fun PoseEstimationRouter(onClose: () -> Unit) {
    var currentScreen by remember { mutableStateOf(PoseScreen.ScenarioSelect) }
    var playbackSession by remember { mutableStateOf<PoseVideoSession?>(null) }
    var isFirstPassProcessing by remember { mutableStateOf(true) }

    when (currentScreen) {
        PoseScreen.ScenarioSelect -> {
            PoseScenarioSelectScreen(
                onNavigateRealtime = { currentScreen = PoseScreen.Realtime },
                onNavigateDanceLearning = { currentScreen = PoseScreen.DanceLearning },
                onNavigateFitness = { currentScreen = PoseScreen.Fitness },
                onClose = onClose
            )
        }

        PoseScreen.Realtime -> {
            val viewModel: PoseEstimationViewModel = viewModel()
            val detectorState by viewModel.detectorState.collectAsStateWithLifecycle()
            val detectorConfig by viewModel.detectorConfig.collectAsStateWithLifecycle()
            val poseResult by viewModel.poseResult.collectAsStateWithLifecycle()
            val performanceStats by viewModel.performanceStats.collectAsStateWithLifecycle()
            val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

            PoseEstimationScreen(
                detectorState = detectorState,
                detectorConfig = detectorConfig,
                poseResult = poseResult,
                performanceStats = performanceStats,
                errorMessage = errorMessage,
                onInitDetector = viewModel::initDetector,
                onProcessFrame = viewModel::processFrame,
                onUpdateModelComplexity = viewModel::updateModelComplexity,
                onUpdateDelegateType = viewModel::updateDelegateType,
                onUpdateMaxNumPoses = viewModel::updateMaxNumPoses,
                onUpdateDetectionConfidence = viewModel::updateDetectionConfidence,
                onClose = { currentScreen = PoseScreen.ScenarioSelect }
            )
        }

        PoseScreen.DanceLearning -> {
            val viewModel: DanceLearningViewModel = viewModel()
            val sessions by viewModel.sessions.collectAsStateWithLifecycle()
            val pendingPath by viewModel.pendingVideoPath.collectAsStateWithLifecycle()
            val segResult by viewModel.segmentationResult.collectAsStateWithLifecycle()

            // Navigate to FramePicker once video is ready
            LaunchedEffect(pendingPath) {
                if (pendingPath != null) {
                    currentScreen = PoseScreen.DanceFramePicker
                }
            }

            // Show segmentation result dialog
            segResult?.let { msg ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viewModel.clearSegmentationResult() },
                    title = { androidx.compose.material3.Text("动作切分") },
                    text = { androidx.compose.material3.Text(msg) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { viewModel.clearSegmentationResult() }) {
                            androidx.compose.material3.Text("好的")
                        }
                    }
                )
            }

            DanceLearningScreen(
                sessions = sessions,
                onPickVideo = { uri -> viewModel.prepareVideo(uri) },
                onRenameSession = viewModel::renameSession,
                onDeleteSession = viewModel::deleteSession,
                onProcessSession = { session ->
                    playbackSession = session
                    isFirstPassProcessing = session.processingStatus == PoseVideoSession.ProcessingStatus.PENDING
                    currentScreen = PoseScreen.DanceProcessing
                },
                onPreviewSession = { session ->
                    playbackSession = session
                    currentScreen = PoseScreen.DancePlayback
                },
                onSegmentSession = { session ->
                    viewModel.runSegmentation(session)
                },
                onPracticeSession = { session ->
                    playbackSession = session
                    currentScreen = PoseScreen.DancePractice
                },
                onBack = { currentScreen = PoseScreen.ScenarioSelect }
            )
        }

        PoseScreen.DanceFramePicker -> {
            val viewModel: DanceLearningViewModel = viewModel()
            val durationMs by viewModel.pendingVideoDurationMs.collectAsStateWithLifecycle()
            val pendingPath by viewModel.pendingVideoPath.collectAsStateWithLifecycle()

            // If video was cleared (cancel/error), go back
            LaunchedEffect(pendingPath) {
                if (pendingPath == null && currentScreen == PoseScreen.DanceFramePicker) {
                    currentScreen = PoseScreen.DanceLearning
                }
            }

            DanceFramePickerScreen(
                videoDurationMs = durationMs,
                videoReady = pendingPath != null && durationMs > 0,
                onExtractFrame = { timeMs -> viewModel.extractFrameAtTime(timeMs) },
                onConfirm = { result ->
                    viewModel.confirmImport(result)
                    currentScreen = PoseScreen.DanceLearning
                },
                onCancel = {
                    viewModel.cancelImport()
                    currentScreen = PoseScreen.DanceLearning
                }
            )
        }

        PoseScreen.DanceProcessing -> {
            val session = playbackSession
            val viewModel: DanceLearningViewModel = viewModel()
            if (session != null) {
                DanceProcessingScreen(
                    session = session,
                    isFirstPass = isFirstPassProcessing,
                    onComplete = {
                        viewModel.markSessionReady(session.id)
                        currentScreen = PoseScreen.DanceLearning
                    },
                    onStop = {
                        currentScreen = PoseScreen.DanceLearning
                    }
                )
            } else {
                currentScreen = PoseScreen.DanceLearning
            }
        }

        PoseScreen.DancePlayback -> {
            val session = playbackSession
            if (session != null) {
                DancePosePlaybackScreen(
                    sessionId = session.id,
                    videoPath = session.sourceVideoPath,
                    title = session.title,
                    clipStartMs = session.clipStartMs,
                    clipEndMs = session.clipEndMs,
                    onBack = { currentScreen = PoseScreen.DanceLearning }
                )
            } else {
                currentScreen = PoseScreen.DanceLearning
            }
        }

        PoseScreen.Fitness -> {
            val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
            LaunchedEffect(Unit) {
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            FitnessScreen(
                onBack = {
                    // Navigate first (triggers compose disposal & engine release),
                    // then restore orientation after a brief delay
                    currentScreen = PoseScreen.ScenarioSelect
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }, 300)
                }
            )
        }

        PoseScreen.DancePractice -> {
            val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
            // Force landscape
            LaunchedEffect(Unit) {
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            val session = playbackSession
            if (session != null) {
                DancePracticeScreen(
                    session = session,
                    onBack = {
                        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        currentScreen = PoseScreen.DanceLearning
                    }
                )
            } else {
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                currentScreen = PoseScreen.DanceLearning
            }
        }
    }
}

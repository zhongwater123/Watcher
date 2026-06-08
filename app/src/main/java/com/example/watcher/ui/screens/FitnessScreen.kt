package com.example.watcher.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.watcher.data.local.pose.ArUcoCalibrator
import com.example.watcher.data.local.pose.DelegateType
import org.opencv.android.OpenCVLoader
import com.example.watcher.data.local.pose.ModelComplexity
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.local.pose.PoseDetectorConfig
import com.example.watcher.data.local.pose.PoseEstimationEngine
import com.example.watcher.ui.components.PoseOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AI Fitness dual-stream screen with ArUco calibration state machine.
 *
 * State flow: CALIBRATING → CALIBRATED → LOADING_ENGINES → READY
 */

enum class FitnessState {
    CALIBRATING,      // Dual streams active, detecting ArUco marker
    CALIBRATED,       // Calibration result shown (2s pause)
    LOADING_ENGINES,  // Initializing MediaPipe engines
    READY             // Full dual-pose detection mode
}

@Composable
fun FitnessScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Reserve the ESP32 stream — releases home page MjpegStreamPlayer connection
    DisposableEffect(Unit) {
        com.example.watcher.data.repository.StreamReservation.reserve("fitness")
        onDispose { com.example.watcher.data.repository.StreamReservation.release("fitness") }
    }

    // Initialize OpenCV (required before ArUco detection)
    var openCvReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        openCvReady = OpenCVLoader.initLocal()
        if (!openCvReady) {
            android.util.Log.e("Fitness", "OpenCV initialization FAILED")
        } else {
            android.util.Log.i("Fitness", "OpenCV initialized successfully")
        }
    }

    // State machine
    var fitnessState by remember { mutableStateOf(FitnessState.CALIBRATING) }

    // ArUco calibration (created only after OpenCV is loaded)
    var calibrator by remember { mutableStateOf<ArUcoCalibrator?>(null) }
    LaunchedEffect(openCvReady) {
        if (openCvReady && calibrator == null) {
            calibrator = ArUcoCalibrator(markerSizeCm = 5f)
            android.util.Log.i("Fitness", "ArUcoCalibrator created")
        }
    }
    var calibrationResult by remember { mutableStateOf<ArUcoCalibrator.CalibrationResult?>(null) }
    var frontMarkerDetected by remember { mutableStateOf(false) }
    var sideMarkerDetected by remember { mutableStateOf(false) }

    // Shared stream state (used in both CALIBRATING and READY)
    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sideBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sideConnected by remember { mutableStateOf(false) }

    // MediaPipe state (only used in READY)
    var frontPoseResult by remember { mutableStateOf<PoseDetectionResult?>(null) }
    var frontFps by remember { mutableIntStateOf(0) }
    var frontInferenceMs by remember { mutableLongStateOf(0L) }
    var sidePoseResult by remember { mutableStateOf<PoseDetectionResult?>(null) }
    var sideFps by remember { mutableIntStateOf(0) }
    var sideInferenceMs by remember { mutableLongStateOf(0L) }

    // Engine references (lazy init in LOADING_ENGINES)
    val frontEngine = remember { PoseEstimationEngine(context) }
    val sideEngine = remember { PoseEstimationEngine(context) }
    var frontEngineReady by remember { mutableStateOf(false) }
    var sideEngineReady by remember { mutableStateOf(false) }
    val frontAlive = remember { AtomicBoolean(true) }
    val sideAlive = remember { AtomicBoolean(true) }
    val sideProcessing = remember { AtomicBoolean(false) }

    // CALIBRATED → auto transition to LOADING_ENGINES after 2s
    LaunchedEffect(fitnessState) {
        if (fitnessState == FitnessState.CALIBRATED) {
            delay(2000L)
            fitnessState = FitnessState.LOADING_ENGINES
        }
    }

    // LOADING_ENGINES → init MediaPipe
    LaunchedEffect(fitnessState) {
        if (fitnessState != FitnessState.LOADING_ENGINES) return@LaunchedEffect
        android.util.Log.i("Fitness", "LOADING_ENGINES: initializing front engine (GPU, LITE)...")
        withContext(Dispatchers.IO) {
            runCatching {
                frontEngine.initializeForVideo(
                    config = PoseDetectorConfig(
                        modelComplexity = ModelComplexity.LITE,
                        delegateType = DelegateType.GPU
                    )
                )
                frontEngineReady = true
                android.util.Log.i("Fitness", "Front engine ready!")
            }.onFailure {
                android.util.Log.e("Fitness", "Front engine init FAILED: ${it.message}", it)
            }

            delay(1000L)

            android.util.Log.i("Fitness", "Initializing side engine (CPU, LITE)...")
            runCatching {
                sideEngine.initializeForVideo(
                    config = PoseDetectorConfig(
                        modelComplexity = ModelComplexity.LITE,
                        delegateType = DelegateType.CPU
                    )
                )
                sideEngineReady = true
                android.util.Log.i("Fitness", "Side engine ready!")
            }.onFailure {
                android.util.Log.e("Fitness", "Side engine init FAILED: ${it.message}", it)
            }
        }
        if (frontEngineReady && sideEngineReady) {
            fitnessState = FitnessState.READY
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            android.util.Log.i("Fitness", "Disposing engines...")
            frontAlive.set(false)
            sideAlive.set(false)
            Thread.sleep(150)
            runCatching { frontEngine.release() }
            runCatching { sideEngine.release() }
            runCatching { calibrator?.release() }
        }
    }

    // Main layout
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (fitnessState) {
            FitnessState.CALIBRATING -> CalibrationView(
                frontBitmap = frontBitmap,
                sideBitmap = sideBitmap,
                sideConnected = sideConnected,
                frontDetected = frontMarkerDetected,
                sideDetected = sideMarkerDetected,
                onFrontFrame = { frontBitmap = it },
                onSideFrame = { sideBitmap = it },
                onSideConnected = { sideConnected = it },
                calibrator = calibrator,
                onFrontDetected = { frontMarkerDetected = it },
                onSideDetected = { sideMarkerDetected = it },
                onCalibrated = { result ->
                    calibrationResult = result
                    fitnessState = FitnessState.CALIBRATED
                },
                onBack = onBack,
                modifier = Modifier.fillMaxSize()
            )

            FitnessState.CALIBRATED -> CalibratedView(
                result = calibrationResult,
                modifier = Modifier.fillMaxSize()
            )

            FitnessState.LOADING_ENGINES -> LoadingEnginesView(
                frontReady = frontEngineReady,
                sideReady = sideEngineReady,
                modifier = Modifier.fillMaxSize()
            )

            FitnessState.READY -> ReadyView(
                frontBitmap = frontBitmap,
                sideBitmap = sideBitmap,
                sideConnected = sideConnected,
                frontPoseResult = frontPoseResult,
                sidePoseResult = sidePoseResult,
                frontFps = frontFps,
                sideFps = sideFps,
                frontInferenceMs = frontInferenceMs,
                sideInferenceMs = sideInferenceMs,
                calibrationAngle = calibrationResult?.cameraAngleDeg ?: 0f,
                onFrontFrame = { frontBitmap = it },
                onSideFrame = { sideBitmap = it },
                onSideConnected = { sideConnected = it },
                onFrontPoseResult = { frontPoseResult = it },
                onSidePoseResult = { sidePoseResult = it },
                onFrontFps = { frontFps = it },
                onSideFps = { sideFps = it },
                onFrontInferenceMs = { frontInferenceMs = it },
                onSideInferenceMs = { sideInferenceMs = it },
                frontEngine = frontEngine,
                sideEngine = sideEngine,
                frontAlive = frontAlive,
                sideAlive = sideAlive,
                sideProcessing = sideProcessing,
                sideEngineReady = sideEngineReady,
                onRecalibrate = {
                    frontMarkerDetected = false
                    sideMarkerDetected = false
                    calibrationResult = null
                    fitnessState = FitnessState.CALIBRATING
                },
                onBack = onBack,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// CALIBRATING view
// ══════════════════════════════════════════════════════════════

@Composable
private fun CalibrationView(
    frontBitmap: Bitmap?,
    sideBitmap: Bitmap?,
    sideConnected: Boolean,
    frontDetected: Boolean,
    sideDetected: Boolean,
    onFrontFrame: (Bitmap) -> Unit,
    onSideFrame: (Bitmap) -> Unit,
    onSideConnected: (Boolean) -> Unit,
    calibrator: ArUcoCalibrator?,
    onFrontDetected: (Boolean) -> Unit,
    onSideDetected: (Boolean) -> Unit,
    onCalibrated: (ArUcoCalibrator.CalibrationResult) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ArUco detection shared state
    val latestFrontFrame = remember { AtomicReference<Bitmap?>(null) }
    val latestSideJpeg = remember { AtomicReference<ByteArray?>(null) }

    // Keep latest front frame updated
    LaunchedEffect(frontBitmap) { latestFrontFrame.set(frontBitmap) }

    // MJPEG stream for calibration
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = com.example.watcher.data.local.AppDatabase.getDatabase(context).videoStreamSettingsDao()
            val settings = dao.getSettingsSync()
            val streamUrl = settings?.streamUrl ?: "http://192.168.4.1:81/stream"

            while (isActive) {
                try {
                    val client = OkHttpClient.Builder().build()
                    val request = Request.Builder().url(streamUrl).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        delay(2000L)
                        continue
                    }
                    onSideConnected(true)
                    val stream = BufferedInputStream(response.body?.byteStream() ?: continue)

                    while (isActive) {
                        val jpegBytes = readMjpegFrame(stream) ?: break
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: continue
                        onSideFrame(bitmap)
                        latestSideJpeg.set(jpegBytes)  // Store raw bytes for ArUco detection
                    }
                    response.close()
                } catch (e: Exception) {
                    onSideConnected(false)
                    delay(3000L)
                }
            }
        }
    }

    // ArUco detection loop — continuous polling with multi-frame averaging
    LaunchedEffect(calibrator) {
        val cal = calibrator ?: return@LaunchedEffect
        android.util.Log.i("Fitness", "ArUco detection loop started")
        var detectCount = 0
        val calibrationSamples = mutableListOf<ArUcoCalibrator.CalibrationResult>()
        val requiredSamples = 7  // Collect 7 samples, take median

        withContext(Dispatchers.Default) {
            while (isActive) {
                val sideBytes = latestSideJpeg.get()
                val front = latestFrontFrame.get()

                if (sideBytes == null || front == null) {
                    delay(100)
                    continue
                }

                detectCount++
                if (detectCount % 10 == 1) {
                    android.util.Log.d("Fitness", "ArUco detecting... frame #$detectCount samples=${calibrationSamples.size}/$requiredSamples")
                }

                // Detect side using raw JPEG bytes (bypasses Bitmap conversion issues)
                val sideResult = runCatching { cal.detectFromJpegBytes(sideBytes, isFrontCamera = false) }.getOrNull()
                val sideOk = sideResult != null
                onSideDetected(sideOk)

                // Detect front using Bitmap (works fine from CameraX)
                val frontResult = runCatching { cal.detectInFrame(front, isFrontCamera = true) }.getOrNull()
                val frontOk = frontResult != null
                onFrontDetected(frontOk)

                if (detectCount % 10 == 1) {
                    android.util.Log.d("Fitness", "ArUco: front=$frontOk side=$sideOk")
                }

                // Both detected → collect calibration sample (with outlier filtering)
                if (frontOk && sideOk) {
                    val result = runCatching { cal.calibrateWithBytes(front, sideBytes) }.getOrNull()
                    if (result != null && result.success) {
                        // Outlier filter: discard samples with unreasonable values
                        val isOutlier = if (calibrationSamples.size >= 2) {
                            val prevAngles = calibrationSamples.map { it.cameraAngleDeg }
                            val prevMedian = prevAngles.sorted()[prevAngles.size / 2]
                            val deviation = kotlin.math.abs(result.cameraAngleDeg - prevMedian)
                            // Reject if angle deviates >30° from current median
                            deviation > 30f
                        } else false

                        if (isOutlier) {
                            android.util.Log.w("Fitness", "OUTLIER rejected: angle=${"%.1f".format(result.cameraAngleDeg)} dist=${"%.1f".format(result.cameraDistanceCm)}cm")
                        } else {
                            calibrationSamples.add(result)
                            android.util.Log.i("Fitness", "Sample ${calibrationSamples.size}/$requiredSamples: " +
                                "angle=${"%.1f".format(result.cameraAngleDeg)} dist=${"%.1f".format(result.cameraDistanceCm)}cm")
                        }

                        // Enough samples → compute median and finalize
                        if (calibrationSamples.size >= requiredSamples) {
                            val medianAngle = calibrationSamples.map { it.cameraAngleDeg }.sorted()[requiredSamples / 2]
                            val medianDist = calibrationSamples.map { it.cameraDistanceCm }.sorted()[requiredSamples / 2]
                            val medianHeight = calibrationSamples.map { it.heightDiffCm }.sorted()[requiredSamples / 2]
                            val medianFrontDist = calibrationSamples.map { it.frontDistanceCm }.sorted()[requiredSamples / 2]
                            val medianSideDist = calibrationSamples.map { it.sideDistanceCm }.sorted()[requiredSamples / 2]

                            val finalResult = ArUcoCalibrator.CalibrationResult(
                                cameraAngleDeg = medianAngle,
                                cameraDistanceCm = medianDist,
                                heightDiffCm = medianHeight,
                                frontDistanceCm = medianFrontDist,
                                sideDistanceCm = medianSideDist,
                                success = true
                            )
                            android.util.Log.i("Fitness", "Calibration FINAL (median of $requiredSamples): " +
                                "angle=${"%.1f".format(medianAngle)} dist=${"%.1f".format(medianDist)}cm height=${"%.1f".format(medianHeight)}cm")
                            onCalibrated(finalResult)
                            return@withContext
                        }
                    }
                }

                delay(150) // ~6-7 detections per second
            }
        }
    }

    Column(modifier = modifier) {
        // Dual stream preview
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Front camera
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                FrontCameraPreview(onFrame = onFrontFrame)
                // Detection indicator
                Text(
                    if (frontDetected) "正面 [已检测]" else "正面 [未检测]",
                    color = if (frontDetected) Color.Green else Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }

            Divider(modifier = Modifier.fillMaxHeight().width(2.dp), color = Color.White.copy(alpha = 0.3f))

            // Side camera
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val frame = sideBitmap
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        if (sideConnected) "接收中..." else "等待 Watcher...",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Text(
                    if (sideDetected) "侧面 [已检测]" else "侧面 [未检测]",
                    color = if (sideDetected) Color.Green else Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }
        }

        // Instructions bar
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A2E)).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ArUco 标定",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "请将 ArUco 标记举在两摄像头中间",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onBack) {
                Text("返回", color = Color.White)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// CALIBRATED view (2 second display)
// ══════════════════════════════════════════════════════════════

@Composable
private fun CalibratedView(
    result: ArUcoCalibrator.CalibrationResult?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "标定完成",
                color = Color.Green,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            result?.let {
                Text(
                    "夹角: ${"%.1f".format(it.cameraAngleDeg)}°",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "距离: ${"%.0f".format(it.cameraDistanceCm)}cm | 高度差: ${"%.1f".format(it.heightDiffCm)}cm",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "正在加载运动引擎...",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// LOADING_ENGINES view
// ══════════════════════════════════════════════════════════════

@Composable
private fun LoadingEnginesView(
    frontReady: Boolean,
    sideReady: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "正在加载姿态检测引擎...",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "正面引擎 (GPU): ${if (frontReady) "就绪" else "加载中..."}",
                color = if (frontReady) Color.Green else Color.Yellow,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "侧面引擎 (CPU): ${if (sideReady) "就绪" else "加载中..."}",
                color = if (sideReady) Color.Green else Color.Yellow,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// READY view (full dual-pose mode)
// ══════════════════════════════════════════════════════════════

@Composable
private fun ReadyView(
    frontBitmap: Bitmap?,
    sideBitmap: Bitmap?,
    sideConnected: Boolean,
    frontPoseResult: PoseDetectionResult?,
    sidePoseResult: PoseDetectionResult?,
    frontFps: Int,
    sideFps: Int,
    frontInferenceMs: Long,
    sideInferenceMs: Long,
    calibrationAngle: Float,
    onFrontFrame: (Bitmap) -> Unit,
    onSideFrame: (Bitmap) -> Unit,
    onSideConnected: (Boolean) -> Unit,
    onFrontPoseResult: (PoseDetectionResult?) -> Unit,
    onSidePoseResult: (PoseDetectionResult?) -> Unit,
    onFrontFps: (Int) -> Unit,
    onSideFps: (Int) -> Unit,
    onFrontInferenceMs: (Long) -> Unit,
    onSideInferenceMs: (Long) -> Unit,
    frontEngine: PoseEstimationEngine,
    sideEngine: PoseEstimationEngine,
    frontAlive: AtomicBoolean,
    sideAlive: AtomicBoolean,
    sideProcessing: AtomicBoolean,
    sideEngineReady: Boolean,
    onRecalibrate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // MJPEG stream + side inference
    LaunchedEffect(sideEngineReady) {
        if (!sideEngineReady) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val dao = com.example.watcher.data.local.AppDatabase.getDatabase(context).videoStreamSettingsDao()
            val settings = dao.getSettingsSync()
            val streamUrl = settings?.streamUrl ?: "http://192.168.4.1:81/stream"
            var frameCount = 0
            var lastFpsTime = System.currentTimeMillis()

            while (isActive && sideAlive.get()) {
                try {
                    val client = OkHttpClient.Builder().build()
                    val request = Request.Builder().url(streamUrl).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        delay(2000L)
                        continue
                    }
                    onSideConnected(true)
                    val stream = BufferedInputStream(response.body?.byteStream() ?: continue)

                    val latestFrame = AtomicReference<Bitmap?>(null)

                    kotlinx.coroutines.coroutineScope {
                        launch(Dispatchers.Default) {
                            while (isActive && sideAlive.get()) {
                                val frame = latestFrame.getAndSet(null)
                                if (frame != null && sideAlive.get() && sideProcessing.compareAndSet(false, true)) {
                                    val t0 = System.currentTimeMillis()
                                    runCatching { sideEngine.detectForVideo(frame, t0) }
                                        .onSuccess { result ->
                                            onSideInferenceMs(System.currentTimeMillis() - t0)
                                            onSidePoseResult(result)
                                        }
                                    sideProcessing.set(false)
                                } else {
                                    delay(5)
                                }
                            }
                        }

                        while (isActive && sideAlive.get()) {
                            val jpegBytes = readMjpegFrame(stream) ?: break
                            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: continue
                            onSideFrame(bitmap)
                            latestFrame.set(bitmap)

                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTime >= 1000L) {
                                onSideFps(frameCount)
                                frameCount = 0
                                lastFpsTime = now
                            }
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    onSideConnected(false)
                    delay(3000L)
                }
            }
        }
    }

    Column(modifier = modifier) {
        // Dual stream area
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // LEFT: Front camera + pose
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                FrontCameraWithPose(
                    onPoseResult = onFrontPoseResult,
                    onFps = onFrontFps,
                    onInferenceMs = onFrontInferenceMs,
                    onFrame = onFrontFrame,
                    engine = frontEngine,
                    frontAlive = frontAlive
                )
            }

            Divider(modifier = Modifier.fillMaxHeight().width(2.dp), color = Color.White.copy(alpha = 0.3f))

            // RIGHT: Watcher MJPEG + pose
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val frame = sideBitmap
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    PoseOverlay(
                        result = sidePoseResult,
                        imageAspectRatio = frame.width.toFloat() / frame.height,
                        modifier = Modifier.fillMaxSize(),
                        visibilityThreshold = 0.8f
                    )
                } else {
                    Text(
                        if (sideConnected) "接收中..." else "等待 Watcher...",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Diagnostics bar
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "正面 ${frontFps}fps ${frontInferenceMs}ms",
                color = Color.Green.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                "标定: ${"%.1f".format(calibrationAngle)}°",
                color = Color.Yellow,
                style = MaterialTheme.typography.labelSmall
            )
            TextButton(onClick = onRecalibrate) {
                Text("重新标定", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onBack) {
                Text("返回", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "侧面 ${sideFps}fps ${sideInferenceMs}ms ${if (sideConnected) "" else "断开"}",
                color = if (sideConnected) Color.Cyan.copy(alpha = 0.8f) else Color.Red.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// Front camera preview (calibration mode - no pose)
// ══════════════════════════════════════════════════════════════

@Composable
private fun FrontCameraPreview(onFrame: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var displayFrame by remember { mutableStateOf<Bitmap?>(null) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val lastUpdate = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    DisposableEffect(hasPermission, lifecycleOwner) {
        if (!hasPermission) { onDispose { } } else {
            var provider: ProcessCameraProvider? = null
            val targetRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val prov = future.get()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(android.util.Size(640, 480))
                        .setTargetRotation(targetRotation)
                        .setOutputImageRotationEnabled(true)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build().apply {
                            setAnalyzer(cameraExecutor) { image ->
                                val frame = image.toBitmapFitness()
                                image.close()
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate.get() >= 66L) {
                                    lastUpdate.set(now)
                                    mainExecutor.execute {
                                        displayFrame = frame
                                        onFrame(frame)
                                    }
                                }
                            }
                        }
                    prov.unbindAll()
                    prov.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                    provider = prov
                }
            }, mainExecutor)
            onDispose {
                cameraExecutor.shutdownNow()
                provider?.unbindAll()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val frame = displayFrame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = -1f)
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (!hasPermission) "需要摄像头权限" else "启动中...", color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// Front camera + MediaPipe pose (READY mode)
// ══════════════════════════════════════════════════════════════

@Composable
private fun FrontCameraWithPose(
    onPoseResult: (PoseDetectionResult?) -> Unit,
    onFps: (Int) -> Unit,
    onInferenceMs: (Long) -> Unit,
    onFrame: (Bitmap) -> Unit,
    engine: PoseEstimationEngine,
    frontAlive: AtomicBoolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val isProcessing = remember { AtomicBoolean(false) }

    var displayFrame by remember { mutableStateOf<Bitmap?>(null) }
    var localPoseResult by remember { mutableStateOf<PoseDetectionResult?>(null) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val lastDisplayUpdate = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var frameCount by remember { mutableIntStateOf(0) }
    var lastFpsTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(hasPermission, lifecycleOwner) {
        if (!hasPermission) { onDispose { } } else {
            var provider: ProcessCameraProvider? = null
            val targetRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }

            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val prov = future.get()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(android.util.Size(640, 480))
                        .setTargetRotation(targetRotation)
                        .setOutputImageRotationEnabled(true)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build().apply {
                            setAnalyzer(cameraExecutor) { image ->
                                val frame = image.toBitmapFitness()
                                image.close()
                                val now = System.currentTimeMillis()
                                if (now - lastDisplayUpdate.get() >= 66L) {
                                    lastDisplayUpdate.set(now)
                                    mainExecutor.execute {
                                        displayFrame = frame
                                        onFrame(frame)
                                    }
                                }
                                if (frontAlive.get() && isProcessing.compareAndSet(false, true)) {
                                    val t0 = System.currentTimeMillis()
                                    val result = runCatching { engine.detectForVideo(frame, t0) }.getOrNull()
                                    val inferenceTime = System.currentTimeMillis() - t0
                                    if (result != null && frontAlive.get()) {
                                        mainExecutor.execute {
                                            localPoseResult = result
                                            onPoseResult(result)
                                            onInferenceMs(inferenceTime)
                                        }
                                    }
                                    isProcessing.set(false)
                                    frameCount++
                                    if (now - lastFpsTime >= 1000L) {
                                        val fps = frameCount
                                        frameCount = 0
                                        lastFpsTime = now
                                        mainExecutor.execute { onFps(fps) }
                                    }
                                }
                            }
                        }
                    prov.unbindAll()
                    prov.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                    provider = prov
                }
            }, mainExecutor)
            onDispose {
                cameraExecutor.shutdownNow()
                provider?.unbindAll()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val frame = displayFrame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = -1f)
            )
            PoseOverlay(
                result = localPoseResult,
                mirrorHorizontally = true,
                imageAspectRatio = frame.width.toFloat() / frame.height,
                modifier = Modifier.fillMaxSize(),
                visibilityThreshold = 0.8f
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (!hasPermission) "需要摄像头权限" else "启动中...", color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// ── Helpers ──

private fun readMjpegFrame(stream: BufferedInputStream): ByteArray? {
    val buffer = ByteArrayOutputStream()
    var prev = 0
    var foundStart = false

    while (true) {
        val b = stream.read()
        if (b == -1) return null

        if (!foundStart) {
            if (prev == 0xFF && b == 0xD8) {
                foundStart = true
                buffer.reset()
                buffer.write(0xFF)
                buffer.write(0xD8)
            }
        } else {
            buffer.write(b)
            if (prev == 0xFF && b == 0xD9) {
                return buffer.toByteArray()
            }
        }
        prev = b
    }
}

private fun ImageProxy.toBitmapFitness(): Bitmap {
    val plane = planes.first()
    val width = width
    val height = height
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val rowBytes = ByteArray(rowStride)
    val pixels = IntArray(width * height)
    var pixelIndex = 0
    for (row in 0 until height) {
        buffer.position(row * rowStride)
        buffer.get(rowBytes, 0, rowStride)
        var col = 0
        while (col < width * pixelStride && pixelIndex < pixels.size) {
            val r = rowBytes[col].toInt() and 0xFF
            val g = rowBytes[col + 1].toInt() and 0xFF
            val b = rowBytes[col + 2].toInt() and 0xFF
            val a = rowBytes[col + 3].toInt() and 0xFF
            pixels[pixelIndex] = android.graphics.Color.argb(a, r, g, b)
            pixelIndex++
            col += pixelStride
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

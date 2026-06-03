package com.example.watcher.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.watcher.data.local.pose.DelegateType
import com.example.watcher.data.local.pose.ModelComplexity
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.local.pose.PoseDetectorConfig
import com.example.watcher.data.local.pose.PoseDetectorState
import com.example.watcher.data.local.pose.PosePerformanceStats
import com.example.watcher.ui.components.PoseOverlay
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoseEstimationScreen(
    detectorState: PoseDetectorState,
    detectorConfig: PoseDetectorConfig,
    poseResult: PoseDetectionResult?,
    performanceStats: PosePerformanceStats,
    errorMessage: String?,
    onInitDetector: (PoseDetectorConfig) -> Unit,
    onProcessFrame: (Bitmap) -> Unit,
    onUpdateModelComplexity: (ModelComplexity) -> Unit,
    onUpdateDelegateType: (DelegateType) -> Unit,
    onUpdateMaxNumPoses: (Int) -> Unit,
    onUpdateDetectionConfidence: (Float) -> Unit,
    onClose: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(true) }

    // Auto-init detector on first composition
    LaunchedEffect(Unit) {
        if (detectorState == PoseDetectorState.Idle) {
            onInitDetector(detectorConfig)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("姿态识别") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "切换摄像头")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Camera preview with pose overlay
            CameraPosePreview(
                detectorState = detectorState,
                poseResult = poseResult,
                useFrontCamera = useFrontCamera,
                onFrame = onProcessFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Performance stats bar
            PerformanceStatsBar(
                stats = performanceStats,
                detectorState = detectorState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            // Error message
            AnimatedVisibility(visible = errorMessage != null) {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick config chips
            QuickConfigSection(
                config = detectorConfig,
                onUpdateModelComplexity = onUpdateModelComplexity,
                onUpdateDelegateType = onUpdateDelegateType,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
    }

    // Settings bottom sheet
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SettingsSheet(
                config = detectorConfig,
                onUpdateModelComplexity = onUpdateModelComplexity,
                onUpdateDelegateType = onUpdateDelegateType,
                onUpdateMaxNumPoses = onUpdateMaxNumPoses,
                onUpdateDetectionConfidence = onUpdateDetectionConfidence
            )
        }
    }
}

@Composable
private fun CameraPosePreview(
    detectorState: PoseDetectorState,
    poseResult: PoseDetectionResult?,
    useFrontCamera: Boolean,
    onFrame: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var displayFrame by remember { mutableStateOf<Bitmap?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var hasPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val currentOnFrame by rememberUpdatedState(onFrame)
    // Throttle display updates to ~15 fps max to avoid over-recomposition
    val lastDisplayUpdate = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(cameraExecutor) {
        onDispose { cameraExecutor.shutdown() }
    }

    DisposableEffect(hasPermission, useFrontCamera, lifecycleOwner) {
        if (!hasPermission) {
            onDispose { }
        } else {
            var cameraProvider: ProcessCameraProvider? = null
            val targetRotation = resolveTargetRotation(context)
            val cameraSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val providerFuture = ProcessCameraProvider.getInstance(context)
            val bindRunnable = Runnable {
                runCatching {
                    val provider = providerFuture.get()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(640, 480))
                        .setTargetRotation(targetRotation)
                        .setOutputImageRotationEnabled(true)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .apply {
                            setAnalyzer(cameraExecutor) { image ->
                                val frame = image.toBitmapCompat()
                                image.close()
                                // Update display at throttled rate for smooth preview
                                val now = System.currentTimeMillis()
                                if (now - lastDisplayUpdate.get() >= 50L) { // ~20 fps display
                                    lastDisplayUpdate.set(now)
                                    mainExecutor.execute {
                                        displayFrame = frame
                                        cameraReady = true
                                    }
                                }
                                currentOnFrame(frame)
                            }
                        }

                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, analysis)
                    cameraProvider = provider
                }.onFailure { error ->
                    Log.e("PoseEstimation", "Camera bind failed", error)
                }
            }
            providerFuture.addListener(bindRunnable, mainExecutor)

            onDispose {
                cameraProvider?.unbindAll()
                displayFrame = null
                cameraReady = false
            }
        }
    }

    val frame = displayFrame
    val frameAspectRatio = frame?.let { it.width.toFloat() / it.height.toFloat() } ?: (3f / 4f)

    Box(
        modifier = modifier
            .aspectRatio(frameAspectRatio)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
    ) {
        if (frame != null) {
            val mirrorModifier = if (useFrontCamera) {
                Modifier.fillMaxSize().graphicsLayer(scaleX = -1f)
            } else {
                Modifier.fillMaxSize()
            }
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = mirrorModifier
            )
            PoseOverlay(
                result = poseResult,
                mirrorHorizontally = useFrontCamera,
                imageAspectRatio = frameAspectRatio,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (!hasPermission) "需要摄像头权限" else "正在启动摄像头...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PerformanceStatsBar(
    stats: PosePerformanceStats,
    detectorState: PoseDetectorState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatItem(label = "FPS", value = "${stats.fps}")
        StatItem(label = "推理", value = "${stats.inferenceTimeMs}ms")
        StatItem(label = "人数", value = "${stats.detectedPoseCount}")
        StatItem(
            label = "委托",
            value = stats.delegateInUse.ifBlank {
                when (detectorState) {
                    PoseDetectorState.Idle -> "待初始化"
                    PoseDetectorState.Initializing -> "加载中"
                    PoseDetectorState.Ready -> "就绪"
                    PoseDetectorState.Error -> "错误"
                }
            }
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickConfigSection(
    config: PoseDetectorConfig,
    onUpdateModelComplexity: (ModelComplexity) -> Unit,
    onUpdateDelegateType: (DelegateType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "模型配置",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelComplexity.entries.forEach { complexity ->
                FilterChip(
                    selected = config.modelComplexity == complexity,
                    onClick = { onUpdateModelComplexity(complexity) },
                    label = { Text(complexity.label) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DelegateType.entries.forEach { delegate ->
                FilterChip(
                    selected = config.delegateType == delegate,
                    onClick = { onUpdateDelegateType(delegate) },
                    label = { Text(delegate.label) }
                )
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    config: PoseDetectorConfig,
    onUpdateModelComplexity: (ModelComplexity) -> Unit,
    onUpdateDelegateType: (DelegateType) -> Unit,
    onUpdateMaxNumPoses: (Int) -> Unit,
    onUpdateDetectionConfidence: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "检测设置",
            style = MaterialTheme.typography.headlineSmall
        )

        // Model complexity
        Column {
            Text("模型复杂度", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelComplexity.entries.forEach { complexity ->
                    FilterChip(
                        selected = config.modelComplexity == complexity,
                        onClick = { onUpdateModelComplexity(complexity) },
                        label = { Text(complexity.label) }
                    )
                }
            }
        }

        // Delegate type
        Column {
            Text("推理委托", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DelegateType.entries.forEach { delegate ->
                    FilterChip(
                        selected = config.delegateType == delegate,
                        onClick = { onUpdateDelegateType(delegate) },
                        label = { Text(delegate.label) }
                    )
                }
            }
        }

        // Max poses
        Column {
            Text(
                "最大检测人数: ${config.maxNumPoses}",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = config.maxNumPoses.toFloat(),
                onValueChange = { onUpdateMaxNumPoses(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3
            )
        }

        // Detection confidence
        Column {
            Text(
                "检测阈值: ${"%.2f".format(config.minDetectionConfidence)}",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = config.minDetectionConfidence,
                onValueChange = { onUpdateDetectionConfidence(it) },
                valueRange = 0.1f..0.9f
            )
        }
    }
}

private fun resolveTargetRotation(context: android.content.Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return context.display?.rotation ?: Surface.ROTATION_0
    }
    @Suppress("DEPRECATION")
    return (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
}

private fun ImageProxy.toBitmapCompat(): Bitmap {
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
        var columnOffset = 0
        while (columnOffset < width * pixelStride && pixelIndex < pixels.size) {
            val red = rowBytes[columnOffset].toInt() and 0xFF
            val green = rowBytes[columnOffset + 1].toInt() and 0xFF
            val blue = rowBytes[columnOffset + 2].toInt() and 0xFF
            val alpha = rowBytes[columnOffset + 3].toInt() and 0xFF
            pixels[pixelIndex] = android.graphics.Color.argb(alpha, red, green, blue)
            pixelIndex += 1
            columnOffset += pixelStride
        }
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

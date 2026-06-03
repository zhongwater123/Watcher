package com.example.watcher.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.watcher.data.local.pose.DanceSegmentationEngine
import com.example.watcher.data.local.pose.DelegateType
import com.example.watcher.data.local.pose.ModelComplexity
import com.example.watcher.data.local.pose.NormalizedLandmark
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.local.pose.PoseDetectorConfig
import com.example.watcher.data.local.pose.PoseEstimationEngine
import com.example.watcher.data.local.pose.PoseFileFormat
import com.example.watcher.data.local.pose.PoseLandmarkSet
import com.example.watcher.data.local.pose.PoseScoreCalculator
import com.example.watcher.data.local.pose.PoseVideoSession
import com.example.watcher.ui.components.PoseOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dance Practice Screen — landscape split-screen for dance learning.
 * Left: live camera + real-time MediaPipe detection (user's pose)
 * Right: reference video + cached pose overlay (teacher's pose)
 */
@Composable
fun DancePracticeScreen(
    session: PoseVideoSession,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Reference pose file
    val poseDir = remember { File(context.filesDir, "pose_data") }
    val poseFile = remember { File(poseDir, "session_${session.id}.pose") }
    val slotFile = remember(poseFile) {
        if (poseFile.exists() && poseFile.length() >= 64) {
            runCatching { PoseFileFormat.SlotFile(poseFile) }.getOrNull()
        } else null
    }
    val videoFps = slotFile?.header?.fps?.toInt() ?: 30

    // Segmentation for color
    val segFile = remember { File(poseDir, "session_${session.id}.segments.json") }
    val segmentation = remember { DanceSegmentationEngine().loadFromFile(segFile) }

    // Live detection state
    var livePoseResult by remember { mutableStateOf<PoseDetectionResult?>(null) }

    // Reference playback state
    var refPoseResult by remember { mutableStateOf<PoseDetectionResult?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }

    // Scoring state
    var mirrorMode by remember { mutableStateOf(true) }
    var currentFrameScore by remember { mutableFloatStateOf(0f) }
    var moveFrameScores by remember { mutableStateOf(mutableListOf<Float>()) }
    var currentMoveIdx by remember { mutableStateOf(-1) }
    var lastRating by remember { mutableStateOf<PoseScoreCalculator.MoveRating?>(null) }
    var lastRatingScore by remember { mutableFloatStateOf(0f) }
    var ratingVisible by remember { mutableStateOf(false) }
    var allMoveScores by remember { mutableStateOf(mutableListOf<Float>()) }
    var showSummary by remember { mutableStateOf(false) }

    // ExoPlayer for reference video
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val clipStart = if (session.clipStartMs > 0) session.clipStartMs * 1000 else 0L
            val clipEnd = if (session.clipEndMs > 0) session.clipEndMs * 1000 else Long.MIN_VALUE
            val item = if (clipEnd > clipStart) {
                MediaItem.Builder()
                    .setUri(session.sourceVideoPath)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(session.clipStartMs)
                            .setEndPositionMs(session.clipEndMs)
                            .build()
                    ).build()
            } else {
                MediaItem.fromUri(session.sourceVideoPath)
            }
            setMediaItem(item)
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        val endListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Score final move
                    if (moveFrameScores.isNotEmpty()) {
                        allMoveScores.add((moveFrameScores.average() * 100).toFloat())
                    }
                    showSummary = true
                }
            }
        }
        player.addListener(endListener)
        onDispose {
            player.removeListener(endListener)
            player.release()
            slotFile?.close()
        }
    }

    // Update playback speed
    LaunchedEffect(playbackSpeed) {
        player.setPlaybackSpeed(playbackSpeed)
    }

    // Pose reading + scoring loop
    LaunchedEffect(slotFile) {
        if (slotFile == null) return@LaunchedEffect
        while (isActive) {
            if (player.isPlaying) {
                currentPositionMs = player.currentPosition
                val frameIdx = PoseFileFormat.frameIndexForPosition(
                    currentPositionMs, videoFps, slotFile.totalFrames
                )
                // Display pose (current frame)
                val nearest = slotFile.findNearestFilled(frameIdx, 30)
                if (nearest != null) {
                    val frame = slotFile.readFrame(nearest)
                    if (frame != null) {
                        refPoseResult = frameToPoseResult(frame)
                    }
                }

                // Scoring: skip first 0.5s (insufficient delay data)
                if (currentPositionMs >= 500L) {
                    val liveLm = livePoseResult?.landmarks?.firstOrNull()?.normalizedLandmarks
                    val delayedPos = currentPositionMs - 500L
                    val delayedIdx = PoseFileFormat.frameIndexForPosition(delayedPos, videoFps, slotFile.totalFrames)
                    val delayedNearest = slotFile.findNearestFilled(delayedIdx, 30)
                    val refLm = if (delayedNearest != null) {
                        slotFile.readFrame(delayedNearest)?.let { frameToPoseResult(it) }
                            ?.landmarks?.firstOrNull()?.normalizedLandmarks
                    } else null

                    if (liveLm != null && refLm != null) {
                        val result = PoseScoreCalculator.calculateFrameScore(liveLm, refLm, mirrorMode)
                        currentFrameScore = result.frameScore
                        moveFrameScores.add(result.frameScore)
                        // Cap moveFrameScores to prevent unbounded growth
                        if (moveFrameScores.size > 500) {
                            moveFrameScores = moveFrameScores.takeLast(200).toMutableList()
                        }
                    }
                }

                // Check move transitions for scoring
                if (segmentation != null) {
                    val moveIdx = segmentation.atomicMoves.indexOfLast { it.startMs <= currentPositionMs }
                    if (moveIdx != currentMoveIdx && moveIdx >= 0) {
                        if (moveFrameScores.isNotEmpty()) {
                            val rating = PoseScoreCalculator.rateMove(moveFrameScores)
                            lastRating = rating
                            lastRatingScore = (moveFrameScores.average() * 100).toFloat()
                            ratingVisible = true
                            allMoveScores.add(lastRatingScore)
                        }
                        moveFrameScores = mutableListOf()
                        currentMoveIdx = moveIdx
                    } else if (currentMoveIdx == -1 && moveIdx >= 0) {
                        currentMoveIdx = moveIdx
                    }
                } else {
                    // Fallback: no segmentation → rate every 3 seconds
                    val interval = 3000L
                    val currentInterval = (currentPositionMs / interval).toInt()
                    if (currentInterval != currentMoveIdx && currentPositionMs >= 500L) {
                        if (moveFrameScores.isNotEmpty()) {
                            val rating = PoseScoreCalculator.rateMove(moveFrameScores)
                            lastRating = rating
                            lastRatingScore = (moveFrameScores.average() * 100).toFloat()
                            ratingVisible = true
                            allMoveScores.add(lastRatingScore)
                        }
                        moveFrameScores = mutableListOf()
                        currentMoveIdx = currentInterval
                    }
                }
            }
            delay(50L)
        }
    }

    // Auto-hide rating after 1.5s (timestamp-based to handle rapid transitions)
    var ratingTimestamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ratingVisible) {
        if (ratingVisible) {
            ratingTimestamp = System.currentTimeMillis()
            delay(1500L)
            if (System.currentTimeMillis() - ratingTimestamp >= 1400L) {
                ratingVisible = false
            }
        }
    }

    // Main layout
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Split-screen area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // LEFT: Live camera + detection
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                LiveCameraPanel(
                    onPoseResult = { livePoseResult = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Divider
            Divider(
                modifier = Modifier.fillMaxHeight().width(2.dp),
                color = Color.White.copy(alpha = 0.3f)
            )

            // RIGHT: 观察窗口 — 视频 Crop 模式 + 垂直/水平滚动跟随人物
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black)
                    .clipToBounds()
            ) {
                var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }  // default to landscape
                var scrollX by remember { mutableFloatStateOf(0f) }
                var scrollY by remember { mutableFloatStateOf(0f) }

                val vpW = constraints.maxWidth.toFloat()
                val vpH = constraints.maxHeight.toFloat()
                val vpAspect = if (vpH > 0) vpW / vpH else 1f

                // Content size: Crop mode (fill short edge, overflow long edge)
                val contentW: Float
                val contentH: Float
                if (videoAspectRatio < vpAspect) {
                    contentW = vpW
                    contentH = vpW / videoAspectRatio
                } else {
                    contentH = vpH
                    contentW = vpH * videoAspectRatio
                }

                // Scroll tracking — all based on pose data
                LaunchedEffect(refPoseResult) {
                    val landmarks = refPoseResult?.landmarks?.firstOrNull()?.normalizedLandmarks
                    if (landmarks != null && landmarks.size >= 33) {
                        val feetY = landmarks.maxOf { it.y }
                        val centerX = (landmarks.minOf { it.x } + landmarks.maxOf { it.x }) / 2f

                        // Horizontal: center person in viewport
                        val personPxX = centerX * contentW
                        val targetX = -(personPxX - vpW / 2f)
                        val clampedX = targetX.coerceIn(-(contentW - vpW).coerceAtLeast(0f), 0f)

                        // Vertical: feet at 93% of viewport
                        val feetPx = feetY * contentH
                        val targetY = -(feetPx - vpH * 0.93f)
                        val clampedY = targetY.coerceIn(-(contentH - vpH).coerceAtLeast(0f), 0f)

                        // Smooth tracking (α=0.15 — responsive but not jittery)
                        scrollX = scrollX * 0.85f + clampedX * 0.15f
                        scrollY = scrollY * 0.85f + clampedY * 0.15f
                    }
                }

                DisposableEffect(player) {
                    val listener = object : Player.Listener {
                        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                            if (size.width > 0 && size.height > 0) {
                                videoAspectRatio = size.width.toFloat() / size.height
                            }
                        }
                    }
                    player.addListener(listener)
                    onDispose { player.removeListener(listener) }
                }

                // Content: scrollable container with video at full aspect ratio
                val density = LocalDensity.current
                val contentWDp = with(density) { contentW.toInt().toDp() }
                val contentHDp = with(density) { contentH.toInt().toDp() }

                val hScrollState = rememberScrollState()
                val vScrollState = rememberScrollState()

                // Programmatic scroll to follow person
                LaunchedEffect(scrollX, scrollY) {
                    hScrollState.scrollTo((-scrollX).toInt().coerceAtLeast(0))
                    vScrollState.scrollTo((-scrollY).toInt().coerceAtLeast(0))
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(hScrollState)
                        .verticalScroll(vScrollState)
                ) {
                Box(
                    modifier = Modifier
                        .requiredWidth(contentWDp)
                        .requiredHeight(contentHDp)
                ) {
                    // Video (MATCH_PARENT, no stretch — Box matches video aspect)
                    AndroidView(
                        factory = { ctx ->
                            val container = FrameLayout(ctx)
                            val tv = TextureView(ctx)
                            container.addView(tv, FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER
                            ))
                            player.setVideoTextureView(tv)
                            container
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Skeleton (fillMaxSize = same as video = perfect alignment)
                    val moveColorIdx = if (segmentation != null) {
                        val moveIdx = segmentation.atomicMoves.indexOfLast { it.startMs <= currentPositionMs }
                        if (moveIdx >= 0) {
                            val phrase = segmentation.phrases.firstOrNull { p ->
                                segmentation.atomicMoves[moveIdx].id in p.moveIds
                            }
                            phrase?.moveIds?.indexOf(segmentation.atomicMoves[moveIdx].id) ?: moveIdx
                        } else -1
                    } else -1

                    PoseOverlay(
                        result = refPoseResult,
                        imageAspectRatio = videoAspectRatio,
                        modifier = Modifier.fillMaxSize(),
                        moveColorIndex = if (moveColorIdx >= 0) moveColorIdx else -1
                    )
                }
                } // scrollable Box

                // Floating rating overlay (top-right corner)
                if (ratingVisible && lastRating != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "${lastRating!!.label} ${"%.0f".format(lastRatingScore)}",
                            color = when (lastRating) {
                                PoseScoreCalculator.MoveRating.PERFECT -> Color(0xFF4CAF50)
                                PoseScoreCalculator.MoveRating.GREAT -> Color(0xFFFFEB3B)
                                PoseScoreCalculator.MoveRating.GOOD -> Color(0xFFFF9800)
                                else -> Color(0xFFF44336)
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

        // Score bar + rating overlay
        // Score progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.DarkGray)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(currentFrameScore.coerceIn(0f, 1f))
                    .background(
                        when {
                            currentFrameScore >= 0.85f -> Color(0xFF4CAF50)
                            currentFrameScore >= 0.70f -> Color(0xFFFFEB3B)
                            currentFrameScore >= 0.50f -> Color(0xFFFF9800)
                            else -> Color(0xFFF44336)
                        }
                    )
            )
        }

        // Bottom control bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("返回", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val speeds = listOf(0.25f, 0.5f, 0.75f, 1f)
                speeds.forEach { speed ->
                    TextButton(onClick = { playbackSpeed = speed }) {
                        Text(
                            "${speed}x",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (playbackSpeed == speed) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            TextButton(onClick = { mirrorMode = !mirrorMode }) {
                Text(
                    if (mirrorMode) "镜像:开" else "镜像:关",
                    color = if (mirrorMode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            TextButton(onClick = {
                isPlaying = !isPlaying
                player.playWhenReady = isPlaying
            }) {
                Text(
                    if (isPlaying) "暂停" else "播放",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Score display (centered)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "${"%.0f".format(currentFrameScore * 100)}",
                    color = when {
                        currentFrameScore >= 0.85f -> Color(0xFF4CAF50)
                        currentFrameScore >= 0.70f -> Color(0xFFFFEB3B)
                        currentFrameScore >= 0.50f -> Color(0xFFFF9800)
                        else -> Color.White
                    },
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }

        // Session summary dialog
        if (showSummary && allMoveScores.isNotEmpty()) {
            val totalScore = allMoveScores.average().toFloat()
            val perfectCount = allMoveScores.count { it >= 85f }
            val greatCount = allMoveScores.count { it in 70f..84.9f }
            val goodCount = allMoveScores.count { it in 50f..69.9f }
            val missCount = allMoveScores.count { it < 50f }
            val bestScore = allMoveScores.maxOrNull() ?: 0f
            val worstScore = allMoveScores.minOrNull() ?: 0f

            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSummary = false },
                title = { Text("练习总结") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "总分: ${"%.0f".format(totalScore)}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = when {
                                totalScore >= 85f -> Color(0xFF4CAF50)
                                totalScore >= 70f -> Color(0xFFFFEB3B)
                                totalScore >= 50f -> Color(0xFFFF9800)
                                else -> Color(0xFFF44336)
                            }
                        )
                        Text("动作数: ${allMoveScores.size}")
                        Text("Perfect: $perfectCount | Great: $greatCount | Good: $goodCount | Miss: $missCount")
                        Text("最高: ${"%.0f".format(bestScore)} | 最低: ${"%.0f".format(worstScore)}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSummary = false
                        // Restart
                        player.seekTo(0)
                        player.playWhenReady = true
                        allMoveScores = mutableListOf()
                        currentMoveIdx = -1
                        moveFrameScores = mutableListOf()
                    }) { Text("再来一次") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showSummary = false
                        onBack()
                    }) { Text("退出") }
                }
            )
        }
    }
}

/**
 * Live camera panel with real-time MediaPipe detection.
 * Uses front camera, LITE model, CPU delegate for stability.
 */
@Composable
private fun LiveCameraPanel(
    onPoseResult: (PoseDetectionResult?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val isProcessing = remember { AtomicBoolean(false) }

    var displayFrame by remember { mutableStateOf<Bitmap?>(null) }
    var localPoseResult by remember { mutableStateOf<PoseDetectionResult?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // MediaPipe engine (LITE + CPU for practice mode stability)
    val engine = remember { PoseEstimationEngine(context) }
    var engineReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            engine.initializeForVideo(
                config = PoseDetectorConfig(
                    modelComplexity = ModelComplexity.LITE,
                    delegateType = DelegateType.CPU
                )
            )
            engineReady = true
        }
    }

    DisposableEffect(cameraExecutor) {
        onDispose {
            cameraExecutor.shutdown()
            engine.release()
        }
    }

    val lastDisplayUpdate = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // Camera binding
    DisposableEffect(hasPermission, engineReady, lifecycleOwner) {
        if (!hasPermission || !engineReady) {
            onDispose { }
        } else {
            var provider: ProcessCameraProvider? = null
            val targetRotation = resolveTargetRotationPractice(context)

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
                        .build()
                        .apply {
                            setAnalyzer(cameraExecutor) { image ->
                                val frame = image.toBitmapPractice()
                                image.close()
                                val now = System.currentTimeMillis()
                                if (now - lastDisplayUpdate.get() >= 66L) { // ~15fps display
                                    lastDisplayUpdate.set(now)
                                    mainExecutor.execute { displayFrame = frame }
                                }
                                if (isProcessing.compareAndSet(false, true)) {
                                    val result = engine.detectForVideo(frame, now)
                                    mainExecutor.execute {
                                        localPoseResult = result
                                        onPoseResult(result)
                                    }
                                    isProcessing.set(false)
                                }
                            }
                        }

                    prov.unbindAll()
                    prov.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                    provider = prov
                }
            }, mainExecutor)

            onDispose { provider?.unbindAll() }
        }
    }

    // Render
    Box(modifier = modifier) {
        val frame = displayFrame
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = -1f) // Mirror front camera
            )
            PoseOverlay(
                result = localPoseResult,
                mirrorHorizontally = true,
                imageAspectRatio = frame.width.toFloat() / frame.height,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (!hasPermission) "需要摄像头权限" else "正在启动...",
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// Helper: convert pose frame to PoseDetectionResult
private fun frameToPoseResult(frame: PoseFileFormat.PoseFrame): PoseDetectionResult {
    val set = PoseLandmarkSet(
        normalizedLandmarks = frame.landmarks.map {
            NormalizedLandmark(it.nx, it.ny, it.nz, it.visibility, it.presence)
        },
        worldLandmarks = null
    )
    return PoseDetectionResult(listOf(set), frame.timestampMs, 0)
}


private fun resolveTargetRotationPractice(context: android.content.Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return context.display?.rotation ?: Surface.ROTATION_0
    }
    @Suppress("DEPRECATION")
    return (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
}

private fun ImageProxy.toBitmapPractice(): Bitmap {
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
            pixelIndex++
            columnOffset += pixelStride
        }
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

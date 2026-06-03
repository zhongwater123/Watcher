package com.example.watcher.ui.screens

import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.view.TextureView
import com.example.watcher.data.local.pose.BeatAnalysisProcessor
import com.example.watcher.data.local.pose.BeatFileFormat
import com.example.watcher.data.local.pose.DanceSegmentationEngine
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.watcher.data.local.pose.DelegateType
import com.example.watcher.data.local.pose.ModelComplexity
import com.example.watcher.data.local.pose.NormalizedLandmark
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.local.pose.PoseDetectorConfig
import com.example.watcher.data.local.pose.PoseEstimationEngine
import com.example.watcher.data.local.pose.PoseFileFormat
import com.example.watcher.data.local.pose.PoseLandmarkSet
import com.example.watcher.data.local.pose.PoseVideoSession
import com.example.watcher.ui.components.PoseOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanceProcessingScreen(
    session: PoseVideoSession,
    isFirstPass: Boolean,
    onComplete: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    // Config state (shown before processing starts)
    // Default to CPU — GPU has tensor conflicts during continuous video processing
    var configConfirmed by remember { mutableStateOf(false) }
    var selectedComplexity by remember { mutableStateOf(ModelComplexity.LITE) }
    var selectedDelegate by remember { mutableStateOf(DelegateType.CPU) }
    var beatAnalysisDone by remember { mutableStateOf(false) }

    if (!configConfirmed) {
        ConfigPanel(
            isFirstPass = isFirstPass,
            selectedComplexity = selectedComplexity,
            selectedDelegate = selectedDelegate,
            onComplexityChange = { selectedComplexity = it },
            onDelegateChange = { selectedDelegate = it },
            onStart = { configConfirmed = true }
        )
        return
    }

    // ── Beat analysis step (before pose processing) ──
    if (!beatAnalysisDone && isFirstPass) {
        BeatAnalysisInlineStep(
            session = session,
            onDone = { beatAnalysisDone = true }
        )
        return
    }

    // ── Pose processing active ──
    ProcessingActiveScreen(
        session = session,
        isFirstPass = isFirstPass,
        modelComplexity = selectedComplexity,
        delegateType = selectedDelegate,
        onComplete = onComplete,
        onStop = onStop
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigPanel(
    isFirstPass: Boolean,
    selectedComplexity: ModelComplexity,
    selectedDelegate: DelegateType,
    onComplexityChange: (ModelComplexity) -> Unit,
    onDelegateChange: (DelegateType) -> Unit,
    onStart: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(if (isFirstPass) "首次处理配置" else "优化配置") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("模型复杂度", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelComplexity.entries.forEach { c ->
                    FilterChip(selected = selectedComplexity == c, onClick = { onComplexityChange(c) }, label = { Text(c.label) })
                }
            }

            Text("推理委托", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DelegateType.entries.forEach { d ->
                    FilterChip(selected = selectedDelegate == d, onClick = { onDelegateChange(d) }, label = { Text(d.label) })
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("开始处理")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessingActiveScreen(
    session: PoseVideoSession,
    isFirstPass: Boolean,
    modelComplexity: ModelComplexity,
    delegateType: DelegateType,
    onComplete: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    var poseResult by remember { mutableStateOf<PoseDetectionResult?>(null) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var isPlaying by remember { mutableStateOf(false) } // Start paused, play after engine ready
    var playbackCompleted by remember { mutableStateOf(false) }

    // Diagnostics
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }
    var filledFrameCount by remember { mutableIntStateOf(0) }
    var totalFrameCount by remember { mutableIntStateOf(0) }
    var sessionNewFrames by remember { mutableIntStateOf(0) }
    var poseFileSize by remember { mutableLongStateOf(0L) }
    var currentFps by remember { mutableIntStateOf(0) }
    var lastInferenceMs by remember { mutableLongStateOf(0L) }
    var actualDelegate by remember { mutableStateOf("...") }

    val processing = remember { AtomicBoolean(false) }
    val engine = remember { PoseEstimationEngine(context) }
    var engineReady by remember { mutableStateOf(false) }

    // Pose file setup
    val poseOutputDir = remember { File(context.filesDir, "pose_data").apply { mkdirs() } }
    val poseFile = remember { File(poseOutputDir, "session_${session.id}.pose") }

    // Get video fps for frame indexing
    val videoFps = remember {
        if (session.sourceFps > 0) session.sourceFps else {
            runCatching {
                val r = MediaMetadataRetriever()
                r.setDataSource(session.sourceVideoPath)
                val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toIntOrNull() ?: 30
                r.release()
                fps
            }.getOrDefault(30)
        }
    }

    // Create/open pose file — use session.frameCount (exact, from MediaExtractor)
    val slotFile = remember {
        val clipDurationMs = if (session.clipEndMs > session.clipStartMs) {
            session.clipEndMs - session.clipStartMs
        } else {
            session.sourceVideoDurationMs
        }
        // Use stored exact frame count, or estimate as fallback
        val frameCount = if (session.frameCount > 0) {
            session.frameCount
        } else {
            ((clipDurationMs / 1000.0) * videoFps).toInt().coerceAtLeast(1)
        }

        if (!poseFile.exists() || poseFile.length() < 64) {
            PoseFileFormat.createFile(poseFile, PoseFileFormat.PoseFileHeader(
                totalFrameCount = frameCount,
                fps = videoFps.toShort(),
                videoDurationMs = clipDurationMs,
                videoWidth = session.sourceVideoWidth.toShort(),
                videoHeight = session.sourceVideoHeight.toShort()
            ))
        }
        PoseFileFormat.SlotFile(poseFile)
    }

    // Init diagnostics from existing file
    LaunchedEffect(slotFile) {
        totalFrameCount = slotFile.totalFrames
        filledFrameCount = slotFile.filledFrameCount
        poseFileSize = poseFile.length()
    }

    // Initialize engine THEN start playback
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.Default) {
            engine.initialize(PoseDetectorConfig(
                modelComplexity = modelComplexity,
                delegateType = delegateType
            ))
        }
        actualDelegate = result.getOrDefault(DelegateType.CPU).label
        engineReady = true
        isPlaying = true // Start playback only after engine is ready
    }

    // Player (starts paused)
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = if (session.clipStartMs > 0 || session.clipEndMs > 0) {
                MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(File(session.sourceVideoPath)))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(session.clipStartMs)
                            .setEndPositionMs(if (session.clipEndMs > session.clipStartMs) session.clipEndMs else Long.MAX_VALUE)
                            .build()
                    ).build()
            } else {
                MediaItem.fromUri(android.net.Uri.fromFile(File(session.sourceVideoPath)))
            }
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false // Will be set to true after engine init
            prepare()
        }
    }

    LaunchedEffect(isPlaying) { player.playWhenReady = isPlaying }

    // Detect playback end
    LaunchedEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playbackCompleted = true
            }
        }
        player.addListener(listener)
    }

    LaunchedEffect(playbackCompleted) {
        if (playbackCompleted) {
            slotFile.close()
            // Auto-run segmentation after pose processing completes
            withContext(Dispatchers.IO) {
                val segEngine = DanceSegmentationEngine()
                val segResult = segEngine.segment(poseFile, session.id)
                if (segResult != null) {
                    val segFile = File(poseOutputDir, "session_${session.id}.segments.json")
                    segEngine.saveToFile(segResult, segFile)
                }
            }
            onComplete()
        }
    }

    var textureView by remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            engine.release()
            if (!playbackCompleted) slotFile.close()
        }
    }

    if (isFirstPass) BackHandler { /* blocked */ }

    // FPS tracking
    var fpsFrameCount by remember { mutableIntStateOf(0) }
    var fpsLastUpdate by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Detection loop with diagnostics
    LaunchedEffect(engineReady) {
        if (!engineReady) return@LaunchedEffect
        var loopCount = 0
        var gateBlockedCount = 0
        var tvNullCount = 0
        var notPlayingCount = 0
        var bitmapNullCount = 0
        var detectNullCount = 0
        var detectEmptyCount = 0
        var detectSuccessCount = 0
        var cachedHitCount = 0
        var lastLogTime = System.currentTimeMillis()

        android.util.Log.i("PoseProcessing", "Detection loop started. Engine ready, delegate=$actualDelegate")

        while (isActive && !playbackCompleted) {
            loopCount++
            val tv = textureView
            val isPlayerPlaying = player.isPlaying

            if (tv == null) { tvNullCount++; delay(16L); continue }
            if (!isPlayerPlaying) { notPlayingCount++; delay(16L); continue }

            if (!processing.compareAndSet(false, true)) {
                gateBlockedCount++
                delay(16L)
                continue
            }

            val posMs = player.currentPosition
            val frameIdx = PoseFileFormat.frameIndexForPosition(posMs, videoFps, slotFile.totalFrames)

            if (slotFile.isFrameFilled(frameIdx)) {
                cachedHitCount++
                val cached = slotFile.readFrame(frameIdx)
                if (cached != null) poseResult = frameToPoseResult(cached)
            } else {
                val bitmap = tv.bitmap
                if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                    bitmapNullCount++
                } else {
                    val startNs = System.nanoTime()
                    val detectResult = withContext(Dispatchers.Default) {
                        runCatching { engine.detect(bitmap) }
                    }
                    val inferMs = (System.nanoTime() - startNs) / 1_000_000L
                    bitmap.recycle()

                    val result = detectResult.getOrNull()
                    if (result == null) {
                        detectNullCount++
                        val error = detectResult.exceptionOrNull()
                        if (detectNullCount <= 5 || detectNullCount % 100 == 0) {
                            android.util.Log.e("PoseProcessing", "detect() FAILED #$detectNullCount: ${error?.message}", error)
                        }
                    } else if (result.landmarks.isEmpty()) {
                        detectEmptyCount++
                    } else {
                        detectSuccessCount++
                        poseResult = result
                        lastInferenceMs = inferMs

                        val poseFrame = detectionToFrame(result, frameIdx)
                        withContext(Dispatchers.IO) {
                            slotFile.writeFrame(frameIdx, poseFrame)
                            sessionNewFrames++
                            filledFrameCount = slotFile.filledFrameCount
                            poseFileSize = poseFile.length()
                        }
                    }
                }
            }

            fpsFrameCount++
            val now = System.currentTimeMillis()
            if (now - fpsLastUpdate >= 1000L) {
                currentFps = fpsFrameCount
                fpsFrameCount = 0
                fpsLastUpdate = now
            }

            // Log summary every 5 seconds
            if (now - lastLogTime >= 5000L) {
                android.util.Log.i("PoseProcessing",
                    "STATS | loops=$loopCount gateBlocked=$gateBlockedCount tvNull=$tvNullCount " +
                    "notPlaying=$notPlayingCount bmpNull=$bitmapNullCount " +
                    "detectNull=$detectNullCount detectEmpty=$detectEmptyCount " +
                    "success=$detectSuccessCount cached=$cachedHitCount " +
                    "pos=${posMs}ms frame=$frameIdx/$totalFrameCount")
                lastLogTime = now
            }

            processing.set(false)

            currentPositionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(1L)
            delay(16L)
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(if (isFirstPass) "首次处理" else "优化补齐") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            // Video + overlay
            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        val container = FrameLayout(ctx)
                        val tv = TextureView(ctx)
                        container.addView(tv, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
                        ))
                        textureView = tv
                        player.setVideoTextureView(tv)
                        player.addListener(object : Player.Listener {
                            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                                if (videoSize.width > 0 && videoSize.height > 0) {
                                    videoAspectRatio = videoSize.width.toFloat() / videoSize.height
                                    tv.post {
                                        val pw = container.width; val ph = container.height
                                        if (pw > 0 && ph > 0) {
                                            val lp = tv.layoutParams as FrameLayout.LayoutParams
                                            val ca = pw.toFloat() / ph
                                            if (videoAspectRatio > ca) { lp.width = pw; lp.height = (pw / videoAspectRatio).toInt() }
                                            else { lp.height = ph; lp.width = (ph * videoAspectRatio).toInt() }
                                            lp.gravity = Gravity.CENTER; tv.layoutParams = lp
                                        }
                                    }
                                }
                            }
                        })
                        container
                    },
                    modifier = Modifier.fillMaxSize()
                )
                PoseOverlay(result = poseResult, imageAspectRatio = videoAspectRatio, modifier = Modifier.fillMaxSize())
            }

            // Progress (non-interactive)
            LinearProgressIndicator(
                progress = { if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // Diagnostics
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("处理状态", style = MaterialTheme.typography.labelLarge)
                    DiagRow("帧覆盖", "$filledFrameCount / $totalFrameCount (${if (totalFrameCount > 0) "${filledFrameCount * 100 / totalFrameCount}%" else "?"})")
                    DiagRow("本次新增", "$sessionNewFrames 帧")
                    DiagRow(".pose 文件", formatFileSize(poseFileSize))
                    DiagRow("检测帧率", "$currentFps fps")
                    DiagRow("推理延迟", "${lastInferenceMs}ms")

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("参数", style = MaterialTheme.typography.labelLarge)
                    DiagRow("模型", modelComplexity.label)
                    DiagRow("委托", actualDelegate)
                    DiagRow("视频", "${session.sourceVideoWidth}×${session.sourceVideoHeight} @${videoFps}fps")
                    DiagRow("时长", formatTime(session.sourceVideoDurationMs))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!isFirstPass && engineReady) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = { isPlaying = !isPlaying }) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                    }
                    IconButton(onClick = { slotFile.close(); onStop() }) {
                        Icon(Icons.Default.Stop, contentDescription = "停止并保存")
                    }
                }
            } else {
                Text(
                    "首次处理中，请等待播放完毕...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeatAnalysisInlineStep(
    session: PoseVideoSession,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("提取音频...") }
    var isComplete by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var resultSummary by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session.id) {
        try {
            val processor = BeatAnalysisProcessor(context)
            processor.analyze(session) { progress ->
                statusText = when (progress) {
                    is BeatAnalysisProcessor.AnalysisProgress.ExtractingAudio -> "提取音频..."
                    is BeatAnalysisProcessor.AnalysisProgress.RunningDSP -> "本地节拍检测..."
                    is BeatAnalysisProcessor.AnalysisProgress.UploadingAudio -> "上传视频..."
                    is BeatAnalysisProcessor.AnalysisProgress.WaitingLLM -> "AI 节拍校准..."
                    is BeatAnalysisProcessor.AnalysisProgress.WritingBeatFile -> "写入节拍文件..."
                    is BeatAnalysisProcessor.AnalysisProgress.Complete -> "节拍分析完成"
                    is BeatAnalysisProcessor.AnalysisProgress.Failed -> "失败: ${progress.error}"
                }
            }
            // Read result summary from .beat file
            val beatFile = File(File(context.filesDir, "pose_data"), "session_${session.id}.beat")
            if (beatFile.exists()) {
                val data = BeatFileFormat.readFile(beatFile)
                if (data != null) {
                    resultSummary = "BPM: ${"%.0f".format(data.header.bpm)}  |  " +
                        "${data.header.timeSignatureNum}/${data.header.timeSignatureDen}  |  " +
                        "${data.beats.size} 拍  |  ${data.segments.size} 段  |  ${data.phrases.size} 短语"
                }
            }
            isComplete = true
            delay(2000L)
            onDone()
        } catch (e: Exception) {
            errorText = e.message
            isComplete = true
            delay(2000L)
            onDone()
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("节拍分析") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isComplete) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
            } else if (resultSummary != null) {
                // Success summary
                Text("节拍分析完成", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        resultSummary ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("即将开始姿态处理...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // Failed
                Text("节拍分析失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorText ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("将跳过节拍，继续姿态处理...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(24.dp))
            if (!isComplete) {
                Button(onClick = onDone, shape = RoundedCornerShape(12.dp)) {
                    Text("跳过")
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun frameToPoseResult(frame: PoseFileFormat.PoseFrame): PoseDetectionResult {
    val set = PoseLandmarkSet(
        normalizedLandmarks = frame.landmarks.map { NormalizedLandmark(it.nx, it.ny, it.nz, it.visibility, it.presence) },
        worldLandmarks = null
    )
    return PoseDetectionResult(listOf(set), frame.timestampMs, 0)
}

private fun detectionToFrame(result: PoseDetectionResult, frameIndex: Int): PoseFileFormat.PoseFrame {
    val landmarks = result.landmarks.firstOrNull()?.let { ps ->
        ps.normalizedLandmarks.mapIndexed { i, nl ->
            val wl = ps.worldLandmarks?.getOrNull(i)
            PoseFileFormat.PoseLandmarkData(nl.x, nl.y, nl.z, nl.visibility, nl.presence, wl?.x ?: 0f, wl?.y ?: 0f, wl?.z ?: 0f)
        }
    } ?: List(33) { PoseFileFormat.PoseLandmarkData(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) }
    return PoseFileFormat.PoseFrame(frameIndex = frameIndex, landmarks = landmarks)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:${"%02d".format(s % 60)}"
}

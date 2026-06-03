package com.example.watcher.ui.screens

import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.watcher.data.local.pose.BeatFileFormat
import com.example.watcher.data.local.pose.DanceSegmentation
import com.example.watcher.data.local.pose.DanceSegmentationEngine
import com.example.watcher.data.local.pose.NormalizedLandmark
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.local.pose.PoseFileFormat
import com.example.watcher.data.local.pose.PoseLandmarkSet
import com.example.watcher.ui.components.MOVE_RAINBOW_COLORS
import com.example.watcher.ui.components.PoseOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DancePosePlaybackScreen(
    sessionId: Long,
    videoPath: String,
    title: String,
    clipStartMs: Long = 0L,
    clipEndMs: Long = 0L,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }
    var poseResult by remember { mutableStateOf<PoseDetectionResult?>(null) }
    var isSeeking by remember { mutableStateOf(false) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    // Pose file — same path as ProcessingScreen: session_${id}.pose
    val poseOutputDir = remember { File(context.filesDir, "pose_data").apply { mkdirs() } }
    val poseFile = remember { File(poseOutputDir, "session_${sessionId}.pose") }
    val slotFile = remember(poseFile) {
        if (poseFile.exists() && poseFile.length() >= 64) {
            runCatching { PoseFileFormat.SlotFile(poseFile) }.getOrNull()
        } else null
    }
    val videoFps = slotFile?.header?.fps?.toInt() ?: 30

    // Beat file
    val beatFile = remember { File(poseOutputDir, "session_${sessionId}.beat") }
    val beatData = remember(beatFile) {
        val exists = beatFile.exists()
        val size = if (exists) beatFile.length() else 0L
        android.util.Log.i("BeatAnalysis", "Playback: beatFile=${beatFile.absolutePath}, exists=$exists, size=$size")
        if (exists && size >= 128) {
            val data = runCatching { BeatFileFormat.readFile(beatFile) }.getOrNull()
            android.util.Log.i("BeatAnalysis", "Playback: loaded ${data?.beats?.size ?: 0} beats, ${data?.segments?.size ?: 0} segments")
            data
        } else null
    }

    // Segmentation file
    val segFile = remember { File(poseOutputDir, "session_${sessionId}.segments.json") }
    val segmentation = remember(segFile) {
        val engine = DanceSegmentationEngine()
        engine.loadFromFile(segFile)
    }

    // ExoPlayer with clipping
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = if (clipStartMs > 0 || clipEndMs > 0) {
                MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(File(videoPath)))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clipStartMs)
                            .setEndPositionMs(if (clipEndMs > clipStartMs) clipEndMs else Long.MAX_VALUE)
                            .build()
                    )
                    .build()
            } else {
                MediaItem.fromUri(android.net.Uri.fromFile(File(videoPath)))
            }
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    var textureView by remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            slotFile?.close()
        }
    }

    // Preview loop: ONLY read from .pose file cache — no real-time detection.
    // Cached frames are perfectly aligned (no inference delay).
    LaunchedEffect(Unit) {
        while (isActive) {
            if (player.isPlaying && !isSeeking) {
                val posMs = player.currentPosition
                val sf = slotFile
                if (sf != null) {
                    val frameIdx = PoseFileFormat.frameIndexForPosition(posMs, videoFps, sf.totalFrames)
                    // Find exact or nearest cached frame (search ±30 frames ≈ ±1 second)
                    val cached = if (sf.isFrameFilled(frameIdx)) {
                        sf.readFrame(frameIdx)
                    } else {
                        sf.findNearestFilled(frameIdx, 30)?.let { nearIdx -> sf.readFrame(nearIdx) }
                    }
                    if (cached != null) {
                        poseResult = cachedFrameToPoseResult(cached)
                    }
                }
            }

            if (!isSeeking) {
                currentPositionMs = player.currentPosition
                durationMs = player.duration.coerceAtLeast(1L)
            }
            delay(33L) // 30fps refresh for smooth overlay
        }
    }

    LaunchedEffect(playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }
    LaunchedEffect(isPlaying) { player.playWhenReady = isPlaying }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        val container = FrameLayout(ctx)
                        val tv = TextureView(ctx)
                        container.addView(tv, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        ))
                        textureView = tv
                        player.setVideoTextureView(tv)

                        player.addListener(object : Player.Listener {
                            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                                if (videoSize.width > 0 && videoSize.height > 0) {
                                    val aspect = videoSize.width.toFloat() / videoSize.height
                                    videoAspectRatio = aspect
                                    tv.post {
                                        val pw = container.width
                                        val ph = container.height
                                        if (pw > 0 && ph > 0) {
                                            val ca = pw.toFloat() / ph
                                            val lp = tv.layoutParams as FrameLayout.LayoutParams
                                            if (aspect > ca) {
                                                lp.width = pw
                                                lp.height = (pw / aspect).toInt()
                                            } else {
                                                lp.height = ph
                                                lp.width = (ph * aspect).toInt()
                                            }
                                            lp.gravity = Gravity.CENTER
                                            tv.layoutParams = lp
                                        }
                                    }
                                }
                            }
                        })
                        container
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Compute move color index from segmentation + current position
                val currentMoveColorIdx = if (segmentation != null) {
                    val currentMove = segmentation.atomicMoves.indexOfLast { it.startMs <= currentPositionMs }
                    if (currentMove >= 0) {
                        // Find which phrase this move belongs to, reset color per phrase
                        val phrase = segmentation.phrases.firstOrNull { p ->
                            segmentation.atomicMoves.getOrNull(currentMove)?.id in p.moveIds
                        }
                        val moveIdxInPhrase = phrase?.moveIds?.indexOf(
                            segmentation.atomicMoves[currentMove].id
                        ) ?: currentMove
                        moveIdxInPhrase.coerceAtLeast(0)
                    } else -1
                } else -1

                PoseOverlay(
                    result = poseResult,
                    imageAspectRatio = videoAspectRatio,
                    modifier = Modifier.fillMaxSize(),
                    moveColorIndex = currentMoveColorIdx
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Move segmentation color bar (atomic moves rainbow)
                if (segmentation != null && durationMs > 0) {
                    MoveColorBar(
                        segmentation = segmentation,
                        durationMs = durationMs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .padding(horizontal = 4.dp)
                    )
                }
                // Song structure color bar (verse/chorus/intro)
                if (beatData != null && beatData.segments.isNotEmpty() && durationMs > 0) {
                    SegmentColorBar(
                        segments = beatData.segments,
                        durationMs = durationMs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .padding(horizontal = 4.dp)
                    )
                }
                Slider(
                    value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f,
                    onValueChange = { fraction ->
                        isSeeking = true
                        currentPositionMs = (fraction * durationMs).toLong()
                    },
                    onValueChangeFinished = {
                        player.seekTo(currentPositionMs)
                        isSeeking = false
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatPlaybackTime(currentPositionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatPlaybackTime(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Beat visualization (always show if available)
                if (beatData != null && durationMs > 0) {
                    BeatPulseIndicator(
                        beats = beatData.beats,
                        currentPositionMs = currentPositionMs,
                        bpm = beatData.header.bpm,
                        timeSignature = "${beatData.header.timeSignatureNum}/${beatData.header.timeSignatureDen}",
                        currentSegment = beatData.segments.lastOrNull { it.startMs <= currentPositionMs.toInt() }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { isPlaying = !isPlaying }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
                        FilterChip(
                            selected = playbackSpeed == speed,
                            onClick = { playbackSpeed = speed },
                            label = { Text("${speed}x") }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BeatPulseIndicator(
    beats: List<BeatFileFormat.BeatEntry>,
    currentPositionMs: Long,
    bpm: Float,
    timeSignature: String,
    currentSegment: BeatFileFormat.SegmentEntry?
) {
    // Find nearest beat to current position
    val nearestBeat = beats.minByOrNull { kotlin.math.abs(it.timestampMs - currentPositionMs.toInt()) }
    val distanceToNearestMs = nearestBeat?.let { kotlin.math.abs(it.timestampMs - currentPositionMs.toInt()) } ?: 1000
    val isOnBeat = distanceToNearestMs < 80
    val isDownbeat = nearestBeat?.beatType == BeatFileFormat.BeatType.DOWNBEAT && isOnBeat

    // Pulse animation
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isOnBeat, currentPositionMs / 50) {
        if (isOnBeat) {
            val targetScale = if (isDownbeat) 1.6f else 1.3f
            scale.snapTo(targetScale)
            scale.animateTo(1f, animationSpec = tween(durationMillis = 200))
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing drum circle
        Canvas(modifier = Modifier.size(36.dp)) {
            val radius = (size.minDimension / 2f) * scale.value
            val color = if (isDownbeat) primaryColor else if (isOnBeat) tertiaryColor else primaryColor.copy(alpha = 0.3f)
            drawCircle(color = color, radius = radius)
        }

        // BPM + info
        Column {
            Text(
                "BPM: ${"%.0f".format(bpm)}  $timeSignature",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (currentSegment != null) {
                Text(
                    currentSegment.segmentType.name.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/**
 * Move-colored timeline bar. Each atomic move gets a rainbow color,
 * resetting to red at phrase boundaries.
 */
@Composable
private fun MoveColorBar(
    segmentation: DanceSegmentation,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        segmentation.atomicMoves.forEachIndexed { idx, move ->
            // Determine color index within phrase (resets to 0 per phrase)
            val phrase = segmentation.phrases.firstOrNull { move.id in it.moveIds }
            val colorIdx = phrase?.moveIds?.indexOf(move.id) ?: idx
            val color = MOVE_RAINBOW_COLORS[colorIdx % MOVE_RAINBOW_COLORS.size]

            val startX = (move.startMs.toFloat() / durationMs) * width
            val endX = (move.endMs.toFloat() / durationMs) * width
            drawRect(
                color = color.copy(alpha = 0.7f),
                topLeft = androidx.compose.ui.geometry.Offset(startX, 0f),
                size = androidx.compose.ui.geometry.Size((endX - startX).coerceAtLeast(1f), height)
            )
        }

        // Phrase boundaries as thin dark lines
        segmentation.phrases.forEach { phrase ->
            val x = (phrase.startMs.toFloat() / durationMs) * width
            drawLine(
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x, height),
                strokeWidth = 2f
            )
        }
    }
}

/**
 * Segment-colored progress overlay drawn behind the Slider.
 * Each segment gets a different color band showing the music structure at a glance.
 */
@Composable
private fun SegmentColorBar(
    segments: List<BeatFileFormat.SegmentEntry>,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    val colors = mapOf(
        BeatFileFormat.SegmentType.INTRO to androidx.compose.ui.graphics.Color(0xFF9E9E9E),
        BeatFileFormat.SegmentType.VERSE to androidx.compose.ui.graphics.Color(0xFF42A5F5),
        BeatFileFormat.SegmentType.CHORUS to androidx.compose.ui.graphics.Color(0xFFEF5350),
        BeatFileFormat.SegmentType.BRIDGE to androidx.compose.ui.graphics.Color(0xFFAB47BC),
        BeatFileFormat.SegmentType.OUTRO to androidx.compose.ui.graphics.Color(0xFF78909C),
        BeatFileFormat.SegmentType.BREAK to androidx.compose.ui.graphics.Color(0xFFFFCA28)
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        segments.forEach { seg ->
            val startX = (seg.startMs.toFloat() / durationMs) * width
            val endX = (seg.endMs.toFloat() / durationMs) * width
            val color = colors[seg.segmentType] ?: androidx.compose.ui.graphics.Color.Gray
            drawRect(
                color = color.copy(alpha = 0.35f),
                topLeft = androidx.compose.ui.geometry.Offset(startX, 0f),
                size = androidx.compose.ui.geometry.Size(endX - startX, height)
            )
        }
    }
}

private fun cachedFrameToPoseResult(frame: PoseFileFormat.PoseFrame): PoseDetectionResult {
    val landmarkSet = PoseLandmarkSet(
        normalizedLandmarks = frame.landmarks.map { lm ->
            NormalizedLandmark(lm.nx, lm.ny, lm.nz, lm.visibility, lm.presence)
        },
        worldLandmarks = null
    )
    return PoseDetectionResult(
        landmarks = listOf(landmarkSet),
        timestampMs = frame.timestampMs,
        inferenceTimeMs = 0
    )
}


private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${"%02d".format(seconds)}"
}

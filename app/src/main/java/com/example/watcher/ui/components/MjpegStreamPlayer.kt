package com.example.watcher.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.R
import com.example.watcher.data.model.VideoStreamSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class MjpegStreamUiState(
    val currentFrame: Bitmap? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val fps: Int = 0,
    val activeStreamUrl: String? = null,
    val source: StreamSource = StreamSource.None,
    val sourceLabel: String = "",
    val showCameraChooser: Boolean = false,
    val chooseCameraLens: (CameraFallbackLens) -> Unit = {},
    val requestSwitchCamera: () -> Unit = {}
)

enum class StreamSource {
    None,
    RemoteMjpeg,
    FrontCameraFallback,
    BackCameraFallback;

    val isCameraFallback: Boolean
        get() = this == FrontCameraFallback || this == BackCameraFallback
}

private const val REMOTE_STREAM_RETRY_ATTEMPTS = 3
private const val REMOTE_STREAM_RETRY_DELAY_MS = 750L
private const val REMOTE_STREAM_UI_FRAME_INTERVAL_MS = 66L

internal class MjpegFramePublishGate(
    private val minFrameIntervalMs: Long = REMOTE_STREAM_UI_FRAME_INTERVAL_MS
) {
    private var lastPublishedFrameMs: Long? = null

    fun shouldPublishFrame(nowMs: Long): Boolean {
        val previous = lastPublishedFrameMs
        if (previous != null && nowMs - previous < minFrameIntervalMs) {
            return false
        }
        lastPublishedFrameMs = nowMs
        return true
    }
}

internal class MjpegFrameDispatchPolicy(
    private val previewActive: Boolean,
    private val previewPublishGate: MjpegFramePublishGate
) {
    fun shouldDispatchBusinessFrame(): Boolean = true

    fun shouldPublishPreviewFrame(nowMs: Long): Boolean {
        return previewActive && previewPublishGate.shouldPublishFrame(nowMs)
    }
}

@Composable
fun rememberMjpegStreamState(
    settings: VideoStreamSettings,
    isPlaying: Boolean,
    reconnectToken: Int = 0,
    previewActive: Boolean = true,
    reservationOwner: String? = null,
    autoSelectFallbackOnUnavailable: Boolean = false,
    onFrameUpdate: (Bitmap?) -> Unit = {},
    onStreamSourceChanged: (StreamSource) -> Unit = {},
    onRemoteStreamUnavailable: () -> Unit = {}
): MjpegStreamUiState {
    // Stream reservation: pause when another component (e.g., AI Fitness) holds exclusive access
    // This replaces lifecycle-based disconnection which broke background monitoring tasks
    val streamReserved by com.example.watcher.data.repository.StreamReservation.owner
        .collectAsState()
    val effectivePlaying = isPlaying && (streamReserved == null || streamReserved == reservationOwner)

    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var connectionStatus by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected) }
    var fps by remember { mutableStateOf(0) }
    var activeStreamUrl by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(StreamSource.None) }
    var sourceLabel by remember { mutableStateOf("") }
    var fallbackRequested by remember { mutableStateOf(false) }
    var showCameraChooser by remember { mutableStateOf(false) }
    var selectedLens by rememberSaveable { mutableStateOf(CameraFallbackPreference.selectedLens) }
    val latestPreviewActive by rememberUpdatedState(previewActive)
    val latestSettings by rememberUpdatedState(settings.normalized())

    fun activateFallback(lens: CameraFallbackLens) {
        selectedLens = lens
        fallbackRequested = true
        showCameraChooser = false
        val chosenLabel = cameraSourceLabel(lens)
        activeStreamUrl = chosenLabel
        source = when (lens) {
            CameraFallbackLens.Front -> StreamSource.FrontCameraFallback
            CameraFallbackLens.Back -> StreamSource.BackCameraFallback
        }
        sourceLabel = chosenLabel
        connectionStatus = ConnectionStatus.Connecting
        CameraFallbackPreference.recordSelectedLens(lens)
    }

    val fallbackState = rememberCameraFallbackState(
        active = effectivePlaying && fallbackRequested && !showCameraChooser,
        lens = selectedLens,
        reconnectToken = reconnectToken,
        frameTransform = { frame ->
            val frameSettings = latestSettings
            StreamFrameTransform.apply(
                bitmap = frame,
                rotationDegrees = frameSettings.rotationDegrees,
                mirrorHorizontally = frameSettings.mirrorHorizontally
            )
        },
        onFrameUpdate = onFrameUpdate
    )

    LaunchedEffect(source) {
        onStreamSourceChanged(source)
    }

    LaunchedEffect(previewActive) {
        if (!previewActive) {
            currentFrame = null
        }
    }

    LaunchedEffect(effectivePlaying, settings.streamUrl, reconnectToken) {
        if (!effectivePlaying) {
            fallbackRequested = false
            connectionStatus = ConnectionStatus.Disconnected
            currentFrame = null
            fps = 0
            activeStreamUrl = null
            source = StreamSource.None
            sourceLabel = ""
            onFrameUpdate(null)
            return@LaunchedEffect
        }

        fallbackRequested = false
        connectionStatus = ConnectionStatus.Connecting
        source = StreamSource.RemoteMjpeg
        sourceLabel = settings.streamDisplayUrl
        activeStreamUrl = settings.streamDisplayUrl
        if (CameraFallbackPreference.preferFallbackFirst) {
            activateFallback(CameraFallbackPreference.selectedLens)
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            var shouldUseFallback = false
            var streamStopped = false
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build()
                var lastError: String? = null

                candidateLoop@ for ((index, streamUrl) in settings.candidateStreamUrls.withIndex()) {
                    for (attempt in 0 until REMOTE_STREAM_RETRY_ATTEMPTS) {
                        if (!isActive || !isPlaying) {
                            streamStopped = true
                            break@candidateLoop
                        }

                        var attemptFailed = false

                        withContext(Dispatchers.Main) {
                            connectionStatus = ConnectionStatus.Connecting
                            activeStreamUrl = streamUrl
                            source = StreamSource.RemoteMjpeg
                            sourceLabel = settings.streamDisplayUrl
                        }

                        try {
                            val request = Request.Builder()
                                .url(streamUrl)
                                .build()

                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    lastError = "HTTP ${response.code}"
                                    attemptFailed = true
                                    return@use
                                }

                                val body = response.body ?: run {
                                    lastError = "Empty response body"
                                    attemptFailed = true
                                    return@use
                                }

                                val reader = MjpegReader(BufferedInputStream(body.byteStream(), 8_192))
                                withContext(Dispatchers.Main) {
                                    connectionStatus = ConnectionStatus.Connected
                                    source = StreamSource.RemoteMjpeg
                                    sourceLabel = settings.streamDisplayUrl
                                    CameraFallbackPreference.markRemoteConnected()
                                }

                                var frameCount = 0
                                var lastFpsTimestamp = System.currentTimeMillis()
                                val framePublishGate = MjpegFramePublishGate()

                                while (isActive && isPlaying) {
                                    try {
                                        val frame = reader.readFrame()
                                        if (frame != null) {
                                            val frameSettings = latestSettings
                                            val displayFrame = StreamFrameTransform.apply(
                                                bitmap = frame,
                                                rotationDegrees = frameSettings.rotationDegrees,
                                                mirrorHorizontally = frameSettings.mirrorHorizontally
                                            )
                                            val now = System.currentTimeMillis()
                                            val dispatchPolicy = MjpegFrameDispatchPolicy(
                                                previewActive = latestPreviewActive,
                                                previewPublishGate = framePublishGate
                                            )
                                            if (dispatchPolicy.shouldDispatchBusinessFrame()) {
                                                withContext(Dispatchers.Main) {
                                                    onFrameUpdate(displayFrame)
                                                    if (dispatchPolicy.shouldPublishPreviewFrame(now)) {
                                                        currentFrame = displayFrame
                                                    }
                                                }
                                            }
                                            frameCount += 1
                                            if (now - lastFpsTimestamp >= 1_000) {
                                                withContext(Dispatchers.Main) {
                                                    fps = frameCount
                                                }
                                                frameCount = 0
                                                lastFpsTimestamp = now
                                            }
                                        }
                                    } catch (_: SocketTimeoutException) {
                                        continue
                                    } catch (error: Exception) {
                                        Log.e("MjpegStream", "Failed to read frame", error)
                                        lastError = error.message ?: "Stream read failed"
                                        attemptFailed = true
                                        break
                                    }
                                }

                                if (!isActive || !isPlaying) {
                                    streamStopped = true
                                } else if (!attemptFailed) {
                                    lastError = lastError ?: "Stream disconnected"
                                    attemptFailed = true
                                }
                            }
                        } catch (error: Exception) {
                            Log.e("MjpegStream", "Failed to connect stream", error)
                            lastError = error.message ?: "Connection failed"
                            attemptFailed = true
                        }

                        if (streamStopped) {
                            break@candidateLoop
                        }

                        if (!attemptFailed) {
                            break@candidateLoop
                        }

                        val hasMoreAttempts = attempt < REMOTE_STREAM_RETRY_ATTEMPTS - 1
                        if (hasMoreAttempts) {
                            withContext(Dispatchers.Main) {
                                connectionStatus = ConnectionStatus.Connecting
                            }
                            delay(REMOTE_STREAM_RETRY_DELAY_MS)
                            continue
                        }

                        val hasMoreCandidates = index < settings.candidateStreamUrls.lastIndex
                        if (hasMoreCandidates) {
                            withContext(Dispatchers.Main) {
                                connectionStatus = ConnectionStatus.Connecting
                            }
                            break
                        }

                        shouldUseFallback = true
                        withContext(Dispatchers.Main) {
                            connectionStatus = ConnectionStatus.Error(lastError ?: "Connection failed")
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("MjpegStream", "Failed to connect stream", error)
                shouldUseFallback = true
            } finally {
                withContext(Dispatchers.Main) {
                    if (shouldUseFallback) {
                        onRemoteStreamUnavailable()
                        currentFrame = null
                        fps = 0
                        onFrameUpdate(null)
                        if (autoSelectFallbackOnUnavailable) {
                            activateFallback(CameraFallbackPreference.selectedLens)
                        } else {
                            showCameraChooser = true
                            connectionStatus = ConnectionStatus.Connecting
                        }
                    } else {
                        currentFrame = null
                        fps = 0
                        activeStreamUrl = null
                        source = StreamSource.None
                        sourceLabel = ""
                        onFrameUpdate(null)
                        if (!streamStopped && connectionStatus is ConnectionStatus.Connected) {
                            connectionStatus = ConnectionStatus.Disconnected
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(fallbackRequested, fallbackState, selectedLens) {
        if (!fallbackRequested) {
            return@LaunchedEffect
        }
        currentFrame = fallbackState.currentFrame
        connectionStatus = fallbackState.connectionStatus
        fps = fallbackState.fps
        activeStreamUrl = fallbackState.sourceLabel
        source = when (selectedLens) {
            CameraFallbackLens.Front -> StreamSource.FrontCameraFallback
            CameraFallbackLens.Back -> StreamSource.BackCameraFallback
        }
        sourceLabel = fallbackState.sourceLabel
    }

    return MjpegStreamUiState(
        currentFrame = currentFrame,
        connectionStatus = connectionStatus,
        fps = fps,
        activeStreamUrl = activeStreamUrl,
        source = source,
        sourceLabel = sourceLabel,
        showCameraChooser = showCameraChooser,
        chooseCameraLens = { lens ->
            activateFallback(lens)
        },
        requestSwitchCamera = {
            showCameraChooser = true
        }
    )
}

@Composable
fun MjpegStreamPlayer(
    settings: VideoStreamSettings,
    isPlaying: Boolean,
    previewActive: Boolean = true,
    reservationOwner: String? = null,
    autoSelectFallbackOnUnavailable: Boolean = false,
    onPlayingChange: (Boolean) -> Unit,
    onFrameCaptured: (Bitmap) -> Unit = {},
    onFrameUpdate: (Bitmap?) -> Unit = {},
    onStreamSourceChanged: (StreamSource) -> Unit = {},
    onRemoteStreamUnavailable: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val streamState = rememberMjpegStreamState(
        settings = settings,
        isPlaying = isPlaying,
        previewActive = previewActive,
        reservationOwner = reservationOwner,
        autoSelectFallbackOnUnavailable = autoSelectFallbackOnUnavailable,
        onFrameUpdate = onFrameUpdate,
        onStreamSourceChanged = onStreamSourceChanged,
        onRemoteStreamUnavailable = onRemoteStreamUnavailable
    )

    val connectionAccent = when (streamState.connectionStatus) {
        ConnectionStatus.Connected -> MaterialTheme.colorScheme.tertiary
        ConnectionStatus.Connecting -> MaterialTheme.colorScheme.secondary
        is ConnectionStatus.Error -> MaterialTheme.colorScheme.error
        ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.stream_live_content_description),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = streamState.sourceLabel.ifBlank { settings.streamDisplayUrl },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StreamStatusPill(
                    text = when (streamState.connectionStatus) {
                        ConnectionStatus.Connected -> stringResource(R.string.stream_connection_connected)
                        ConnectionStatus.Connecting -> stringResource(R.string.stream_connection_connecting)
                        is ConnectionStatus.Error -> stringResource(R.string.stream_connection_error)
                        ConnectionStatus.Disconnected -> stringResource(R.string.stream_connection_disconnected)
                    },
                    accent = connectionAccent
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (streamState.currentFrame != null) {
                        Image(
                            bitmap = streamState.currentFrame.asImageBitmap(),
                            contentDescription = stringResource(R.string.stream_live_content_description),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.34f)
                                        )
                                    )
                                )
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = null,
                                        modifier = Modifier.padding(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = when (val status = streamState.connectionStatus) {
                                        ConnectionStatus.Connected -> stringResource(R.string.stream_status_receiving)
                                        ConnectionStatus.Connecting -> stringResource(R.string.stream_status_connecting)
                                        is ConnectionStatus.Error -> status.message
                                        ConnectionStatus.Disconnected -> stringResource(R.string.stream_status_idle)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (streamState.source.isCameraFallback) {
                            FilledTonalIconButton(
                                onClick = { streamState.requestSwitchCamera() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cameraswitch,
                                    contentDescription = "切换摄像头"
                                )
                            }
                        }
                        FilledTonalIconButton(
                            enabled = streamState.currentFrame != null,
                            onClick = { streamState.currentFrame?.let(onFrameCaptured) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = stringResource(R.string.stream_capture_snapshot)
                            )
                        }
                        FilledTonalIconButton(onClick = { onPlayingChange(!isPlaying) }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) {
                                    stringResource(R.string.stream_stop)
                                } else {
                                    stringResource(R.string.stream_start)
                                }
                            )
                        }
                    }
                }
            }

            if (streamState.connectionStatus is ConnectionStatus.Connected) {
                Text(
                    text = buildString {
                        append(stringResource(R.string.stream_fps, streamState.fps))
                        if (streamState.source.isCameraFallback) {
                            val badge = if (streamState.source == StreamSource.FrontCameraFallback) "前摄降级" else "后摄降级"
                            append(" · $badge")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun StreamStatusPill(
    text: String,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

private fun formatStreamConnectionError(
    error: Throwable,
    settings: VideoStreamSettings
): String {
    return when (error) {
        is UnknownHostException -> {
            if (settings.ipAddress.endsWith(".local", ignoreCase = true)) {
                "Cannot resolve ${settings.ipAddress}. Try the camera's LAN IP instead of mDNS."
            } else {
                "Cannot resolve host ${settings.ipAddress}."
            }
        }
        is ConnectException -> "Cannot connect to ${settings.streamDisplayUrl}. Check the hotspot or local network."
        is SocketTimeoutException -> "The camera stream timed out. Check the network or whether /stream is reachable."
        is SocketException -> error.message ?: "The network connection was interrupted."
        else -> error.message ?: "Failed to connect to the camera stream."
    }
}

class MjpegReader(private val stream: BufferedInputStream) {
    private val frameBuffer = ByteArrayOutputStream(64 * 1024)

    fun readFrame(): Bitmap? {
        while (true) {
            val currentByte = stream.read()
            if (currentByte == -1) {
                return null
            }
            if (currentByte == 0xFF) {
                val nextByte = stream.read()
                if (nextByte == 0xD8) {
                    frameBuffer.reset()
                    frameBuffer.write(0xFF)
                    frameBuffer.write(0xD8)
                    var previousByte = 0xFF

                    while (true) {
                        val value = stream.read()
                        if (value == -1) {
                            return null
                        }
                        frameBuffer.write(value)
                        if (previousByte == 0xFF && value == 0xD9) {
                            val byteArray = frameBuffer.toByteArray()
                            return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                        }
                        previousByte = value
                    }
                }
            }
        }
    }
}

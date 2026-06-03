package com.example.watcher.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.ui.screens.HubPage
import com.example.watcher.ui.screens.StepState
import com.example.watcher.ui.theme.LocalWatcherExtendedColors

@Composable
internal fun WatcherCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
        tonalElevation = 4.dp,
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            extendedColors.surfaceContainerLow.copy(alpha = 0.92f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
internal fun ConnectionConfigCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    detail: String,
    onClick: () -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current
    WatcherCard(modifier = modifier, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = extendedColors.surfaceContainer) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp),
                    tint = pageAccent(HubPage.Hub)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Edit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun CameraPreviewCard(
    title: String,
    subtitle: String,
    streamState: MjpegStreamUiState,
    isPlaying: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onCaptureSnapshot: (Bitmap) -> Unit,
    onOpenSettings: () -> Unit,
    compact: Boolean,
    showFooterText: Boolean = true,
    showAiBadge: Boolean = false
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val cardShape = RoundedCornerShape(if (compact) 28.dp else 36.dp)
    val showReconnect = !isPlaying ||
        streamState.connectionStatus is ConnectionStatus.Error ||
        streamState.connectionStatus == ConnectionStatus.Disconnected

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        color = extendedColors.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
        shadowElevation = 12.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 220.dp else 300.dp)
                .clip(cardShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            extendedColors.surfaceContainerHighest,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.30f)
                        )
                    )
                )
        ) {
            PreviewImage(streamState = streamState)
            PreviewHeader(streamState = streamState, showAiBadge = showAiBadge)
            if (showFooterText) {
                PreviewFooter(
                    title = title,
                    subtitle = subtitle,
                    streamState = streamState
                )
            }
            PreviewActions(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                currentFrame = streamState.currentFrame,
                showReconnect = showReconnect,
                onPlayingChange = onPlayingChange,
                onReconnect = onReconnect,
                onCaptureSnapshot = onCaptureSnapshot,
                onOpenSettings = onOpenSettings
            )
        }
    }
}

@Composable
private fun PreviewHeader(
    streamState: MjpegStreamUiState,
    showAiBadge: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                text = connectionHeadline(streamState.connectionStatus),
                accent = connectionColor(streamState.connectionStatus)
            )
            if (streamState.connectionStatus is ConnectionStatus.Connected) {
                StatusPill(
                    text = buildString {
                        append("${streamState.fps} FPS")
                        if (streamState.source.isCameraFallback) {
                            val badge = if (streamState.source == StreamSource.FrontCameraFallback) "前摄" else "后摄"
                            append(" · $badge")
                        }
                    },
                    accent = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (showAiBadge) {
            StatusPill(text = "AI ready", accent = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun BoxScope.PreviewFooter(
    title: String,
    subtitle: String,
    streamState: MjpegStreamUiState
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.84f)
        )
        Text(
            text = when (val status = streamState.connectionStatus) {
                ConnectionStatus.Connected -> if (streamState.source.isCameraFallback) {
                    val cameraName = if (streamState.source == StreamSource.FrontCameraFallback) "前置" else "后置"
                    "ESP32 流不可用，当前已自动切换到手机${cameraName}摄像头。"
                } else {
                    "Auto-connect is on. Saving a new address reconnects right away."
                }
                ConnectionStatus.Connecting -> "Connecting to ${streamState.activeStreamUrl ?: streamState.sourceLabel.ifBlank { subtitle }}"
                is ConnectionStatus.Error -> status.message
                ConnectionStatus.Disconnected -> "Stream is paused. Tap reconnect to resume."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BoxScope.PreviewActions(
    modifier: Modifier,
    currentFrame: Bitmap?,
    showReconnect: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onCaptureSnapshot: (Bitmap) -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = { currentFrame?.let(onCaptureSnapshot) },
            enabled = currentFrame != null,
            shape = RoundedCornerShape(18.dp),
            colors = previewActionColors()
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Snapshot")
        }
    }
}

@Composable
private fun BoxScope.PreviewImage(streamState: MjpegStreamUiState) {
    val frame = streamState.currentFrame
    if (frame != null) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "Camera preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.08f),
                            Color.Black.copy(alpha = 0.44f)
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.18f)) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = null,
                        modifier = Modifier.padding(18.dp),
                        tint = Color.White
                    )
                }
                Text(
                    text = connectionPlaceholder(streamState.connectionStatus),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent, CircleShape)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
internal fun StepProgressRow(steps: List<StepState>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEach { step ->
            val accent = when {
                step.active -> MaterialTheme.colorScheme.primary
                step.completed -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent.copy(alpha = if (step.active) 1f else 0.45f))
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (step.active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
internal fun StepBlock(
    number: Int,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = extendedColors.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Text(
                        text = number.toString(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
internal fun ActionRow(
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    primaryEnabled: Boolean,
    secondaryLabel: String,
    onSecondaryClick: () -> Unit,
    secondaryEnabled: Boolean,
    secondaryIcon: ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onPrimaryClick,
            enabled = primaryEnabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(primaryLabel)
        }
        FilledTonalButton(
            onClick = onSecondaryClick,
            enabled = secondaryEnabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(secondaryIcon, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(secondaryLabel)
        }
    }
}

@Composable
internal fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = formFieldColors()
    )
}

@Composable
internal fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun HistoryTile(
    title: String,
    subtitle: String,
    supporting: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val extendedColors = LocalWatcherExtendedColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) accent.copy(alpha = 0.12f) else extendedColors.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    StatusPill(text = "Selected", accent = accent)
                } else if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete")
                    }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
internal fun formFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
)

@Composable
private fun previewActionColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = Color.White.copy(alpha = 0.86f),
    contentColor = MaterialTheme.colorScheme.onSurface
)

private fun connectionHeadline(status: ConnectionStatus): String {
    return when (status) {
        ConnectionStatus.Connected -> "Camera online"
        ConnectionStatus.Connecting -> "Connecting"
        is ConnectionStatus.Error -> "Connection failed"
        ConnectionStatus.Disconnected -> "Paused"
    }
}

private fun connectionPlaceholder(status: ConnectionStatus): String {
    return when (status) {
        ConnectionStatus.Connected -> "Receiving live frames"
        ConnectionStatus.Connecting -> "Connecting to the live stream"
        is ConnectionStatus.Error -> status.message
        ConnectionStatus.Disconnected -> "Stream is paused"
    }
}

private fun connectionColor(status: ConnectionStatus): Color {
    return when (status) {
        ConnectionStatus.Connected -> Color(0xFF006C49)
        ConnectionStatus.Connecting -> Color(0xFF0058BE)
        is ConnectionStatus.Error -> Color(0xFFBA1A1A)
        ConnectionStatus.Disconnected -> Color(0xFF727785)
    }
}

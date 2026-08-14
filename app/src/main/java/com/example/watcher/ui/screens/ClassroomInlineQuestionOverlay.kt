package com.example.watcher.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.ClassroomInlineQuestionType
import com.example.watcher.data.model.ClassroomInlineQuestionUiState
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.repository.ClassroomTranscriptSelectionPolicy
import com.example.watcher.ui.components.RoseFourLoader
import java.io.File


internal data class InlineQuestionFramePreviewState(
    val framePath: String,
    val frameTimestampMs: Long
)


@Composable
internal fun InlineQuestionGlassOverlay(
    status: VideoProcessingStatus,
    selectedCount: Int,
    expansionReady: Boolean,
    onAnswerInlineQuestion: (ClassroomInlineQuestionType) -> Unit,
    onDismissInlineQuestion: () -> Unit,
    onOpenFramePreview: (String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val questionState = status.inlineQuestionState
    val subtitle = when {
        questionState.isLoading -> "正在结合课堂生成解释..."
        questionState.answerText.isNotBlank() && questionState.questionType != null -> "已回答：${questionState.questionType.label}"
        questionState.errorMessage != null && questionState.questionType != null -> "处理失败：${questionState.questionType.label}"
        else -> "已选 $selectedCount 条：1 条核心 + 2 条次要即可提问"
    }
    val panelMode = when {
        questionState.isLoading -> InlineQuestionPanelMode.Loading
        questionState.answerText.isNotBlank() -> InlineQuestionPanelMode.Answer
        questionState.errorMessage != null -> InlineQuestionPanelMode.Error
        selectedCount < ClassroomTranscriptSelectionPolicy.MIN_SELECTIONS_TO_ASK || !expansionReady -> InlineQuestionPanelMode.Priming
        else -> InlineQuestionPanelMode.ActionPicker
    }
    GlassFloatingCard(
        modifier = modifier,
        compact = panelMode == InlineQuestionPanelMode.Priming
    ) {
        Crossfade(
            targetState = panelMode,
            animationSpec = tween(durationMillis = 240, easing = LinearEasing),
            label = "inline-question-panel"
        ) { mode ->
            when (mode) {
                InlineQuestionPanelMode.Priming -> InlineQuestionPrimingCapsule()
                else -> InlineQuestionExpandedContent(
                    mode = mode,
                    subtitle = subtitle,
                    questionState = questionState,
                    onAnswerInlineQuestion = onAnswerInlineQuestion,
                    onDismissInlineQuestion = onDismissInlineQuestion,
                    onOpenFramePreview = onOpenFramePreview
                )
            }
        }
    }
}

private enum class InlineQuestionPanelMode {
    Priming,
    ActionPicker,
    Loading,
    Answer,
    Error
}

@Composable
private fun InlineQuestionPrimingCapsule() {
    RoseFourLoader(modifier = Modifier.size(46.dp))
}

@Composable
private fun InlineQuestionExpandedContent(
    mode: InlineQuestionPanelMode,
    subtitle: String,
    questionState: ClassroomInlineQuestionUiState,
    onAnswerInlineQuestion: (ClassroomInlineQuestionType) -> Unit,
    onDismissInlineQuestion: () -> Unit,
    onOpenFramePreview: (String, Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = tween(durationMillis = 280, easing = LinearEasing)
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("快捷解释", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            InlineQuestionCloseButton(onClick = onDismissInlineQuestion)
        }
        when (mode) {
            InlineQuestionPanelMode.Loading -> InlineQuestionLoadingPanel()
            InlineQuestionPanelMode.Answer -> InlineQuestionAnswerContent(
                questionState = questionState,
                onOpenFramePreview = onOpenFramePreview
            )
            InlineQuestionPanelMode.Error -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = questionState.errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                questionState.questionType?.let { type ->
                    InlineQuestionActionButton(
                        text = "重试当前问题",
                        onClick = { onAnswerInlineQuestion(type) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            InlineQuestionPanelMode.ActionPicker -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    ClassroomInlineQuestionType.Explain,
                    ClassroomInlineQuestionType.Example,
                    ClassroomInlineQuestionType.Why
                ).forEach { type ->
                    InlineQuestionActionButton(
                        text = type.label,
                        onClick = { onAnswerInlineQuestion(type) },
                        enabled = !questionState.isLoading,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            InlineQuestionPanelMode.Priming -> Spacer(modifier = Modifier.size(0.dp))
        }
    }
}

@Composable
private fun InlineQuestionLoadingPanel() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoseFourLoader(modifier = Modifier.size(52.dp))
        Text(
            text = "正在结合课堂生成解释...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InlineQuestionAnswerContent(
    questionState: ClassroomInlineQuestionUiState,
    onOpenFramePreview: (String, Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = questionState.answerText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        InlineQuestionFrameThumbnail(
            framePath = questionState.visualFramePath,
            frameTimestampMs = questionState.visualFrameTimestampMs,
            onClick = {
                onOpenFramePreview(
                    questionState.visualFramePath,
                    questionState.visualFrameTimestampMs
                )
            }
        )
    }
}

@Composable
private fun InlineQuestionFrameThumbnail(
    framePath: String,
    frameTimestampMs: Long,
    onClick: () -> Unit
) {
    val bitmap = rememberFrameBitmap(framePath) ?: return
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.26f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.12f)
                    )
                )
            )
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.58f)),
                shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(width = 96.dp, height = 62.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.62f)),
                    RoundedCornerShape(14.dp)
                ),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "对应时刻",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${formatInlineFrameTime(frameTimestampMs)} · 课堂画面",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "点击查看大图",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun InlineQuestionFramePreviewOverlay(
    preview: InlineQuestionFramePreviewState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = rememberFrameBitmap(preview.framePath) ?: return
    var scale by remember(preview.framePath) { mutableStateOf(1f) }
    var offset by remember(preview.framePath) { mutableStateOf(Offset.Zero) }
    val imageClickSource = remember { MutableInteractionSource() }
    val outsideClickSource = remember { MutableInteractionSource() }
    val bitmapAspectRatio = remember(bitmap) {
        (bitmap.width.toFloat() / bitmap.height.toFloat()).takeIf { it.isFinite() && it > 0f } ?: 1f
    }
    BoxWithConstraints(
        modifier = modifier
            .clickable(
                interactionSource = outsideClickSource,
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val maxImageWidthPx = with(density) { maxWidth.toPx() * 0.92f }
        val maxImageHeightPx = with(density) { maxHeight.toPx() * 0.92f }
        val availableAspectRatio = maxImageWidthPx / maxImageHeightPx
        val imageWidthPx: Float
        val imageHeightPx: Float
        if (availableAspectRatio > bitmapAspectRatio) {
            imageHeightPx = maxImageHeightPx
            imageWidthPx = imageHeightPx * bitmapAspectRatio
        } else {
            imageWidthPx = maxImageWidthPx
            imageHeightPx = imageWidthPx / bitmapAspectRatio
        }
        val imageWidth = with(density) { imageWidthPx.toDp() }
        val imageHeight = with(density) { imageHeightPx.toDp() }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(width = imageWidth, height = imageHeight)
                .pointerInput(preview.framePath) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        offset = if (nextScale > 1f) {
                            offset + pan
                        } else {
                            Offset.Zero
                        }
                    }
                }
                .clickable(
                    interactionSource = imageClickSource,
                    indication = null,
                    onClick = {}
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun rememberFrameBitmap(framePath: String): Bitmap? {
    return remember(framePath) {
        framePath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
            ?.let { file -> runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
    }
}

@Composable
private fun InlineQuestionCloseButton(onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = primary
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "关闭",
            style = MaterialTheme.typography.labelMedium,
            color = primary
        )
    }
}

@Composable
private fun InlineQuestionActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(14.dp)
    val primary = MaterialTheme.colorScheme.primary
    val contentColor = if (enabled) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        primary.copy(alpha = if (enabled) 0.10f else 0.04f),
                        Color.White.copy(alpha = if (enabled) 0.18f else 0.08f)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    if (enabled) primary.copy(alpha = 0.42f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
                ),
                shape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GlassFloatingCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .then(
                if (compact) {
                    Modifier.size(56.dp)
                } else {
                    Modifier
                        .fillMaxWidth(0.92f)
                        .widthIn(max = 520.dp)
                }
            )
            .animateContentSize(
                animationSpec = tween(durationMillis = 380, easing = LinearEasing)
            )
            .clip(shape)
            .background(surface.copy(alpha = 0.90f))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.44f),
                        surfaceVariant.copy(alpha = 0.36f),
                        primary.copy(alpha = 0.12f),
                        surface.copy(alpha = 0.48f)
                    )
                )
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.Transparent,
                        Color.White.copy(alpha = 0.08f)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.96f),
                            primary.copy(alpha = 0.46f),
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.26f)
                        )
                    )
                ),
                shape
            )
            .padding(if (compact) 5.dp else 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}


private fun formatInlineFrameTime(timestampMs: Long): String {
    return formatDuration((timestampMs / 1_000L).toInt().coerceAtLeast(0))
}

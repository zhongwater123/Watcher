package com.example.watcher.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.watcher.ui.components.pageAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal enum class ConciseMonitorSetupPath {
    Reference,
    Direct
}

private data class ConciseTypewriterCopy(
    val line1: String,
    val line2: String
)

private val conciseReferenceChoiceCopies = listOf(
    ConciseTypewriterCopy("TA来了...", "就跟我说"),
    ConciseTypewriterCopy("看到这小猫", "就告诉我"),
    ConciseTypewriterCopy("TA进画面了", "就提醒我"),
    ConciseTypewriterCopy("这个人来了", "就告诉我")
)

private val conciseDirectChoiceCopies = listOf(
    ConciseTypewriterCopy("书被拿了", "就告诉我"),
    ConciseTypewriterCopy("检测到异动", "立即提醒"),
    ConciseTypewriterCopy("设备关了", "就通知我"),
    ConciseTypewriterCopy("现场变样了", "提醒我"),
    ConciseTypewriterCopy("有人来了", "就和我说"),
    ConciseTypewriterCopy("物品移动", "自动提醒"),
    ConciseTypewriterCopy("状态变化", "及时通知")
)

@Composable
internal fun ConciseDynamicSetupFrame(
    selectedPath: ConciseMonitorSetupPath?,
    isPageFocused: Boolean,
    onSelectPath: (ConciseMonitorSetupPath) -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    var revealContent by remember(selectedPath) { mutableStateOf(false) }
    LaunchedEffect(selectedPath) {
        revealContent = false
        if (selectedPath != null) {
            delay(560L)
            revealContent = true
        }
    }
    val isExpanded = selectedPath != null && revealContent
    val shape = RoundedCornerShape(if (isExpanded) 32.dp else 38.dp)
    val collapsedHeight = 76.dp
    val borderAccent = when (selectedPath) {
        ConciseMonitorSetupPath.Reference -> pageAccent(HubPage.Monitor)
        ConciseMonitorSetupPath.Direct -> pageAccent(HubPage.Hub)
        null -> MaterialTheme.colorScheme.primary
    }
    val contentAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(220, delayMillis = if (isExpanded) 120 else 0),
        label = "conciseDynamicContentAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(360, easing = FastOutSlowInEasing)),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (isExpanded) 0.88f else 0.94f),
                    borderAccent.copy(alpha = if (isExpanded) 0.32f else 0.22f),
                    MaterialTheme.colorScheme.outline.copy(alpha = if (isExpanded) 0.18f else 0.10f)
                )
            )
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = collapsedHeight)
        ) {
            ConciseWaveDividerBackground(
                selectedPath = selectedPath,
                isPageFocused = isPageFocused,
                modifier = Modifier.matchParentSize()
            )
            ConciseFrostedGlassLayer(
                selectedPath = selectedPath,
                modifier = Modifier.matchParentSize()
            )

            if (!isExpanded) {
                Row(modifier = Modifier.fillMaxWidth().height(collapsedHeight)) {
                    ConciseDynamicChoice(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        side = ConciseMonitorSetupPath.Reference,
                        selectedPath = selectedPath,
                        copies = conciseReferenceChoiceCopies,
                        initialDelayMillis = 0L,
                        onClick = { onSelectPath(ConciseMonitorSetupPath.Reference) }
                    )
                    ConciseDynamicChoice(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        side = ConciseMonitorSetupPath.Direct,
                        selectedPath = selectedPath,
                        copies = conciseDirectChoiceCopies,
                        initialDelayMillis = 6_000L,
                        onClick = { onSelectPath(ConciseMonitorSetupPath.Direct) }
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                        .graphicsLayer { alpha = contentAlpha },
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val titleBlock: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ConciseTypewriterTitle(
                                    line1 = if (selectedPath == ConciseMonitorSetupPath.Reference) {
                                        "让Watcher帮你留意"
                                    } else {
                                        "让Watcher帮你看管"
                                    },
                                    line2 = if (selectedPath == ConciseMonitorSetupPath.Reference) {
                                        "某个人或物"
                                    } else {
                                        "环境中的情况"
                                    },
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                    cursorColor = Color.Black,
                                    horizontalAlignment = Alignment.Start
                                )
                                ConciseTypewriterTitle(
                                    line1 = if (selectedPath == ConciseMonitorSetupPath.Reference) {
                                        "人海物影中，只为你认出TA"
                                    } else {
                                        "昼夜守望，替你看见异动"
                                    },
                                    line2 = "",
                                    style = MaterialTheme.typography.bodySmall,
                                    cursorColor = Color.Black.copy(alpha = 0.68f),
                                    textColor = Color.Black.copy(alpha = 0.68f),
                                    horizontalAlignment = Alignment.Start,
                                    keepCursorAfterComplete = false
                                )
                            }
                        }
                        val backButton: @Composable () -> Unit = {
                            TextButton(onClick = onBack) {
                                if (selectedPath == ConciseMonitorSetupPath.Direct) {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.Black
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("返回", color = Color.Black)
                                } else {
                                    Text("返回", color = Color.Black)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .graphicsLayer { rotationZ = 180f },
                                        tint = Color.Black
                                    )
                                }
                            }
                        }
                        if (selectedPath == ConciseMonitorSetupPath.Direct) {
                            backButton()
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                titleBlock()
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f)) {
                                titleBlock()
                            }
                            backButton()
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.90f),
                                    Color.Black.copy(alpha = 0.16f),
                                    borderAccent.copy(alpha = 0.10f)
                                )
                            )
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConciseDynamicChoice(
    modifier: Modifier,
    side: ConciseMonitorSetupPath,
    selectedPath: ConciseMonitorSetupPath?,
    copies: List<ConciseTypewriterCopy>,
    initialDelayMillis: Long,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    var copyIndex by remember(copies) { mutableStateOf(0) }
    LaunchedEffect(copies, initialDelayMillis) {
        copyIndex = 0
        if (initialDelayMillis > 0L) {
            delay(initialDelayMillis)
            copyIndex = (copyIndex + 1) % copies.size
        }
        while (true) {
            delay(12_000L)
            copyIndex = (copyIndex + 1) % copies.size
        }
    }
    val currentCopy = copies[copyIndex]
    val selected = selectedPath == side
    val sweptAway = selectedPath != null && !selected
    val textOffset by animateFloatAsState(
        targetValue = when {
            !sweptAway -> 0f
            side == ConciseMonitorSetupPath.Reference -> -86f
            else -> 86f
        },
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "conciseChoiceTextSweep"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (sweptAway) 0f else 1f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "conciseChoiceTextAlpha"
    )
    Box(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        ),
        contentAlignment = if (side == ConciseMonitorSetupPath.Reference) {
            Alignment.CenterEnd
        } else {
            Alignment.CenterStart
        }
    ) {
        Column(
            modifier = Modifier
                .width(96.dp)
                .graphicsLayer {
                    translationX = textOffset + if (side == ConciseMonitorSetupPath.Reference) -120f else 120f
                    alpha = textAlpha
                },
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            ConciseTypewriterTitle(
                line1 = currentCopy.line1,
                line2 = currentCopy.line2,
                horizontalAlignment = Alignment.Start
            )
        }
    }
}

@Composable
internal fun ConciseTypewriterTitle(
    line1: String,
    line2: String,
    style: TextStyle? = null,
    cursorColor: Color? = null,
    textColor: Color = Color.Black,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    keepCursorAfterComplete: Boolean = true
) {
    var displayedLine1 by remember(line1, line2) { mutableStateOf("") }
    var displayedLine2 by remember(line1, line2) { mutableStateOf("") }
    var showCursor by remember(line1, line2) { mutableStateOf(true) }
    var typingComplete by remember(line1, line2) { mutableStateOf(false) }

    LaunchedEffect(line1, line2) {
        displayedLine1 = ""
        displayedLine2 = ""
        showCursor = true
        typingComplete = false
        for (i in 1..line1.length) {
            displayedLine1 = line1.substring(0, i)
            delay(55L)
        }
        if (line2.isNotEmpty()) {
            delay(120L)
            for (i in 1..line2.length) {
                displayedLine2 = line2.substring(0, i)
                delay(55L)
            }
        }
        typingComplete = true
        if (!keepCursorAfterComplete) {
            showCursor = false
            return@LaunchedEffect
        }
        while (true) {
            showCursor = true
            delay(900L)
            showCursor = false
            delay(260L)
        }
    }

    val cursorChar = if (showCursor) "▌" else ""
    val shouldShowCursor = showCursor && (!typingComplete || keepCursorAfterComplete)
    val resolvedCursorColor = cursorColor ?: pageAccent(HubPage.Monitor)
    val lineStyle = style ?: MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)

    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = buildAnnotatedString {
                append(displayedLine1)
                if (displayedLine2.isEmpty() && shouldShowCursor) {
                    withStyle(SpanStyle(color = resolvedCursorColor)) { append(cursorChar) }
                }
            },
            style = lineStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        if (line2.isNotEmpty()) {
            Text(
                text = buildAnnotatedString {
                    append(displayedLine2)
                    if (displayedLine2.isNotEmpty() && shouldShowCursor) {
                        withStyle(SpanStyle(color = resolvedCursorColor)) { append(cursorChar) }
                    }
                },
                style = lineStyle,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
internal fun ConciseTypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    cursorColor: Color = Color.Black,
    textColor: Color = Color.Black,
    keepCursorAfterComplete: Boolean = true,
    maxLines: Int = 2
) {
    var displayedText by remember(text) { mutableStateOf("") }
    var showCursor by remember(text) { mutableStateOf(true) }
    var typingComplete by remember(text) { mutableStateOf(false) }

    LaunchedEffect(text) {
        displayedText = ""
        showCursor = true
        typingComplete = false
        for (i in 1..text.length) {
            displayedText = text.substring(0, i)
            delay(42L)
        }
        typingComplete = true
        if (!keepCursorAfterComplete) {
            showCursor = false
            return@LaunchedEffect
        }
        while (true) {
            showCursor = true
            delay(900L)
            showCursor = false
            delay(260L)
        }
    }

    val shouldShowCursor = showCursor && (!typingComplete || keepCursorAfterComplete)
    val resolvedStyle = style ?: MaterialTheme.typography.titleMedium
    Text(
        text = buildAnnotatedString {
            append(displayedText)
            if (shouldShowCursor) {
                withStyle(SpanStyle(color = cursorColor)) { append("▌") }
            }
        },
        modifier = modifier,
        style = resolvedStyle,
        color = textColor,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun ConciseWaveDividerBackground(
    selectedPath: ConciseMonitorSetupPath?,
    isPageFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val sweep by animateFloatAsState(
        targetValue = when (selectedPath) {
            ConciseMonitorSetupPath.Reference -> 1f
            ConciseMonitorSetupPath.Direct -> -1f
            null -> 0f
        },
        animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
        label = "conciseWaveDividerSweep"
    )
    val animateWave = isPageFocused
    var elapsedSeconds by remember { mutableStateOf(0f) }
    LaunchedEffect(animateWave) {
        if (!animateWave) {
            elapsedSeconds = 0f
            return@LaunchedEffect
        }
        val startNanos = System.nanoTime()
        while (isActive) {
            elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000f
            delay(80L)
        }
    }
    val phaseTime = if (animateWave) elapsedSeconds else 0f

    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    surface.copy(alpha = 0.92f),
                    surfaceVariant.copy(alpha = 0.44f),
                    surface.copy(alpha = 0.86f)
                )
            ),
            size = size
        )
        val centerX = size.width * (0.5f + sweep * 0.62f)
        val sweepActivity = kotlin.math.abs(sweep).coerceIn(0f, 1f)
        val amplitude = size.width * (0.018f + sweepActivity * 0.040f)
        val spread = size.width * (0.009f + sweepActivity * 0.050f)
        val steps = 40
        val curveCount = 6
        repeat(curveCount) { index ->
            val normalizedIndex = index - (curveCount - 1) / 2f
            val path = Path()
            val isMainLine = index == 2 || index == 3
            val distanceFromCore = kotlin.math.min(
                kotlin.math.abs(index - 2),
                kotlin.math.abs(index - 3)
            ).toFloat()
            val lineFrequency = 1.54 + index * 0.045
            val direction = if (index % 2 == 0) 1f else -1f
            val lineSpeed = 0.24f + index * 0.018f
            val linePhase = (phaseTime * lineSpeed * direction + index * 0.74f) % (kotlin.math.PI * 2.0).toFloat()
            val lineAmplitude = amplitude * (0.90f + index * 0.045f)
            val lateralWeave = kotlin.math.sin(
                (phaseTime * (0.88f + index * 0.035f) + index * 1.18f).toDouble()
            ).toFloat() * spread * 0.62f
            for (step in 0..steps) {
                val t = step / steps.toFloat()
                val y = size.height * t
                val wave = kotlin.math.sin(
                    (t * kotlin.math.PI * 2.0 * lineFrequency) +
                        linePhase
                ).toFloat()
                val x = centerX +
                    normalizedIndex * spread +
                    lateralWeave +
                    wave * lineAmplitude
                if (step == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            drawPath(
                path = path,
                color = Color.Black.copy(
                    alpha = if (isMainLine) {
                        0.94f
                    } else {
                        (0.48f - distanceFromCore * 0.07f).coerceAtLeast(0.26f)
                    }
                ),
                style = Stroke(
                    width = size.height * if (isMainLine) 0.038f else 0.012f,
                    cap = StrokeCap.Round
                )
            )
        }
        val fadeHeight = size.height * 0.22f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    surface.copy(alpha = 0.92f),
                    surface.copy(alpha = 0f)
                ),
                startY = 0f,
                endY = fadeHeight
            ),
            size = Size(size.width, fadeHeight)
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    surface.copy(alpha = 0f),
                    surface.copy(alpha = 0.92f)
                ),
                startY = size.height - fadeHeight,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - fadeHeight),
            size = Size(size.width, fadeHeight)
        )
    }
}

@Composable
internal fun ConciseFrostedGlassLayer(
    selectedPath: ConciseMonitorSetupPath?,
    modifier: Modifier = Modifier
) {
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val accent = when (selectedPath) {
        ConciseMonitorSetupPath.Reference -> pageAccent(HubPage.Monitor)
        ConciseMonitorSetupPath.Direct -> pageAccent(HubPage.Hub)
        null -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .background(surface.copy(alpha = 0.42f))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.44f),
                        surfaceVariant.copy(alpha = 0.22f),
                        accent.copy(alpha = 0.06f),
                        surface.copy(alpha = 0.32f)
                    )
                )
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.26f),
                        Color.Transparent,
                        Color.White.copy(alpha = 0.10f)
                    )
                )
            )
    )
}

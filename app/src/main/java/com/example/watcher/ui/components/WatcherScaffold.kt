package com.example.watcher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import com.example.watcher.ui.screens.HubPage
import com.example.watcher.ui.theme.LocalWatcherExtendedColors
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun PageScaffold(
    page: HubPage,
    pageOffset: Float,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val focus = pageFocus(pageOffset)
    val accent = blendedPageAccent(selectionPosition(page, pageOffset))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.02f + focus * 0.06f),
                            Color.Transparent,
                            Color.Transparent
                        )
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content
        )
    }
}

@Composable
internal fun BottomGlassScrim(
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val surfaceTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    val glassTint = extendedColors.glassOverlay.copy(alpha = 0.86f)
    val baseTint = extendedColors.surfaceContainerHigh.copy(alpha = 0.96f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(228.dp)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            glassTint.copy(alpha = 0.22f),
                            glassTint.copy(alpha = 0.48f),
                            surfaceTint.copy(alpha = 0.88f),
                            surfaceTint,
                            baseTint
                        )
                    )
                )
            }
    )
}

@Composable
internal fun SharedWorkspaceHeader(
    modifier: Modifier = Modifier,
    pagerPosition: Float,
    onNavigate: (HubPage) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 20.dp)
    ) {
        RouteSelectionStrip(
            pagerPosition = pagerPosition,
            onNavigate = onNavigate
        )
    }
}

@Composable
internal fun WatcherTopBar(
    eyebrow: String,
    title: String,
    subtitle: String,
    currentPage: HubPage,
    pageOffset: Float,
    showConciseModeToggle: Boolean = false,
    isConciseMode: Boolean = false,
    onConciseModeChange: ((Boolean) -> Unit)? = null,
    rotaryRotationDegrees: Float,
    onRotaryRotationChange: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgentConfig: (() -> Unit)? = null,
    onOpenWalletConfig: (() -> Unit)? = null
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val accent = blendedPageAccent(selectionPosition(currentPage, pageOffset))
    val actionItems = remember(onOpenWalletConfig, onOpenAgentConfig, onOpenSettings) {
        buildList {
            if (onOpenWalletConfig != null) {
                add(
                    RotaryActionItem(
                        icon = Icons.Default.VpnKey,
                        contentDescription = "Open API wallet",
                        onClick = onOpenWalletConfig
                    )
                )
            }
            if (onOpenAgentConfig != null) {
                add(
                    RotaryActionItem(
                        icon = Icons.Default.Psychology,
                        contentDescription = "Open aent settings",
                        onClick = onOpenAgentConfig
                    )
                )
            }
            add(
                RotaryActionItem(
                    icon = Icons.Default.Settings,
                    contentDescription = "Open settings",
                    onClick = onOpenSettings
                )
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelLarge,
                color = accent
            )
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (showConciseModeToggle && onConciseModeChange != null) {
                ConciseModeToggleChip(
                    isConciseMode = isConciseMode,
                    accent = accent,
                    onToggle = { onConciseModeChange(!isConciseMode) }
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(176.dp),
            contentAlignment = Alignment.Center
        ) {
            RotaryActionCluster(
                items = actionItems,
                accent = accent,
                glassOverlay = extendedColors.glassOverlay,
                rotationDegrees = rotaryRotationDegrees,
                onRotationChange = onRotaryRotationChange
            )
        }
    }
}

@Composable
internal fun ConciseModeToggleChip(
    isConciseMode: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.24f)
    val pillColor = Color.White.copy(alpha = 0.88f)
    val trackBlurTint = Color.White.copy(alpha = 0.34f)
    val frostedOverlay = Color.White.copy(alpha = 0.16f)
    val sliderOffset by animateDpAsState(
        targetValue = if (isConciseMode) 78.dp else 0.dp,
        animationSpec = tween(
            durationMillis = 320,
            easing = FastOutSlowInEasing
        ),
        label = "conciseModeSliderOffset"
    )
    val exploreColor by animateColorAsState(
        targetValue = if (isConciseMode) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 180),
        label = "conciseModeExploreColor"
    )
    val conciseColor by animateColorAsState(
        targetValue = if (isConciseMode) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 180),
        label = "conciseModeColor"
    )
    val exploreBlur by animateDpAsState(
        targetValue = if (isConciseMode) 1.6.dp else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "conciseModeExploreBlur"
    )
    val conciseBlur by animateDpAsState(
        targetValue = if (isConciseMode) 0.dp else 1.6.dp,
        animationSpec = tween(durationMillis = 220),
        label = "conciseModeBlur"
    )

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.12f)),
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = modifier
                .width(164.dp)
                .height(40.dp)
                .padding(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    performPenClickHaptic(view)
                    onToggle()
                }
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(999.dp))
                    .background(trackBlurTint)
                    .blur(28.dp)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(999.dp))
                    .background(frostedOverlay)
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = sliderOffset)
                    .width(74.dp)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(999.dp),
                color = pillColor,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.56f)),
                shadowElevation = 2.dp
            ) {}

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "探索",
                        modifier = Modifier.blur(exploreBlur),
                        style = MaterialTheme.typography.labelLarge,
                        color = exploreColor
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "简洁",
                        modifier = Modifier.blur(conciseBlur),
                        style = MaterialTheme.typography.labelLarge,
                        color = conciseColor
                    )
                }
            }
        }
    }
}

@Composable
internal fun SwipeCoachmarkOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Swipe between workspaces",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Swipe left for realtime monitoring and swipe right for video analysis. Home keeps the latest task state in sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HubPage.entries.forEach { page ->
                    StatusPill(text = page.label, accent = pageAccent(page))
                }
                Button(onClick = onDismiss, shape = RoundedCornerShape(20.dp)) {
                    Text("Got it")
                }
            }
        }
    }
}

@Composable
private fun RouteSelectionStrip(
    pagerPosition: Float,
    onNavigate: (HubPage) -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val indicatorPosition = pagerPosition.coerceIn(0f, (HubPage.entries.size - 1).toFloat())
    val accent = blendedPageAccent(indicatorPosition)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = extendedColors.glassOverlay.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 8.dp
                val slotWidth = (maxWidth - gap * (HubPage.entries.size - 1)) / HubPage.entries.size
                val offsetX = (slotWidth + gap) * indicatorPosition
                val indicatorScale = navigationIndicatorScale(indicatorPosition)
                val indicatorAlpha = navigationIndicatorAlpha(indicatorPosition)

                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .offset(x = offsetX)
                            .graphicsLayer {
                                scaleX = indicatorScale
                                scaleY = indicatorScale
                                alpha = indicatorAlpha
                            }
                            .width(slotWidth)
                            .height(44.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = accent.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.14f))
                    ) {}

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        HubPage.entries.forEach { page ->
                            val emphasis = 1f - (abs(indicatorPosition - page.pageIndex).coerceIn(0f, 1f) * 0.55f)
                            val contentColor = lerp(
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                accent,
                                emphasis
                            )

                            Box(
                                modifier = Modifier
                                    .width(slotWidth)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onNavigate(page) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = page.icon,
                                    contentDescription = page.label,
                                    tint = contentColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun pageAccent(page: HubPage): Color {
    return when (page) {
        HubPage.Monitor -> Color(0xFF0E8B65)
        HubPage.Hub -> Color(0xFF0058BE)
        HubPage.Analysis -> Color(0xFF9A5B00)
        HubPage.History -> Color(0xFF6A4CB0)
        HubPage.Templates -> Color(0xFF8B6914)
    }
}

private fun navigationIndicatorScale(indicatorPosition: Float): Float {
    val segmentProgress = indicatorPosition - indicatorPosition.toInt()
    val transitionDepth = 1f - (kotlin.math.abs(segmentProgress - 0.5f) * 2f).coerceIn(0f, 1f)
    return lerpFloat(start = 1f, stop = 0.46f, fraction = transitionDepth)
}

private fun navigationIndicatorAlpha(indicatorPosition: Float): Float {
    val segmentProgress = indicatorPosition - indicatorPosition.toInt()
    val transitionDepth = 1f - (kotlin.math.abs(segmentProgress - 0.5f) * 2f).coerceIn(0f, 1f)
    return lerpFloat(start = 1f, stop = 0.72f, fraction = transitionDepth)
}

internal data class RotaryActionItem(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit
)

@Composable
internal fun RotaryActionCluster(
    items: List<RotaryActionItem>,
    accent: Color,
    glassOverlay: Color,
    rotationDegrees: Float,
    onRotationChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val clusterSize = 148.dp
    val dialSize = 138.dp
    val buttonSize = 26.dp
    val coreSize = 16.dp
    val density = LocalDensity.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val latestRotationDegrees by rememberUpdatedState(rotationDegrees)
    val latestOnRotationChange by rememberUpdatedState(onRotationChange)
    val latestItems by rememberUpdatedState(items)
    var previousTouchAngle by remember { mutableStateOf<Float?>(null) }
    var previousDragTimestampMillis by remember { mutableStateOf<Long?>(null) }
    var angularVelocity by remember { mutableFloatStateOf(0f) }
    var inertiaJob by remember { mutableStateOf<Job?>(null) }
    var lastHapticStep by remember { mutableIntStateOf(rotationDegrees.toHapticStep()) }

    LaunchedEffect(rotationDegrees) {
        val currentStep = rotationDegrees.toHapticStep()
        if (currentStep != lastHapticStep) {
            ViewCompat.performHapticFeedback(
                view,
                HapticFeedbackConstantsCompat.CLOCK_TICK
            )
            lastHapticStep = currentStep
        }
    }

    DisposableEffect(Unit) {
        onDispose { inertiaJob?.cancel() }
    }

    Box(
        modifier = modifier
            .size(clusterSize)
            .pointerInput(items.size) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        inertiaJob?.cancel()
                        inertiaJob = null
                        previousTouchAngle = startOffset.toAngleAround(size.centerOffset())
                        previousDragTimestampMillis = null
                        angularVelocity = 0f
                    },
                    onDragCancel = {
                        previousTouchAngle = null
                        previousDragTimestampMillis = null
                        inertiaJob?.cancel()
                        inertiaJob = coroutineScope.launch {
                            settleRotarySelection(
                                rotationDegrees = latestRotationDegrees,
                                items = latestItems,
                                onRotationChange = latestOnRotationChange
                            )
                        }
                    },
                    onDragEnd = {
                        previousTouchAngle = null
                        previousDragTimestampMillis = null
                        val initialVelocity = angularVelocity
                        if (abs(initialVelocity) < 16f) {
                            inertiaJob?.cancel()
                            inertiaJob = coroutineScope.launch {
                                settleRotarySelection(
                                    rotationDegrees = latestRotationDegrees,
                                    items = latestItems,
                                    onRotationChange = latestOnRotationChange
                                )
                            }
                            return@detectDragGestures
                        }
                        inertiaJob?.cancel()
                        inertiaJob = coroutineScope.launch {
                            var velocity = initialVelocity
                            var previousFrameNanos = 0L
                            while (abs(velocity) > 4f) {
                                withFrameNanos { frameTimeNanos ->
                                    if (previousFrameNanos == 0L) {
                                        previousFrameNanos = frameTimeNanos
                                        return@withFrameNanos
                                    }
                                    val deltaSeconds =
                                        (frameTimeNanos - previousFrameNanos) / 1_000_000_000f
                                    previousFrameNanos = frameTimeNanos
                                    latestOnRotationChange(latestRotationDegrees + velocity * deltaSeconds)
                                    val frameDamping = 0.92f.pow(deltaSeconds * 60f)
                                    velocity *= frameDamping
                                }
                            }
                            angularVelocity = 0f
                            settleRotarySelection(
                                rotationDegrees = latestRotationDegrees,
                                items = latestItems,
                                onRotationChange = latestOnRotationChange
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val center = size.centerOffset()
                        val previousAngle = previousTouchAngle ?: change.position.toAngleAround(center)
                        val currentAngle = change.position.toAngleAround(center)
                        val deltaDegrees = normalizedAngleDelta(currentAngle - previousAngle)
                        latestOnRotationChange(latestRotationDegrees + deltaDegrees)
                        val previousTimestamp = previousDragTimestampMillis
                        if (previousTimestamp != null) {
                            val deltaMillis = (change.uptimeMillis - previousTimestamp).coerceAtLeast(1L)
                            angularVelocity = (deltaDegrees / deltaMillis) * 1000f
                        }
                        previousTouchAngle = currentAngle
                        previousDragTimestampMillis = change.uptimeMillis
                    }
                )
            }
    ) {
        val dialSizePx = with(density) { dialSize.toPx() }
        val buttonSizePx = with(density) { buttonSize.toPx() }
        val orbitRadiusPx = ((dialSizePx - buttonSizePx) / 2f - with(density) { 14.dp.toPx() })
            .coerceAtLeast(buttonSizePx * 0.5f)
        val dialCenterPx = dialSizePx / 2f
        val bezelColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
        val dialBase = glassOverlay.copy(alpha = 0.98f)
        val dialMid = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
        val dialHighlight = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        val slotButtonColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        val slotButtonBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
        val coreSurfaceColor = MaterialTheme.colorScheme.surface

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(dialSize)
                .graphicsLayer {
                    rotationZ = rotationDegrees
                }
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = dialBase,
                border = BorderStroke(1.dp, bezelColor.copy(alpha = 0.85f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        dialHighlight,
                                        dialMid,
                                        accent.copy(alpha = 0.09f)
                                    ),
                                    center = Offset(size.width * 0.36f, size.height * 0.30f),
                                    radius = size.minDimension * 0.82f
                                )
                            )
                            val tickRingRadius = size.minDimension * 0.425f
                            val tickOuterRadius = size.minDimension * 0.475f
                            val tickInnerRadius = size.minDimension * 0.438f
                            repeat(30) { tickIndex ->
                                val angle = Math.toRadians(-90.0 + tickIndex * 12.0)
                                val start = Offset(
                                    x = center.x + cos(angle).toFloat() * tickInnerRadius,
                                    y = center.y + sin(angle).toFloat() * tickInnerRadius
                                )
                                val end = Offset(
                                    x = center.x + cos(angle).toFloat() * tickOuterRadius,
                                    y = center.y + sin(angle).toFloat() * tickOuterRadius
                                )
                                drawLine(
                                    color = accent.copy(alpha = 0.22f),
                                    start = start,
                                    end = end,
                                    strokeWidth = size.minDimension * 0.010f
                                )
                            }
                            drawCircle(
                                color = accent.copy(alpha = 0.12f),
                                radius = tickRingRadius,
                                style = Stroke(width = size.minDimension * 0.020f)
                            )
                            drawCircle(
                                color = accent.copy(alpha = 0.05f),
                                radius = size.minDimension * 0.12f
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.08f),
                                radius = size.minDimension * 0.07f,
                                center = Offset(size.width * 0.34f, size.height * 0.28f)
                            )
                        }
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(coreSize),
                shape = CircleShape,
                color = coreSurfaceColor,
                border = BorderStroke(1.dp, bezelColor.copy(alpha = 0.65f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawCircle(color = accent.copy(alpha = 0.08f))
                        }
                )
            }

            items.forEachIndexed { index, item ->
                val baseAngle = baseActionAngle(index, items.size)
                val radians = Math.toRadians(baseAngle.toDouble())
                val itemCenterX = dialCenterPx + cos(radians).toFloat() * orbitRadiusPx
                val itemCenterY = dialCenterPx + sin(radians).toFloat() * orbitRadiusPx
                val iconRotation = baseAngle + 90f

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (itemCenterX - buttonSizePx / 2f).roundToInt(),
                                y = (itemCenterY - buttonSizePx / 2f).roundToInt()
                            )
                        }
                        .size(buttonSize),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        onClick = item.onClick,
                        shape = CircleShape,
                        color = slotButtonColor,
                        border = BorderStroke(1.dp, slotButtonBorder),
                        shadowElevation = 0.dp
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.contentDescription,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    rotationZ = iconRotation
                                }
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .size(width = 6.dp, height = 14.dp),
            shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
            color = accent.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
            shadowElevation = 0.dp
        ) {}
    }
}

private fun baseActionAngle(index: Int, itemCount: Int): Float {
    return when (itemCount) {
        1 -> -90f
        2 -> if (index == 0) -35f else 145f
        3 -> listOf(-90f, 30f, 150f)[index]
        else -> (-90f + (360f / itemCount) * index)
    }
}

private fun Offset.toAngleAround(center: Offset): Float {
    return radiansToDegrees(atan2(y - center.y, x - center.x))
}

private fun normalizedAngleDelta(deltaDegrees: Float): Float {
    val wrapped = ((deltaDegrees + 180f) % 360f + 360f) % 360f - 180f
    return if (wrapped == -180f) 180f else wrapped
}

private fun radiansToDegrees(radians: Float): Float {
    return Math.toDegrees(radians.toDouble()).toFloat()
}

private fun IntSize.centerOffset(): Offset {
    return Offset(width / 2f, height / 2f)
}

private fun Float.toHapticStep(): Int {
    return floor(this / 12f).toInt()
}

private suspend fun settleRotarySelection(
    rotationDegrees: Float,
    items: List<RotaryActionItem>,
    onRotationChange: (Float) -> Unit
) {
    val snapTarget = findSnapTarget(rotationDegrees, items.size) ?: return
    animateRotarySnap(
        from = rotationDegrees,
        delta = snapTarget.deltaToPointer,
        onRotationChange = onRotationChange
    )
    items.getOrNull(snapTarget.index)?.onClick?.invoke()
}

private suspend fun animateRotarySnap(
    from: Float,
    delta: Float,
    onRotationChange: (Float) -> Unit
) {
    val durationMillis = 120L
    val frameMillis = 16L
    val steps = (durationMillis / frameMillis).toInt().coerceAtLeast(1)
    for (step in 1..steps) {
        val t = step / steps.toFloat()
        val eased = 1f - (1f - t) * (1f - t)
        onRotationChange(from + delta * eased)
        delay(frameMillis)
    }
    onRotationChange(from + delta)
}

private data class RotarySnapTarget(
    val index: Int,
    val deltaToPointer: Float
)

private fun findSnapTarget(
    rotationDegrees: Float,
    itemCount: Int
): RotarySnapTarget? {
    if (itemCount <= 0) return null

    val pointerAngle = -90f
    val snapHalfWindow = 18f
    var bestIndex = -1
    var bestDelta = 0f
    var bestDistance = Float.MAX_VALUE

    repeat(itemCount) { index ->
        val itemAngle = baseActionAngle(index, itemCount) + rotationDegrees
        val deltaToPointer = normalizedAngleDelta(pointerAngle - itemAngle)
        val distance = abs(deltaToPointer)
        if (distance <= snapHalfWindow && distance < bestDistance) {
            bestIndex = index
            bestDelta = deltaToPointer
            bestDistance = distance
        }
    }

    return if (bestIndex >= 0) {
        RotarySnapTarget(index = bestIndex, deltaToPointer = bestDelta)
    } else {
        null
    }
}

/**
 * Pen-click haptic: CLICK (press) + TICK (rebound) in quick succession.
 */
private fun performPenClickHaptic(view: android.view.View) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = view.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() == true) {
            // CLICK first, then TICK after 30ms gap for the "rebound" feel
            val click = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            vibrator.vibrate(click)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val tick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                vibrator.vibrate(tick)
            }, 30)
            return
        }
    }
    ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
}

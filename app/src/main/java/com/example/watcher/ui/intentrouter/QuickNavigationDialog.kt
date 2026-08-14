package com.example.watcher.ui.intentrouter

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.watcher.data.intentrouter.IntentRouteId
import com.example.watcher.ui.components.RoseFourLoader
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun QuickNavigationDialog(
    state: IntentRouterUiState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onExampleSelected: (String) -> Unit,
    onShortcutSelected: (IntentRouteId) -> Unit,
    anchorBounds: Rect?,
    onDismiss: () -> Unit
) {
    if (!state.visible) return

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val transitionProgress = remember { Animatable(0f) }
    val cardSize = remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }
    var isClosing by remember { mutableStateOf(false) }
    LaunchedEffect(state.visible) {
        if (state.visible) {
            transitionProgress.snapTo(0f)
            transitionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = QUICK_NAV_MORPH_MS, easing = LinearEasing)
            )
        }
    }
    fun hideInput() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    fun submitInput() {
        if (state.input.isNotBlank()) {
            hideInput()
        }
        onSubmit()
    }
    fun animateDismiss() {
        if (state.isRouting || isClosing) return
        isClosing = true
        hideInput()
        coroutineScope.launch {
            transitionProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = QUICK_NAV_MORPH_MS, easing = LinearEasing)
            )
            onDismiss()
        }
    }
    BackHandler(enabled = !state.isRouting && !isClosing) {
        animateDismiss()
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val screenCenterX = constraints.maxWidth / 2f
        val screenCenterY = constraints.maxHeight / 2f
        val anchorCenterX = anchorBounds?.center?.x ?: screenCenterX
        val anchorCenterY = anchorBounds?.center?.y ?: screenCenterY
        val deltaX = anchorCenterX - screenCenterX
        val deltaY = anchorCenterY - screenCenterY
        val currentCardSize = cardSize.value
        val targetScale = when {
            anchorBounds != null && currentCardSize.width > 0 && currentCardSize.height > 0 -> {
                val cardMinDimension = max(1f, minOf(currentCardSize.width, currentCardSize.height).toFloat())
                (anchorBounds.width / cardMinDimension).coerceIn(0.14f, 0.24f)
            }
            anchorBounds != null -> 0.18f
            else -> 0.82f
        }
        val progress = transitionProgress.value
        val cardScale = lerpQuickNav(targetScale, 1f, progress)
        val cardTranslationX = lerpQuickNav(deltaX, 0f, progress)
        val cardTranslationY = lerpQuickNav(deltaY, 0f, progress)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.30f * progress))
                .clickable(
                    interactionSource = scrimInteractionSource,
                    indication = null,
                    enabled = !state.isRouting && !isClosing
                ) {
                    animateDismiss()
                }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .widthIn(max = 560.dp)
                .onGloballyPositioned { coordinates ->
                    cardSize.value = coordinates.size
                }
                .clickable(
                    interactionSource = cardInteractionSource,
                    indication = null
                ) {
                    // Consume clicks inside the card so only the outside scrim dismisses it.
                }
                .graphicsLayer {
                    alpha = lerpQuickNav(0.35f, 1f, progress)
                    scaleX = cardScale
                    scaleY = cardScale
                    translationX = cardTranslationX
                    translationY = cardTranslationY
                },
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "快速导航",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    RoseFourLoader(
                        modifier = Modifier.size(36.dp),
                        active = !isClosing
                    )
                }
                if (state.selectedRoute == null) {
                    Text(
                        text = "说出你想做什么，我会带你到对应功能。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isRouting,
                        label = { Text("我想...") },
                        placeholder = { Text("比如：查看历史记录") },
                        isError = state.errorMessage != null,
                        supportingText = {
                            state.errorMessage?.let { message ->
                                Text(message)
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitInput() })
                    )
                    if (state.examplePrompts.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "试试这些说法",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            QuickNavigationChipFlow {
                                state.examplePrompts.forEach { example ->
                                    SuggestionChip(
                                        onClick = {
                                            hideInput()
                                            onExampleSelected(example)
                                        },
                                        enabled = !state.isRouting,
                                        label = { Text(example) }
                                    )
                                }
                            }
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "直接打开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        QuickNavigationChipFlow {
                            state.shortcutRoutes.forEach { route ->
                                SuggestionChip(
                                    onClick = {
                                        hideInput()
                                        onShortcutSelected(route.id)
                                    },
                                    enabled = !state.isRouting,
                                    icon = {
                                        Icon(
                                            imageVector = routeIcon(route.id),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    label = { Text(route.title) }
                                )
                            }
                        }
                    }
                } else {
                    state.selectedRoute?.let { route ->
                        QuickNavigationSuccessContent(
                            routeId = route.id,
                            title = route.title,
                            guidance = route.guidance
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isRouting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("识别中")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { animateDismiss() },
                        enabled = !state.isRouting && !isClosing
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

private const val QUICK_NAV_MORPH_MS = 180

private fun lerpQuickNav(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

@Composable
private fun QuickNavigationSuccessContent(
    routeId: IntentRouteId,
    title: String,
    guidance: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "已为你打开",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = routeIcon(routeId),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "可以直接在当前页面继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "下一步建议",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = quickNavigationNextStepText(guidance),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun quickNavigationNextStepText(guidance: String): String {
    return guidance.substringAfter("。", guidance).trim().ifBlank { guidance }
}

private fun routeIcon(routeId: IntentRouteId): ImageVector {
    return when (routeId) {
        IntentRouteId.Monitor -> Icons.Filled.Visibility
        IntentRouteId.Home -> Icons.Filled.Home
        IntentRouteId.Analysis -> Icons.Filled.Search
        IntentRouteId.History -> Icons.Filled.History
        IntentRouteId.Templates -> Icons.Filled.Settings
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickNavigationChipFlow(
    content: @Composable () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
}

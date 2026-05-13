package com.example.watcher.ui.components

import android.util.Log
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class TemplateTickerItem(
    val id: String,
    val label: String
)

private const val TICKER_REPEAT_COPY_COUNT = 5
private const val TOP_ROW_SPEED_RATIO = 1.0f
private const val BOTTOM_ROW_SPEED_RATIO = 0.82f
private const val TICKER_LOG_TAG = "InfiniteTemplateTicker"

@Composable
internal fun InfiniteTemplateTicker(
    items: List<TemplateTickerItem>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoScrollSpeedDpPerSecond: Float = 18f,
    resumeDelayMillis: Long = 1_200L
) {
    if (items.isEmpty()) return

    val (topRowItems, bottomRowItems) = remember(items) { splitTickerRows(items) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InfiniteTemplateTickerRow(
            debugLabel = "top",
            items = topRowItems,
            onItemClick = onItemClick,
            autoScrollSpeedDpPerSecond = autoScrollSpeedDpPerSecond * TOP_ROW_SPEED_RATIO,
            resumeDelayMillis = resumeDelayMillis,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentPadding = PaddingValues(start = 0.dp, end = 12.dp)
        )
        InfiniteTemplateTickerRow(
            debugLabel = "bottom",
            items = bottomRowItems,
            onItemClick = onItemClick,
            autoScrollSpeedDpPerSecond = autoScrollSpeedDpPerSecond * BOTTOM_ROW_SPEED_RATIO,
            resumeDelayMillis = resumeDelayMillis,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentPadding = PaddingValues(start = 28.dp, end = 12.dp),
            initialIndexOffset = 1
        )
    }
}

@Composable
private fun InfiniteTemplateTickerRow(
    debugLabel: String,
    items: List<TemplateTickerItem>,
    onItemClick: (String) -> Unit,
    autoScrollSpeedDpPerSecond: Float,
    resumeDelayMillis: Long,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    initialIndexOffset: Int = 0
) {
    if (items.isEmpty()) return

    val repeatedItems = remember(items) {
        List(TICKER_REPEAT_COPY_COUNT) { items }.flatten()
    }
    val baseItemCount = items.size
    val middleCopyIndex = TICKER_REPEAT_COPY_COUNT / 2
    val normalizedInitialOffset = if (baseItemCount == 0) 0 else {
        ((initialIndexOffset % baseItemCount) + baseItemCount) % baseItemCount
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (baseItemCount * middleCopyIndex) + normalizedInitialOffset
    )
    val density = LocalDensity.current
    val speedPxPerSecond = remember(density, autoScrollSpeedDpPerSecond) {
        with(density) { autoScrollSpeedDpPerSecond.dp.toPx() }
    }
    var shouldAutoScroll by remember { mutableStateOf(true) }
    var isAutoScrollInProgress by remember { mutableStateOf(false) }
    var isUserDragging by remember { mutableStateOf(false) }
    var resumeGeneration by remember { mutableIntStateOf(0) }
    var autoScrollActiveLogged by remember { mutableStateOf(false) }

    LaunchedEffect(listState, baseItemCount, middleCopyIndex) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { isScrolling ->
                logTickerEvent(
                    debugLabel = debugLabel,
                    message = "scroll_state=$isScrolling index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset} auto=$isAutoScrollInProgress dragging=$isUserDragging enabled=$shouldAutoScroll"
                )
                if (!isScrolling) {
                    listState.requestScrollToMiddle(
                        debugLabel = debugLabel,
                        baseItemCount = baseItemCount,
                        middleCopyIndex = middleCopyIndex
                    )
                }
            }
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserDragging = true
                    shouldAutoScroll = false
                    resumeGeneration += 1
                    autoScrollActiveLogged = false
                    logTickerEvent(
                        debugLabel = debugLabel,
                        message = "drag_start gen=$resumeGeneration index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                    )
                }

                is DragInteraction.Stop,
                is DragInteraction.Cancel -> {
                    isUserDragging = false
                    shouldAutoScroll = false
                    resumeGeneration += 1
                    autoScrollActiveLogged = false
                    logTickerEvent(
                        debugLabel = debugLabel,
                        message = "drag_end type=${interaction::class.simpleName} gen=$resumeGeneration index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                    )
                }
            }
        }
    }

    LaunchedEffect(resumeGeneration, isUserDragging) {
        if (isUserDragging) {
            logTickerEvent(
                debugLabel = debugLabel,
                message = "resume_timer_skipped dragging=true gen=$resumeGeneration"
            )
            return@LaunchedEffect
        }
        val generation = resumeGeneration
        logTickerEvent(
            debugLabel = debugLabel,
            message = "resume_timer_start gen=$generation delayMs=$resumeDelayMillis index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
        )
        delay(resumeDelayMillis)
        if (!isUserDragging && generation == resumeGeneration) {
            shouldAutoScroll = true
            logTickerEvent(
                debugLabel = debugLabel,
                message = "resume_timer_fire gen=$generation index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
            )
        } else {
            logTickerEvent(
                debugLabel = debugLabel,
                message = "resume_timer_cancelled scheduledGen=$generation currentGen=$resumeGeneration dragging=$isUserDragging"
            )
        }
    }

    LaunchedEffect(items, speedPxPerSecond, resumeDelayMillis) {
        if (baseItemCount == 0) return@LaunchedEffect
        var previousFrameNanos = 0L
        while (true) {
            if (!shouldAutoScroll || (listState.isScrollInProgress && !isAutoScrollInProgress)) {
                autoScrollActiveLogged = false
                previousFrameNanos = 0L
                delay(32L)
                continue
            }
            if (!autoScrollActiveLogged) {
                autoScrollActiveLogged = true
                logTickerEvent(
                    debugLabel = debugLabel,
                    message = "auto_scroll_active index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                )
            }

            val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
            val lastFrame = previousFrameNanos
            previousFrameNanos = frameNanos
            if (lastFrame == 0L) continue

            val elapsedSeconds = (frameNanos - lastFrame) / 1_000_000_000f
            val deltaPx = speedPxPerSecond * elapsedSeconds
            if (deltaPx <= 0f) continue

            listState.requestScrollToMiddle(
                debugLabel = debugLabel,
                baseItemCount = baseItemCount,
                middleCopyIndex = middleCopyIndex
            )
            isAutoScrollInProgress = true
            try {
                try {
                    listState.scrollBy(deltaPx)
                } catch (cancelled: CancellationException) {
                    autoScrollActiveLogged = false
                    previousFrameNanos = 0L
                    logTickerEvent(
                        debugLabel = debugLabel,
                        message = "auto_scroll_interrupted reason=${cancelled::class.simpleName} index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                    )
                    continue
                }
                listState.requestScrollToMiddle(
                    debugLabel = debugLabel,
                    baseItemCount = baseItemCount,
                    middleCopyIndex = middleCopyIndex
                )
            } finally {
                isAutoScrollInProgress = false
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items = repeatedItems,
            key = { index, item -> "ticker_${index}_${item.id}" }
        ) { _, item ->
            TemplateTickerChip(
                label = item.label,
                onClick = { onItemClick(item.id) }
            )
        }
    }
}

@Composable
private fun TemplateTickerChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

private fun splitTickerRows(items: List<TemplateTickerItem>): Pair<List<TemplateTickerItem>, List<TemplateTickerItem>> {
    if (items.size == 1) return items to items

    val topRow = mutableListOf<TemplateTickerItem>()
    val bottomRow = mutableListOf<TemplateTickerItem>()
    items.forEachIndexed { index, item ->
        if (index % 2 == 0) {
            topRow += item
        } else {
            bottomRow += item
        }
    }
    if (topRow.isEmpty()) topRow += items.first()
    if (bottomRow.isEmpty()) bottomRow += items.last()
    return topRow to bottomRow
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.requestScrollToMiddle(
    debugLabel: String,
    baseItemCount: Int,
    middleCopyIndex: Int
) {
    if (baseItemCount <= 0) return

    val currentIndex = firstVisibleItemIndex
    val middleStartIndex = baseItemCount
        .times(middleCopyIndex - 1)
        .coerceAtLeast(0)
    val middleEndExclusive = baseItemCount
        .times(middleCopyIndex + 2)
        .coerceAtLeast(baseItemCount)
    if (currentIndex >= middleStartIndex && currentIndex < middleEndExclusive) return

    val normalizedIndex = ((currentIndex % baseItemCount) + baseItemCount) % baseItemCount
    logTickerEvent(
        debugLabel = debugLabel,
        message = "recenter fromIndex=$currentIndex normalizedIndex=$normalizedIndex offset=$firstVisibleItemScrollOffset"
    )
    scrollToItem(
        index = normalizedIndex + (baseItemCount * middleCopyIndex),
        scrollOffset = firstVisibleItemScrollOffset
    )
}

private fun logTickerEvent(
    debugLabel: String,
    message: String
) {
    Log.d(TICKER_LOG_TAG, "row=$debugLabel $message")
}

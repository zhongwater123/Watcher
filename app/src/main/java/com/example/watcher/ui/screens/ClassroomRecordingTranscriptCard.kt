package com.example.watcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.ClassroomTranscriptUiItem
import com.example.watcher.data.model.ClassroomTranscriptWeightLevel
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.ui.components.WatcherCard


@Composable
internal fun ClassroomTranscriptCard(
    status: VideoProcessingStatus,
    onToggleTranscriptSelection: (Long) -> Unit,
    onTranscriptInteractionAnchorChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    WatcherCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            var displayOrder by rememberSaveable {
                mutableStateOf(RealtimeTranscriptDisplayOrder.Chronological)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClassroomBreathingStatusDot(
                    color = Color(0xFFEF4444),
                    active = status.realtimeConnectionState != "Closed" &&
                        status.realtimeConnectionState != "Failed"
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("课堂字幕", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = realtimeConnectionLabel(status.realtimeConnectionState),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.realtimeConnectionState == "Failed") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                RealtimeTranscriptOrderToggle(
                    displayOrder = displayOrder,
                    onDisplayOrderChange = { displayOrder = it }
                )
            }
            RealtimeTranscriptPanel(
                status = status,
                displayOrder = displayOrder,
                onToggleTranscriptSelection = onToggleTranscriptSelection,
                onTranscriptInteractionAnchorChanged = onTranscriptInteractionAnchorChanged
            )
        }
    }
}

@Composable
private fun RealtimeTranscriptPanel(
    status: VideoProcessingStatus,
    displayOrder: RealtimeTranscriptDisplayOrder,
    onToggleTranscriptSelection: (Long) -> Unit,
    onTranscriptInteractionAnchorChanged: (Float) -> Unit
) {
    val transcriptItems = status.realtimeTranscriptItems
    val renderedTranscriptItems = remember(transcriptItems, displayOrder) {
        orderRealtimeTranscriptItems(transcriptItems, displayOrder)
    }
    val partialText = remember(status.realtimeTranscript, transcriptItems) {
        status.realtimeTranscript.trim()
            .takeIf { it.isNotBlank() && it != transcriptItems.lastOrNull()?.text }
            .orEmpty()
    }
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    var scrollToLatestRequest by remember { mutableStateOf(0) }
    val itemCount = transcriptItems.size + if (partialText.isNotBlank()) 1 else 0
    val latestScrollAnchor = remember(transcriptItems, partialText) {
        buildRealtimeTranscriptScrollAnchor(transcriptItems, partialText)
    }
    val activeSelectionOrder = transcriptItems
        .filter { it.isSelected && !it.isAnswered }
        .maxOfOrNull { it.selectionOrder ?: 0 }
    val isAtLatest by remember(displayOrder) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            realtimeTranscriptIsLatestVisible(
                visibleIndices = visibleIndices,
                itemCount = total,
                order = displayOrder,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
            )
        }
    }
    LaunchedEffect(isAtLatest, listState.isScrollInProgress) {
        if (isAtLatest) {
            followLatest = true
        } else if (listState.isScrollInProgress) {
            followLatest = false
        }
    }
    LaunchedEffect(latestScrollAnchor, followLatest, displayOrder) {
        val targetIndex = realtimeTranscriptLatestTargetIndex(itemCount, displayOrder)
        if (followLatest && targetIndex != null) {
            if (displayOrder == RealtimeTranscriptDisplayOrder.Reverse) {
                listState.scrollToItem(index = targetIndex, scrollOffset = 0)
            } else {
                listState.animateScrollToItem(index = targetIndex, scrollOffset = 0)
            }
        }
    }
    LaunchedEffect(scrollToLatestRequest) {
        val targetIndex = realtimeTranscriptLatestTargetIndex(itemCount, displayOrder)
        if (scrollToLatestRequest > 0 && targetIndex != null) {
            listState.scrollToItem(index = targetIndex, scrollOffset = 0)
            followLatest = true
        }
    }
    LaunchedEffect(displayOrder) {
        val targetIndex = realtimeTranscriptLatestTargetIndex(itemCount, displayOrder)
        if (targetIndex != null) {
            listState.scrollToItem(index = targetIndex, scrollOffset = 0)
            followLatest = true
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .onGloballyPositioned { coordinates ->
                        onTranscriptInteractionAnchorChanged(
                            coordinates.positionInRoot().y + coordinates.size.height * 0.5f
                        )
                    }
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f), RoundedCornerShape(8.dp)),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (transcriptItems.isEmpty() && partialText.isBlank()) {
                    item {
                        Text(
                            text = "正在等待稳定转写...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (displayOrder == RealtimeTranscriptDisplayOrder.Reverse && partialText.isNotBlank()) {
                    item(key = "partial-transcript") {
                        Text(
                            text = "正在识别：$partialText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                    }
                }
                items(renderedTranscriptItems, key = { it.key }) { item ->
                    ClassroomTranscriptRow(
                        item = item,
                        onToggleTranscriptSelection = onToggleTranscriptSelection,
                        onSelectedRowPositioned = if (activeSelectionOrder != null && item.selectionOrder == activeSelectionOrder) {
                            onTranscriptInteractionAnchorChanged
                        } else {
                            null
                        }
                    )
                }
                if (displayOrder == RealtimeTranscriptDisplayOrder.Chronological && partialText.isNotBlank()) {
                    item(key = "partial-transcript") {
                        Text(
                            text = "正在识别：$partialText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                    }
                }
            }
            if (!isAtLatest && itemCount > 0) {
                TextButton(
                    onClick = {
                        scrollToLatestRequest += 1
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                ) {
                    Text("回到最新")
                }
            }
        }
    }
}

@Composable
private fun RealtimeTranscriptOrderToggle(
    displayOrder: RealtimeTranscriptDisplayOrder,
    onDisplayOrderChange: (RealtimeTranscriptDisplayOrder) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        RealtimeTranscriptOrderButton(
            text = "顺序",
            selected = displayOrder == RealtimeTranscriptDisplayOrder.Chronological,
            onClick = { onDisplayOrderChange(RealtimeTranscriptDisplayOrder.Chronological) }
        )
        RealtimeTranscriptOrderButton(
            text = "倒序",
            selected = displayOrder == RealtimeTranscriptDisplayOrder.Reverse,
            onClick = { onDisplayOrderChange(RealtimeTranscriptDisplayOrder.Reverse) }
        )
    }
}

@Composable
private fun RealtimeTranscriptOrderButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

internal fun buildRealtimeTranscriptScrollAnchor(
    transcriptItems: List<ClassroomTranscriptUiItem>,
    partialText: String
): String {
    val latestStableItemKey = transcriptItems.lastOrNull()?.key.orEmpty()
    return "${transcriptItems.size}|$latestStableItemKey|$partialText"
}

internal enum class RealtimeTranscriptDisplayOrder {
    Chronological,
    Reverse
}

internal fun realtimeTranscriptLatestTargetIndex(
    itemCount: Int,
    order: RealtimeTranscriptDisplayOrder = RealtimeTranscriptDisplayOrder.Chronological
): Int? {
    return when {
        itemCount <= 0 -> null
        order == RealtimeTranscriptDisplayOrder.Reverse -> 0
        else -> itemCount - 1
    }
}

internal fun realtimeTranscriptIsLatestVisible(
    visibleIndices: List<Int>,
    itemCount: Int,
    order: RealtimeTranscriptDisplayOrder,
    firstVisibleItemIndex: Int = visibleIndices.firstOrNull() ?: 0,
    firstVisibleItemScrollOffset: Int = 0
): Boolean {
    val targetIndex = realtimeTranscriptLatestTargetIndex(itemCount, order) ?: return true
    return when (order) {
        RealtimeTranscriptDisplayOrder.Chronological -> visibleIndices.contains(targetIndex)
        RealtimeTranscriptDisplayOrder.Reverse ->
            firstVisibleItemIndex == targetIndex && firstVisibleItemScrollOffset == 0
    }
}

internal fun orderRealtimeTranscriptItems(
    transcriptItems: List<ClassroomTranscriptUiItem>,
    order: RealtimeTranscriptDisplayOrder
): List<ClassroomTranscriptUiItem> {
    return when (order) {
        RealtimeTranscriptDisplayOrder.Chronological -> transcriptItems
        RealtimeTranscriptDisplayOrder.Reverse -> transcriptItems.asReversed()
    }
}

@Composable
private fun ClassroomTranscriptRow(
    item: ClassroomTranscriptUiItem,
    onToggleTranscriptSelection: (Long) -> Unit,
    onSelectedRowPositioned: ((Float) -> Unit)? = null
) {
    val transcriptId = item.transcriptId
    val weight = item.weightLevel
    val accentColor = when {
        item.isAnswered -> Color(0xFF22C55E)
        weight == ClassroomTranscriptWeightLevel.Core -> Color(0xFFEF4444)
        weight == ClassroomTranscriptWeightLevel.Important -> Color(0xFFF97316)
        weight == ClassroomTranscriptWeightLevel.Context -> Color(0xFFEAB308)
        else -> MaterialTheme.colorScheme.outline
    }
    val containerColor = if (item.isSelected || item.isAnswered) {
        accentColor.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
    }
    val stateLabel = when {
        item.isAnswered -> "已答"
        weight == ClassroomTranscriptWeightLevel.Core -> "核心"
        weight == ClassroomTranscriptWeightLevel.Important -> "重要"
        weight == ClassroomTranscriptWeightLevel.Context -> "参考"
        else -> "未选"
    }
    val rowShape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = if (item.isSelected || item.isAnswered) {
                    accentColor.copy(alpha = 0.34f)
                } else {
                    Color.Transparent
                },
                shape = rowShape
            )
            .then(
                if (onSelectedRowPositioned != null) {
                    Modifier.onGloballyPositioned { coordinates ->
                        onSelectedRowPositioned(
                            coordinates.positionInRoot().y + coordinates.size.height * 0.5f
                        )
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (transcriptId != null && !item.isAnswered) {
                    Modifier.clickable { onToggleTranscriptSelection(transcriptId) }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(accentColor.copy(alpha = if (item.isSelected || item.isAnswered) 0.16f else 0.08f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Text(
                text = item.timestampLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        if (item.sourceText.isNotBlank()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "原文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
                )
                Text(
                    text = item.sourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

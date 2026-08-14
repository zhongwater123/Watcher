package com.example.watcher.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.ClassroomKnowledgeFrameRef
import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeNodeStatus
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeProcessingStatus
import com.example.watcher.data.model.ClassroomKnowledgeTreeProgress
import com.example.watcher.ui.components.WatcherCard


@Composable
internal fun ClassroomInsightsCard(
    insights: List<String>,
    knowledgeTree: ClassroomKnowledgeTree?,
    changedNodeIds: List<String>,
    newNodeIds: List<String> = emptyList(),
    knowledgeTreeStatus: String,
    knowledgeTreeProgress: ClassroomKnowledgeTreeProgress = ClassroomKnowledgeTreeProgress(),
    knowledgeFrameRefs: List<ClassroomKnowledgeFrameRef>,
    emptyMessage: String = "正在形成知识结构...",
    modifier: Modifier = Modifier
) {
    WatcherCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ClassroomKnowledgeTreeStatusIndicator(knowledgeTreeStatus)
                Text("课堂要点", style = MaterialTheme.typography.titleLarge)
            }
            when {
                knowledgeTree != null && knowledgeTree.nodes.isNotEmpty() -> {
                    ClassroomKnowledgeTreePanel(
                        tree = knowledgeTree,
                        changedNodeIds = changedNodeIds.toSet(),
                        newNodeIds = newNodeIds.toSet(),
                        progress = knowledgeTreeProgress,
                        status = ClassroomKnowledgeTreeProcessingStatus.fromValue(knowledgeTreeStatus),
                        frameRefs = knowledgeFrameRefs.associateBy { it.nodeId }
                    )
                }

                insights.isNotEmpty() -> RealtimeInsightsPanel(
                    insights = insights,
                    progress = knowledgeTreeProgress,
                    status = ClassroomKnowledgeTreeProcessingStatus.fromValue(knowledgeTreeStatus)
                )
                else -> ClassroomKnowledgeTreeEmptyState(
                    message = emptyMessage,
                    progress = knowledgeTreeProgress,
                    status = ClassroomKnowledgeTreeProcessingStatus.fromValue(knowledgeTreeStatus)
                )
            }
        }
    }
}

@Composable
private fun ClassroomKnowledgeTreeStatusIndicator(statusValue: String) {
    when (ClassroomKnowledgeTreeProcessingStatus.fromValue(statusValue)) {
        ClassroomKnowledgeTreeProcessingStatus.Updating -> {
            ClassroomBreathingStatusDot(
                color = Color(0xFF16A34A),
                active = true
            )
        }

        ClassroomKnowledgeTreeProcessingStatus.Completed -> {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF16A34A).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF16A34A)
                )
            }
        }

        ClassroomKnowledgeTreeProcessingStatus.Failed -> {
            ClassroomBreathingStatusDot(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.76f),
                active = false
            )
        }

        ClassroomKnowledgeTreeProcessingStatus.Waiting -> {
            ClassroomBreathingStatusDot(
                color = Color(0xFF2F80ED),
                active = true
            )
        }
    }
}

@Composable
private fun RealtimeInsightsPanel(
    insights: List<String>,
    progress: ClassroomKnowledgeTreeProgress,
    status: ClassroomKnowledgeTreeProcessingStatus
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(560.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        KnowledgeTreeProgressLine(
            text = classroomKnowledgeProgressText(progress, status),
            modifier = Modifier.fillMaxWidth()
        )
        if (insights.isEmpty()) {
            Text(
                text = "正在积累稳定转写，稍后生成滚动要点。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            insights.take(4).forEach { insight ->
                Text(
                    text = "· $insight",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ClassroomKnowledgeFramePreview(frameRef: ClassroomKnowledgeFrameRef) {
    val bitmap = remember(frameRef.framePath) {
        BitmapFactory.decodeFile(frameRef.framePath)
    }
    if (bitmap == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "对应时刻 · ${formatKnowledgeTime(frameRef.frameTimestampMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
        )
    }
}

@Composable
private fun ClassroomKnowledgeTreePanel(
    tree: ClassroomKnowledgeTree,
    changedNodeIds: Set<String>,
    newNodeIds: Set<String>,
    progress: ClassroomKnowledgeTreeProgress,
    status: ClassroomKnowledgeTreeProcessingStatus,
    frameRefs: Map<String, ClassroomKnowledgeFrameRef>
) {
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val activeNodeIds = remember(tree) { collectActiveKnowledgeNodeIds(tree.nodes).toSet() }
    LaunchedEffect(activeNodeIds) {
        if (activeNodeIds.isNotEmpty()) {
            expandedIds = expandedIds + activeNodeIds
        }
    }
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(590.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
    ) {
        item {
            ClassroomKnowledgeTreeRootHeader(
                title = tree.rootTitle,
                structureLabel = classroomKnowledgeStructureLabel(
                    tree = tree,
                    progress = progress,
                    status = status,
                    changedNodeIds = changedNodeIds,
                    newNodeIds = newNodeIds
                )
            )
            KnowledgeTreeProgressLine(
                text = classroomKnowledgeProgressText(progress, status),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            )
        }
        itemsIndexed(tree.nodes, key = { _, node -> node.id }) { index, node ->
            ClassroomKnowledgeTreeBranch(
                node = node,
                depth = 1,
                isFirstSibling = index == 0,
                isLastSibling = index == tree.nodes.lastIndex,
                ancestorContinuations = emptyList(),
                expandedIds = expandedIds,
                changedNodeIds = changedNodeIds,
                newNodeIds = newNodeIds,
                frameRefs = frameRefs,
                onToggleExpanded = { target ->
                    expandedIds = if (target.id in expandedIds) {
                        expandedIds - target.id
                    } else {
                        expandedIds + target.id
                    }
                }
            )
        }
    }
}

@Composable
private fun ClassroomKnowledgeTreeRootHeader(
    title: String,
    structureLabel: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title.ifBlank { "课堂知识树" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = structureLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClassroomKnowledgeTreeBranch(
    node: ClassroomKnowledgeNode,
    depth: Int,
    isFirstSibling: Boolean,
    isLastSibling: Boolean,
    ancestorContinuations: List<Boolean>,
    expandedIds: Set<String>,
    changedNodeIds: Set<String>,
    newNodeIds: Set<String>,
    frameRefs: Map<String, ClassroomKnowledgeFrameRef>,
    onToggleExpanded: (ClassroomKnowledgeNode) -> Unit
) {
    val hasChildren = node.children.isNotEmpty()
    val hasDetails = hasKnowledgeNodeDetails(node)
    val expanded = node.id in expandedIds
    val changed = node.id in changedNodeIds
    val isNew = node.id in newNodeIds
    val hasNextSibling = !isLastSibling
    val isOnActivePath = node.status == ClassroomKnowledgeNodeStatus.Active || hasActiveKnowledgeDescendant(node)
    val statusColor = when (node.status) {
        ClassroomKnowledgeNodeStatus.Active -> MaterialTheme.colorScheme.primary
        ClassroomKnowledgeNodeStatus.Completed -> MaterialTheme.colorScheme.tertiary
        ClassroomKnowledgeNodeStatus.Draft -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val lineColor = if (isOnActivePath) MaterialTheme.colorScheme.primary else statusColor
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 180, easing = LinearEasing)),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top
        ) {
            ClassroomTreeRail(
                depth = depth,
                color = lineColor,
                status = node.status,
                ancestorContinuations = ancestorContinuations,
                isFirstSibling = isFirstSibling,
                continuesBelow = hasNextSibling || (expanded && hasChildren)
            )
            Column(modifier = Modifier.weight(1f)) {
                ClassroomKnowledgeNodePill(
                    node = node,
                    expanded = expanded,
                    changed = changed,
                    isNew = isNew,
                    statusColor = statusColor,
                    isOnActivePath = isOnActivePath,
                    enabled = hasChildren || hasDetails,
                    onClick = { onToggleExpanded(node) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (expanded) {
            if (hasDetails) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    ClassroomTreeContinuation(
                        depth = depth,
                        ancestorContinuations = ancestorContinuations,
                        visible = hasNextSibling || hasChildren,
                        color = lineColor
                    )
                    ClassroomKnowledgeNodeDetails(
                        node = node,
                        frameRef = frameRefs[node.id],
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (hasChildren) {
                ClassroomTreeChildGroupGate(
                    parentDepth = depth,
                    ancestorContinuations = ancestorContinuations,
                    color = lineColor,
                    status = node.status,
                    parentContinuesBelow = hasNextSibling
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    node.children.forEachIndexed { index, child ->
                        ClassroomKnowledgeTreeBranch(
                            node = child,
                            depth = depth + 1,
                            isFirstSibling = index == 0,
                            isLastSibling = index == node.children.lastIndex,
                            ancestorContinuations = ancestorContinuations + hasNextSibling,
                            expandedIds = expandedIds,
                            changedNodeIds = changedNodeIds,
                            newNodeIds = newNodeIds,
                            frameRefs = frameRefs,
                            onToggleExpanded = onToggleExpanded
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassroomTreeRail(
    depth: Int,
    color: Color,
    status: ClassroomKnowledgeNodeStatus,
    ancestorContinuations: List<Boolean>,
    isFirstSibling: Boolean,
    continuesBelow: Boolean
) {
    val depthAlpha = knowledgeTreeDepthAlpha(depth)
    val lineColor = color.copy(alpha = (if (status == ClassroomKnowledgeNodeStatus.Draft) 0.22f else 0.56f) * depthAlpha)
    val branchColor = color.copy(alpha = (if (status == ClassroomKnowledgeNodeStatus.Draft) 0.26f else 0.70f) * depthAlpha)
    val dotColor = color.copy(alpha = if (status == ClassroomKnowledgeNodeStatus.Draft) 0.42f else 1f)
    Canvas(
        modifier = Modifier
            .width(knowledgeTreeRailWidth(depth))
            .fillMaxHeight()
    ) {
        ancestorContinuations.forEachIndexed { index, shouldContinue ->
            if (shouldContinue) {
                val ancestorX = knowledgeTreeLevelX(index + 1).toPx()
                drawLine(
                    color = lineColor.copy(alpha = lineColor.alpha * 0.46f),
                    start = Offset(ancestorX, 0f),
                    end = Offset(ancestorX, size.height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        val x = knowledgeTreeLevelX(depth).toPx()
        val y = 25.dp.toPx().coerceAtMost(size.height / 2f)
        val stroke = 2.dp.toPx()
        if (!isFirstSibling || depth > 1) {
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
        if (continuesBelow) {
            drawLine(
                color = lineColor,
                start = Offset(x, y),
                end = Offset(x, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
        drawLine(
            color = branchColor,
            start = Offset(x, y),
            end = Offset(size.width - 6.dp.toPx(), y),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = dotColor.copy(alpha = 0.16f * depthAlpha),
            radius = 9.dp.toPx(),
            center = Offset(x, y)
        )
        drawCircle(
            color = dotColor,
            radius = if (status == ClassroomKnowledgeNodeStatus.Active) 5.5.dp.toPx() else 4.5.dp.toPx(),
            center = Offset(x, y)
        )
    }
}

@Composable
private fun ClassroomTreeChildGroupGate(
    parentDepth: Int,
    ancestorContinuations: List<Boolean>,
    color: Color,
    status: ClassroomKnowledgeNodeStatus,
    parentContinuesBelow: Boolean
) {
    val depthAlpha = knowledgeTreeDepthAlpha(parentDepth + 1)
    val lineColor = color.copy(alpha = (if (status == ClassroomKnowledgeNodeStatus.Draft) 0.20f else 0.50f) * depthAlpha)
    val fillColor = color.copy(alpha = (if (status == ClassroomKnowledgeNodeStatus.Draft) 0.05f else 0.10f) * depthAlpha)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(
            modifier = Modifier
                .width(knowledgeTreeRailWidth(parentDepth + 1))
                .fillMaxHeight()
        ) {
            ancestorContinuations.forEachIndexed { index, shouldContinue ->
                if (shouldContinue) {
                    val ancestorX = knowledgeTreeLevelX(index + 1).toPx()
                    drawLine(
                        color = lineColor.copy(alpha = lineColor.alpha * 0.46f),
                        start = Offset(ancestorX, 0f),
                        end = Offset(ancestorX, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            val parentXPx = knowledgeTreeLevelX(parentDepth).toPx()
            val childXPx = knowledgeTreeLevelX(parentDepth + 1).toPx()
            val topY = 0f
            val midY = size.height * 0.54f
            val bottomY = size.height
            val stroke = 2.dp.toPx()
            drawLine(
                color = lineColor,
                start = Offset(parentXPx, topY),
                end = Offset(parentXPx, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            if (parentContinuesBelow) {
                drawLine(
                    color = lineColor.copy(alpha = lineColor.alpha * 0.72f),
                    start = Offset(parentXPx, midY),
                    end = Offset(parentXPx, bottomY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            drawLine(
                color = lineColor,
                start = Offset(parentXPx, midY),
                end = Offset(childXPx, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = lineColor,
                start = Offset(childXPx, midY),
                end = Offset(childXPx, bottomY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = fillColor,
                radius = 5.dp.toPx(),
                center = Offset(childXPx, midY)
            )
        }
    }
}

@Composable
private fun ClassroomTreeContinuation(
    depth: Int,
    ancestorContinuations: List<Boolean>,
    visible: Boolean,
    color: Color
) {
    if (!visible) {
        Spacer(Modifier.width(knowledgeTreeRailWidth(depth)))
        return
    }
    Canvas(
        modifier = Modifier
            .width(knowledgeTreeRailWidth(depth))
            .fillMaxHeight()
    ) {
        val depthAlpha = knowledgeTreeDepthAlpha(depth)
        ancestorContinuations.forEachIndexed { index, shouldContinue ->
            if (shouldContinue) {
                val ancestorX = knowledgeTreeLevelX(index + 1).toPx()
                drawLine(
                    color = color.copy(alpha = 0.14f * knowledgeTreeDepthAlpha(index + 1)),
                    start = Offset(ancestorX, 0f),
                    end = Offset(ancestorX, size.height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        val x = knowledgeTreeLevelX(depth).toPx()
        drawLine(
            color = color.copy(alpha = 0.22f * depthAlpha),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun ClassroomKnowledgeNodePill(
    node: ClassroomKnowledgeNode,
    expanded: Boolean,
    changed: Boolean,
    isNew: Boolean,
    statusColor: Color,
    isOnActivePath: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when (node.status) {
        ClassroomKnowledgeNodeStatus.Active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
        ClassroomKnowledgeNodeStatus.Completed -> if (isOnActivePath) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.065f)
        } else {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.075f)
        }
        ClassroomKnowledgeNodeStatus.Draft -> MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
    }
    val borderColor = if (changed) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    } else if (isOnActivePath) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    } else {
        statusColor.copy(alpha = if (node.status == ClassroomKnowledgeNodeStatus.Draft) 0.10f else 0.18f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                KnowledgeNodeChangeBadge(isNew = isNew, changed = changed)
            }
            node.oneLineTakeaway.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = knowledgeNodeStatusLabel(node.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
                val timeRange = formatKnowledgeNodeTimeRange(node)
                if (timeRange.isNotBlank()) {
                    Text(
                        text = timeRange,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (enabled) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun KnowledgeNodeChangeBadge(
    isNew: Boolean,
    changed: Boolean
) {
    val label = when {
        isNew -> "新"
        changed -> "已更新"
        else -> return
    }
    val color = if (isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun ClassroomKnowledgeNodeDetails(
    node: ClassroomKnowledgeNode,
    frameRef: ClassroomKnowledgeFrameRef?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.06f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        node.oneLineTakeaway.takeIf(String::isNotBlank)?.let {
            KnowledgeNodeDetailSection(title = "一句话理解", items = listOf(it))
        }
        KnowledgeNodeDetailSection(title = "老师强调", items = node.teacherEmphasis)
        KnowledgeNodeDetailSection(title = "例子", items = node.examples)
        KnowledgeNodeDetailSection(title = "易错点", items = node.misunderstandings)
        frameRef?.let { ClassroomKnowledgeFramePreview(it) }
    }
}

@Composable
private fun KnowledgeNodeDetailSection(title: String, items: List<String>) {
    val safeItems = items.map(String::trim).filter(String::isNotBlank)
    if (safeItems.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        safeItems.forEach { item ->
            Text(
                text = "· $item",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClassroomKnowledgeTreeEmptyState(
    message: String,
    progress: ClassroomKnowledgeTreeProgress,
    status: ClassroomKnowledgeTreeProcessingStatus
) {
    val skeletonLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val skeletonDotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(590.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        KnowledgeTreeProgressLine(
            text = classroomKnowledgeProgressText(progress, status),
            modifier = Modifier.fillMaxWidth()
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(
                        modifier = Modifier
                            .width(38.dp)
                            .height(38.dp)
                    ) {
                        val x = 18.dp.toPx()
                        val y = size.height / 2f
                        drawLine(
                            color = skeletonLineColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = skeletonLineColor,
                            start = Offset(x, y),
                            end = Offset(size.width, y),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawCircle(
                            color = skeletonDotColor,
                            radius = 5.dp.toPx(),
                            center = Offset(x, y)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.78f else 0.56f)
                            .height(22.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                    )
                }
            }
        }
    }
}

private fun collectActiveKnowledgeNodeIds(nodes: List<ClassroomKnowledgeNode>): List<String> {
    val ids = mutableListOf<String>()
    fun visit(node: ClassroomKnowledgeNode): Boolean {
        val childHasActive = node.children.any(::visit)
        val selfActive = node.status == ClassroomKnowledgeNodeStatus.Active
        if (selfActive || childHasActive) ids.add(node.id)
        return selfActive || childHasActive
    }
    nodes.forEach(::visit)
    return ids
}

private fun hasActiveKnowledgeDescendant(node: ClassroomKnowledgeNode): Boolean {
    return node.children.any { child ->
        child.status == ClassroomKnowledgeNodeStatus.Active || hasActiveKnowledgeDescendant(child)
    }
}

private fun hasKnowledgeNodeDetails(node: ClassroomKnowledgeNode): Boolean {
    return node.oneLineTakeaway.isNotBlank() ||
        node.teacherEmphasis.any(String::isNotBlank) ||
        node.examples.any(String::isNotBlank) ||
        node.misunderstandings.any(String::isNotBlank)
}

@Composable
private fun KnowledgeTreeProgressLine(
    text: String,
    modifier: Modifier = Modifier
) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.48f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

internal fun classroomKnowledgeStructureLabel(
    tree: ClassroomKnowledgeTree,
    progress: ClassroomKnowledgeTreeProgress = ClassroomKnowledgeTreeProgress(),
    status: ClassroomKnowledgeTreeProcessingStatus = ClassroomKnowledgeTreeProcessingStatus.Waiting,
    changedNodeIds: Set<String> = emptySet(),
    newNodeIds: Set<String> = emptySet()
): String {
    activeKnowledgeNodeTitle(tree)?.let { return "正在讲：$it" }
    val totalNodes = countKnowledgeNodes(tree.nodes)
    val coverage = formatKnowledgeTreeCoverage(tree)
    if (totalNodes > 0 && coverage.isNotBlank()) {
        return "已整理 $totalNodes 个知识点 · 覆盖 $coverage"
    }
    if (newNodeIds.isNotEmpty() || changedNodeIds.isNotEmpty()) {
        val newCount = newNodeIds.size
        val updatedCount = (changedNodeIds - newNodeIds).size
        return when {
            newCount > 0 && updatedCount > 0 -> "新增 $newCount 个 · 更新 $updatedCount 个知识点"
            newCount > 0 -> "新增 $newCount 个知识点"
            else -> "更新 $updatedCount 个知识点"
        }
    }
    if (totalNodes > 0) {
        return "已整理 $totalNodes 个知识点"
    }
    val topicCount = countKnowledgeNodesAtDepth(tree.nodes, targetDepth = 2)
    return if (topicCount > 0) {
        "课堂结构 · $topicCount 个主题"
    } else {
        "课堂结构 · 正在形成"
    }
}

internal fun classroomKnowledgeProgressText(
    progress: ClassroomKnowledgeTreeProgress,
    status: ClassroomKnowledgeTreeProcessingStatus
): String {
    if (status == ClassroomKnowledgeTreeProcessingStatus.Updating || progress.jobActive) {
        return "正在整理知识结构"
    }
    if (progress.requiredChars <= 0 || progress.requiredIntervalMs <= 0L) return ""
    val seconds = (progress.remainingMs / 1_000L).coerceAtLeast(0L)
    val chars = "${progress.addedChars.coerceAtMost(progress.requiredChars)}/${progress.requiredChars} 字"
    return when {
        progress.remainingChars > 0 && seconds > 0L -> "正在积累本段内容 · 已积累 $chars · 约 ${seconds} 秒后整理"
        progress.remainingChars > 0 -> "正在积累本段内容 · 已积累 $chars · 继续听课后整理"
        seconds > 0L -> "内容已足够 · 约 ${seconds} 秒后整理"
        else -> "即将整理知识结构"
    }
}

private fun activeKnowledgeNodeTitle(tree: ClassroomKnowledgeTree): String? {
    fun find(nodes: List<ClassroomKnowledgeNode>): String? {
        nodes.forEach { node ->
            find(node.children)?.let { return it }
            if (node.status == ClassroomKnowledgeNodeStatus.Active) return node.title
        }
        return null
    }
    return find(tree.nodes)?.takeIf(String::isNotBlank)
}

private fun countKnowledgeNodes(nodes: List<ClassroomKnowledgeNode>): Int {
    return nodes.sumOf { 1 + countKnowledgeNodes(it.children) }
}

private fun formatKnowledgeTreeCoverage(tree: ClassroomKnowledgeTree): String {
    val ranges = collectKnowledgeNodeTimeRanges(tree.nodes)
    val start = ranges.minOfOrNull { it.first } ?: return ""
    val end = ranges.maxOfOrNull { it.second } ?: return ""
    return "${formatKnowledgeTime(start)}-${formatKnowledgeTime(end)}"
}

private fun collectKnowledgeNodeTimeRanges(nodes: List<ClassroomKnowledgeNode>): List<Pair<Long, Long>> {
    return nodes.flatMap { node ->
        val self = if (
            node.startMs != null &&
            node.endMs != null &&
            node.endMs >= node.startMs &&
            !(node.startMs == 0L && node.endMs == 0L)
        ) {
            listOf(node.startMs to node.endMs)
        } else {
            emptyList()
        }
        self + collectKnowledgeNodeTimeRanges(node.children)
    }
}

private fun countKnowledgeNodesAtDepth(
    nodes: List<ClassroomKnowledgeNode>,
    targetDepth: Int,
    depth: Int = 1
): Int {
    return nodes.sumOf { node ->
        if (depth == targetDepth) {
            1
        } else {
            countKnowledgeNodesAtDepth(node.children, targetDepth, depth + 1)
        }
    }
}

private fun knowledgeNodeStatusLabel(status: ClassroomKnowledgeNodeStatus): String {
    return when (status) {
        ClassroomKnowledgeNodeStatus.Active -> "正在讲"
        ClassroomKnowledgeNodeStatus.Completed -> "已完成"
        ClassroomKnowledgeNodeStatus.Draft -> "草稿"
    }
}

private fun knowledgeTreeDepthAlpha(depth: Int): Float {
    return when (depth) {
        1 -> 1f
        2 -> 0.82f
        else -> 0.66f
    }
}

private fun knowledgeTreeLevelX(depth: Int) = (18 + 20 * (depth - 1).coerceAtLeast(0)).dp

private fun knowledgeTreeRailWidth(depth: Int) = (42 + 20 * (depth - 1).coerceAtLeast(0)).dp

private fun formatKnowledgeNodeTimeRange(node: ClassroomKnowledgeNode): String {
    val start = node.startMs ?: return ""
    val end = node.endMs
    return if (end != null && end >= start) {
        "${formatKnowledgeTime(start)} - ${formatKnowledgeTime(end)}"
    } else {
        formatKnowledgeTime(start)
    }
}

private fun formatKnowledgeTime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

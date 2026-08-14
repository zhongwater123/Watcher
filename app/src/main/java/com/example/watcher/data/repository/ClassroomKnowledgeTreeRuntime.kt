package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeUpdate
import java.util.Locale
import kotlin.math.max

internal data class ClassroomKnowledgeTranscriptLine(
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val source: String,
    val asrLogId: String?
)

internal data class ClassroomKnowledgeTranscriptWindow(
    val lines: List<ClassroomKnowledgeTranscriptLine>
) {
    val startMs: Long? = lines.minOfOrNull { it.startMs }
    val endMs: Long? = lines.maxOfOrNull { it.endMs }
    val charCount: Int = lines.sumOf { it.text.length }

    fun isBlank(): Boolean = lines.isEmpty() || lines.all { it.text.isBlank() }

    fun renderForPrompt(): String {
        if (lines.isEmpty()) return "(no stable ASR lines)"
        return lines.joinToString(separator = "\n") { line ->
            "[${formatTimestamp(line.startMs)}-${formatTimestamp(line.endMs)}] ${line.text}"
        }
    }

    fun describeRange(): String {
        val start = startMs ?: return "-"
        val end = endMs ?: start
        return "${formatTimestamp(start)}-${formatTimestamp(end)}"
    }

    companion object {
        fun formatTimestamp(ms: Long): String {
            val safeMs = ms.coerceAtLeast(0L)
            val totalSeconds = safeMs / 1_000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            val centiseconds = (safeMs % 1_000L) / 10L
            return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centiseconds)
        }
    }
}

internal data class ClassroomKnowledgeTreeMergeStats(
    val kept: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val ignoredReparents: Int = 0,
    val mergedDuplicates: Int = 0,
    val validTimeNodes: Int = 0,
    val zeroTimeNodes: Int = 0,
    val fallbackTimeNodes: Int = 0
) {
    operator fun plus(other: ClassroomKnowledgeTreeMergeStats): ClassroomKnowledgeTreeMergeStats {
        return ClassroomKnowledgeTreeMergeStats(
            kept = kept + other.kept,
            added = added + other.added,
            updated = updated + other.updated,
            ignoredReparents = ignoredReparents + other.ignoredReparents,
            mergedDuplicates = mergedDuplicates + other.mergedDuplicates,
            validTimeNodes = validTimeNodes + other.validTimeNodes,
            zeroTimeNodes = zeroTimeNodes + other.zeroTimeNodes,
            fallbackTimeNodes = fallbackTimeNodes + other.fallbackTimeNodes
        )
    }
}

internal data class ClassroomKnowledgeTreeMergeResult(
    val update: ClassroomKnowledgeTreeUpdate,
    val stats: ClassroomKnowledgeTreeMergeStats
)

internal object ClassroomKnowledgeTreeMergePolicy {
    fun merge(
        currentTree: ClassroomKnowledgeTree?,
        candidateUpdate: ClassroomKnowledgeTreeUpdate,
        window: ClassroomKnowledgeTranscriptWindow,
        finalFlush: Boolean
    ): ClassroomKnowledgeTreeMergeResult {
        val current = currentTree
        val candidateTree = candidateUpdate.tree
        val currentIds = current?.nodes.orEmpty().flatMap(::flattenNode).map { it.id }.toSet()
        val candidateById = candidateTree.nodes.flatMap(::flattenNode).associateBy { it.id }
        val candidateParentById = candidateTree.nodes.flatMap { flattenWithParent(it, it.parentId) }.toMap()
        val currentParentById = current?.nodes.orEmpty().flatMap { flattenWithParent(it, it.parentId) }.toMap()
        var stats = ClassroomKnowledgeTreeMergeStats()

        fun normalizeNodeTime(
            old: ClassroomKnowledgeNode?,
            candidate: ClassroomKnowledgeNode,
            isNewNode: Boolean
        ): Pair<ClassroomKnowledgeNode, ClassroomKnowledgeTreeMergeStats> {
            val candidateHasValidTime = candidate.hasValidTime(window)
            val oldHasValidTime = old?.hasValidTime(window) == true
            val normalized = when {
                candidateHasValidTime -> candidate
                oldHasValidTime -> candidate.copy(startMs = old?.startMs, endMs = old?.endMs)
                isNewNode && window.startMs != null -> candidate.copy(startMs = window.startMs, endMs = window.endMs)
                else -> candidate.copy(startMs = null, endMs = null)
            }
            val fallback = if (!candidateHasValidTime && (oldHasValidTime || isNewNode && window.startMs != null)) 1 else 0
            return normalized to ClassroomKnowledgeTreeMergeStats(fallbackTimeNodes = fallback)
        }

        fun mergeNode(old: ClassroomKnowledgeNode): ClassroomKnowledgeNode {
            val candidate = candidateById[old.id]
            if (candidate == null) {
                stats += ClassroomKnowledgeTreeMergeStats(kept = 1)
                return old.copy(children = old.children.map(::mergeNode))
            }
            if (candidateParentById[old.id] != null && currentParentById[old.id] != candidateParentById[old.id]) {
                stats += ClassroomKnowledgeTreeMergeStats(ignoredReparents = 1)
            }
            val (timeFixed, timeStats) = normalizeNodeTime(old, candidate, isNewNode = false)
            stats += timeStats + ClassroomKnowledgeTreeMergeStats(updated = 1)
            val oldChildIds = old.children.map { it.id }.toSet()
            val mergedExistingChildren = old.children.map(::mergeNode)
            val appendedChildren = timeFixed.children
                .filter { it.id !in oldChildIds && it.id !in currentIds }
                .mapNotNull { addNewSubtree(it, parentId = old.id, window = window, blockedIds = currentIds) }
            return old.copy(
                title = timeFixed.title.ifBlank { old.title },
                oneLineTakeaway = timeFixed.oneLineTakeaway.ifBlank { old.oneLineTakeaway },
                teacherEmphasis = mergeDetailList(old.teacherEmphasis, timeFixed.teacherEmphasis, maxItems = 4),
                examples = mergeDetailList(old.examples, timeFixed.examples, maxItems = 4),
                misunderstandings = mergeDetailList(old.misunderstandings, timeFixed.misunderstandings, maxItems = 3),
                startMs = timeFixed.startMs,
                endMs = timeFixed.endMs,
                status = timeFixed.status,
                updatedAtMs = max(old.updatedAtMs, timeFixed.updatedAtMs),
                children = mergedExistingChildren + appendedChildren
            )
        }

        fun mergedTree(): ClassroomKnowledgeTree {
            if (current == null || current.nodes.isEmpty()) {
                val addedNodes = candidateTree.nodes.mapNotNull {
                    addNewSubtree(it, parentId = null, window = window, blockedIds = currentIds)
                }
                return candidateTree.copy(nodes = addedNodes)
            }
            val currentRootIds = current.nodes.map { it.id }.toSet()
            val mergedRoots = current.nodes.map(::mergeNode)
            val newRoots = candidateTree.nodes
                .filter { it.id !in currentRootIds && it.id !in currentIds }
                .mapNotNull { addNewSubtree(it, parentId = null, window = window, blockedIds = currentIds) }
            return current.copy(
                rootTitle = candidateTree.rootTitle.ifBlank { current.rootTitle },
                nodes = mergedRoots + newRoots,
                updatedAtMs = max(current.updatedAtMs, candidateTree.updatedAtMs)
            )
        }

        val deduped = if (finalFlush) {
            dedupeSiblings(mergedTree()).also { stats += it.second }.first
        } else {
            mergedTree()
        }
        val timeNormalized = normalizeParentTimes(deduped)
        val addedCount = timeNormalized.nodes
            .flatMap(::flattenNode)
            .count { it.id !in currentIds }
        stats += ClassroomKnowledgeTreeMergeStats(added = addedCount)
        val quality = timeQuality(timeNormalized, window)
        val changedIds = candidateUpdate.changedNodeIds.filter { id ->
            timeNormalized.nodes.any { containsNodeId(it, id) }
        }
        return ClassroomKnowledgeTreeMergeResult(
            update = ClassroomKnowledgeTreeUpdate(tree = timeNormalized, changedNodeIds = changedIds),
            stats = stats + quality
        )
    }

    private fun addNewSubtree(
        node: ClassroomKnowledgeNode,
        parentId: String?,
        window: ClassroomKnowledgeTranscriptWindow,
        blockedIds: Set<String>
    ): ClassroomKnowledgeNode? {
        val hasValidTime = node.hasValidTime(window)
        val normalized = if (hasValidTime) {
            node
        } else {
            node.copy(startMs = window.startMs, endMs = window.endMs)
        }
        val children = normalized.children
            .filter { it.id !in blockedIds }
            .mapNotNull { addNewSubtree(it, parentId = normalized.id, window = window, blockedIds = blockedIds) }
        val lostAllChildren = normalized.children.isNotEmpty() && children.isEmpty()
        if (
            lostAllChildren &&
            normalized.oneLineTakeaway.isBlank() &&
            normalized.teacherEmphasis.isEmpty() &&
            normalized.examples.isEmpty() &&
            normalized.misunderstandings.isEmpty()
        ) {
            return null
        }
        return normalized.copy(
            parentId = parentId,
            children = children
        )
    }

    private fun normalizeParentTimes(tree: ClassroomKnowledgeTree): ClassroomKnowledgeTree {
        return tree.copy(nodes = tree.nodes.map(::normalizeParentTimes))
    }

    private fun normalizeParentTimes(node: ClassroomKnowledgeNode): ClassroomKnowledgeNode {
        val children = node.children.map(::normalizeParentTimes)
        val ranges = buildList {
            node.usableRange()?.let(::add)
            children.forEach { child ->
                child.usableRange()?.let(::add)
            }
        }
        return if (ranges.isEmpty()) {
            node.copy(children = children)
        } else {
            node.copy(
                startMs = ranges.minOf { it.first },
                endMs = ranges.maxOf { it.second },
                children = children
            )
        }
    }

    private fun dedupeSiblings(tree: ClassroomKnowledgeTree): Pair<ClassroomKnowledgeTree, ClassroomKnowledgeTreeMergeStats> {
        var merged = 0
        fun dedupe(nodes: List<ClassroomKnowledgeNode>): List<ClassroomKnowledgeNode> {
            val output = mutableListOf<ClassroomKnowledgeNode>()
            nodes.forEach { node ->
                val normalized = node.copy(children = dedupe(node.children))
                val existingIndex = output.indexOfFirst {
                    normalizeTitle(it.title) == normalizeTitle(normalized.title) &&
                        normalizeTitle(normalized.title).isNotBlank()
                }
                if (existingIndex >= 0) {
                    val existing = output[existingIndex]
                    output[existingIndex] = existing.copy(
                        oneLineTakeaway = existing.oneLineTakeaway.ifBlank { normalized.oneLineTakeaway },
                        teacherEmphasis = mergeDetailList(existing.teacherEmphasis, normalized.teacherEmphasis, 4),
                        examples = mergeDetailList(existing.examples, normalized.examples, 4),
                        misunderstandings = mergeDetailList(existing.misunderstandings, normalized.misunderstandings, 3),
                        children = existing.children + normalized.children
                    )
                    merged += 1
                } else {
                    output += normalized
                }
            }
            return output
        }
        return tree.copy(nodes = dedupe(tree.nodes)) to ClassroomKnowledgeTreeMergeStats(mergedDuplicates = merged)
    }

    private fun timeQuality(
        tree: ClassroomKnowledgeTree,
        window: ClassroomKnowledgeTranscriptWindow
    ): ClassroomKnowledgeTreeMergeStats {
        val nodes = tree.nodes.flatMap(::flattenNode)
        val valid = nodes.count { it.hasValidTime(window) }
        val zero = nodes.count { it.startMs == 0L && it.endMs == 0L && (window.startMs ?: 0L) > 5_000L }
        return ClassroomKnowledgeTreeMergeStats(validTimeNodes = valid, zeroTimeNodes = zero)
    }

    private fun ClassroomKnowledgeNode.hasValidTime(window: ClassroomKnowledgeTranscriptWindow): Boolean {
        val start = startMs ?: return false
        val end = endMs ?: return false
        if (start < 0L || end < start) return false
        if (start == 0L && end == 0L && (window.startMs ?: 0L) > 5_000L) return false
        return true
    }

    private fun ClassroomKnowledgeNode.usableRange(): Pair<Long, Long>? {
        val start = startMs ?: return null
        val end = endMs ?: return null
        if (start < 0L || end < start) return null
        if (start == 0L && end == 0L) return null
        return start to end
    }

    private fun flattenNode(node: ClassroomKnowledgeNode): List<ClassroomKnowledgeNode> {
        return listOf(node) + node.children.flatMap(::flattenNode)
    }

    private fun flattenWithParent(node: ClassroomKnowledgeNode, parentId: String?): List<Pair<String, String?>> {
        return listOf(node.id to parentId) + node.children.flatMap { child -> flattenWithParent(child, node.id) }
    }

    private fun containsNodeId(node: ClassroomKnowledgeNode, id: String): Boolean {
        return node.id == id || node.children.any { containsNodeId(it, id) }
    }

    private fun mergeDetailList(old: List<String>, candidate: List<String>, maxItems: Int): List<String> {
        return (old + candidate)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(maxItems)
    }

    private fun normalizeTitle(title: String): String {
        return title.filter { it.isLetterOrDigit() }.lowercase()
    }
}

internal fun List<ClassroomKnowledgeTranscriptLine>.takeWindowFrom(
    startIndex: Int,
    maxChars: Int
): ClassroomKnowledgeTranscriptWindow {
    if (startIndex !in indices) return ClassroomKnowledgeTranscriptWindow(emptyList())
    var chars = 0
    val selected = mutableListOf<ClassroomKnowledgeTranscriptLine>()
    for (line in drop(startIndex)) {
        if (selected.isNotEmpty() && chars + line.text.length > maxChars) break
        selected += line
        chars += line.text.length
    }
    return ClassroomKnowledgeTranscriptWindow(selected)
}

package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeNodeStatus
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeUpdate
import org.json.JSONArray
import org.json.JSONObject

internal object ClassroomKnowledgeTreeParser {
    const val MAX_DEPTH = 4

    fun parseUpdate(rawText: String, nowMs: Long = System.currentTimeMillis()): ClassroomKnowledgeTreeUpdate? {
        val jsonText = extractJsonObject(rawText) ?: return null
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val treeObject = root.optJSONObject("tree") ?: root
        val tree = parseTree(treeObject, nowMs)
            .normalizeSingleActiveNode()
            .takeIf { it.nodes.isNotEmpty() }
            ?: return null
        val changedIds = root.optJSONArray("changedNodeIds").toStringList()
        return ClassroomKnowledgeTreeUpdate(tree = tree, changedNodeIds = changedIds)
    }

    fun toPromptJson(tree: ClassroomKnowledgeTree?): String {
        if (tree == null || tree.nodes.isEmpty()) return "{}"
        return JSONObject().apply {
            put("rootTitle", tree.rootTitle)
            put("updatedAtMs", tree.updatedAtMs)
            put("nodes", tree.nodes.toJsonArray())
        }.toString()
    }

    fun countNodes(tree: ClassroomKnowledgeTree?): Int {
        return tree?.nodes.orEmpty().sumOf(::countNodes)
    }

    fun countActiveNodes(tree: ClassroomKnowledgeTree?): Int {
        return tree?.nodes.orEmpty().sumOf(::countActiveNodes)
    }

    fun maxDepth(tree: ClassroomKnowledgeTree?): Int {
        return tree?.nodes.orEmpty().maxOfOrNull { maxDepth(it, depth = 1) } ?: 0
    }

    fun maxDetailItems(tree: ClassroomKnowledgeTree?): Int {
        return tree?.nodes.orEmpty().maxOfOrNull(::maxDetailItems) ?: 0
    }

    fun countEmptyDetailNodes(tree: ClassroomKnowledgeTree?): Int {
        return tree?.nodes.orEmpty().sumOf(::countEmptyDetailNodes)
    }

    fun countValidTimeNodes(tree: ClassroomKnowledgeTree?): Int {
        return tree?.nodes.orEmpty().sumOf(::countValidTimeNodes)
    }

    fun countZeroTimeNodes(tree: ClassroomKnowledgeTree?): Int {
        return tree?.nodes.orEmpty().sumOf(::countZeroTimeNodes)
    }

    fun countNodesAtDepth(tree: ClassroomKnowledgeTree?, targetDepth: Int): Int {
        return tree?.nodes.orEmpty().sumOf { countNodesAtDepth(it, targetDepth, depth = 1) }
    }

    fun countRepresentativeTimeNodesAtDepth(tree: ClassroomKnowledgeTree?, targetDepth: Int): Int {
        return tree?.nodes.orEmpty().sumOf {
            countRepresentativeTimeNodesAtDepth(it, targetDepth, depth = 1)
        }
    }

    fun renderDebugOutline(tree: ClassroomKnowledgeTree?): String {
        if (tree == null || tree.nodes.isEmpty()) return "(empty knowledge tree)"
        return buildString {
            appendLine("root=${tree.rootTitle} updatedAtMs=${tree.updatedAtMs}")
            tree.nodes.forEach { appendNode(it, depth = 1) }
        }.trim()
    }

    fun renderActivePathSummary(tree: ClassroomKnowledgeTree?): String {
        if (tree == null || tree.nodes.isEmpty()) return ""
        val activePath = tree.nodes.firstNotNullOfOrNull { findActivePath(it, emptyList()) } ?: return ""
        return buildString {
            appendLine("当前 active 路径：")
            activePath.forEachIndexed { index, node ->
                appendLine(
                    "L${index + 1}: [${node.status.value}] ${node.title} id=${node.id} time=${node.startMs ?: "-"}-${node.endMs ?: "-"}"
                )
            }
            activePath.lastOrNull()?.let { active ->
                if (active.oneLineTakeaway.isNotBlank()) {
                    appendLine("当前节点一句话：${active.oneLineTakeaway}")
                }
            }
        }.trim()
    }

    private fun parseTree(treeObject: JSONObject, nowMs: Long): ClassroomKnowledgeTree {
        val rootTitle = treeObject.optString("rootTitle").ifBlank { "课堂知识树" }
        val nodes = treeObject.optJSONArray("nodes")
            ?.let { parseNodes(it, depth = 1, fallbackParentId = null, nowMs = nowMs) }
            .orEmpty()
        return ClassroomKnowledgeTree(
            rootTitle = rootTitle,
            nodes = nodes,
            updatedAtMs = treeObject.optLong("updatedAtMs", nowMs).takeIf { it > 0L } ?: nowMs
        )
    }

    private fun parseNodes(
        array: JSONArray,
        depth: Int,
        fallbackParentId: String?,
        nowMs: Long
    ): List<ClassroomKnowledgeNode> {
        if (depth > MAX_DEPTH) return emptyList()
        val parsed = buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val title = obj.optString("title").trim()
                if (title.isBlank()) continue
                val explicitParentId = obj.optParentId()
                val resolvedParentId = explicitParentId ?: fallbackParentId
                val id = obj.optString("id").trim().ifBlank {
                    buildGeneratedId(resolvedParentId, depth, index, title)
                }
                val children = if (depth < MAX_DEPTH) {
                    obj.optJSONArray("children")
                        ?.let { parseNodes(it, depth = depth + 1, fallbackParentId = id, nowMs = nowMs) }
                        .orEmpty()
                } else {
                    emptyList()
                }
                val overflowDetails = if (depth >= MAX_DEPTH) {
                    obj.optJSONArray("children").toOverflowDetails()
                } else {
                    OverflowDetails()
                }
                val teacherEmphasis = obj.optJSONArray("teacherEmphasis")
                    .toStringList(maxItems = 4)
                    .plus(overflowDetails.teacherEmphasis)
                    .take(4)
                val examples = obj.optJSONArray("examples")
                    .toStringList(maxItems = 4)
                    .plus(overflowDetails.examples)
                    .take(4)
                val misunderstandings = obj.optJSONArray("misunderstandings")
                    .toStringList(maxItems = 3)
                    .plus(overflowDetails.misunderstandings)
                    .take(3)
                add(
                    ClassroomKnowledgeNode(
                        id = id,
                        parentId = resolvedParentId,
                        title = title,
                        oneLineTakeaway = obj.optString("oneLineTakeaway").trim(),
                        teacherEmphasis = teacherEmphasis,
                        examples = examples,
                        misunderstandings = misunderstandings,
                        startMs = obj.optNullableLong("startMs"),
                        endMs = obj.optNullableLong("endMs"),
                        status = ClassroomKnowledgeNodeStatus.fromValue(obj.optString("status")),
                        children = children,
                        updatedAtMs = obj.optLong("updatedAtMs", nowMs).takeIf { it > 0L } ?: nowMs
                    )
                )
            }
        }
        return parsed.nestFlatChildren(parentId = fallbackParentId, depth = depth)
    }

    private fun List<ClassroomKnowledgeNode>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { node ->
            array.put(JSONObject().apply {
                put("id", node.id)
                put("parentId", node.parentId ?: JSONObject.NULL)
                put("title", node.title)
                put("oneLineTakeaway", node.oneLineTakeaway)
                put("teacherEmphasis", JSONArray(node.teacherEmphasis))
                put("examples", JSONArray(node.examples))
                put("misunderstandings", JSONArray(node.misunderstandings))
                put("startMs", node.startMs ?: JSONObject.NULL)
                put("endMs", node.endMs ?: JSONObject.NULL)
                put("status", node.status.value)
                put("updatedAtMs", node.updatedAtMs)
                put("children", node.children.toJsonArray())
            })
        }
        return array
    }

    private fun JSONArray?.toStringList(maxItems: Int = Int.MAX_VALUE): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                if (size >= maxItems) break
                val value = when (val item = opt(index)) {
                    is JSONObject -> item.optString("text")
                        .ifBlank { item.optString("title") }
                        .ifBlank { item.optString("summary") }
                    else -> item?.toString().orEmpty()
                }.trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private data class OverflowDetails(
        val teacherEmphasis: List<String> = emptyList(),
        val examples: List<String> = emptyList(),
        val misunderstandings: List<String> = emptyList()
    )

    private fun JSONArray?.toOverflowDetails(): OverflowDetails {
        if (this == null) return OverflowDetails()
        val emphasis = mutableListOf<String>()
        val examples = mutableListOf<String>()
        val misunderstandings = mutableListOf<String>()
        fun collect(array: JSONArray?) {
            if (array == null) return
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val title = obj.optString("title").trim()
                val takeaway = obj.optString("oneLineTakeaway").trim()
                val summary = listOf(title, takeaway)
                    .filter { it.isNotBlank() }
                    .joinToString("：")
                if (summary.isNotBlank()) emphasis += "下级知识点摘要：$summary"
                examples += obj.optJSONArray("examples").toStringList()
                misunderstandings += obj.optJSONArray("misunderstandings").toStringList()
                collect(obj.optJSONArray("children"))
            }
        }
        collect(this)
        return OverflowDetails(
            teacherEmphasis = emphasis,
            examples = examples,
            misunderstandings = misunderstandings
        )
    }

    private fun List<ClassroomKnowledgeNode>.toOverflowDetails(): OverflowDetails {
        val emphasis = mutableListOf<String>()
        val examples = mutableListOf<String>()
        val misunderstandings = mutableListOf<String>()
        fun collect(nodes: List<ClassroomKnowledgeNode>) {
            nodes.forEach { node ->
                val summary = listOf(node.title, node.oneLineTakeaway)
                    .filter { it.isNotBlank() }
                    .joinToString("：")
                if (summary.isNotBlank()) emphasis += "下级知识点摘要：$summary"
                examples += node.examples
                misunderstandings += node.misunderstandings
                collect(node.children)
            }
        }
        collect(this)
        return OverflowDetails(
            teacherEmphasis = emphasis,
            examples = examples,
            misunderstandings = misunderstandings
        )
    }

    private fun JSONObject.optParentId(): String? {
        if (!has("parentId") || isNull("parentId")) return null
        return optString("parentId")
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return runCatching { optLong(key) }.getOrNull()?.takeIf { it >= 0L }
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    private fun buildGeneratedId(parentId: String?, depth: Int, index: Int, title: String): String {
        val seed = title.filter { it.isLetterOrDigit() }.take(12).ifBlank { "node" }
        return listOfNotNull(parentId, "d$depth", "${index + 1}", seed).joinToString("_")
    }

    private fun ClassroomKnowledgeTree.normalizeSingleActiveNode(): ClassroomKnowledgeTree {
        val activeIds = nodes.flatMap(::collectActiveIds)
        if (activeIds.size <= 1) return this
        val keptActiveId = activeIds.last()
        return copy(nodes = nodes.map { it.normalizeActiveNode(keptActiveId) })
    }

    private fun List<ClassroomKnowledgeNode>.nestFlatChildren(
        parentId: String?,
        depth: Int
    ): List<ClassroomKnowledgeNode> {
        if (isEmpty()) {
            return map { it.copy(parentId = it.parentId ?: parentId) }
        }
        val idsAtThisLevel = map { it.id }.toSet()
        val groupedChildren = filter { it.parentId?.let(idsAtThisLevel::contains) == true }
            .groupBy { it.parentId }
        fun attach(node: ClassroomKnowledgeNode, currentDepth: Int): ClassroomKnowledgeNode {
            if (currentDepth >= MAX_DEPTH) {
                val flatChildren = groupedChildren[node.id].orEmpty()
                    .map { child -> child.copy(parentId = node.id) }
                val overflowDetails = flatChildren.toOverflowDetails()
                return node.copy(
                    teacherEmphasis = (node.teacherEmphasis + overflowDetails.teacherEmphasis).take(4),
                    examples = (node.examples + overflowDetails.examples).take(4),
                    misunderstandings = (node.misunderstandings + overflowDetails.misunderstandings).take(3),
                    children = emptyList()
                )
            }
            val flatChildren = groupedChildren[node.id].orEmpty()
                .map { child -> child.copy(parentId = node.id) }
            return node.copy(
                children = (node.children + flatChildren)
                    .map { child -> attach(child, currentDepth + 1) }
            )
        }
        return filterNot { it.parentId?.let(idsAtThisLevel::contains) == true }
            .map { node ->
                attach(node.copy(parentId = node.parentId ?: parentId), depth)
            }
    }

    private fun collectActiveIds(node: ClassroomKnowledgeNode): List<String> {
        val self = if (node.status == ClassroomKnowledgeNodeStatus.Active) listOf(node.id) else emptyList()
        return self + node.children.flatMap(::collectActiveIds)
    }

    private fun findActivePath(
        node: ClassroomKnowledgeNode,
        ancestors: List<ClassroomKnowledgeNode>
    ): List<ClassroomKnowledgeNode>? {
        val path = ancestors + node
        val childPath = node.children.firstNotNullOfOrNull { findActivePath(it, path) }
        return childPath ?: if (node.status == ClassroomKnowledgeNodeStatus.Active) path else null
    }

    private fun ClassroomKnowledgeNode.normalizeActiveNode(keptActiveId: String): ClassroomKnowledgeNode {
        val normalizedStatus = if (status == ClassroomKnowledgeNodeStatus.Active && id != keptActiveId) {
            ClassroomKnowledgeNodeStatus.Completed
        } else {
            status
        }
        return copy(
            status = normalizedStatus,
            children = children.map { it.normalizeActiveNode(keptActiveId) }
        )
    }

    private fun StringBuilder.appendNode(node: ClassroomKnowledgeNode, depth: Int) {
        val indent = "  ".repeat((depth - 1).coerceAtLeast(0))
        appendLine(
            "$indent- [${node.status.value}] ${node.title} id=${node.id} time=${node.startMs ?: "-"}-${node.endMs ?: "-"}"
        )
        if (node.oneLineTakeaway.isNotBlank()) {
            appendLine("$indent  takeaway=${node.oneLineTakeaway}")
        }
        appendLine(
            "$indent  details emphasis=${node.teacherEmphasis.size} examples=${node.examples.size} misunderstandings=${node.misunderstandings.size}"
        )
        node.teacherEmphasis.take(2).forEach { appendLine("$indent  emphasis: $it") }
        node.examples.take(1).forEach { appendLine("$indent  example: $it") }
        node.misunderstandings.take(1).forEach { appendLine("$indent  misunderstanding: $it") }
        node.children.forEach { appendNode(it, depth + 1) }
    }

    private fun countNodes(node: ClassroomKnowledgeNode): Int {
        return 1 + node.children.sumOf(::countNodes)
    }

    private fun countActiveNodes(node: ClassroomKnowledgeNode): Int {
        val self = if (node.status == ClassroomKnowledgeNodeStatus.Active) 1 else 0
        return self + node.children.sumOf(::countActiveNodes)
    }

    private fun maxDepth(node: ClassroomKnowledgeNode, depth: Int): Int {
        return maxOf(depth, node.children.maxOfOrNull { maxDepth(it, depth + 1) } ?: depth)
    }

    private fun maxDetailItems(node: ClassroomKnowledgeNode): Int {
        val self = node.teacherEmphasis.size + node.examples.size + node.misunderstandings.size
        return maxOf(self, node.children.maxOfOrNull(::maxDetailItems) ?: 0)
    }

    private fun countEmptyDetailNodes(node: ClassroomKnowledgeNode): Int {
        val emptySelf = node.oneLineTakeaway.isBlank() &&
            node.teacherEmphasis.isEmpty() &&
            node.examples.isEmpty() &&
            node.misunderstandings.isEmpty()
        return (if (emptySelf) 1 else 0) + node.children.sumOf(::countEmptyDetailNodes)
    }

    private fun countValidTimeNodes(node: ClassroomKnowledgeNode): Int {
        val self = if (
            node.startMs != null &&
            node.endMs != null &&
            node.endMs >= node.startMs &&
            !(node.startMs == 0L && node.endMs == 0L)
        ) {
            1
        } else {
            0
        }
        return self + node.children.sumOf(::countValidTimeNodes)
    }

    private fun countZeroTimeNodes(node: ClassroomKnowledgeNode): Int {
        val self = if (node.startMs == 0L && node.endMs == 0L) 1 else 0
        return self + node.children.sumOf(::countZeroTimeNodes)
    }

    private fun countNodesAtDepth(node: ClassroomKnowledgeNode, targetDepth: Int, depth: Int): Int {
        val self = if (depth == targetDepth) 1 else 0
        return self + node.children.sumOf { countNodesAtDepth(it, targetDepth, depth + 1) }
    }

    private fun countRepresentativeTimeNodesAtDepth(
        node: ClassroomKnowledgeNode,
        targetDepth: Int,
        depth: Int
    ): Int {
        val hasRepresentativeTime = node.startMs != null &&
            node.endMs != null &&
            node.endMs >= node.startMs &&
            !(node.startMs == 0L && node.endMs == 0L)
        val self = if (depth == targetDepth && hasRepresentativeTime) 1 else 0
        return self + node.children.sumOf { countRepresentativeTimeNodesAtDepth(it, targetDepth, depth + 1) }
    }
}

package com.example.watcher.data.model

enum class ClassroomKnowledgeNodeStatus(val value: String) {
    Draft("draft"),
    Active("active"),
    Completed("completed");

    companion object {
        fun fromValue(value: String?): ClassroomKnowledgeNodeStatus {
            return entries.firstOrNull { it.value == value } ?: Draft
        }
    }
}

enum class ClassroomKnowledgeTreeProcessingStatus(val value: String) {
    Waiting("waiting"),
    Updating("updating"),
    Completed("completed"),
    Failed("failed");

    companion object {
        fun fromValue(value: String?): ClassroomKnowledgeTreeProcessingStatus {
            return entries.firstOrNull { it.value == value } ?: Waiting
        }
    }
}

data class ClassroomKnowledgeNode(
    val id: String,
    val parentId: String? = null,
    val title: String,
    val oneLineTakeaway: String = "",
    val teacherEmphasis: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val misunderstandings: List<String> = emptyList(),
    val startMs: Long? = null,
    val endMs: Long? = null,
    val status: ClassroomKnowledgeNodeStatus = ClassroomKnowledgeNodeStatus.Draft,
    val children: List<ClassroomKnowledgeNode> = emptyList(),
    val updatedAtMs: Long = 0L
)

data class ClassroomKnowledgeTree(
    val rootTitle: String = "课堂知识树",
    val nodes: List<ClassroomKnowledgeNode> = emptyList(),
    val updatedAtMs: Long = 0L
)

data class ClassroomKnowledgeTreeUpdate(
    val tree: ClassroomKnowledgeTree,
    val changedNodeIds: List<String> = emptyList()
)

data class ClassroomKnowledgeTreeProgress(
    val addedChars: Int = 0,
    val requiredChars: Int = 0,
    val elapsedMs: Long = 0L,
    val requiredIntervalMs: Long = 0L,
    val jobActive: Boolean = false
) {
    val remainingChars: Int
        get() = (requiredChars - addedChars).coerceAtLeast(0)

    val remainingMs: Long
        get() = (requiredIntervalMs - elapsedMs).coerceAtLeast(0L)

    val ready: Boolean
        get() = addedChars >= requiredChars && elapsedMs >= requiredIntervalMs && !jobActive
}

data class ClassroomKnowledgeFrameRef(
    val nodeId: String,
    val frameTimestampMs: Long,
    val framePath: String,
    val width: Int = 0,
    val height: Int = 0,
    val byteLength: Long = 0L,
    val sha256: String = "",
    val source: String = "",
    val status: String = ""
)

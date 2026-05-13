package com.example.watcher.agentframework.core

enum class AgentMessageRole {
    User,
    Assistant,
    System,
    Tool,
    Observation
}

data class AgentConversationItem(
    val role: AgentMessageRole,
    val content: String,
    val name: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class AgentMemoryScope {
    Working,
    Episodic
}

data class AgentMemoryWrite(
    val scope: AgentMemoryScope,
    val content: String,
    val tags: Set<String> = emptySet()
)

sealed interface AgentAction {
    data object Continue : AgentAction

    data class UseTools(
        val calls: List<AgentToolCall>
    ) : AgentAction

    data class Wait(
        val reason: String,
        val resumeAfterMillis: Long = 0L
    ) : AgentAction

    data class Finish(
        val reason: String,
        val success: Boolean = true
    ) : AgentAction
}

data class AgentDecision(
    val thinking: String = "",
    val reply: String? = null,
    val memoryWrites: List<AgentMemoryWrite> = emptyList(),
    val action: AgentAction = AgentAction.Continue,
    val metadata: Map<String, String> = emptyMap()
)

data class AgentToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<AgentToolParameter> = emptyList()
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?> = emptyMap()
)

data class AgentToolResult(
    val callId: String,
    val toolName: String,
    val success: Boolean,
    val output: Map<String, Any?> = emptyMap(),
    val error: String? = null
)

data class AgentTurnRecord(
    val step: Int,
    val decision: AgentDecision,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val toolResults: List<AgentToolResult> = emptyList(),
    val startedAt: Long,
    val completedAt: Long = System.currentTimeMillis()
)

enum class AgentSessionStatus {
    Created,
    Running,
    Waiting,
    Completed,
    Stopped,
    Failed,
    Interrupted,
    Cancelled
}

enum class AgentStopReason {
    GoalAchieved,
    StepLimitReached,
    RuntimeLimitReached,
    IdleLimitReached,
    ConsecutiveFailures,
    StoppedByAgent,
    InterruptedByRestart,
    Cancelled,
    Error
}

data class AgentSessionSnapshot(
    val sessionId: String,
    val agentId: String,
    val agentName: String,
    val goal: String,
    val status: AgentSessionStatus = AgentSessionStatus.Created,
    val stopReason: AgentStopReason? = null,
    val failureMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val stepCount: Int = 0,
    val idleTurns: Int = 0,
    val consecutiveFailures: Int = 0,
    val history: List<AgentConversationItem> = emptyList(),
    val turns: List<AgentTurnRecord> = emptyList(),
    val lastReply: String? = null
)

val AgentSessionSnapshot.isTerminal: Boolean
    get() = status == AgentSessionStatus.Completed ||
        status == AgentSessionStatus.Stopped ||
        status == AgentSessionStatus.Interrupted ||
        status == AgentSessionStatus.Failed ||
        status == AgentSessionStatus.Cancelled

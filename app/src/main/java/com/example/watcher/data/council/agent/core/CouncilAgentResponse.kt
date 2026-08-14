package com.example.watcher.data.council.agent.core

/** Response from an agent backend — either tool calls or final answer. */
sealed interface CouncilAgentResponse {

    /** Agent wants to call tools before giving final answer. */
    data class ToolCalls(val calls: List<CouncilAgentToolCall>) : CouncilAgentResponse

    /** Agent's final analysis opinion. */
    data class FinalAnswer(val opinion: CouncilAgentOpinion) : CouncilAgentResponse
}

/** A single tool invocation requested by the agent. */
data class CouncilAgentToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

/** Result of executing a tool call, fed back to the agent. */
data class CouncilAgentToolResult(
    val callId: String,
    val toolName: String,
    val result: Map<String, Any?>,
    val success: Boolean = true,
    val error: String? = null
)

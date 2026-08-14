package com.example.watcher.data.council.agent.core

/** A single message in the agent conversation (system/user/assistant/tool_result). */
data class CouncilAgentMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL_RESULT = "tool_result"

        fun system(content: String) = CouncilAgentMessage(ROLE_SYSTEM, content)
        fun user(content: String) = CouncilAgentMessage(ROLE_USER, content)
        fun assistant(content: String) = CouncilAgentMessage(ROLE_ASSISTANT, content)
        fun toolResult(callId: String, content: String) = CouncilAgentMessage(ROLE_TOOL_RESULT, content, callId)
    }
}

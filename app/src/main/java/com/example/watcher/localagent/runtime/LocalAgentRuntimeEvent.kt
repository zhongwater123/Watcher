package com.example.watcher.localagent.runtime

sealed interface LocalAgentRuntimeEvent {
    data class Text(
        val author: String,
        val content: String,
        val isFinal: Boolean
    ) : LocalAgentRuntimeEvent

    data class ToolCall(
        val name: String,
        val args: Map<String, Any?>
    ) : LocalAgentRuntimeEvent

    data class ToolResult(
        val name: String,
        val response: Map<String, Any?>
    ) : LocalAgentRuntimeEvent

    data class Error(
        val code: String?,
        val message: String
    ) : LocalAgentRuntimeEvent
}

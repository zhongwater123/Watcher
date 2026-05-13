package com.example.watcher.agentframework.tools

import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentSessionSnapshot
import com.example.watcher.agentframework.core.AgentToolCall
import com.example.watcher.agentframework.core.AgentToolDefinition
import com.example.watcher.agentframework.core.AgentToolResult
import com.example.watcher.agentframework.knowledge.AgentKnowledgeStore
import com.example.watcher.agentframework.memory.AgentMemoryStore
import kotlinx.coroutines.CancellationException

data class AgentToolContext(
    val definition: AgentDefinition,
    val session: AgentSessionSnapshot,
    val memoryStore: AgentMemoryStore,
    val knowledgeStore: AgentKnowledgeStore
)

interface AgentTool {
    val definition: AgentToolDefinition
    suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult
}

interface AgentToolExecutor {
    fun definitions(): List<AgentToolDefinition>
    suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult
}

class AgentToolRegistry : AgentToolExecutor {
    private val lock = Any()
    private val tools = linkedMapOf<String, AgentTool>()

    fun register(tool: AgentTool): AgentToolRegistry {
        synchronized(lock) {
            tools[tool.definition.name] = tool
        }
        return this
    }

    fun filtered(allowedToolNames: Set<String>?): AgentToolExecutor {
        val normalized = allowedToolNames
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: return this
        return FilteredAgentToolExecutor(this, normalized)
    }

    override fun definitions(): List<AgentToolDefinition> = synchronized(lock) {
        tools.values.map { it.definition }
    }

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val tool = synchronized(lock) {
            tools[call.name]
        }
            ?: return AgentToolResult(
                callId = call.id,
                toolName = call.name,
                success = false,
                error = "Unknown tool: ${call.name}"
            )
        return try {
            tool.execute(call, context)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            AgentToolResult(
                callId = call.id,
                toolName = call.name,
                success = false,
                error = error.message ?: "Tool execution failed"
            )
        }
    }
}

private class FilteredAgentToolExecutor(
    private val delegate: AgentToolExecutor,
    private val allowedToolNames: Set<String>
) : AgentToolExecutor {
    override fun definitions(): List<AgentToolDefinition> {
        return delegate.definitions().filter { it.name in allowedToolNames }
    }

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        if (call.name !in allowedToolNames) {
            return AgentToolResult(
                callId = call.id,
                toolName = call.name,
                success = false,
                error = "Tool not allowed for this runtime: ${call.name}"
            )
        }
        return delegate.execute(call, context)
    }
}

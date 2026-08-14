package com.example.watcher.data.council.agent.tools

import com.example.watcher.data.council.agent.core.CouncilAgentToolSchema
import com.example.watcher.data.council.agent.core.CouncilAgentToolCall
import com.example.watcher.data.council.agent.core.CouncilAgentToolResult

/** Registers and executes agent tools. */
class CouncilAgentToolExecutor {

    private val tools = mutableMapOf<String, CouncilAgentTool>()

    fun register(tool: CouncilAgentTool) {
        tools[tool.schema.name] = tool
    }

    fun availableSchemas(): List<CouncilAgentToolSchema> = tools.values.map { it.schema }

    suspend fun execute(agentId: String, call: CouncilAgentToolCall): CouncilAgentToolResult {
        val tool = tools[call.name]
            ?: return CouncilAgentToolResult(call.id, call.name, emptyMap(), success = false, error = "Unknown tool: ${call.name}")
        return try {
            val result = tool.execute(agentId, call.arguments)
            CouncilAgentToolResult(call.id, call.name, result)
        } catch (e: Exception) {
            CouncilAgentToolResult(call.id, call.name, emptyMap(), success = false, error = e.message ?: "Tool execution failed")
        }
    }
}

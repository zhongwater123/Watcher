package com.example.watcher.data.council.agent.tools

import com.example.watcher.data.council.agent.core.CouncilAgentToolSchema
import com.example.watcher.data.council.agent.core.CouncilAgentToolParameterSchema

/** Interface for all agent tools. Each tool defines its schema and execution logic. */
interface CouncilAgentTool {
    val schema: CouncilAgentToolSchema
    suspend fun execute(agentId: String, arguments: Map<String, Any?>): Map<String, Any?>
}

/** Convenience builder for tool parameters. */
fun param(type: String, description: String, required: Boolean = false, default: Any? = null) =
    CouncilAgentToolParameterSchema(type, description, required, default)

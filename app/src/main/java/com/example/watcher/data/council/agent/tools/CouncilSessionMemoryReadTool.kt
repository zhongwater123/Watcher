package com.example.watcher.data.council.agent.tools

import com.example.watcher.data.council.agent.core.CouncilAgentToolSchema
import com.example.watcher.data.council.agent.memory.CouncilAgentSessionMemory

/** Read this agent's session memory (analysis trajectory so far). */
class CouncilSessionMemoryReadTool(
    private val sessionMemory: CouncilAgentSessionMemory
) : CouncilAgentTool {

    override val schema = CouncilAgentToolSchema(
        name = "read_memory",
        description = "读取你在本次直播会话中的分析轨迹记忆。",
        parameters = mapOf(
            "limit" to param("integer", "最大返回条数", default = 10)
        )
    )

    override suspend fun execute(agentId: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 10
        return mapOf("entries" to sessionMemory.read(agentId, limit))
    }
}

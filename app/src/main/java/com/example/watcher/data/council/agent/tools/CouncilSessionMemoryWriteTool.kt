package com.example.watcher.data.council.agent.tools

import com.example.watcher.data.council.agent.core.CouncilAgentToolSchema
import com.example.watcher.data.council.agent.memory.CouncilAgentSessionMemory

/** Write an entry to this agent's session memory. */
class CouncilSessionMemoryWriteTool(
    private val sessionMemory: CouncilAgentSessionMemory
) : CouncilAgentTool {

    override val schema = CouncilAgentToolSchema(
        name = "write_memory",
        description = "将一条观察或分析结论写入你的会话记忆，供后续轮次参考。",
        parameters = mapOf(
            "content" to param("string", "要记住的内容", required = true)
        )
    )

    override suspend fun execute(agentId: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val content = arguments["content"] as? String ?: return mapOf("error" to "missing content")
        sessionMemory.write(agentId, content)
        return mapOf("status" to "saved")
    }
}

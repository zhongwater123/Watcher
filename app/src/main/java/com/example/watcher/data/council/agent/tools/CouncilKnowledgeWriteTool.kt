package com.example.watcher.data.council.agent.tools

import com.example.watcher.data.council.agent.core.CouncilAgentToolSchema
import com.example.watcher.data.council.agent.memory.CouncilAgentKnowledgeStore

/** Write a new entry to this agent's persistent knowledge base. */
class CouncilKnowledgeWriteTool(
    private val knowledgeStore: CouncilAgentKnowledgeStore
) : CouncilAgentTool {

    override val schema = CouncilAgentToolSchema(
        name = "write_knowledge",
        description = "将一条值得跨会话记住的知识写入你的持久知识库。只写入有价值的经验、模式和事实，不要写入临时信息。",
        parameters = mapOf(
            "category" to param("string", "知识类别: expert_calibration(分析经验) 或 user_profile(用户画像)", required = true),
            "content" to param("string", "知识内容", required = true)
        )
    )

    override suspend fun execute(agentId: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val category = arguments["category"] as? String ?: "expert_calibration"
        val content = arguments["content"] as? String ?: return mapOf("error" to "missing content")
        knowledgeStore.write(agentId, category, content)
        return mapOf("status" to "saved")
    }
}

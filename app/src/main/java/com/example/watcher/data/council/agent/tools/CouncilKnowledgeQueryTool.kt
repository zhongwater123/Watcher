package com.example.watcher.data.council.agent.tools

import com.example.watcher.data.council.agent.core.CouncilAgentToolSchema
import com.example.watcher.data.council.agent.memory.CouncilAgentKnowledgeStore

/** Query this agent's persistent knowledge base. */
class CouncilKnowledgeQueryTool(
    private val knowledgeStore: CouncilAgentKnowledgeStore
) : CouncilAgentTool {

    override val schema = CouncilAgentToolSchema(
        name = "query_knowledge",
        description = "从你的持久知识库中检索与当前场景相关的经验和知识。",
        parameters = mapOf(
            "query" to param("string", "检索关键词或描述", required = true),
            "limit" to param("integer", "最大返回条数", default = 5)
        )
    )

    override suspend fun execute(agentId: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val query = arguments["query"] as? String ?: ""
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 5
        val entries = knowledgeStore.query(agentId, query, limit)
        return mapOf("entries" to entries.map { mapOf("content" to it.content, "category" to it.category, "relevance" to it.relevance) })
    }
}

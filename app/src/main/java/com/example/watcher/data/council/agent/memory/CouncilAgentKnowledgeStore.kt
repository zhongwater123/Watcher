package com.example.watcher.data.council.agent.memory

import com.example.watcher.data.local.CouncilKnowledgeDao
import com.example.watcher.data.model.CouncilKnowledgeEntity

/**
 * Per-agent persistent knowledge store. Backed by Room DB with expertId partitioning.
 * Future: migrate to per-agent file storage for export/import.
 */
class CouncilAgentKnowledgeStore(private val dao: CouncilKnowledgeDao) {

    suspend fun query(
        agentId: String,
        query: String,
        limit: Int = 10
    ): List<CouncilAgentKnowledgeEntry> {
        val calibration = dao.getExpertCalibration(agentId, limit)
        val userProfile = dao.getUserProfile(limit = 5)
        val normalizedQuery = query.trim()
        return (calibration + userProfile)
            .distinctBy { it.id }
            .filter {
                normalizedQuery.isBlank() ||
                    it.content.contains(normalizedQuery, ignoreCase = true) ||
                    it.category.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedByDescending { it.relevance }
            .take(limit)
            .map { it.toEntry() }
    }

    suspend fun write(agentId: String, category: String, content: String) {
        val now = System.currentTimeMillis()
        dao.insert(
            CouncilKnowledgeEntity(
                category = category,
                expertId = agentId,
                sceneType = "all",
                content = content.take(300),
                source = agentId,
                relevance = 0.8f,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun CouncilKnowledgeEntity.toEntry() = CouncilAgentKnowledgeEntry(
        id = id,
        category = category,
        content = content,
        relevance = relevance
    )
}

data class CouncilAgentKnowledgeEntry(
    val id: Long,
    val category: String,
    val content: String,
    val relevance: Float
)

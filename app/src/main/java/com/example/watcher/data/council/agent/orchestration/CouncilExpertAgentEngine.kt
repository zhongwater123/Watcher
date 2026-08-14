package com.example.watcher.data.council.agent.orchestration

import com.example.watcher.data.model.CouncilConfig
import com.example.watcher.data.model.CouncilDiscussionTurn
import com.example.watcher.data.model.CouncilExpertOpinion
import com.example.watcher.data.remote.OpenAiCompatibleProvider
import com.example.watcher.data.repository.context.LiveSharedContextSnapshot
import com.example.watcher.data.repository.council.CouncilExpertSpec

data class CouncilExpertAgentBinding(
    val spec: CouncilExpertSpec,
    val provider: OpenAiCompatibleProvider
)

data class CouncilGatherRequest(
    val experts: List<CouncilExpertAgentBinding>,
    val context: LiveSharedContextSnapshot,
    val config: CouncilConfig,
    val roundNumber: Int,
    val sessionId: String
)

data class CouncilDiscussionRequest(
    val sessionId: String,
    val round: Int,
    val previousTurns: List<CouncilDiscussionTurn>
)

interface CouncilExpertAgentEngine {
    suspend fun gather(request: CouncilGatherRequest): List<CouncilExpertOpinion>

    suspend fun discuss(request: CouncilDiscussionRequest): List<CouncilDiscussionTurn>

    fun recordSessionSummary(expertId: String, summary: String)

    fun readSessionMemory(expertId: String): List<String>

    fun allSessionMemory(): Map<String, List<String>>

    fun clearSession()
}

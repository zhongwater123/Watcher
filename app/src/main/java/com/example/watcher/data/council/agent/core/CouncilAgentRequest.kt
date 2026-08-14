package com.example.watcher.data.council.agent.core

/** Request types sent to an agent. */
enum class CouncilAgentRequestType {
    OBSERVE,
    DISCUSS_ASK,
    DISCUSS_REPLY
}

/** Full request payload from orchestrator to agent runtime. */
data class CouncilAgentRequest(
    val type: CouncilAgentRequestType,
    val sessionId: String,
    val roundNumber: Int,
    val context: CouncilAgentContext,
    val profile: CouncilAgentProfile,
    val availableTools: List<CouncilAgentToolSchema>,
    val discussionContext: CouncilAgentDiscussionContext? = null
)

/** Extra context for discussion phases. */
data class CouncilAgentDiscussionContext(
    val allOpinions: List<CouncilAgentOpinion>,
    val previousTurns: List<CouncilAgentDiscussionTurn>,
    val targetAgents: List<CouncilAgentProfile>,
    // For DISCUSS_REPLY
    val questionFrom: String? = null,
    val question: String? = null,
    val questionReason: String? = null
)

data class CouncilAgentDiscussionTurn(
    val fromAgent: String,
    val toAgent: String,
    val kind: String,
    val message: String,
    val detail: String = ""
)

/** Tool schema exposed to the agent so it knows what tools are available. */
data class CouncilAgentToolSchema(
    val name: String,
    val description: String,
    val parameters: Map<String, CouncilAgentToolParameterSchema>
)

data class CouncilAgentToolParameterSchema(
    val type: String,
    val description: String,
    val required: Boolean = false,
    val default: Any? = null
)

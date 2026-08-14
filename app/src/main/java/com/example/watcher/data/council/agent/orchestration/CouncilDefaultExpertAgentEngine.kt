package com.example.watcher.data.council.agent.orchestration

import android.util.Log
import com.example.watcher.data.council.agent.core.CouncilAgentContext
import com.example.watcher.data.council.agent.core.CouncilAgentDiscussionContext
import com.example.watcher.data.council.agent.core.CouncilAgentDiscussionTurn
import com.example.watcher.data.council.agent.core.CouncilAgentOpinion
import com.example.watcher.data.council.agent.core.CouncilAgentProfile
import com.example.watcher.data.council.agent.core.CouncilAgentRequest
import com.example.watcher.data.council.agent.core.CouncilAgentRequestType
import com.example.watcher.data.council.agent.memory.CouncilAgentSessionMemory
import com.example.watcher.data.council.agent.runtime.CouncilAgentRuntime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class CouncilRegisteredAgent(
    val id: String,
    val profile: CouncilAgentProfile,
    val runtime: CouncilAgentRuntime
)

class CouncilDefaultExpertAgentEngine(
    private val runtimeFactory: CouncilAgentRuntimeFactory,
    private val sessionMemory: CouncilAgentSessionMemory
) : CouncilExpertAgentEngine {
    private data class CouncilAgentSession(
        val sessionId: String,
        val experts: List<CouncilExpertAgentBinding>,
        val agents: List<CouncilRegisteredAgent>,
        val context: CouncilAgentContext,
        val opinions: Map<String, CouncilAgentOpinion>
    )

    private var activeSession: CouncilAgentSession? = null

    override suspend fun gather(request: CouncilGatherRequest) =
        request.experts
            .map { binding -> binding to runtimeFactory.create(binding) }
            .let { registrations ->
                val context = CouncilAgentModelMapper.toAgentContext(
                    snapshot = request.context,
                    config = request.config,
                    roundNumber = request.roundNumber
                )
                val agents = registrations.map { it.second }
                val opinions = runGathering(
                    agents = agents,
                    context = context,
                    sessionId = request.sessionId
                ).toMap()
                activeSession = CouncilAgentSession(
                    sessionId = request.sessionId,
                    experts = request.experts,
                    agents = agents,
                    context = context,
                    opinions = opinions
                )
                request.experts.mapNotNull { binding ->
                    opinions[binding.spec.expertId]?.let { opinion ->
                        CouncilAgentModelMapper.toCouncilOpinion(binding.spec, opinion)
                    }
                }
            }

    override suspend fun discuss(request: CouncilDiscussionRequest) =
        activeSession
            ?.takeIf { it.sessionId == request.sessionId }
            ?.let { session ->
                runDiscussionRound(
                    agents = session.agents,
                    opinions = session.opinions,
                    previousTurns = request.previousTurns.map(CouncilAgentModelMapper::toAgentTurn),
                    context = session.context,
                    sessionId = session.sessionId
                ).map { turn ->
                    CouncilAgentModelMapper.toCouncilTurn(turn, request.round, session.experts)
                }
            }
            .orEmpty()

    override fun recordSessionSummary(expertId: String, summary: String) {
        sessionMemory.write(expertId, summary)
    }

    override fun readSessionMemory(expertId: String): List<String> = sessionMemory.read(expertId)

    override fun allSessionMemory(): Map<String, List<String>> = sessionMemory.allEntries()

    override fun clearSession() {
        activeSession = null
        sessionMemory.clear()
    }

    private suspend fun runGathering(
        agents: List<CouncilRegisteredAgent>,
        context: CouncilAgentContext,
        sessionId: String
    ): List<Pair<String, CouncilAgentOpinion>> = coroutineScope {
        val semaphore = Semaphore(PARALLELISM)
        agents.map { agent ->
            async {
                semaphore.withPermit {
                    Log.d(TAG, "Gathering: council agent ${agent.id} starting")
                    val opinion = agent.runtime.execute(
                        agentId = agent.id,
                        request = CouncilAgentRequest(
                            type = CouncilAgentRequestType.OBSERVE,
                            sessionId = sessionId,
                            roundNumber = context.roundNumber,
                            context = context,
                            profile = agent.profile,
                            availableTools = agent.runtime.availableTools()
                        )
                    )
                    Log.d(TAG, "Gathering: council agent ${agent.id} completed")
                    agent.id to opinion
                }
            }
        }.awaitAll()
    }

    private suspend fun runDiscussionRound(
        agents: List<CouncilRegisteredAgent>,
        opinions: Map<String, CouncilAgentOpinion>,
        previousTurns: List<CouncilAgentDiscussionTurn>,
        context: CouncilAgentContext,
        sessionId: String
    ): List<CouncilAgentDiscussionTurn> {
        val sorted = agents.sortedByDescending { agent ->
            val opinion = opinions[agent.id] ?: return@sortedByDescending 0
            voteSeverity(opinion.voteLevel) * 100 + opinion.confidence
        }
        for (asker in sorted) {
            val targets = agents.filter { it.id != asker.id }
            if (targets.isEmpty()) continue
            val askOpinion = asker.runtime.execute(
                agentId = asker.id,
                request = CouncilAgentRequest(
                    type = CouncilAgentRequestType.DISCUSS_ASK,
                    sessionId = sessionId,
                    roundNumber = context.roundNumber,
                    context = context,
                    profile = asker.profile,
                    availableTools = asker.runtime.availableTools(),
                    discussionContext = CouncilAgentDiscussionContext(
                        allOpinions = opinions.values.toList(),
                        previousTurns = previousTurns,
                        targetAgents = targets.map { it.profile }
                    )
                )
            )
            val question = askOpinion.summary
            if (question.isBlank()) continue
            val target = targets.first()
            val replyOpinion = target.runtime.execute(
                agentId = target.id,
                request = CouncilAgentRequest(
                    type = CouncilAgentRequestType.DISCUSS_REPLY,
                    sessionId = sessionId,
                    roundNumber = context.roundNumber,
                    context = context,
                    profile = target.profile,
                    availableTools = target.runtime.availableTools(),
                    discussionContext = CouncilAgentDiscussionContext(
                        allOpinions = opinions.values.toList(),
                        previousTurns = previousTurns,
                        targetAgents = emptyList(),
                        questionFrom = asker.profile.name,
                        question = question,
                        questionReason = askOpinion.voteReason
                    )
                )
            )
            return listOf(
                CouncilAgentDiscussionTurn(
                    fromAgent = asker.profile.name,
                    toAgent = target.profile.name,
                    kind = "ask",
                    message = question,
                    detail = askOpinion.voteReason
                ),
                CouncilAgentDiscussionTurn(
                    fromAgent = target.profile.name,
                    toAgent = asker.profile.name,
                    kind = "reply",
                    message = replyOpinion.summary,
                    detail = replyOpinion.findings.firstOrNull().orEmpty()
                )
            )
        }
        return emptyList()
    }

    private fun voteSeverity(level: String): Int = when (level.lowercase()) {
        "alert" -> 3
        "warn" -> 2
        "watch" -> 1
        else -> 0
    }

    private companion object {
        const val TAG = "CouncilExpertAgent"
        const val PARALLELISM = 2
    }
}

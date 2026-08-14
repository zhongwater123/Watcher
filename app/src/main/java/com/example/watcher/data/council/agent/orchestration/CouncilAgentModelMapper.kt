package com.example.watcher.data.council.agent.orchestration

import com.example.watcher.data.council.agent.core.CouncilAgentContext
import com.example.watcher.data.council.agent.core.CouncilAgentDiscussionTurn
import com.example.watcher.data.council.agent.core.CouncilAgentOpinion
import com.example.watcher.data.council.agent.core.CouncilAgentProfile
import com.example.watcher.data.model.CouncilConfig
import com.example.watcher.data.model.CouncilDiscussionKind
import com.example.watcher.data.model.CouncilDiscussionTurn
import com.example.watcher.data.model.CouncilExpertOpinion
import com.example.watcher.data.model.CouncilVoteLevel
import com.example.watcher.data.repository.context.LiveSharedContextSnapshot
import com.example.watcher.data.repository.council.CouncilExpertSpec
import java.util.UUID

internal object CouncilAgentModelMapper {
    fun toAgentProfile(spec: CouncilExpertSpec) = CouncilAgentProfile(
        name = spec.name,
        description = spec.description,
        persona = spec.persona,
        perspective = spec.perspective,
        expertKind = spec.expertKind.name.lowercase()
    )

    fun toAgentContext(
        snapshot: LiveSharedContextSnapshot,
        config: CouncilConfig,
        roundNumber: Int
    ) = CouncilAgentContext(
        sceneType = config.sceneType.name,
        objective = config.objective,
        focus = config.focus,
        speakerRole = config.speakerRole,
        targetRole = config.targetRole,
        background = config.background,
        recentVisual = snapshot.visual.recentVisual,
        recentSpeech = snapshot.speech.recentSpeech,
        memoryA = snapshot.memory.memoryA,
        memoryB = snapshot.memory.memoryB,
        roundNumber = roundNumber
    )

    fun toCouncilOpinion(
        spec: CouncilExpertSpec,
        opinion: CouncilAgentOpinion
    ) = CouncilExpertOpinion(
        expertId = spec.expertId,
        name = spec.name,
        expertKind = spec.expertKind,
        legacyRole = spec.legacyRole,
        summary = opinion.summary,
        findings = opinion.findings,
        risks = opinion.risks,
        nextActions = opinion.nextActions,
        observationRequests = emptyList(),
        voteLevel = CouncilVoteLevel.fromRaw(opinion.voteLevel),
        voteReason = opinion.voteReason,
        confidence = opinion.confidence
    )

    fun toAgentTurn(turn: CouncilDiscussionTurn) = CouncilAgentDiscussionTurn(
        fromAgent = turn.fromExpertName,
        toAgent = turn.toExpertName,
        kind = turn.kind.name.lowercase(),
        message = turn.message,
        detail = turn.detail
    )

    fun toCouncilTurn(
        turn: CouncilAgentDiscussionTurn,
        round: Int,
        experts: List<CouncilExpertAgentBinding>
    ): CouncilDiscussionTurn {
        return CouncilDiscussionTurn(
            id = "discussion_${turn.kind}_${UUID.randomUUID().toString().take(8)}",
            round = round,
            fromExpertId = experts.firstOrNull { it.spec.name == turn.fromAgent }?.spec?.expertId.orEmpty(),
            fromExpertName = turn.fromAgent,
            toExpertId = experts.firstOrNull { it.spec.name == turn.toAgent }?.spec?.expertId.orEmpty(),
            toExpertName = turn.toAgent,
            kind = if (turn.kind == "ask") CouncilDiscussionKind.Ask else CouncilDiscussionKind.Reply,
            message = turn.message,
            detail = turn.detail
        )
    }
}

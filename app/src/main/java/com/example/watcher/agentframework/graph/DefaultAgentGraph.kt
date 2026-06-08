package com.example.watcher.agentframework.graph

import com.example.watcher.agentframework.autonomy.AgentSignal
import com.example.watcher.agentframework.autonomy.CorrectionAction
import com.example.watcher.agentframework.autonomy.CorrectionRecord
import com.example.watcher.agentframework.autonomy.CorrectionTrigger
import com.example.watcher.agentframework.autonomy.ExecutionOutcome
import com.example.watcher.agentframework.autonomy.SignalChannel
import com.example.watcher.agentframework.autonomy.ValidationStatus
import com.example.watcher.agentframework.core.AgentAction
import com.example.watcher.agentframework.core.AgentConversationItem
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentSessionSnapshot
import com.example.watcher.agentframework.gate.ApprovalContext
import com.example.watcher.agentframework.gate.ApprovalRequest
import com.example.watcher.agentframework.gate.ApprovalStatus
import com.example.watcher.agentframework.gate.RiskLevel
import com.example.watcher.agentframework.knowledge.AgentKnowledgeSnapshot
import com.example.watcher.agentframework.memory.AgentMemoryEntry
import com.example.watcher.agentframework.memory.AgentMemorySnapshot
import com.example.watcher.agentframework.runtime.AgentBrainRequest

/**
 * Builds the default agent execution graph that mirrors the existing
 * AutonomousAgentRuntime pipeline, but as a traversable DAG.
 *
 * Graph topology:
 *   perceive → plan → reason → guard → execute → validate → learn → next
 *                                  ↓                  ↓
 *                                gate              correct → execute
 *                                  ↓
 *                                wait
 *                                finish
 */
fun buildDefaultAgentGraph(): StepGraph {
    return StepGraph.builder("perceive")
        .addNode("perceive", "Perception") { state ->
            stepPerceive(state)
        }
        .addNode("plan", "Goal & Plan") { state ->
            stepPlan(state)
        }
        .addNode("reason", "Reasoning") { state ->
            stepReason(state)
        }
        .addNode("guard", "Rule Guard") { state ->
            stepGuard(state)
        }
        .addNode("gate", "Human Gate") { state ->
            stepGate(state)
        }
        .addNode("execute", "Execution", checkpointAfter = true) { state ->
            stepExecute(state)
        }
        .addNode("validate", "Validation") { state ->
            stepValidate(state)
        }
        .addNode("correct", "Correction") { state ->
            stepCorrect(state)
        }
        .addNode("learn", "Learning") { state ->
            stepLearn(state)
        }
        .addNode("next", "Cycle Router") { state ->
            stepNext(state)
        }
        .addNode("wait", "Wait") { state ->
            StepResult.Suspend("waiting for signal", SuspendKind.Signal)
        }
        .addNode("finish", "Finish") { state ->
            StepResult.Terminal(success = true, reason = "goal achieved")
        }
        .linearChain("perceive", "plan", "reason", "guard", "execute", "validate", "learn", "next")
        .edge("next", "perceive")
        .build()
}

// --- Step Implementations ---

private suspend fun stepPerceive(state: GraphExecutionState): StepResult {
    val modules = state.modules
    val snapshot = buildAutonomousSnapshot(state)
    val adapterSignals = modules.signalAdapters.flatMap { it.collect(snapshot) }
    val inboundSignals = modules.communicationHub.drain(state.sessionId)
    val allSignals = inboundSignals + adapterSignals
    val perception = modules.perceptionPipeline.process(snapshot, allSignals)
    modules.memoryManager.onPerception(state.sessionId, perception)
    state.perceptionFrame = perception
    return StepResult.Advance
}

private suspend fun stepPlan(state: GraphExecutionState): StepResult {
    val modules = state.modules
    val memory = modules.memoryManager.snapshot(state.sessionId)
    val perception = state.perceptionFrame ?: return StepResult.Advance
    val goal = modules.goalParser.resolve(state.definition, perception, memory)
    val plan = modules.taskPlanner.plan(state.definition, goal, perception, memory)
    state.resolvedGoal = goal
    state.taskPlan = plan
    return StepResult.Advance
}

private suspend fun stepReason(state: GraphExecutionState): StepResult {
    val modules = state.modules
    val perception = state.perceptionFrame ?: return StepResult.Advance
    val goal = state.resolvedGoal ?: return StepResult.Advance
    val plan = state.taskPlan ?: return StepResult.Advance
    val memory = modules.memoryManager.snapshot(state.sessionId)

    val snapshot = buildAutonomousSnapshot(state)
    val reasoning = modules.reasoningEngine.reason(
        definition = state.definition,
        snapshot = snapshot,
        perception = perception,
        memory = memory,
        goal = goal,
        plan = plan
    )
    val decision = modules.decisionSelector.select(state.definition, reasoning, memory)
    modules.memoryManager.onDecision(state.sessionId, decision)
    state.reasoning = reasoning
    state.decision = decision
    return StepResult.Advance
}

private suspend fun stepGuard(state: GraphExecutionState): StepResult {
    val modules = state.modules
    val decision = state.decision ?: return StepResult.Advance
    val plan = state.taskPlan ?: return StepResult.Advance
    val snapshot = buildAutonomousSnapshot(state)

    val guarded = modules.ruleConstraintEngine.apply(state.definition, decision, plan, snapshot)
    state.guardedDecision = guarded

    return when (val action = guarded.decision.action) {
        is AgentAction.RequestApproval -> StepResult.Branch("gate", "approval required")
        is AgentAction.Finish -> {
            if (action.success) StepResult.Branch("finish", "goal done")
            else StepResult.Terminal(success = false, reason = action.reason)
        }
        is AgentAction.Wait -> StepResult.Branch("wait", action.reason)
        else -> StepResult.Advance
    }
}

private suspend fun stepGate(state: GraphExecutionState): StepResult {
    val decision = state.decision ?: return StepResult.Advance
    val action = decision.action
    if (action !is AgentAction.RequestApproval) return StepResult.Advance

    val request = ApprovalRequest(
        runtimeId = state.sessionId,
        agentId = state.definition.agentId,
        agentName = state.definition.name,
        context = ApprovalContext(
            goal = state.resolvedGoal?.rootGoal ?: state.definition.goal,
            currentStep = "guard",
            pendingAction = action.pendingAction,
            riskLevel = parseRiskLevel(action.riskLevel),
            toolName = action.toolName,
            toolArguments = action.toolArguments,
            reasoning = action.reason,
            agentOutputSoFar = state.outputs.takeLast(5)
        )
    )
    state.pendingApprovals += request

    val approval = state.gate.requestApproval(request)
    state.pendingApprovals.removeAll { it.gateId == request.gateId }

    return when (approval.decision) {
        ApprovalStatus.Approved -> {
            // Approval granted — proceed to execute
            StepResult.Branch("execute", "approved: ${approval.feedback}")
        }
        ApprovalStatus.Rejected -> {
            StepResult.Terminal(success = false, reason = "Rejected by human: ${approval.feedback}")
        }
        ApprovalStatus.Expired -> {
            StepResult.Terminal(success = false, reason = "Approval timed out")
        }
        else -> StepResult.Advance
    }
}

private suspend fun stepExecute(state: GraphExecutionState): StepResult {
    val modules = state.modules
    val guarded = state.guardedDecision ?: return StepResult.Advance
    val snapshot = buildAutonomousSnapshot(state)

    val outcome = modules.executionCoordinator.execute(state.definition, snapshot, guarded)
    outcome.outputs.forEach { output ->
        modules.communicationHub.publish(state.sessionId, output)
        state.outputs += output
    }

    val goal = state.resolvedGoal ?: return StepResult.Advance
    val validation = modules.resultValidator.validate(goal, guarded, outcome)
    modules.feedbackProcessor.process(state.sessionId, outcome, validation)

    state.executionOutcome = outcome
    state.validationOutcome = validation
    return StepResult.Advance
}

private suspend fun stepValidate(state: GraphExecutionState): StepResult {
    val validation = state.validationOutcome ?: return StepResult.Advance
    val guarded = state.guardedDecision ?: return StepResult.Advance
    val outcome = state.executionOutcome ?: return StepResult.Advance

    // Determine if correction is needed
    val needsCorrection = !guarded.allowed ||
        outcome.toolResults.any { !it.success } ||
        validation.status == ValidationStatus.Partial ||
        validation.status == ValidationStatus.Failed

    return if (needsCorrection && state.config.enableReflectionCorrection) {
        StepResult.Branch("correct", "correction needed")
    } else {
        StepResult.Advance
    }
}

private suspend fun stepCorrect(state: GraphExecutionState): StepResult {
    val modules = state.modules
    val guarded = state.guardedDecision ?: return StepResult.Advance
    val outcome = state.executionOutcome ?: return StepResult.Advance
    val validation = state.validationOutcome ?: return StepResult.Advance

    val trigger = when {
        !guarded.allowed -> CorrectionTrigger.RuleBlocked
        outcome.toolResults.any { !it.success } -> CorrectionTrigger.ToolFailure
        validation.status == ValidationStatus.Partial -> CorrectionTrigger.ValidationPartial
        validation.status == ValidationStatus.Failed -> CorrectionTrigger.ValidationFailed
        else -> return StepResult.Advance
    }

    val attempt = state.corrections.size + 1
    val runtimeAttempts = state.correctionRecords.size + attempt

    val diagnosis = modules.reflectionEngine.reflect(
        cycle = state.cycle,
        attempt = attempt,
        trigger = trigger,
        decision = guarded,
        outcome = outcome,
        validation = validation,
        error = null
    )

    val correctionDecision = modules.correctionPolicy.decide(
        diagnosis = diagnosis,
        attemptInCycle = attempt,
        runtimeAttempts = runtimeAttempts,
        previousCorrections = state.correctionRecords.toList(),
        config = state.config
    )

    val record = CorrectionRecord(
        cycle = state.cycle,
        attempt = attempt,
        trigger = trigger,
        action = correctionDecision.action,
        reason = correctionDecision.reason,
        failureSignature = diagnosis.failureSignature,
        validationStatus = validation.status,
        error = outcome.error
    )
    state.corrections = state.corrections + record
    state.correctionRecords += record
    modules.memoryManager.onCorrection(state.sessionId, record)

    return when (correctionDecision.action) {
        CorrectionAction.RetryOriginalDecision -> {
            if (attempt < state.config.maxCorrectionAttemptsPerCycle &&
                runtimeAttempts < state.config.maxCorrectionAttemptsPerRuntime
            ) {
                StepResult.Branch("execute", "retrying")
            } else {
                StepResult.Terminal(success = false, reason = "Correction limit reached")
            }
        }
        CorrectionAction.AbortFailure -> {
            StepResult.Terminal(success = false, reason = correctionDecision.reason)
        }
        CorrectionAction.WaitForSignal -> {
            StepResult.Advance // continue to learn, expose context in next cycle
        }
        CorrectionAction.AcceptPartial -> {
            StepResult.Advance // accept and move on
        }
    }
}

private suspend fun stepLearn(state: GraphExecutionState): StepResult {
    val modules = state.modules
    val perception = state.perceptionFrame ?: return StepResult.Advance
    val goal = state.resolvedGoal ?: return StepResult.Advance
    val plan = state.taskPlan ?: return StepResult.Advance
    val reasoning = state.reasoning ?: return StepResult.Advance
    val guarded = state.guardedDecision ?: return StepResult.Advance
    val outcome = state.executionOutcome ?: return StepResult.Advance
    val validation = state.validationOutcome ?: return StepResult.Advance

    val record = com.example.watcher.agentframework.autonomy.AutonomousCycleRecord(
        cycle = state.cycle,
        perception = perception,
        goal = goal,
        plan = plan,
        reasoning = reasoning,
        guardedDecision = guarded,
        outcome = outcome,
        validation = validation,
        corrections = state.corrections
    )
    val metrics = modules.evaluationEngine.evaluate(record)
    modules.learningEngine.learn(state.sessionId, record, metrics)
    return StepResult.Advance
}

private suspend fun stepNext(state: GraphExecutionState): StepResult {
    val validation = state.validationOutcome

    // Check terminal conditions
    if (validation != null) {
        if (validation.status == ValidationStatus.Completed) {
            return StepResult.Terminal(success = true, reason = "goal achieved")
        }
        if (!validation.shouldContinue && !validation.shouldRetry) {
            return StepResult.Terminal(
                success = validation.status != ValidationStatus.Failed,
                reason = validation.feedback
            )
        }
    }

    // Continue to next cycle
    state.beginNewCycle()
    return StepResult.Advance
}

// --- Helpers ---

private fun buildAutonomousSnapshot(state: GraphExecutionState): com.example.watcher.agentframework.autonomy.AutonomousAgentSnapshot {
    return com.example.watcher.agentframework.autonomy.AutonomousAgentSnapshot(
        sessionId = state.sessionId,
        definition = state.definition,
        cycle = state.cycle,
        failureCount = state.failureCount,
        idleCount = state.idleCount,
        lastPerception = state.perceptionFrame,
        lastGoal = state.resolvedGoal,
        lastPlan = state.taskPlan,
        lastDecision = state.decision,
        lastOutcome = state.executionOutcome,
        lastValidation = state.validationOutcome,
        outputs = state.outputs.toList()
    )
}

private fun parseRiskLevel(level: String): RiskLevel = when (level.lowercase()) {
    "low" -> RiskLevel.Low
    "high" -> RiskLevel.High
    "critical" -> RiskLevel.Critical
    else -> RiskLevel.Medium
}

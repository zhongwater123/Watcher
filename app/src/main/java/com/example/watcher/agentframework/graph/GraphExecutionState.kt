package com.example.watcher.agentframework.graph

import com.example.watcher.agentframework.autonomy.AutonomousAgentConfig
import com.example.watcher.agentframework.autonomy.AutonomousAgentModules
import com.example.watcher.agentframework.autonomy.CorrectionRecord
import com.example.watcher.agentframework.autonomy.ExecutionOutcome
import com.example.watcher.agentframework.autonomy.GuardedDecision
import com.example.watcher.agentframework.autonomy.PerceptionFrame
import com.example.watcher.agentframework.autonomy.ResolvedGoal
import com.example.watcher.agentframework.autonomy.TaskPlan
import com.example.watcher.agentframework.autonomy.ReasoningEnvelope
import com.example.watcher.agentframework.autonomy.ValidationOutcome
import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.gate.ApprovalRequest
import com.example.watcher.agentframework.gate.HumanGate

/**
 * Mutable execution state that flows through step nodes during graph execution.
 * Each node reads/writes the fields relevant to its concern.
 */
class GraphExecutionState(
    val sessionId: String,
    val definition: AgentDefinition,
    val config: AutonomousAgentConfig,
    val modules: AutonomousAgentModules,
    val gate: HumanGate
) {
    // --- Cycle tracking ---
    var cycle: Int = 0
        internal set
    var totalSteps: Int = 0
        internal set
    var idleCount: Int = 0
        internal set
    var failureCount: Int = 0
        internal set
    var startedAt: Long = System.currentTimeMillis()
        internal set

    // --- Per-cycle intermediate state (reset each cycle) ---
    var perceptionFrame: PerceptionFrame? = null
    var resolvedGoal: ResolvedGoal? = null
    var taskPlan: TaskPlan? = null
    var reasoning: ReasoningEnvelope? = null
    var decision: AgentDecision? = null
    var guardedDecision: GuardedDecision? = null
    var executionOutcome: ExecutionOutcome? = null
    var validationOutcome: ValidationOutcome? = null
    var corrections: List<CorrectionRecord> = emptyList()

    // --- Accumulative state ---
    val outputs: MutableList<String> = mutableListOf()
    val correctionRecords: MutableList<CorrectionRecord> = mutableListOf()
    val pendingApprovals: MutableList<ApprovalRequest> = mutableListOf()

    // --- Generic state bag for custom step data ---
    val stateEntries: MutableMap<String, String> = mutableMapOf()

    // --- Error tracking ---
    var lastError: String? = null

    /**
     * Reset per-cycle fields when starting a new cycle.
     */
    fun beginNewCycle() {
        cycle++
        perceptionFrame = null
        resolvedGoal = null
        taskPlan = null
        reasoning = null
        decision = null
        guardedDecision = null
        executionOutcome = null
        validationOutcome = null
        corrections = emptyList()
        lastError = null
    }

    /**
     * Check if runtime limits have been exceeded.
     */
    fun checkLimits(): GraphStopReason? {
        if (cycle >= config.maxCycles) return GraphStopReason.StepLimitReached
        if (System.currentTimeMillis() - startedAt >= config.maxRuntimeMillis) return GraphStopReason.RuntimeLimitReached
        if (idleCount >= config.maxIdleCycles) return GraphStopReason.IdleLimitReached
        return null
    }

    /**
     * Snapshot current state into a checkpoint.
     */
    fun toCheckpoint(currentNodeId: String, completedNodes: List<String>): GraphCheckpoint {
        return GraphCheckpoint(
            sessionId = sessionId,
            cycle = cycle,
            currentNodeId = currentNodeId,
            completedNodesInCycle = completedNodes,
            perceptionFrame = perceptionFrame,
            resolvedGoal = resolvedGoal,
            taskPlan = taskPlan,
            lastDecision = decision,
            lastOutcome = executionOutcome,
            pendingApprovals = pendingApprovals.toList(),
            outputs = outputs.toList(),
            stateEntries = stateEntries.toMap()
        )
    }

    /**
     * Restore state from a checkpoint.
     */
    fun restoreFromCheckpoint(checkpoint: GraphCheckpoint) {
        cycle = checkpoint.cycle
        perceptionFrame = checkpoint.perceptionFrame
        resolvedGoal = checkpoint.resolvedGoal
        taskPlan = checkpoint.taskPlan
        decision = checkpoint.lastDecision
        executionOutcome = checkpoint.lastOutcome
        outputs.clear()
        outputs.addAll(checkpoint.outputs)
        pendingApprovals.clear()
        pendingApprovals.addAll(checkpoint.pendingApprovals)
        stateEntries.clear()
        stateEntries.putAll(checkpoint.stateEntries)
    }
}

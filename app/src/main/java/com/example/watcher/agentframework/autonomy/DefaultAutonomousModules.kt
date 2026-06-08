package com.example.watcher.agentframework.autonomy

import com.example.watcher.agentframework.core.AgentAction
import com.example.watcher.agentframework.core.AgentConversationItem
import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentSessionSnapshot
import com.example.watcher.agentframework.knowledge.AgentKnowledgeEntry
import com.example.watcher.agentframework.knowledge.AgentKnowledgeSnapshot
import com.example.watcher.agentframework.knowledge.AgentKnowledgeStore
import com.example.watcher.agentframework.knowledge.InMemoryAgentKnowledgeStore
import com.example.watcher.agentframework.memory.AgentMemoryEntry
import com.example.watcher.agentframework.memory.AgentMemorySnapshot
import com.example.watcher.agentframework.memory.AgentMemoryStore
import com.example.watcher.agentframework.memory.InMemoryAgentMemoryStore
import com.example.watcher.agentframework.runtime.AgentBrain
import com.example.watcher.agentframework.runtime.AgentBrainRequest
import com.example.watcher.agentframework.tools.AgentToolContext
import com.example.watcher.agentframework.tools.AgentToolExecutor
import com.example.watcher.agentframework.tools.AgentToolRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock

class NoOpSignalAdapter : SignalAdapter {
    override suspend fun collect(snapshot: AutonomousAgentSnapshot): List<AgentSignal> = emptyList()
}

class DefaultPerceptionPipeline : PerceptionPipeline {
    override suspend fun process(
        snapshot: AutonomousAgentSnapshot,
        signals: List<AgentSignal>
    ): PerceptionFrame {
        val cleaned = signals
            .map { it.copy(content = it.content.trim()) }
            .filter { it.content.isNotBlank() }
            .distinctBy { "${it.channel}:${it.content}" }
        val features = mapOf(
            "signalCount" to cleaned.size.toString(),
            "lastChannel" to cleaned.lastOrNull()?.channel?.name.orEmpty()
        )
        val summary = if (cleaned.isEmpty()) {
            "no new signal"
        } else {
            cleaned.takeLast(5).joinToString(" | ") { "[${it.channel}] ${it.content}" }
        }
        return PerceptionFrame(
            rawSignals = signals,
            cleanedSignals = cleaned,
            extractedFeatures = features,
            contextSummary = summary
        )
    }
}

class DefaultGoalParser : GoalParser {
    override suspend fun resolve(
        definition: AgentDefinition,
        perception: PerceptionFrame,
        memory: StructuredMemorySnapshot
    ): ResolvedGoal {
        val latest = perception.cleanedSignals.lastOrNull()?.content
        return ResolvedGoal(
            rootGoal = definition.goal,
            subGoals = listOfNotNull(latest?.let { "Handle latest signal: $it" }),
            constraints = definition.metadata.map { "${it.key}=${it.value}" },
            priority = 50
        )
    }
}

class DefaultTaskPlanner : TaskPlanner {
    override suspend fun plan(
        definition: AgentDefinition,
        goal: ResolvedGoal,
        perception: PerceptionFrame,
        memory: StructuredMemorySnapshot
    ): TaskPlan {
        val preferredTools = memory.longTerm
            .mapNotNull { it.metadata["tool"] }
            .distinct()
        return TaskPlan(
            summary = "Advance goal with current context",
            steps = listOf(
                "Interpret the latest context",
                "Pick the safest next action",
                "Validate whether the goal moved forward"
            ),
            preferredTools = preferredTools
        )
    }
}

class BrainBackedReasoningEngine(
    private val brain: AgentBrain,
    private val toolExecutor: AgentToolExecutor,
    private val memoryStore: AgentMemoryStore,
    private val knowledgeStore: AgentKnowledgeStore
) : ReasoningEngine {
    override suspend fun reason(
        definition: AgentDefinition,
        snapshot: AutonomousAgentSnapshot,
        perception: PerceptionFrame,
        memory: StructuredMemorySnapshot,
        goal: ResolvedGoal,
        plan: TaskPlan
    ): ReasoningEnvelope {
        val recentCorrections = memory.working
            .filter { it.metadata["kind"] == "correction" }
            .takeLast(3)
        val history = buildList {
            perception.cleanedSignals.takeLast(8).forEach { signal ->
                add(
                    AgentConversationItem(
                        role = when (signal.channel) {
                            SignalChannel.User -> AgentMessageRole.User
                            SignalChannel.Agent -> AgentMessageRole.Assistant
                            else -> AgentMessageRole.Observation
                        },
                        content = signal.content
                    )
                )
            }
            snapshot.outputs.takeLast(4).forEach { output ->
                add(AgentConversationItem(role = AgentMessageRole.Assistant, content = output))
            }
        }
        val decision = brain.decide(
            AgentBrainRequest(
                definition = definition.copy(
                    goal = buildString {
                        appendLine(definition.goal)
                        appendLine("Goal summary: ${goal.rootGoal}")
                        appendLine("Plan summary: ${plan.summary}")
                        appendLine("Perception summary: ${perception.contextSummary}")
                        if (recentCorrections.isNotEmpty()) {
                            appendLine("Recent correction context:")
                            recentCorrections.forEach { appendLine("- ${it.content}") }
                        }
                    }
                ),
                config = com.example.watcher.agentframework.core.AgentRunConfig(),
                session = AgentSessionSnapshot(
                    sessionId = snapshot.sessionId,
                    agentId = definition.agentId,
                    agentName = definition.name,
                    goal = goal.rootGoal,
                    stepCount = snapshot.cycle,
                    history = history
                ),
                memory = AgentMemorySnapshot(
                    working = (
                        memoryStore.read(snapshot.sessionId, AgentMemoryScope.Working, limit = 20) +
                            memory.working.map {
                                AgentMemoryEntry(
                                    scope = AgentMemoryScope.Working,
                                    content = it.content
                                )
                            } +
                            memory.shortTerm.map {
                                AgentMemoryEntry(
                                    scope = AgentMemoryScope.Working,
                                    content = it.content
                                )
                            }
                        ).distinctBy { "${it.scope}:${it.content}:${it.createdAt}" }.takeLast(20),
                    episodic = (
                        memoryStore.read(snapshot.sessionId, AgentMemoryScope.Episodic, limit = 20) +
                            memory.longTerm.map {
                                AgentMemoryEntry(
                                    scope = AgentMemoryScope.Episodic,
                                    content = it.content
                                )
                            }
                        ).distinctBy { "${it.scope}:${it.content}:${it.createdAt}" }.takeLast(20)
                ),
                knowledge = AgentKnowledgeSnapshot(entries = knowledgeStore.read(definition.agentId, limit = 20)),
                recentInputs = emptyList(),
                availableTools = toolExecutor.definitions()
            )
        )
        return ReasoningEnvelope(
            summary = decision.thinking.ifBlank { decision.reply ?: "no reasoning" },
            candidateActions = listOf(decision.action),
            decision = decision,
            confidence = 70
        )
    }
}

class DefaultDecisionSelector : DecisionSelector {
    override suspend fun select(
        definition: AgentDefinition,
        reasoning: ReasoningEnvelope,
        memory: StructuredMemorySnapshot
    ): AgentDecision = reasoning.decision
}

class DefaultRuleConstraintEngine(
    private val maxToolCallsPerCycle: Int = 4
) : RuleConstraintEngine {
    override suspend fun apply(
        definition: AgentDefinition,
        decision: AgentDecision,
        plan: TaskPlan,
        snapshot: AutonomousAgentSnapshot
    ): GuardedDecision {
        val action = decision.action
        if (action is AgentAction.UseTools) {
            if (action.calls.any { it.name.isBlank() }) {
                return GuardedDecision(
                    decision = decision.copy(
                        action = AgentAction.Finish(
                            reason = "Blocked by rule engine: blank tool name",
                            success = false
                        )
                    ),
                    allowed = false,
                    reason = "blank tool name"
                )
            }
            if (action.calls.size > maxToolCallsPerCycle) {
                return GuardedDecision(
                    decision = decision.copy(
                        action = AgentAction.UseTools(action.calls.take(maxToolCallsPerCycle))
                    ),
                    allowed = true,
                    reason = "tool calls trimmed by rule engine"
                )
            }
        }
        return GuardedDecision(decision = decision, allowed = true, reason = "allowed")
    }
}

class ToolCentricExecutionCoordinator(
    private val toolExecutor: AgentToolExecutor,
    private val toolMemoryStore: AgentMemoryStore,
    private val toolKnowledgeStore: AgentKnowledgeStore,
    private val toolTimeoutMillis: Long = 30_000L
) : ExecutionCoordinator {
    override suspend fun execute(
        definition: AgentDefinition,
        snapshot: AutonomousAgentSnapshot,
        guardedDecision: GuardedDecision
    ): ExecutionOutcome {
        val startedAt = System.currentTimeMillis()
        if (!guardedDecision.allowed) {
            return ExecutionOutcome(
                success = false,
                action = guardedDecision.decision.action,
                error = guardedDecision.reason,
                durationMillis = System.currentTimeMillis() - startedAt
            )
        }

        val outputs = mutableListOf<String>()
        guardedDecision.decision.reply?.takeIf { it.isNotBlank() }?.let(outputs::add)

        val toolResults = mutableListOf<com.example.watcher.agentframework.core.AgentToolResult>()
        val session = AgentSessionSnapshot(
            sessionId = snapshot.sessionId,
            agentId = definition.agentId,
            agentName = definition.name,
            goal = definition.goal,
            stepCount = snapshot.cycle
        )
        val context = AgentToolContext(
            definition = definition,
            session = session,
            memoryStore = toolMemoryStore,
            knowledgeStore = toolKnowledgeStore
        )

        when (val action = guardedDecision.decision.action) {
            AgentAction.Continue,
            is AgentAction.Wait,
            is AgentAction.RequestApproval -> Unit

            is AgentAction.Finish -> {
                return ExecutionOutcome(
                    success = action.success,
                    action = action,
                    outputs = outputs,
                    durationMillis = System.currentTimeMillis() - startedAt
                )
            }

            is AgentAction.UseTools -> {
                action.calls.forEach { call ->
                    val result = try {
                        withTimeout(toolTimeoutMillis) {
                            toolExecutor.execute(call, context)
                        }
                    } catch (_: TimeoutCancellationException) {
                        com.example.watcher.agentframework.core.AgentToolResult(
                            callId = call.id,
                            toolName = call.name,
                            success = false,
                            error = "Tool execution timed out after ${toolTimeoutMillis}ms"
                        )
                    }
                    toolResults += result
                }
            }
        }

        val success = toolResults.all { it.success }
        return ExecutionOutcome(
            success = success,
            action = guardedDecision.decision.action,
            toolResults = toolResults,
            outputs = outputs,
            error = toolResults.firstOrNull { !it.success }?.error,
            durationMillis = System.currentTimeMillis() - startedAt
        )
    }
}

class DefaultResultValidator : ResultValidator {
    override suspend fun validate(
        goal: ResolvedGoal,
        decision: GuardedDecision,
        outcome: ExecutionOutcome
    ): ValidationOutcome {
        val action = decision.decision.action
        return when {
            !decision.allowed -> ValidationOutcome(
                status = ValidationStatus.Failed,
                shouldContinue = false,
                shouldRetry = false,
                feedback = "Action blocked: ${decision.reason}"
            )

            action is AgentAction.Finish && action.success -> ValidationOutcome(
                status = ValidationStatus.Completed,
                shouldContinue = false,
                shouldRetry = false,
                feedback = "Goal completed"
            )

            action is AgentAction.Finish && !action.success -> ValidationOutcome(
                status = ValidationStatus.Failed,
                shouldContinue = false,
                shouldRetry = false,
                feedback = action.reason
            )

            action is AgentAction.UseTools && !outcome.success -> ValidationOutcome(
                status = ValidationStatus.Partial,
                shouldContinue = true,
                shouldRetry = true,
                feedback = outcome.error ?: "Tool execution failed"
            )

            action is AgentAction.Wait -> ValidationOutcome(
                status = ValidationStatus.Running,
                shouldContinue = true,
                shouldRetry = false,
                feedback = action.reason
            )

            else -> ValidationOutcome(
                status = ValidationStatus.Running,
                shouldContinue = true,
                shouldRetry = false,
                feedback = "Goal still in progress"
            )
        }
    }
}

class MemoryBackedFeedbackProcessor(
    private val memoryManager: StructuredMemoryManager
) : FeedbackProcessor {
    override suspend fun process(
        sessionId: String,
        outcome: ExecutionOutcome,
        validation: ValidationOutcome
    ) {
        memoryManager.onFeedback(sessionId, outcome, validation)
    }
}

class DefaultEvaluationEngine : EvaluationEngine {
    override suspend fun evaluate(
        record: AutonomousCycleRecord
    ): Map<String, String> {
        return mapOf(
            "validationStatus" to record.validation.status.name,
            "toolResultCount" to record.outcome.toolResults.size.toString(),
            "success" to record.outcome.success.toString()
        )
    }
}

class MemoryBackedLearningEngine(
    private val memoryManager: StructuredMemoryManager
) : LearningEngine {
    override suspend fun learn(
        sessionId: String,
        record: AutonomousCycleRecord,
        metrics: Map<String, String>
    ) {
        val lesson = when (record.validation.status) {
            ValidationStatus.Completed -> StructuredMemoryEntry(
                scope = StructuredMemoryScope.LongTerm,
                content = "Successful strategy: ${record.plan.summary}",
                metadata = metrics
            )

            ValidationStatus.Failed -> StructuredMemoryEntry(
                scope = StructuredMemoryScope.LongTerm,
                content = "Failure lesson: ${record.validation.feedback}",
                metadata = metrics
            )

            else -> return
        }
        memoryManager.onLearning(sessionId, lesson)
    }
}

class DefaultReflectionEngine : ReflectionEngine {
    override suspend fun reflect(
        cycle: Int,
        attempt: Int,
        trigger: CorrectionTrigger,
        decision: GuardedDecision?,
        outcome: ExecutionOutcome?,
        validation: ValidationOutcome?,
        error: Throwable?
    ): CorrectionDiagnosis {
        val reason = when (trigger) {
            CorrectionTrigger.RuleBlocked -> decision?.reason ?: "Action blocked by rule engine"
            CorrectionTrigger.ToolFailure -> outcome?.error ?: firstToolError(outcome) ?: "Tool execution failed"
            CorrectionTrigger.ValidationPartial -> validation?.feedback ?: "Validation reported partial progress"
            CorrectionTrigger.ValidationFailed -> validation?.feedback ?: "Validation failed"
            CorrectionTrigger.CycleException -> error?.message ?: "Autonomous cycle failed"
        }
        val signature = buildString {
            append(trigger.name)
            append(":")
            append(reason.take(120))
        }
        val unrecoverable = reason.contains("Unknown tool", ignoreCase = true) ||
            reason.contains("not allowed", ignoreCase = true)
        return CorrectionDiagnosis(
            trigger = trigger,
            reason = reason,
            failureSignature = signature,
            recoverable = !unrecoverable
        )
    }

    private fun firstToolError(outcome: ExecutionOutcome?): String? {
        return outcome?.toolResults?.firstOrNull { !it.success }?.error
    }
}

class DefaultCorrectionPolicy : CorrectionPolicy {
    override suspend fun decide(
        diagnosis: CorrectionDiagnosis,
        attemptInCycle: Int,
        runtimeAttempts: Int,
        previousCorrections: List<CorrectionRecord>,
        config: AutonomousAgentConfig
    ): CorrectionDecision {
        if (!config.enableReflectionCorrection) {
            return CorrectionDecision(CorrectionAction.AbortFailure, "Reflection correction is disabled")
        }
        if (attemptInCycle > config.maxCorrectionAttemptsPerCycle ||
            runtimeAttempts >= config.maxCorrectionAttemptsPerRuntime
        ) {
            return CorrectionDecision(CorrectionAction.AbortFailure, "Correction attempt limit reached")
        }
        val repeated = previousCorrections.takeLast(1).count {
            it.failureSignature == diagnosis.failureSignature
        } + 1
        if (repeated >= 2) {
            return CorrectionDecision(CorrectionAction.AbortFailure, "Repeated failure signature: ${diagnosis.failureSignature}")
        }
        if (!diagnosis.recoverable) {
            return CorrectionDecision(CorrectionAction.AbortFailure, diagnosis.reason)
        }
        if (diagnosis.trigger == CorrectionTrigger.ToolFailure &&
            diagnosis.reason.contains("timed out", ignoreCase = true)
        ) {
            return CorrectionDecision(CorrectionAction.RetryOriginalDecision, "Retry original decision after timeout")
        }
        return when (diagnosis.trigger) {
            CorrectionTrigger.ValidationPartial -> {
                CorrectionDecision(CorrectionAction.WaitForSignal, "Record partial result and continue with correction context")
            }
            CorrectionTrigger.ToolFailure -> {
                CorrectionDecision(CorrectionAction.WaitForSignal, "Expose tool failure context to the next decision cycle")
            }
            CorrectionTrigger.ValidationFailed,
            CorrectionTrigger.RuleBlocked,
            CorrectionTrigger.CycleException -> {
                CorrectionDecision(CorrectionAction.AbortFailure, diagnosis.reason)
            }
        }
    }
}

class InMemoryCommunicationHub : CommunicationHub {
    private val maxSignalsPerSession: Int
    private val maxOutputsPerSession: Int

    constructor(
        maxSignalsPerSession: Int = 256,
        maxOutputsPerSession: Int = 128
    ) {
        this.maxSignalsPerSession = maxSignalsPerSession
        this.maxOutputsPerSession = maxOutputsPerSession
    }

    private val mutex = Mutex()
    private val signals = mutableMapOf<String, MutableList<AgentSignal>>()
    private val outputs = mutableMapOf<String, MutableList<String>>()

    override suspend fun submit(sessionId: String, signal: AgentSignal) {
        mutex.withLock {
            val queue = signals.getOrPut(sessionId) { mutableListOf() }
            queue += signal
            trimToLast(queue, maxSignalsPerSession)
        }
    }

    override suspend fun drain(sessionId: String): List<AgentSignal> {
        return mutex.withLock {
            val queue = signals.getOrPut(sessionId) { mutableListOf() }
            val drained = queue.toList()
            queue.clear()
            drained
        }
    }

    override suspend fun publish(sessionId: String, output: String) {
        mutex.withLock {
            val queue = outputs.getOrPut(sessionId) { mutableListOf() }
            queue += output
            trimToLast(queue, maxOutputsPerSession)
        }
    }

    override suspend fun outputs(sessionId: String): List<String> {
        return mutex.withLock {
            outputs[sessionId].orEmpty().toList()
        }
    }

    override suspend fun clear(sessionId: String) {
        mutex.withLock {
            signals.remove(sessionId)
            outputs.remove(sessionId)
        }
    }

    private fun <T> trimToLast(items: MutableList<T>, limit: Int) {
        if (limit <= 0) {
            items.clear()
            return
        }
        val overflow = items.size - limit
        if (overflow > 0) {
            repeat(overflow) {
                items.removeAt(0)
            }
        }
    }
}

fun defaultAutonomousModules(
    brain: AgentBrain,
    toolExecutor: AgentToolExecutor = AgentToolRegistry(),
    memoryStore: AgentMemoryStore = InMemoryAgentMemoryStore(),
    knowledgeStore: AgentKnowledgeStore = InMemoryAgentKnowledgeStore(),
    memoryManager: StructuredMemoryManager = InMemoryStructuredMemoryManager(),
    communicationHub: CommunicationHub = InMemoryCommunicationHub(),
    toolTimeoutMillis: Long = 30_000L
): AutonomousAgentModules {
    return AutonomousAgentModules(
        signalAdapters = listOf(NoOpSignalAdapter()),
        perceptionPipeline = DefaultPerceptionPipeline(),
        memoryManager = memoryManager,
        goalParser = DefaultGoalParser(),
        taskPlanner = DefaultTaskPlanner(),
        reasoningEngine = BrainBackedReasoningEngine(brain, toolExecutor, memoryStore, knowledgeStore),
        decisionSelector = DefaultDecisionSelector(),
        ruleConstraintEngine = DefaultRuleConstraintEngine(),
        executionCoordinator = ToolCentricExecutionCoordinator(toolExecutor, memoryStore, knowledgeStore, toolTimeoutMillis),
        resultValidator = DefaultResultValidator(),
        feedbackProcessor = MemoryBackedFeedbackProcessor(memoryManager),
        evaluationEngine = DefaultEvaluationEngine(),
        learningEngine = MemoryBackedLearningEngine(memoryManager),
        reflectionEngine = DefaultReflectionEngine(),
        correctionPolicy = DefaultCorrectionPolicy(),
        communicationHub = communicationHub
    )
}

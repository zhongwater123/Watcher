package com.example.watcher.agentframework.autonomy

import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentDefinition

interface SignalAdapter {
    suspend fun collect(snapshot: AutonomousAgentSnapshot): List<AgentSignal>
}

interface PerceptionPipeline {
    suspend fun process(
        snapshot: AutonomousAgentSnapshot,
        signals: List<AgentSignal>
    ): PerceptionFrame
}

interface StructuredMemoryManager {
    suspend fun snapshot(sessionId: String): StructuredMemorySnapshot
    suspend fun onPerception(sessionId: String, perception: PerceptionFrame)
    suspend fun onDecision(sessionId: String, decision: AgentDecision)
    suspend fun onFeedback(
        sessionId: String,
        outcome: ExecutionOutcome,
        validation: ValidationOutcome
    )
    suspend fun onLearning(sessionId: String, lesson: StructuredMemoryEntry)
    suspend fun onCorrection(sessionId: String, record: CorrectionRecord) = Unit
    suspend fun clear(sessionId: String)
}

interface GoalParser {
    suspend fun resolve(
        definition: AgentDefinition,
        perception: PerceptionFrame,
        memory: StructuredMemorySnapshot
    ): ResolvedGoal
}

interface TaskPlanner {
    suspend fun plan(
        definition: AgentDefinition,
        goal: ResolvedGoal,
        perception: PerceptionFrame,
        memory: StructuredMemorySnapshot
    ): TaskPlan
}

interface ReasoningEngine {
    suspend fun reason(
        definition: AgentDefinition,
        snapshot: AutonomousAgentSnapshot,
        perception: PerceptionFrame,
        memory: StructuredMemorySnapshot,
        goal: ResolvedGoal,
        plan: TaskPlan
    ): ReasoningEnvelope
}

interface DecisionSelector {
    suspend fun select(
        definition: AgentDefinition,
        reasoning: ReasoningEnvelope,
        memory: StructuredMemorySnapshot
    ): AgentDecision
}

interface RuleConstraintEngine {
    suspend fun apply(
        definition: AgentDefinition,
        decision: AgentDecision,
        plan: TaskPlan,
        snapshot: AutonomousAgentSnapshot
    ): GuardedDecision
}

interface ExecutionCoordinator {
    suspend fun execute(
        definition: AgentDefinition,
        snapshot: AutonomousAgentSnapshot,
        guardedDecision: GuardedDecision
    ): ExecutionOutcome
}

interface ResultValidator {
    suspend fun validate(
        goal: ResolvedGoal,
        decision: GuardedDecision,
        outcome: ExecutionOutcome
    ): ValidationOutcome
}

interface FeedbackProcessor {
    suspend fun process(
        sessionId: String,
        outcome: ExecutionOutcome,
        validation: ValidationOutcome
    )
}

interface EvaluationEngine {
    suspend fun evaluate(
        record: AutonomousCycleRecord
    ): Map<String, String>
}

interface LearningEngine {
    suspend fun learn(
        sessionId: String,
        record: AutonomousCycleRecord,
        metrics: Map<String, String>
    )
}

interface ReflectionEngine {
    suspend fun reflect(
        cycle: Int,
        attempt: Int,
        trigger: CorrectionTrigger,
        decision: GuardedDecision?,
        outcome: ExecutionOutcome?,
        validation: ValidationOutcome?,
        error: Throwable?
    ): CorrectionDiagnosis
}

interface CorrectionPolicy {
    suspend fun decide(
        diagnosis: CorrectionDiagnosis,
        attemptInCycle: Int,
        runtimeAttempts: Int,
        previousCorrections: List<CorrectionRecord>,
        config: AutonomousAgentConfig
    ): CorrectionDecision
}

interface CommunicationHub {
    suspend fun submit(sessionId: String, signal: AgentSignal)
    suspend fun drain(sessionId: String): List<AgentSignal>
    suspend fun publish(sessionId: String, output: String)
    suspend fun outputs(sessionId: String): List<String>
    suspend fun clear(sessionId: String)
}

data class AutonomousAgentModules(
    val signalAdapters: List<SignalAdapter>,
    val perceptionPipeline: PerceptionPipeline,
    val memoryManager: StructuredMemoryManager,
    val goalParser: GoalParser,
    val taskPlanner: TaskPlanner,
    val reasoningEngine: ReasoningEngine,
    val decisionSelector: DecisionSelector,
    val ruleConstraintEngine: RuleConstraintEngine,
    val executionCoordinator: ExecutionCoordinator,
    val resultValidator: ResultValidator,
    val feedbackProcessor: FeedbackProcessor,
    val evaluationEngine: EvaluationEngine,
    val learningEngine: LearningEngine,
    val reflectionEngine: ReflectionEngine,
    val correctionPolicy: CorrectionPolicy,
    val communicationHub: CommunicationHub
)

package com.example.watcher.agentframework.autonomy

import com.example.watcher.agentframework.core.AgentAction
import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentToolResult
import java.util.UUID

enum class AutonomousLifecycleState {
    Created,
    Initialized,
    Running,
    Suspended,
    Stopped,
    Failed,
    Destroyed;

    val isTerminal: Boolean
        get() = this == Stopped || this == Failed || this == Destroyed
}

enum class SignalChannel {
    User,
    Environment,
    Tool,
    System,
    Agent
}

enum class AutonomousStopReason {
    GoalAchieved,
    StepLimitReached,
    RuntimeLimitReached,
    IdleLimitReached,
    StoppedByRequest,
    InterruptedByRestart,
    Cancelled,
    Error
}

data class AgentSignal(
    val id: String = UUID.randomUUID().toString(),
    val channel: SignalChannel,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

data class PerceptionFrame(
    val rawSignals: List<AgentSignal>,
    val cleanedSignals: List<AgentSignal>,
    val extractedFeatures: Map<String, String>,
    val contextSummary: String,
    val environmentState: Map<String, String> = emptyMap()
)

enum class StructuredMemoryScope {
    ShortTerm,
    Working,
    LongTerm
}

data class StructuredMemoryEntry(
    val scope: StructuredMemoryScope,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

data class StructuredMemorySnapshot(
    val shortTerm: List<StructuredMemoryEntry>,
    val working: List<StructuredMemoryEntry>,
    val longTerm: List<StructuredMemoryEntry>
)

data class ResolvedGoal(
    val rootGoal: String,
    val subGoals: List<String>,
    val constraints: List<String>,
    val priority: Int = 50
)

data class TaskPlan(
    val summary: String,
    val steps: List<String>,
    val preferredTools: List<String> = emptyList()
)

data class ReasoningEnvelope(
    val summary: String,
    val candidateActions: List<AgentAction>,
    val decision: AgentDecision,
    val confidence: Int
)

data class GuardedDecision(
    val decision: AgentDecision,
    val allowed: Boolean,
    val reason: String
)

data class ExecutionOutcome(
    val success: Boolean,
    val action: AgentAction,
    val toolResults: List<AgentToolResult> = emptyList(),
    val outputs: List<String> = emptyList(),
    val error: String? = null,
    val durationMillis: Long = 0L
)

enum class CorrectionTrigger {
    ToolFailure,
    ValidationPartial,
    ValidationFailed,
    RuleBlocked,
    CycleException
}

enum class CorrectionAction {
    RetryOriginalDecision,
    WaitForSignal,
    AbortFailure,
    AcceptPartial
}

data class CorrectionDiagnosis(
    val trigger: CorrectionTrigger,
    val reason: String,
    val failureSignature: String,
    val recoverable: Boolean
)

data class CorrectionDecision(
    val action: CorrectionAction,
    val reason: String
)

data class CorrectionRecord(
    val cycle: Int,
    val attempt: Int,
    val trigger: CorrectionTrigger,
    val action: CorrectionAction,
    val reason: String,
    val failureSignature: String,
    val validationStatus: ValidationStatus? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ValidationStatus {
    Running,
    Partial,
    Completed,
    Failed
}

data class ValidationOutcome(
    val status: ValidationStatus,
    val shouldContinue: Boolean,
    val shouldRetry: Boolean,
    val feedback: String
)

data class AutonomousCycleRecord(
    val cycle: Int,
    val perception: PerceptionFrame,
    val goal: ResolvedGoal,
    val plan: TaskPlan,
    val reasoning: ReasoningEnvelope,
    val guardedDecision: GuardedDecision,
    val outcome: ExecutionOutcome,
    val validation: ValidationOutcome,
    val corrections: List<CorrectionRecord> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = System.currentTimeMillis()
)

data class AutonomousAgentConfig(
    val maxCycles: Int = 12,
    val maxFailures: Int = 2,
    val maxIdleCycles: Int = 2,
    val loopDelayMillis: Long = 200L,
    val maxRuntimeMillis: Long = 120_000L,
    val maxRecords: Int? = null,
    val enableReflectionCorrection: Boolean = true,
    val maxCorrectionAttemptsPerCycle: Int = 2,
    val maxCorrectionAttemptsPerRuntime: Int = 8,
    val maxCorrectionRecords: Int = 32
)

data class AutonomousAgentSnapshot(
    val sessionId: String,
    val definition: AgentDefinition,
    val lifecycleState: AutonomousLifecycleState = AutonomousLifecycleState.Created,
    val stopReason: AutonomousStopReason? = null,
    val cycle: Int = 0,
    val failureCount: Int = 0,
    val idleCount: Int = 0,
    val lastPerception: PerceptionFrame? = null,
    val lastGoal: ResolvedGoal? = null,
    val lastPlan: TaskPlan? = null,
    val lastReasoning: ReasoningEnvelope? = null,
    val lastDecision: AgentDecision? = null,
    val lastOutcome: ExecutionOutcome? = null,
    val lastValidation: ValidationOutcome? = null,
    val outputs: List<String> = emptyList(),
    val records: List<AutonomousCycleRecord> = emptyList(),
    val correctionRecords: List<CorrectionRecord> = emptyList(),
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

sealed interface AutonomousAgentEvent {
    val sessionId: String
    val timestamp: Long

    data class LifecycleChanged(
        override val sessionId: String,
        val state: AutonomousLifecycleState,
        val stopReason: AutonomousStopReason? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutonomousAgentEvent

    data class CycleCompleted(
        override val sessionId: String,
        val cycle: Int,
        val validationStatus: ValidationStatus,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutonomousAgentEvent

    data class OutputPublished(
        override val sessionId: String,
        val output: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutonomousAgentEvent

    data class FailureRecorded(
        override val sessionId: String,
        val cycle: Int,
        val message: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutonomousAgentEvent
}

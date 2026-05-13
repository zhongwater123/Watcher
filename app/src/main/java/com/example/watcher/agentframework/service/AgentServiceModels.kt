package com.example.watcher.agentframework.service

import com.example.watcher.agentframework.autonomy.AgentSignal
import com.example.watcher.agentframework.autonomy.AutonomousAgentEvent
import com.example.watcher.agentframework.autonomy.AutonomousAgentSnapshot
import com.example.watcher.agentframework.autonomy.AutonomousLifecycleState
import com.example.watcher.agentframework.autonomy.AutonomousStopReason
import com.example.watcher.agentframework.autonomy.CorrectionRecord
import com.example.watcher.agentframework.autonomy.SignalChannel
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentRunConfig
import com.example.watcher.agentframework.core.AgentSessionSnapshot
import com.example.watcher.agentframework.runtime.AgentBrain

data class AgentRegistration(
    val definition: AgentDefinition,
    val brainFactoryId: String = definition.agentId,
    val brain: AgentBrain? = null,
    val config: AgentRunConfig = AgentRunConfig(),
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap()
)

data class RegisteredAgentProfile(
    val definition: AgentDefinition,
    val brainFactoryId: String = definition.agentId,
    val config: AgentRunConfig = AgentRunConfig(),
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val agentId: String
        get() = definition.agentId
}

data class AgentInvocationInput(
    val role: AgentMessageRole,
    val content: String,
    val name: String? = null
)

data class AgentMemorySeed(
    val scope: AgentMemoryScope,
    val content: String,
    val tags: Set<String> = emptySet()
)

data class AgentKnowledgeSeed(
    val content: String,
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap()
)

data class AgentSignalSeed(
    val channel: SignalChannel,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

data class AgentInvocationRequest(
    val agentId: String,
    val inputs: List<AgentInvocationInput>,
    val preloadMemory: List<AgentMemorySeed> = emptyList(),
    val preloadKnowledge: List<AgentKnowledgeSeed> = emptyList(),
    val awaitCompletion: Boolean = true
)

enum class AgentInvocationStatus {
    Pending,
    Running,
    Completed,
    Stopped,
    Failed,
    Interrupted,
    Cancelled
}

data class AgentInvocationRecord(
    // Autonomous invocation contract: invocationId == runtimeId == sessionId.
    val invocationId: String,
    val agentId: String,
    val sessionId: String? = null,
    val status: AgentInvocationStatus = AgentInvocationStatus.Pending,
    val inputs: List<AgentInvocationInput> = emptyList(),
    val outputs: List<String> = emptyList(),
    val finalSnapshot: AgentSessionSnapshot? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AgentRestorationStatus(
    val agentId: String,
    val brainFactoryId: String,
    val restorable: Boolean,
    val reason: String? = null
)

data class AutonomousAgentStartRequest(
    val agentId: String,
    val initialSignals: List<AgentSignalSeed> = emptyList(),
    val preloadMemory: List<AgentMemorySeed> = emptyList(),
    val preloadKnowledge: List<AgentKnowledgeSeed> = emptyList(),
    // null keeps legacy behavior with all registered tools visible; non-null is a runtime whitelist.
    val allowedToolNames: Set<String>? = null
)

data class AutonomousAgentRuntimeRecord(
    // Autonomous invocation contract: runtimeId == sessionId == invocationId.
    val runtimeId: String,
    val agentId: String,
    val lifecycleState: AutonomousLifecycleState = AutonomousLifecycleState.Created,
    val stopReason: AutonomousStopReason? = null,
    val submittedSignals: List<AgentSignal> = emptyList(),
    val outputs: List<String> = emptyList(),
    val snapshot: AutonomousAgentSnapshot? = null,
    val events: List<AutonomousAgentEvent> = emptyList(),
    val correctionRecords: List<CorrectionRecord> = emptyList(),
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

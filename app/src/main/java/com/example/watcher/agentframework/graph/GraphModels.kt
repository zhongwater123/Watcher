package com.example.watcher.agentframework.graph

import com.example.watcher.agentframework.autonomy.AutonomousLifecycleState
import com.example.watcher.agentframework.autonomy.AutonomousStopReason

/**
 * Lifecycle state of a graph runtime execution.
 */
enum class GraphLifecycleState {
    Created,
    Running,
    Suspended,
    Completed,
    Failed,
    Cancelled;

    val isTerminal: Boolean
        get() = this == Completed || this == Failed || this == Cancelled
}

/**
 * Reason the graph execution ended.
 */
enum class GraphStopReason {
    GoalAchieved,
    StepLimitReached,
    RuntimeLimitReached,
    IdleLimitReached,
    ApprovalRejected,
    Cancelled,
    Error
}

/**
 * Runtime snapshot of a graph execution.
 */
data class GraphRuntimeSnapshot(
    val sessionId: String,
    val lifecycleState: GraphLifecycleState = GraphLifecycleState.Created,
    val stopReason: GraphStopReason? = null,
    val currentNodeId: String? = null,
    val cycle: Int = 0,
    val totalSteps: Int = 0,
    val idleCount: Int = 0,
    val outputs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val lastCheckpointId: String? = null,
    val suspendReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Events emitted during graph execution.
 */
sealed interface GraphEvent {
    val sessionId: String
    val timestamp: Long

    data class LifecycleChanged(
        override val sessionId: String,
        val state: GraphLifecycleState,
        val reason: GraphStopReason? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : GraphEvent

    data class StepStarted(
        override val sessionId: String,
        val nodeId: String,
        val nodeName: String,
        val cycle: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : GraphEvent

    data class StepCompleted(
        override val sessionId: String,
        val nodeId: String,
        val result: StepResult,
        val durationMillis: Long,
        override val timestamp: Long = System.currentTimeMillis()
    ) : GraphEvent

    data class CheckpointSaved(
        override val sessionId: String,
        val checkpointId: String,
        val nodeId: String,
        val cycle: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : GraphEvent

    data class Suspended(
        override val sessionId: String,
        val reason: String,
        val kind: SuspendKind,
        override val timestamp: Long = System.currentTimeMillis()
    ) : GraphEvent

    data class Resumed(
        override val sessionId: String,
        val fromCheckpointId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : GraphEvent

    data class OutputProduced(
        override val sessionId: String,
        val output: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : GraphEvent

    data class ErrorOccurred(
        override val sessionId: String,
        val nodeId: String?,
        val message: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : GraphEvent
}

/**
 * Maps graph lifecycle states to the existing autonomous lifecycle states.
 */
fun GraphLifecycleState.toAutonomousLifecycleState(): AutonomousLifecycleState = when (this) {
    GraphLifecycleState.Created -> AutonomousLifecycleState.Created
    GraphLifecycleState.Running -> AutonomousLifecycleState.Running
    GraphLifecycleState.Suspended -> AutonomousLifecycleState.Suspended
    GraphLifecycleState.Completed -> AutonomousLifecycleState.Stopped
    GraphLifecycleState.Failed -> AutonomousLifecycleState.Failed
    GraphLifecycleState.Cancelled -> AutonomousLifecycleState.Destroyed
}

fun GraphStopReason.toAutonomousStopReason(): AutonomousStopReason = when (this) {
    GraphStopReason.GoalAchieved -> AutonomousStopReason.GoalAchieved
    GraphStopReason.StepLimitReached -> AutonomousStopReason.StepLimitReached
    GraphStopReason.RuntimeLimitReached -> AutonomousStopReason.RuntimeLimitReached
    GraphStopReason.IdleLimitReached -> AutonomousStopReason.IdleLimitReached
    GraphStopReason.ApprovalRejected -> AutonomousStopReason.StoppedByRequest
    GraphStopReason.Cancelled -> AutonomousStopReason.Cancelled
    GraphStopReason.Error -> AutonomousStopReason.Error
}

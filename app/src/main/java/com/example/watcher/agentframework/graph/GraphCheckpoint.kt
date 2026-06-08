package com.example.watcher.agentframework.graph

import com.example.watcher.agentframework.autonomy.ExecutionOutcome
import com.example.watcher.agentframework.autonomy.PerceptionFrame
import com.example.watcher.agentframework.autonomy.ResolvedGoal
import com.example.watcher.agentframework.autonomy.TaskPlan
import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.gate.ApprovalRequest
import java.util.UUID

/**
 * Serializable checkpoint capturing the full state of a graph execution at a point in time.
 * Enables resume, rollback, and fork operations.
 */
data class GraphCheckpoint(
    val checkpointId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val cycle: Int,
    val currentNodeId: String,
    val completedNodesInCycle: List<String> = emptyList(),
    val perceptionFrame: PerceptionFrame? = null,
    val resolvedGoal: ResolvedGoal? = null,
    val taskPlan: TaskPlan? = null,
    val lastDecision: AgentDecision? = null,
    val lastOutcome: ExecutionOutcome? = null,
    val pendingApprovals: List<ApprovalRequest> = emptyList(),
    val outputs: List<String> = emptyList(),
    val stateEntries: Map<String, String> = emptyMap(),
    val suspendReason: String? = null,
    val suspendKind: SuspendKind? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isResumable(): Boolean = suspendReason != null

    companion object {
        fun empty(sessionId: String, entryNodeId: String): GraphCheckpoint = GraphCheckpoint(
            sessionId = sessionId,
            cycle = 0,
            currentNodeId = entryNodeId
        )
    }
}

/**
 * Store interface for persisting and retrieving checkpoints.
 */
interface GraphCheckpointStore {
    suspend fun save(checkpoint: GraphCheckpoint)
    suspend fun load(checkpointId: String): GraphCheckpoint?
    suspend fun loadLatest(sessionId: String): GraphCheckpoint?
    suspend fun list(sessionId: String, limit: Int = 50): List<GraphCheckpoint>
    suspend fun delete(checkpointId: String)
    suspend fun deleteAll(sessionId: String)
}

/**
 * In-memory checkpoint store for testing and short-lived executions.
 */
class InMemoryGraphCheckpointStore(
    private val maxPerSession: Int = 100
) : GraphCheckpointStore {
    private val store = linkedMapOf<String, GraphCheckpoint>()
    private val bySession = linkedMapOf<String, MutableList<String>>()

    override suspend fun save(checkpoint: GraphCheckpoint) {
        store[checkpoint.checkpointId] = checkpoint
        val sessionList = bySession.getOrPut(checkpoint.sessionId) { mutableListOf() }
        sessionList += checkpoint.checkpointId
        if (sessionList.size > maxPerSession) {
            val removed = sessionList.removeFirst()
            store.remove(removed)
        }
    }

    override suspend fun load(checkpointId: String): GraphCheckpoint? = store[checkpointId]

    override suspend fun loadLatest(sessionId: String): GraphCheckpoint? {
        val lastId = bySession[sessionId]?.lastOrNull() ?: return null
        return store[lastId]
    }

    override suspend fun list(sessionId: String, limit: Int): List<GraphCheckpoint> {
        val ids = bySession[sessionId] ?: return emptyList()
        return ids.takeLast(limit).mapNotNull { store[it] }
    }

    override suspend fun delete(checkpointId: String) {
        val checkpoint = store.remove(checkpointId) ?: return
        bySession[checkpoint.sessionId]?.remove(checkpointId)
    }

    override suspend fun deleteAll(sessionId: String) {
        val ids = bySession.remove(sessionId) ?: return
        ids.forEach { store.remove(it) }
    }
}

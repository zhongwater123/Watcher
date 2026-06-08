package com.example.watcher.agentframework.graph

import java.util.UUID

/**
 * Handler function for a graph step node.
 * Receives mutable execution state and returns a routing decision.
 */
fun interface StepHandler {
    suspend fun execute(state: GraphExecutionState): StepResult
}

/**
 * A node in the agent execution graph.
 */
data class StepNode(
    val id: String,
    val name: String,
    val checkpointAfter: Boolean = false,
    val handler: StepHandler
)

/**
 * Result returned by a step handler to control graph traversal.
 */
sealed interface StepResult {
    /** Advance to the default next node. */
    data object Advance : StepResult

    /** Jump to a specific node by ID. */
    data class Branch(val targetNodeId: String, val reason: String = "") : StepResult

    /** Save checkpoint, then advance to default next node. */
    data object CheckpointAndAdvance : StepResult

    /** Save checkpoint and suspend execution until external signal. */
    data class Suspend(val reason: String, val suspendKind: SuspendKind = SuspendKind.Signal) : StepResult

    /** Execution complete (success or failure). */
    data class Terminal(val success: Boolean, val reason: String) : StepResult
}

enum class SuspendKind {
    Signal,
    Approval,
    Timer
}

/**
 * Definition of an agent execution graph.
 * Nodes are linked by defaultEdges (linear flow) and handlers can override via Branch.
 */
class StepGraph(
    val nodes: Map<String, StepNode>,
    val entryNodeId: String,
    val defaultEdges: Map<String, String>
) {
    init {
        require(entryNodeId in nodes) { "Entry node '$entryNodeId' not found in graph" }
        defaultEdges.forEach { (from, to) ->
            require(from in nodes) { "Edge source '$from' not found in graph" }
            require(to in nodes) { "Edge target '$to' not found in graph" }
        }
    }

    fun defaultNext(nodeId: String): String? = defaultEdges[nodeId]

    fun resolve(nodeId: String): StepNode? = nodes[nodeId]

    companion object {
        fun builder(entryNodeId: String): StepGraphBuilder = StepGraphBuilder(entryNodeId)
    }
}

class StepGraphBuilder(private val entryNodeId: String) {
    private val nodes = linkedMapOf<String, StepNode>()
    private val edges = linkedMapOf<String, String>()

    fun addNode(node: StepNode): StepGraphBuilder {
        nodes[node.id] = node
        return this
    }

    fun addNode(
        id: String,
        name: String,
        checkpointAfter: Boolean = false,
        handler: StepHandler
    ): StepGraphBuilder {
        nodes[id] = StepNode(id, name, checkpointAfter, handler)
        return this
    }

    fun edge(from: String, to: String): StepGraphBuilder {
        edges[from] = to
        return this
    }

    fun linearChain(vararg nodeIds: String): StepGraphBuilder {
        for (i in 0 until nodeIds.size - 1) {
            edges[nodeIds[i]] = nodeIds[i + 1]
        }
        return this
    }

    fun build(): StepGraph = StepGraph(nodes.toMap(), entryNodeId, edges.toMap())
}

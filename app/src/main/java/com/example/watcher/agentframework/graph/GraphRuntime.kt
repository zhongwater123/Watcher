package com.example.watcher.agentframework.graph

import com.example.watcher.agentframework.autonomy.AgentSignal
import com.example.watcher.agentframework.autonomy.AutonomousAgentConfig
import com.example.watcher.agentframework.autonomy.AutonomousAgentModules
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.gate.HumanGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Graph-based agent execution runtime.
 *
 * Replaces the linear while-loop with a directed graph of step nodes.
 * Supports checkpoint, resume from checkpoint, rollback, and suspend/resume for human gates.
 */
class GraphRuntime(
    private val graph: StepGraph,
    private val definition: AgentDefinition,
    private val config: AutonomousAgentConfig,
    private val modules: AutonomousAgentModules,
    private val gate: HumanGate,
    private val checkpointStore: GraphCheckpointStore = InMemoryGraphCheckpointStore(),
    private val checkpointFrequency: Int = 1,
    parentScope: CoroutineScope
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val mutex = Mutex()
    private val completion = CompletableDeferred<GraphRuntimeSnapshot>()
    private val resumeSignal = MutableSharedFlow<AgentSignal>(extraBufferCapacity = 32)

    private val _snapshot = MutableStateFlow(
        GraphRuntimeSnapshot(sessionId = UUID.randomUUID().toString())
    )
    private val _events = MutableSharedFlow<GraphEvent>(
        replay = 16,
        extraBufferCapacity = 128
    )

    private var job: Job? = null
    @Volatile
    private var stopRequested = false

    val sessionId: String get() = _snapshot.value.sessionId
    val snapshot: StateFlow<GraphRuntimeSnapshot> = _snapshot.asStateFlow()
    val events: SharedFlow<GraphEvent> = _events.asSharedFlow()

    /**
     * Start fresh execution from the graph's entry node.
     */
    suspend fun execute(sessionId: String = UUID.randomUUID().toString()): GraphRuntimeSnapshot {
        mutex.withLock {
            if (job?.isActive == true) return _snapshot.value
            stopRequested = false
            _snapshot.value = GraphRuntimeSnapshot(sessionId = sessionId)
            job = scope.launch(Dispatchers.Default) {
                runGraph(sessionId, startNodeId = graph.entryNodeId, fromCheckpoint = null)
            }
        }
        return awaitCompletion()
    }

    /**
     * Resume execution from a saved checkpoint.
     */
    suspend fun resume(checkpointId: String): GraphRuntimeSnapshot {
        val checkpoint = checkpointStore.load(checkpointId)
            ?: throw IllegalArgumentException("Checkpoint not found: $checkpointId")
        mutex.withLock {
            if (job?.isActive == true) return _snapshot.value
            stopRequested = false
            _snapshot.value = GraphRuntimeSnapshot(
                sessionId = checkpoint.sessionId,
                cycle = checkpoint.cycle,
                currentNodeId = checkpoint.currentNodeId,
                lastCheckpointId = checkpointId
            )
            emitEvent(GraphEvent.Resumed(checkpoint.sessionId, checkpointId))
            job = scope.launch(Dispatchers.Default) {
                runGraph(checkpoint.sessionId, startNodeId = checkpoint.currentNodeId, fromCheckpoint = checkpoint)
            }
        }
        return awaitCompletion()
    }

    /**
     * Rollback to a checkpoint and re-execute from that point.
     */
    suspend fun rollback(checkpointId: String): GraphRuntimeSnapshot {
        stop()
        return resume(checkpointId)
    }

    /**
     * Inject a signal to wake up a suspended graph.
     */
    suspend fun injectSignal(signal: AgentSignal) {
        resumeSignal.emit(signal)
    }

    /**
     * Request stop.
     */
    fun stop() {
        stopRequested = true
    }

    /**
     * Await execution completion.
     */
    suspend fun awaitCompletion(): GraphRuntimeSnapshot {
        job?.join()
        return if (completion.isCompleted) completion.await() else _snapshot.value
    }

    private suspend fun runGraph(
        sessionId: String,
        startNodeId: String,
        fromCheckpoint: GraphCheckpoint?
    ) {
        val state = GraphExecutionState(
            sessionId = sessionId,
            definition = definition,
            config = config,
            modules = modules,
            gate = gate
        )

        if (fromCheckpoint != null) {
            state.restoreFromCheckpoint(fromCheckpoint)
        }

        setLifecycle(sessionId, GraphLifecycleState.Running)
        state.startedAt = System.currentTimeMillis()
        var currentNodeId: String = startNodeId
        val completedInCycle = mutableListOf<String>()

        try {
            while (!stopRequested) {
                val node = graph.resolve(currentNodeId)
                if (node == null) {
                    finishWithError(sessionId, "Node not found: $currentNodeId")
                    return
                }

                // Check limits before executing
                val limitReason = state.checkLimits()
                if (limitReason != null) {
                    finish(sessionId, limitReason, success = false)
                    return
                }

                // Execute the node
                state.totalSteps++
                emitEvent(GraphEvent.StepStarted(sessionId, node.id, node.name, state.cycle))
                val stepStart = System.currentTimeMillis()

                val result = try {
                    node.handler.execute(state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.failureCount++
                    state.lastError = e.message ?: "Step execution failed"
                    emitEvent(GraphEvent.ErrorOccurred(sessionId, node.id, state.lastError!!))
                    if (state.failureCount >= config.maxFailures) {
                        finish(sessionId, GraphStopReason.Error, success = false)
                        return
                    }
                    // On recoverable error, advance to default next
                    StepResult.Advance
                }

                val stepDuration = System.currentTimeMillis() - stepStart
                emitEvent(GraphEvent.StepCompleted(sessionId, node.id, result, stepDuration))
                completedInCycle += node.id

                // Checkpoint if configured
                val shouldCheckpoint = node.checkpointAfter ||
                    result is StepResult.CheckpointAndAdvance ||
                    (checkpointFrequency > 0 && state.totalSteps % checkpointFrequency == 0)

                if (shouldCheckpoint) {
                    val nextId = resolveNextNode(currentNodeId, result)
                    val checkpoint = state.toCheckpoint(
                        currentNodeId = nextId ?: currentNodeId,
                        completedNodes = completedInCycle.toList()
                    )
                    checkpointStore.save(checkpoint)
                    updateSnapshot { it.copy(lastCheckpointId = checkpoint.checkpointId) }
                    emitEvent(GraphEvent.CheckpointSaved(sessionId, checkpoint.checkpointId, node.id, state.cycle))
                }

                // Route based on result
                when (result) {
                    is StepResult.Advance, is StepResult.CheckpointAndAdvance -> {
                        val next = graph.defaultNext(currentNodeId)
                        if (next == null) {
                            finish(sessionId, GraphStopReason.GoalAchieved, success = true)
                            return
                        }
                        currentNodeId = next
                    }

                    is StepResult.Branch -> {
                        if (graph.resolve(result.targetNodeId) == null) {
                            finishWithError(sessionId, "Branch target not found: ${result.targetNodeId}")
                            return
                        }
                        currentNodeId = result.targetNodeId
                    }

                    is StepResult.Suspend -> {
                        val checkpoint = state.toCheckpoint(currentNodeId, completedInCycle.toList()).copy(
                            suspendReason = result.reason,
                            suspendKind = result.suspendKind
                        )
                        checkpointStore.save(checkpoint)
                        updateSnapshot {
                            it.copy(
                                lifecycleState = GraphLifecycleState.Suspended,
                                suspendReason = result.reason,
                                lastCheckpointId = checkpoint.checkpointId,
                                currentNodeId = currentNodeId
                            )
                        }
                        emitEvent(GraphEvent.Suspended(sessionId, result.reason, result.suspendKind))

                        // Wait for resume signal
                        val signal = waitForSignal()
                        if (signal == null) {
                            // stop was requested while waiting
                            finish(sessionId, GraphStopReason.Cancelled, success = false)
                            return
                        }

                        // After resume, submit signal to communication hub and advance
                        modules.communicationHub.submit(sessionId, signal)
                        setLifecycle(sessionId, GraphLifecycleState.Running)
                        val next = graph.defaultNext(currentNodeId)
                        if (next == null) {
                            finish(sessionId, GraphStopReason.GoalAchieved, success = true)
                            return
                        }
                        currentNodeId = next
                    }

                    is StepResult.Terminal -> {
                        val reason = if (result.success) GraphStopReason.GoalAchieved else GraphStopReason.Error
                        finish(sessionId, reason, result.success)
                        return
                    }
                }

                // Track idle
                if (currentNodeId == graph.entryNodeId && currentNodeId != startNodeId) {
                    // Starting a new cycle
                    val wasIdle = state.perceptionFrame?.cleanedSignals?.isEmpty() != false &&
                        state.executionOutcome?.outputs?.isEmpty() != false
                    if (wasIdle) state.idleCount++ else state.idleCount = 0
                    state.beginNewCycle()
                    completedInCycle.clear()
                }

                updateSnapshot {
                    it.copy(
                        currentNodeId = currentNodeId,
                        cycle = state.cycle,
                        totalSteps = state.totalSteps,
                        idleCount = state.idleCount,
                        outputs = state.outputs.toList()
                    )
                }
            }

            // Stop requested
            finish(sessionId, GraphStopReason.Cancelled, success = false)

        } catch (_: CancellationException) {
            finish(sessionId, GraphStopReason.Cancelled, success = false)
        }
    }

    private fun resolveNextNode(currentNodeId: String, result: StepResult): String? {
        return when (result) {
            is StepResult.Advance, is StepResult.CheckpointAndAdvance -> graph.defaultNext(currentNodeId)
            is StepResult.Branch -> result.targetNodeId
            else -> null
        }
    }

    private suspend fun waitForSignal(): AgentSignal? {
        // Collect the first signal emitted, or null if stopped
        var received: AgentSignal? = null
        val collectJob = scope.launch {
            resumeSignal.collect { signal ->
                received = signal
                return@collect
            }
        }
        // Poll for stop or signal
        while (!stopRequested && received == null) {
            kotlinx.coroutines.delay(100)
        }
        collectJob.cancel()
        return received
    }

    private suspend fun finish(sessionId: String, reason: GraphStopReason, success: Boolean) {
        val state = if (success) GraphLifecycleState.Completed else {
            if (reason == GraphStopReason.Cancelled) GraphLifecycleState.Cancelled
            else GraphLifecycleState.Failed
        }
        updateSnapshot {
            it.copy(
                lifecycleState = state,
                stopReason = reason,
                updatedAt = System.currentTimeMillis()
            )
        }
        emitEvent(GraphEvent.LifecycleChanged(sessionId, state, reason))
        completeIfNeeded()
    }

    private suspend fun finishWithError(sessionId: String, message: String) {
        updateSnapshot {
            it.copy(
                lifecycleState = GraphLifecycleState.Failed,
                stopReason = GraphStopReason.Error,
                errorMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        }
        emitEvent(GraphEvent.ErrorOccurred(sessionId, null, message))
        completeIfNeeded()
    }

    private suspend fun setLifecycle(sessionId: String, state: GraphLifecycleState) {
        updateSnapshot { it.copy(lifecycleState = state, updatedAt = System.currentTimeMillis()) }
        emitEvent(GraphEvent.LifecycleChanged(sessionId, state))
    }

    private suspend fun updateSnapshot(transform: (GraphRuntimeSnapshot) -> GraphRuntimeSnapshot) {
        mutex.withLock {
            _snapshot.value = transform(_snapshot.value)
        }
    }

    private suspend fun emitEvent(event: GraphEvent) {
        _events.emit(event)
    }

    private fun completeIfNeeded() {
        if (!completion.isCompleted) {
            completion.complete(_snapshot.value)
        }
    }
}

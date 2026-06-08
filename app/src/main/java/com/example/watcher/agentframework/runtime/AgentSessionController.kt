package com.example.watcher.agentframework.runtime

import com.example.watcher.agentframework.core.AgentAction
import com.example.watcher.agentframework.core.AgentConversationItem
import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentEvent
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentMemoryWrite
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentRunConfig
import com.example.watcher.agentframework.core.AgentSessionSnapshot
import com.example.watcher.agentframework.core.AgentSessionStatus
import com.example.watcher.agentframework.core.AgentStopReason
import com.example.watcher.agentframework.core.AgentToolCall
import com.example.watcher.agentframework.core.AgentToolResult
import com.example.watcher.agentframework.core.AgentTurnRecord
import com.example.watcher.agentframework.core.isTerminal
import com.example.watcher.agentframework.knowledge.AgentKnowledgeSnapshot
import com.example.watcher.agentframework.knowledge.AgentKnowledgeStore
import com.example.watcher.agentframework.memory.AgentMemoryEntry
import com.example.watcher.agentframework.memory.AgentMemorySnapshot
import com.example.watcher.agentframework.memory.AgentMemoryStore
import com.example.watcher.agentframework.tools.AgentToolContext
import com.example.watcher.agentframework.tools.AgentToolRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock

@Deprecated(
    message = "Legacy runtime path. Use AgentFrameworkService with AutonomousAgentRuntime as the primary agent runtime entry point.",
    level = DeprecationLevel.WARNING
)
class AgentSessionController internal constructor(
    private val definition: AgentDefinition,
    private val brain: AgentBrain,
    private val config: AgentRunConfig,
    private val memoryStore: AgentMemoryStore,
    private val knowledgeStore: AgentKnowledgeStore,
    private val toolRegistry: AgentToolRegistry,
    parentScope: CoroutineScope,
    sessionId: String = UUID.randomUUID().toString(),
    initialMessages: List<AgentConversationItem> = emptyList()
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val mutex = Mutex()
    private val completion = CompletableDeferred<AgentSessionSnapshot>()
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 128)
    private val _snapshot = MutableStateFlow(
        AgentSessionSnapshot(
            sessionId = sessionId,
            agentId = definition.agentId,
            agentName = definition.name,
            goal = definition.goal,
            history = initialMessages.takeLast(config.maxHistoryItems)
        )
    )

    private var job: Job? = null

    val snapshot: StateFlow<AgentSessionSnapshot> = _snapshot.asStateFlow()
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    suspend fun start() {
        mutex.withLock {
            if (job != null) return
            job = scope.launch(Dispatchers.Default) {
                runLoop()
            }
        }
    }

    suspend fun submitMessage(
        role: AgentMessageRole,
        content: String,
        name: String? = null
    ) {
        mutateSnapshot { current ->
            current.copy(
                history = appendHistory(current.history, AgentConversationItem(role = role, content = content, name = name)),
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun stop() {
        job?.cancel(CancellationException("Agent session stopped"))
    }

    suspend fun awaitCompletion(): AgentSessionSnapshot {
        job?.join()
        return if (completion.isCompleted) completion.await() else snapshot.value
    }

    private suspend fun runLoop() {
        val startedAt = System.currentTimeMillis()
        mutateSnapshot { current ->
            current.copy(
                status = AgentSessionStatus.Running,
                startedAt = startedAt,
                updatedAt = startedAt
            )
        }
        _events.emit(AgentEvent.SessionStarted(snapshot.value.sessionId, definition.agentId))

        try {
            while (true) {
                val current = snapshot.value
                if (current.stepCount >= config.maxSteps) {
                    finishSession(AgentSessionStatus.Stopped, AgentStopReason.StepLimitReached)
                    return
                }
                if (System.currentTimeMillis() - startedAt >= config.maxRuntimeMillis) {
                    finishSession(AgentSessionStatus.Stopped, AgentStopReason.RuntimeLimitReached)
                    return
                }

                val step = current.stepCount + 1
                _events.emit(AgentEvent.StepStarted(current.sessionId, step))
                val memory = loadMemory(current.sessionId)

                val decision = try {
                    brain.decide(
                        AgentBrainRequest(
                            definition = definition,
                            config = config,
                            session = current,
                            memory = memory,
                            knowledge = loadKnowledge(current.agentId),
                            recentInputs = emptyList(),
                            availableTools = toolRegistry.definitions()
                        )
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val shouldContinue = handleDecisionError(step, error)
                    if (shouldContinue) {
                        continue
                    }
                    return
                }

                _events.emit(AgentEvent.DecisionProduced(current.sessionId, step, decision))
                persistMemories(current.sessionId, decision.memoryWrites)
                if (!decision.reply.isNullOrBlank()) {
                    mutateSnapshot { snapshotState ->
                        snapshotState.copy(
                            history = appendHistory(
                                snapshotState.history,
                                AgentConversationItem(
                                    role = AgentMessageRole.Assistant,
                                    content = decision.reply
                                )
                            ),
                            lastReply = decision.reply,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    _events.emit(AgentEvent.ReplyGenerated(current.sessionId, step, decision.reply))
                }

                when (val action = decision.action) {
                    AgentAction.Continue -> {
                        recordTurn(step, decision)
                        resetFailuresAndIdle(step)
                    }

                    is AgentAction.UseTools -> {
                        val calls = action.calls.take(config.maxToolCallsPerStep)
                        val results = executeTools(step, calls)
                        recordTurn(step, decision, calls, results)
                        resetFailuresAndIdle(step)
                    }

                    is AgentAction.Wait -> {
                        recordTurn(step, decision)
                        val newIdleTurns = snapshot.value.idleTurns + 1
                        if (newIdleTurns >= config.maxIdleTurns) {
                            mutateSnapshot { state ->
                                state.copy(
                                    stepCount = step,
                                    idleTurns = newIdleTurns,
                                    updatedAt = System.currentTimeMillis()
                                )
                            }
                            finishSession(AgentSessionStatus.Stopped, AgentStopReason.IdleLimitReached)
                            return
                        }
                        val waitMillis = normalizeWaitMillis(action.resumeAfterMillis)
                        mutateSnapshot { state ->
                            state.copy(
                                stepCount = step,
                                status = AgentSessionStatus.Waiting,
                                idleTurns = newIdleTurns,
                                consecutiveFailures = 0,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        _events.emit(
                            AgentEvent.Waiting(
                                sessionId = current.sessionId,
                                step = step,
                                reason = action.reason,
                                resumeAfterMillis = waitMillis
                            )
                        )
                        delay(waitMillis)
                        mutateSnapshot { state ->
                            state.copy(
                                status = AgentSessionStatus.Running,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    }

                    is AgentAction.RequestApproval -> {
                        // Legacy session controller does not support approval gates; treat as wait.
                        recordTurn(step, decision)
                        resetFailuresAndIdle(step)
                    }

                    is AgentAction.Finish -> {
                        recordTurn(step, decision)
                        mutateSnapshot { state ->
                            state.copy(
                                stepCount = step,
                                idleTurns = 0,
                                consecutiveFailures = 0,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        finishSession(
                            status = if (action.success) AgentSessionStatus.Completed else AgentSessionStatus.Stopped,
                            stopReason = if (action.success) AgentStopReason.GoalAchieved else AgentStopReason.StoppedByAgent
                        )
                        return
                    }
                }
            }
        } catch (_: CancellationException) {
            if (!snapshot.value.isTerminal) {
                finishSession(AgentSessionStatus.Cancelled, AgentStopReason.Cancelled)
            }
        }
    }

    private suspend fun resetFailuresAndIdle(step: Int) {
        mutateSnapshot { state ->
            state.copy(
                stepCount = step,
                idleTurns = 0,
                consecutiveFailures = 0,
                status = AgentSessionStatus.Running,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun executeTools(
        step: Int,
        calls: List<AgentToolCall>
    ): List<AgentToolResult> {
        val session = snapshot.value
        val context = AgentToolContext(
            definition = definition,
            session = session,
            memoryStore = memoryStore,
            knowledgeStore = knowledgeStore
        )
        val results = mutableListOf<AgentToolResult>()
        for (call in calls) {
            _events.emit(AgentEvent.ToolCallStarted(session.sessionId, step, call))
            val result = try {
                withTimeout(config.toolTimeoutMillis) {
                    toolRegistry.execute(call, context)
                }
            } catch (_: TimeoutCancellationException) {
                AgentToolResult(
                    callId = call.id,
                    toolName = call.name,
                    success = false,
                    error = "Tool execution timed out after ${config.toolTimeoutMillis}ms"
                )
            }
            results += result
            val content = if (result.success) {
                "tool=${result.toolName} output=${result.output}"
            } else {
                "tool=${result.toolName} error=${result.error}"
            }
            mutateSnapshot { state ->
                state.copy(
                    history = appendHistory(
                        state.history,
                        AgentConversationItem(
                            role = AgentMessageRole.Tool,
                            content = content,
                            name = result.toolName
                        )
                    ),
                    updatedAt = System.currentTimeMillis()
                )
            }
            _events.emit(AgentEvent.ToolCallCompleted(session.sessionId, step, result))
        }
        return results
    }

    private suspend fun persistMemories(
        sessionId: String,
        writes: List<AgentMemoryWrite>
    ) {
        writes.forEach { write ->
            memoryStore.write(
                sessionId = sessionId,
                entry = AgentMemoryEntry(
                    scope = write.scope,
                    content = write.content,
                    tags = write.tags
                )
            )
        }
    }

    private suspend fun loadMemory(sessionId: String): AgentMemorySnapshot {
        val working = memoryStore.read(sessionId, AgentMemoryScope.Working, limit = 20)
        val episodic = memoryStore.read(sessionId, AgentMemoryScope.Episodic, limit = 20)
        return AgentMemorySnapshot(working = working, episodic = episodic)
    }

    private suspend fun loadKnowledge(agentId: String): AgentKnowledgeSnapshot {
        return AgentKnowledgeSnapshot(entries = knowledgeStore.read(agentId, limit = 20))
    }

    private suspend fun handleDecisionError(step: Int, error: Exception): Boolean {
        val message = error.message ?: "Agent decision failed"
        val updated = mutateSnapshotAndGet { state ->
            state.copy(
                consecutiveFailures = state.consecutiveFailures + 1,
                failureMessage = message,
                history = appendHistory(
                    state.history,
                    AgentConversationItem(
                        role = AgentMessageRole.System,
                        content = "decision_error=$message"
                    )
                ),
                updatedAt = System.currentTimeMillis()
            )
        }
        _events.emit(AgentEvent.SessionErrored(updated.sessionId, step, message))
        return if (updated.consecutiveFailures >= config.maxConsecutiveFailures) {
            finishSession(
                status = AgentSessionStatus.Failed,
                stopReason = AgentStopReason.ConsecutiveFailures,
                failureMessage = message
            )
            false
        } else {
            true
        }
    }

    private suspend fun recordTurn(
        step: Int,
        decision: AgentDecision,
        toolCalls: List<AgentToolCall> = emptyList(),
        toolResults: List<AgentToolResult> = emptyList()
    ) {
        val timestamp = System.currentTimeMillis()
        mutateSnapshot { state ->
            state.copy(
                turns = state.turns + AgentTurnRecord(
                    step = step,
                    decision = decision,
                    toolCalls = toolCalls,
                    toolResults = toolResults,
                    startedAt = timestamp,
                    completedAt = System.currentTimeMillis()
                ),
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun finishSession(
        status: AgentSessionStatus,
        stopReason: AgentStopReason,
        failureMessage: String? = null
    ) {
        val finalSnapshot = mutateSnapshotAndGet { state ->
            state.copy(
                status = status,
                stopReason = stopReason,
                failureMessage = failureMessage ?: state.failureMessage,
                updatedAt = System.currentTimeMillis()
            )
        }
        _events.emit(
            AgentEvent.SessionFinished(
                sessionId = finalSnapshot.sessionId,
                status = status,
                stopReason = stopReason
            )
        )
        if (!completion.isCompleted) {
            completion.complete(finalSnapshot)
        }
    }

    private fun normalizeWaitMillis(requested: Long): Long {
        if (requested <= 0L) return config.defaultWaitMillis
        return requested.coerceAtMost(config.maxWaitMillis)
    }

    private suspend fun mutateSnapshot(transform: (AgentSessionSnapshot) -> AgentSessionSnapshot) {
        mutex.withLock {
            _snapshot.value = transform(_snapshot.value)
        }
    }

    private suspend fun mutateSnapshotAndGet(
        transform: (AgentSessionSnapshot) -> AgentSessionSnapshot
    ): AgentSessionSnapshot {
        return mutex.withLock {
            transform(_snapshot.value).also { _snapshot.value = it }
        }
    }

    private fun appendHistory(
        history: List<AgentConversationItem>,
        item: AgentConversationItem
    ): List<AgentConversationItem> {
        return (history + item).takeLast(config.maxHistoryItems)
    }
}

package com.example.watcher.agentframework.runtime

import com.example.watcher.agentframework.core.AgentAction
import com.example.watcher.agentframework.core.AgentConversationItem
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentEvent
import com.example.watcher.agentframework.core.AgentInput
import com.example.watcher.agentframework.core.AgentInputKind
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentMemoryWrite
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentRunConfig
import com.example.watcher.agentframework.core.AgentSessionSnapshot
import com.example.watcher.agentframework.core.AgentSessionStatus
import com.example.watcher.agentframework.core.AgentToolResult
import com.example.watcher.agentframework.core.ManagedAgentLifecycleState
import com.example.watcher.agentframework.core.ManagedAgentSnapshot
import com.example.watcher.agentframework.knowledge.AgentKnowledgeEntry
import com.example.watcher.agentframework.knowledge.AgentKnowledgeSnapshot
import com.example.watcher.agentframework.knowledge.AgentKnowledgeStore
import com.example.watcher.agentframework.memory.AgentMemoryEntry
import com.example.watcher.agentframework.memory.AgentMemorySnapshot
import com.example.watcher.agentframework.memory.AgentMemoryStore
import com.example.watcher.agentframework.tools.AgentToolContext
import com.example.watcher.agentframework.tools.AgentToolExecutor
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Deprecated(
    message = "Legacy runtime path. Use AgentFrameworkService with AutonomousAgentRuntime as the primary agent runtime entry point.",
    level = DeprecationLevel.WARNING
)
class ManagedAgent internal constructor(
    definition: AgentDefinition,
    config: AgentRunConfig,
    private val brain: AgentBrain,
    private val memoryStore: AgentMemoryStore,
    private val knowledgeStore: AgentKnowledgeStore,
    private val toolExecutor: AgentToolExecutor,
    private val outputPort: AgentOutputPort,
    private val evolutionEngine: AgentEvolutionEngine,
    parentScope: CoroutineScope,
    sessionId: String = UUID.randomUUID().toString()
) : AgentInputPort {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job())
    private val mutex = Mutex()
    private val completion = CompletableDeferred<ManagedAgentSnapshot>()
    private val inputs = Channel<AgentInput>(128)
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 128)
    private val _snapshot = MutableStateFlow(
        ManagedAgentSnapshot(
            agentId = definition.agentId,
            sessionId = sessionId,
            definition = definition,
            config = config
        )
    )

    private var job: Job? = null
    @Volatile
    private var stopRequested = false

    val snapshot: StateFlow<ManagedAgentSnapshot> = _snapshot.asStateFlow()
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    suspend fun start() {
        mutex.withLock {
            if (job?.isActive == true) return
            stopRequested = false
            job = scope.launch(Dispatchers.Default) {
                runLoop()
            }
        }
    }

    override suspend fun submit(input: AgentInput) {
        inputs.send(input)
        mutateSnapshot { state ->
            state.copy(
                pendingInputs = state.pendingInputs + input,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun updateDefinition(definition: AgentDefinition) {
        mutateSnapshot { state ->
            state.copy(
                definition = definition,
                activeGoal = definition.goal,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun updateConfig(config: AgentRunConfig) {
        mutateSnapshot { state ->
            state.copy(
                config = config,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun writeMemory(write: AgentMemoryWrite) {
        memoryStore.write(
            snapshot.value.sessionId,
            AgentMemoryEntry(
                scope = write.scope,
                content = write.content,
                tags = write.tags
            )
        )
    }

    suspend fun readMemory(
        scope: AgentMemoryScope? = null,
        limit: Int = 20
    ) = memoryStore.read(snapshot.value.sessionId, scope, limit)

    suspend fun writeKnowledge(entry: AgentKnowledgeEntry) {
        knowledgeStore.write(snapshot.value.agentId, entry)
    }

    suspend fun readKnowledge(limit: Int = 20) = knowledgeStore.read(snapshot.value.agentId, limit)

    suspend fun queryKnowledge(
        query: String,
        tags: Set<String> = emptySet(),
        limit: Int = 10
    ) = knowledgeStore.query(snapshot.value.agentId, query, tags, limit)

    fun stop() {
        stopRequested = true
    }

    suspend fun awaitStopped(): ManagedAgentSnapshot {
        job?.join()
        return if (completion.isCompleted) completion.await() else snapshot.value
    }

    private suspend fun runLoop() {
        setLifecycle(ManagedAgentLifecycleState.Idle)
        try {
            while (!stopRequested) {
                val current = snapshot.value
                val batch = awaitInputs(current.config)
                val activeGoal = batch.lastOrNull { it.kind == AgentInputKind.GoalUpdate }?.content ?: current.activeGoal
                val historyItems = batch.map { input ->
                    AgentConversationItem(
                        role = when (input.kind) {
                            AgentInputKind.UserMessage -> AgentMessageRole.User
                            AgentInputKind.Observation -> AgentMessageRole.Observation
                            AgentInputKind.SystemDirective -> AgentMessageRole.System
                            AgentInputKind.GoalUpdate -> AgentMessageRole.System
                        },
                        content = input.content
                    )
                }
                if (batch.isNotEmpty()) {
                    mutateSnapshot { state ->
                        state.copy(
                            activeGoal = activeGoal,
                            pendingInputs = emptyList(),
                            lifecycleState = ManagedAgentLifecycleState.Running,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                } else {
                    setLifecycle(ManagedAgentLifecycleState.Waiting)
                }

                val workingMemory = loadMemory()
                val knowledge = loadKnowledge()
                val session = AgentSessionSnapshot(
                    sessionId = current.sessionId,
                    agentId = current.agentId,
                    agentName = current.definition.name,
                    goal = activeGoal,
                    status = when (snapshot.value.lifecycleState) {
                        ManagedAgentLifecycleState.Waiting -> AgentSessionStatus.Waiting
                        ManagedAgentLifecycleState.Failed -> AgentSessionStatus.Failed
                        ManagedAgentLifecycleState.Stopped -> AgentSessionStatus.Stopped
                        else -> AgentSessionStatus.Running
                    },
                    stepCount = current.cycleCount,
                    history = historyItems
                )

                val decision = try {
                    brain.decide(
                        AgentBrainRequest(
                            definition = current.definition.copy(goal = activeGoal),
                            config = current.config,
                            session = session,
                            memory = workingMemory,
                            knowledge = knowledge,
                            recentInputs = batch,
                            availableTools = toolExecutor.definitions()
                        )
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val failures = current.consecutiveFailures + 1
                    mutateSnapshot { state ->
                        state.copy(
                            consecutiveFailures = failures,
                            lifecycleState = if (failures >= state.config.maxConsecutiveFailures) {
                                ManagedAgentLifecycleState.Failed
                            } else {
                                ManagedAgentLifecycleState.Idle
                            },
                            errorMessage = error.message ?: "Agent decision failed",
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    if (failures >= snapshot.value.config.maxConsecutiveFailures) {
                        break
                    }
                    delay(current.config.defaultWaitMillis)
                    continue
                }

                val toolResults = executeAction(decision, session, current.definition, current.config)
                persistMemories(current.sessionId, decision.memoryWrites)
                if (!decision.reply.isNullOrBlank()) {
                    outputPort.publish(AgentOutput(content = decision.reply))
                }
                applyEvolution(decision, current, workingMemory, knowledge)

                mutateSnapshot { state ->
                    state.copy(
                        lifecycleState = when (decision.action) {
                            is AgentAction.Wait -> ManagedAgentLifecycleState.Waiting
                            else -> ManagedAgentLifecycleState.Idle
                        },
                        activeGoal = if (decision.action is AgentAction.Finish) {
                            state.definition.goal
                        } else {
                            activeGoal
                        },
                        cycleCount = state.cycleCount + 1,
                        consecutiveFailures = 0,
                        lastReply = decision.reply,
                        lastDecision = decision,
                        errorMessage = toolResults.firstOrNull { !it.success }?.error,
                        updatedAt = System.currentTimeMillis()
                    )
                }

                if (decision.action is AgentAction.Wait) {
                    delay((decision.action as AgentAction.Wait).resumeAfterMillis.takeIf { it > 0L }
                        ?: current.config.defaultWaitMillis)
                } else {
                    delay(current.config.defaultWaitMillis)
                }
            }
        } catch (_: CancellationException) {
            // controlled stop
        } finally {
            setLifecycle(
                if (snapshot.value.lifecycleState == ManagedAgentLifecycleState.Failed) {
                    ManagedAgentLifecycleState.Failed
                } else {
                    ManagedAgentLifecycleState.Stopped
                }
            )
            if (!completion.isCompleted) {
                completion.complete(snapshot.value)
            }
        }
    }

    private suspend fun awaitInputs(config: AgentRunConfig): List<AgentInput> {
        val first = select<AgentInput?> {
            inputs.onReceive { it }
            onTimeout(config.defaultWaitMillis) { null }
        }
        val batch = mutableListOf<AgentInput>()
        if (first != null) {
            batch += first
        }
        while (true) {
            val extra = inputs.tryReceive().getOrNull() ?: break
            batch += extra
        }
        return batch
    }

    private suspend fun executeAction(
        decision: com.example.watcher.agentframework.core.AgentDecision,
        session: AgentSessionSnapshot,
        definition: AgentDefinition,
        config: AgentRunConfig
    ): List<AgentToolResult> {
        val action = decision.action
        if (action !is AgentAction.UseTools) {
            return emptyList()
        }
        val results = mutableListOf<AgentToolResult>()
        val context = AgentToolContext(
            definition = definition,
            session = session,
            memoryStore = memoryStore,
            knowledgeStore = knowledgeStore
        )
        for (call in action.calls.take(config.maxToolCallsPerStep)) {
            results += toolExecutor.execute(call, context)
        }
        return results
    }

    private suspend fun applyEvolution(
        decision: com.example.watcher.agentframework.core.AgentDecision,
        current: ManagedAgentSnapshot,
        memory: AgentMemorySnapshot,
        knowledge: AgentKnowledgeSnapshot
    ) {
        val result = evolutionEngine.evolve(
            AgentEvolutionRequest(
                definition = current.definition,
                config = current.config,
                snapshot = current,
                latestDecision = decision,
                memory = memory,
                knowledge = knowledge
            )
        ) ?: return
        result.updatedDefinition?.let { updateDefinition(it) }
        result.updatedConfig?.let { updateConfig(it) }
        result.memoryWrites.forEach { writeMemory(it) }
        result.knowledgeWrites.forEach { writeKnowledge(it) }
    }

    private suspend fun persistMemories(sessionId: String, writes: List<AgentMemoryWrite>) {
        for (write in writes) {
            memoryStore.write(
                sessionId,
                AgentMemoryEntry(
                    scope = write.scope,
                    content = write.content,
                    tags = write.tags
                )
            )
        }
    }

    private suspend fun loadMemory(): AgentMemorySnapshot {
        return AgentMemorySnapshot(
            working = memoryStore.read(snapshot.value.sessionId, AgentMemoryScope.Working, limit = 20),
            episodic = memoryStore.read(snapshot.value.sessionId, AgentMemoryScope.Episodic, limit = 20)
        )
    }

    private suspend fun loadKnowledge(): AgentKnowledgeSnapshot {
        return AgentKnowledgeSnapshot(entries = knowledgeStore.read(snapshot.value.agentId, limit = 20))
    }

    private suspend fun setLifecycle(state: ManagedAgentLifecycleState) {
        mutateSnapshot { current ->
            current.copy(
                lifecycleState = state,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun mutateSnapshot(transform: (ManagedAgentSnapshot) -> ManagedAgentSnapshot) {
        mutex.withLock {
            _snapshot.value = transform(_snapshot.value)
        }
    }
}

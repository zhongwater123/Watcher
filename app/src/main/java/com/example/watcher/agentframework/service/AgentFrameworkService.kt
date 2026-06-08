package com.example.watcher.agentframework.service

import com.example.watcher.agentframework.autonomy.AgentSignal
import com.example.watcher.agentframework.autonomy.AutonomousAgentEvent
import com.example.watcher.agentframework.autonomy.AutonomousAgentRuntime
import com.example.watcher.agentframework.autonomy.AutonomousLifecycleState
import com.example.watcher.agentframework.autonomy.AutonomousStopReason
import com.example.watcher.agentframework.autonomy.CommunicationHub
import com.example.watcher.agentframework.autonomy.DefaultStructuredMemoryManagerFactory
import com.example.watcher.agentframework.autonomy.FileBackedStructuredMemoryStore
import com.example.watcher.agentframework.autonomy.InMemoryCommunicationHub
import com.example.watcher.agentframework.autonomy.InMemoryStructuredMemoryStore
import com.example.watcher.agentframework.autonomy.StructuredMemoryManagerFactory
import com.example.watcher.agentframework.autonomy.StructuredMemoryEntry
import com.example.watcher.agentframework.autonomy.StructuredMemoryStore
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentRunConfig
import com.example.watcher.agentframework.core.AgentSessionSnapshot
import com.example.watcher.agentframework.core.AgentSessionStatus
import com.example.watcher.agentframework.core.AgentStopReason
import com.example.watcher.agentframework.core.AgentTurnRecord
import com.example.watcher.agentframework.knowledge.AgentKnowledgeEntry
import com.example.watcher.agentframework.knowledge.AgentKnowledgeStore
import com.example.watcher.agentframework.knowledge.InMemoryAgentKnowledgeStore
import com.example.watcher.agentframework.memory.AgentMemoryEntry
import com.example.watcher.agentframework.memory.AgentMemoryStore
import com.example.watcher.agentframework.memory.InMemoryAgentMemoryStore
import com.example.watcher.agentframework.runtime.AgentBrain
import com.example.watcher.agentframework.tools.AgentTool
import com.example.watcher.agentframework.tools.AgentToolRegistry
import com.example.watcher.agentframework.tools.registerDefaultContextTools
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AgentFrameworkService(
    private val profileStore: AgentProfileStore = InMemoryAgentProfileStore(),
    private val memoryStore: AgentMemoryStore = InMemoryAgentMemoryStore(),
    private val knowledgeStore: AgentKnowledgeStore = InMemoryAgentKnowledgeStore(),
    private val invocationStore: AgentInvocationStore = InMemoryAgentInvocationStore(),
    private val runtimeRecordStore: AutonomousRuntimeRecordStore = InMemoryAutonomousRuntimeRecordStore(),
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry(),
    private val evolutionStrategy: AgentProfileEvolutionStrategy = HeuristicAgentProfileEvolutionStrategy(),
    private val structuredMemoryStore: StructuredMemoryStore = InMemoryStructuredMemoryStore(),
    private val structuredMemoryManagerFactory: StructuredMemoryManagerFactory =
        DefaultStructuredMemoryManagerFactory(),
    private val communicationHub: CommunicationHub = InMemoryCommunicationHub(),
    private val modulesFactory: AutonomousModulesFactory = DefaultAutonomousModulesFactory(),
    private val recoveryPolicy: AgentRuntimeRecoveryPolicy = CancelRunningExecutionsRecoveryPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxRuntimeEvents: Int = 256,
    val humanGate: com.example.watcher.agentframework.gate.HumanGate =
        com.example.watcher.agentframework.gate.SuspendingApprovalGate()
) {
    private val mutex = Mutex()
    @Volatile
    private var closed = false
    private val registrations = linkedMapOf<String, AgentRegistration>()
    private val brainFactories = linkedMapOf<String, AgentBrainFactory>()
    private val autonomousRuntimes = linkedMapOf<String, AutonomousRuntimeHandle>()
    private val autonomousMemoryManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        structuredMemoryManagerFactory.create(structuredMemoryStore)
    }

    private fun checkNotClosed() {
        check(!closed) { "AgentFrameworkService has been shut down" }
    }

    init {
        toolRegistry.registerDefaultContextTools()
    }

    companion object {
        fun createPersistent(
            rootDir: File,
            toolRegistry: AgentToolRegistry = AgentToolRegistry(),
            brainFactories: List<AgentBrainFactory> = emptyList(),
            evolutionStrategy: AgentProfileEvolutionStrategy = HeuristicAgentProfileEvolutionStrategy(),
            structuredMemoryManagerFactory: StructuredMemoryManagerFactory =
                DefaultStructuredMemoryManagerFactory(),
            communicationHub: CommunicationHub = InMemoryCommunicationHub(),
            modulesFactory: AutonomousModulesFactory = DefaultAutonomousModulesFactory(),
            recoveryPolicy: AgentRuntimeRecoveryPolicy = CancelRunningExecutionsRecoveryPolicy(),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): AgentFrameworkService {
            val storage = AgentFrameworkStorage.createPersistent(rootDir)
            val service = AgentFrameworkService(
                profileStore = storage.profileStore,
                memoryStore = storage.memoryStore,
                knowledgeStore = storage.knowledgeStore,
                invocationStore = storage.invocationStore,
                runtimeRecordStore = storage.runtimeRecordStore,
                toolRegistry = toolRegistry,
                evolutionStrategy = evolutionStrategy,
                structuredMemoryStore = FileBackedStructuredMemoryStore(
                    File(File(rootDir, "agentframework"), "structured-memory")
                ),
                structuredMemoryManagerFactory = structuredMemoryManagerFactory,
                communicationHub = communicationHub,
                modulesFactory = modulesFactory,
                recoveryPolicy = recoveryPolicy,
                scope = scope
            )
            service.registerBrainFactories(brainFactories)
            runBlocking {
                service.reconcilePersistentExecutionState()
            }
            return service
        }

        fun builder(): AgentFrameworkBuilder = AgentFrameworkBuilder()
    }

    fun registerTool(tool: AgentTool): AgentFrameworkService {
        toolRegistry.register(tool)
        return this
    }

    fun registerBrainFactory(factory: AgentBrainFactory): AgentFrameworkService {
        runBlocking {
            mutex.withLock {
                brainFactories[factory.factoryId] = factory
            }
        }
        return this
    }

    fun registerBrainFactories(factories: Iterable<AgentBrainFactory>): AgentFrameworkService {
        runBlocking {
            mutex.withLock {
                factories.forEach { factory ->
                    brainFactories[factory.factoryId] = factory
                }
            }
        }
        return this
    }

    suspend fun registerAgent(registration: AgentRegistration): RegisteredAgentProfile {
        checkNotClosed()
        val profile = RegisteredAgentProfile(
            definition = registration.definition,
            brainFactoryId = registration.brainFactoryId,
            config = registration.config,
            tags = registration.tags,
            metadata = registration.metadata
        )
        mutex.withLock {
            registrations[registration.definition.agentId] = registration
            val existingFactory = brainFactories[registration.brainFactoryId]
            when {
                existingFactory != null -> Unit
                registration.brain != null -> {
                    brainFactories[registration.brainFactoryId] =
                        StaticAgentBrainFactory(registration.brainFactoryId, registration.brain)
                }

                else -> {
                    throw IllegalArgumentException(
                        "No brain factory registered for ${registration.definition.agentId} " +
                            "(${registration.brainFactoryId})"
                    )
                }
            }
        }
        profileStore.upsert(profile)
        return profile
    }

    suspend fun unregisterAgent(agentId: String) {
        mutex.withLock {
            registrations.remove(agentId)
        }
        profileStore.remove(agentId)
    }

    suspend fun listAgents(): List<RegisteredAgentProfile> = profileStore.list()

    suspend fun getAgentProfile(agentId: String): RegisteredAgentProfile? = profileStore.get(agentId)

    suspend fun listAgentRestorationStatus(): List<AgentRestorationStatus> {
        return profileStore.list().map { profile ->
            val registered = mutex.withLock { brainFactories.containsKey(profile.brainFactoryId) }
            AgentRestorationStatus(
                agentId = profile.agentId,
                brainFactoryId = profile.brainFactoryId,
                restorable = registered,
                reason = if (registered) {
                    null
                } else {
                    "Missing registered brain factory: ${profile.brainFactoryId}"
                }
            )
        }
    }

    suspend fun readInvocationMemory(
        invocationId: String,
        scope: AgentMemoryScope? = null,
        limit: Int = 20
    ): List<AgentMemoryEntry> {
        val sessionId = resolveSessionId(invocationId)
            ?: throw IllegalArgumentException("Unknown invocation: $invocationId")
        return memoryStore.read(sessionId, scope, limit)
    }

    suspend fun writeInvocationMemory(
        invocationId: String,
        scope: AgentMemoryScope,
        content: String,
        tags: Set<String> = emptySet()
    ): AgentMemoryEntry {
        val sessionId = resolveSessionId(invocationId)
            ?: throw IllegalArgumentException("Unknown invocation: $invocationId")
        val entry = AgentMemoryEntry(
            scope = scope,
            content = content,
            tags = tags
        )
        memoryStore.write(sessionId, entry)
        return entry
    }

    suspend fun clearInvocationMemory(invocationId: String) {
        val sessionId = resolveSessionId(invocationId)
            ?: throw IllegalArgumentException("Unknown invocation: $invocationId")
        memoryStore.clear(sessionId)
    }

    suspend fun readAgentKnowledge(
        agentId: String,
        limit: Int = 20
    ): List<AgentKnowledgeEntry> {
        ensureKnownAgent(agentId)
        return knowledgeStore.read(agentId, limit)
    }

    suspend fun queryAgentKnowledge(
        agentId: String,
        query: String,
        tags: Set<String> = emptySet(),
        limit: Int = 10
    ): List<AgentKnowledgeEntry> {
        ensureKnownAgent(agentId)
        return knowledgeStore.query(agentId, query, tags, limit)
    }

    suspend fun writeAgentKnowledge(
        agentId: String,
        content: String,
        tags: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap()
    ): AgentKnowledgeEntry {
        ensureKnownAgent(agentId)
        val entry = AgentKnowledgeEntry(
            content = content,
            tags = tags,
            metadata = metadata
        )
        knowledgeStore.write(agentId, entry)
        return entry
    }

    suspend fun startAutonomousAgent(
        request: AutonomousAgentStartRequest
    ): AutonomousAgentRuntimeRecord {
        checkNotClosed()
        val profile = profileStore.get(request.agentId)
            ?: throw IllegalArgumentException("Missing agent profile: ${request.agentId}")
        val brain = resolveBrain(profile)
        val toolExecutor = toolRegistry.filtered(request.allowedToolNames)
        val runtimeId = UUID.randomUUID().toString()
        val runtime = AutonomousAgentRuntime(
            definition = profile.definition,
            config = profile.config.toAutonomousConfig(),
            modules = modulesFactory.create(
                profile = profile,
                brain = brain,
                toolExecutor = toolExecutor,
                memoryStore = memoryStore,
                knowledgeStore = knowledgeStore,
                memoryManager = autonomousMemoryManager,
                communicationHub = communicationHub
            ),
            parentScope = scope,
            sessionId = runtimeId,
            useGraphRuntime = profile.config.useGraphRuntime,
            humanGate = humanGate
        )
        val handle = AutonomousRuntimeHandle(
            runtime = runtime,
            record = AutonomousAgentRuntimeRecord(
                runtimeId = runtimeId,
                agentId = request.agentId
            )
        )
        mutex.withLock {
            autonomousRuntimes[runtimeId] = handle
        }
        runtimeRecordStore.upsert(handle.record)
        preloadContext(runtimeId, request.agentId, request.preloadMemory, request.preloadKnowledge)
        val collectorJob = scope.launch {
            runtime.events.collect { event ->
                appendAutonomousEvent(runtimeId, event)
            }
        }
        runtime.start()
        val completionJob = scope.launch {
            completeAutonomousRuntime(runtimeId)
        }
        setAutonomousJobs(runtimeId, collectorJob, completionJob)
        request.initialSignals.forEach { submitAutonomousSignal(runtimeId, it) }
        return getAutonomousRuntime(runtimeId) ?: handle.toRecord()
    }

    suspend fun submitAutonomousSignal(
        runtimeId: String,
        signal: AgentSignalSeed
    ): AutonomousAgentRuntimeRecord {
        checkNotClosed()
        val handle = mutex.withLock { autonomousRuntimes[runtimeId] }
            ?: throw IllegalArgumentException("Unknown autonomous runtime: $runtimeId")
        val runtimeSignal = AgentSignal(
            channel = signal.channel,
            content = signal.content,
            metadata = signal.metadata
        )
        handle.runtime.submitSignal(runtimeSignal)
        mutex.withLock {
            val current = autonomousRuntimes[runtimeId] ?: return@withLock
            autonomousRuntimes[runtimeId] = current.copy(
                record = current.record.copy(
                    submittedSignals = current.record.submittedSignals + runtimeSignal,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val updated = getAutonomousRuntime(runtimeId)
            ?: throw IllegalStateException("Failed to update autonomous runtime: $runtimeId")
        runtimeRecordStore.upsert(updated)
        return updated
    }

    suspend fun getAutonomousRuntime(runtimeId: String): AutonomousAgentRuntimeRecord? {
        val active = mutex.withLock {
            autonomousRuntimes[runtimeId]?.toRecord()
        }
        if (active != null) {
            runtimeRecordStore.upsert(active)
        }
        return active ?: runtimeRecordStore.get(runtimeId)
    }

    suspend fun listAutonomousRuntimes(agentId: String? = null): List<AutonomousAgentRuntimeRecord> {
        val stored = runtimeRecordStore.list().associateBy { it.runtimeId }.toMutableMap()
        val active = mutex.withLock { autonomousRuntimes.values.map { it.toRecord() } }
        active.forEach { record ->
            stored[record.runtimeId] = record
        }
        return stored.values
            .filter { record -> agentId == null || record.agentId == agentId }
            .sortedByDescending { it.createdAt }
    }

    suspend fun awaitAutonomousRuntime(runtimeId: String): AutonomousAgentRuntimeRecord? {
        val job = mutex.withLock { autonomousRuntimes[runtimeId]?.completionJob }
            ?: return getAutonomousRuntime(runtimeId)
        job.join()
        return getAutonomousRuntime(runtimeId)
    }

    suspend fun stopAutonomousRuntime(runtimeId: String): Boolean {
        val handle = mutex.withLock { autonomousRuntimes[runtimeId] } ?: return false
        handle.runtime.stop()
        return true
    }

    suspend fun getAutonomousRuntimeEvents(runtimeId: String): List<AutonomousAgentEvent> {
        val activeEvents = mutex.withLock { autonomousRuntimes[runtimeId]?.record?.events }
        return activeEvents ?: runtimeRecordStore.get(runtimeId)?.events.orEmpty()
    }

    suspend fun readRuntimeMemory(
        runtimeId: String,
        scope: AgentMemoryScope? = null,
        limit: Int = 20
    ): List<AgentMemoryEntry> {
        return memoryStore.read(runtimeId, scope, limit)
    }

    suspend fun clearAgentKnowledge(agentId: String) {
        knowledgeStore.clear(agentId)
    }

    suspend fun deleteAgentKnowledgeEntry(agentId: String, entryId: String): Boolean {
        ensureKnownAgent(agentId)
        return knowledgeStore.remove(agentId, entryId)
    }

    suspend fun writeRuntimeMemory(
        runtimeId: String,
        scope: AgentMemoryScope,
        content: String,
        tags: Set<String> = emptySet()
    ): AgentMemoryEntry {
        val entry = AgentMemoryEntry(scope = scope, content = content, tags = tags)
        memoryStore.write(runtimeId, entry)
        return entry
    }

    suspend fun clearRuntimeMemory(runtimeId: String) {
        memoryStore.clear(runtimeId)
    }

    suspend fun clearStructuredMemory(sessionId: String) {
        structuredMemoryStore.clear(sessionId)
    }

    suspend fun readStructuredMemory(sessionId: String): List<StructuredMemoryEntry> {
        return structuredMemoryStore.read(sessionId)
    }

    suspend fun deleteInvocationRecord(invocationId: String) {
        invocationStore.remove(invocationId)
    }

    suspend fun deleteAutonomousRuntimeRecord(runtimeId: String) {
        val handle = mutex.withLock { autonomousRuntimes.remove(runtimeId) }
        handle?.runtime?.stop()
        handle?.collectorJob?.cancel()
        runtimeRecordStore.remove(runtimeId)
    }

    // --- Human Gate / Approval API ---

    suspend fun listPendingApprovals(
        runtimeId: String? = null
    ): List<com.example.watcher.agentframework.gate.ApprovalRequest> {
        return humanGate.pendingApprovals(runtimeId)
    }

    suspend fun getApproval(
        gateId: String
    ): com.example.watcher.agentframework.gate.ApprovalRequest? {
        return humanGate.getApproval(gateId)
    }

    suspend fun submitApprovalDecision(
        decision: com.example.watcher.agentframework.gate.ApprovalDecision
    ): Boolean {
        return humanGate.submitDecision(decision)
    }

    suspend fun invoke(request: AgentInvocationRequest): AgentInvocationRecord {
        checkNotClosed()
        profileStore.get(request.agentId)
            ?: throw IllegalArgumentException("Missing agent profile: ${request.agentId}")
        val runtime = startAutonomousAgent(
            AutonomousAgentStartRequest(
                agentId = request.agentId,
                initialSignals = request.inputs.map { it.toSignalSeed() },
                preloadMemory = request.preloadMemory,
                preloadKnowledge = request.preloadKnowledge
            )
        )
        val record = AgentInvocationRecord(
            invocationId = runtime.runtimeId,
            agentId = request.agentId,
            sessionId = runtime.runtimeId,
            status = runtime.toInvocationStatus(),
            inputs = request.inputs,
            outputs = runtime.outputs,
            finalSnapshot = runtime.snapshot?.takeIf { it.isTerminal() }?.toSessionSnapshot(),
            errorMessage = runtime.errorMessage
        )
        invocationStore.upsert(record)

        return if (request.awaitCompletion) {
            awaitInvocation(record.invocationId) ?: record
        } else {
            record
        }
    }

    suspend fun getInvocation(invocationId: String): AgentInvocationRecord? {
        val stored = invocationStore.get(invocationId) ?: return null
        val runtime = getAutonomousRuntime(invocationId)
        val resolved = if (runtime != null) {
            stored.copy(
                status = runtime.toInvocationStatus(),
                outputs = runtime.outputs,
                finalSnapshot = runtime.snapshot?.takeIf { it.isTerminal() }?.toSessionSnapshot(),
                errorMessage = runtime.errorMessage,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            stored
        }
        invocationStore.upsert(resolved)
        return resolved
    }

    suspend fun listInvocations(agentId: String? = null): List<AgentInvocationRecord> {
        val records = invocationStore.list()
        return records.mapNotNull { record ->
            getInvocation(record.invocationId)
        }.filter { record ->
            agentId == null || record.agentId == agentId
        }.sortedByDescending { it.createdAt }
    }

    suspend fun awaitInvocation(invocationId: String): AgentInvocationRecord? {
        awaitAutonomousRuntime(invocationId)
        return getInvocation(invocationId)
    }

    suspend fun stopInvocation(invocationId: String): Boolean {
        return stopAutonomousRuntime(invocationId)
    }

    fun shutdown() {
        runBlocking {
            shutdownAndJoin()
        }
    }

    suspend fun shutdownAndJoin() {
        closed = true
        val handles = mutex.withLock { autonomousRuntimes.values.toList() }
        handles.forEach { handle ->
            handle.runtime.stop()
        }
        handles.forEach { handle ->
            handle.completionJob?.join()
        }
        handles.forEach { handle ->
            handle.collectorJob?.cancel()
        }
        handles.forEach { handle ->
            handle.collectorJob?.join()
        }
        mutex.withLock { autonomousRuntimes.clear() }
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun setAutonomousJobs(runtimeId: String, collectorJob: Job, completionJob: Job) {
        mutex.withLock {
            val runtime = autonomousRuntimes[runtimeId] ?: return@withLock
            autonomousRuntimes[runtimeId] = runtime.copy(
                collectorJob = collectorJob,
                completionJob = completionJob
            )
        }
    }

    private suspend fun preloadContext(
        sessionId: String,
        agentId: String,
        preloadMemory: List<AgentMemorySeed>,
        preloadKnowledge: List<AgentKnowledgeSeed>
    ) {
        if (sessionId.isBlank()) return
        preloadMemory.forEach { seed ->
            memoryStore.write(
                sessionId,
                AgentMemoryEntry(
                    scope = seed.scope,
                    content = seed.content,
                    tags = seed.tags
                )
            )
        }
        preloadKnowledge.forEach { seed ->
            knowledgeStore.write(
                agentId,
                AgentKnowledgeEntry(
                    content = seed.content,
                    tags = seed.tags,
                    metadata = seed.metadata
                )
            )
        }
    }

    private suspend fun resolveSessionId(invocationId: String): String? {
        val storedSessionId = invocationStore.get(invocationId)?.sessionId
        if (storedSessionId != null) return storedSessionId
        return mutex.withLock { autonomousRuntimes[invocationId]?.record?.runtimeId }
    }

    private suspend fun ensureKnownAgent(agentId: String) {
        val known = mutex.withLock { registrations.containsKey(agentId) } || profileStore.get(agentId) != null
        if (!known) {
            throw IllegalArgumentException("Unknown agent: $agentId")
        }
    }

    private suspend fun resolveBrain(profile: RegisteredAgentProfile): AgentBrain {
        val (registrationBrain, factory) = mutex.withLock {
            registrations[profile.agentId]?.brain to brainFactories[profile.brainFactoryId]
        }
        if (registrationBrain != null) return registrationBrain
        val resolvedFactory = factory
            ?: throw IllegalArgumentException(
                "No brain factory registered for ${profile.agentId} (${profile.brainFactoryId})"
            )
        return resolvedFactory.create(profile)
    }

    private suspend fun appendAutonomousEvent(runtimeId: String, event: AutonomousAgentEvent) {
        val updatedRecord: AutonomousAgentRuntimeRecord? = mutex.withLock {
            val handle = autonomousRuntimes[runtimeId] ?: return@withLock null
            val snapshot = handle.runtime.snapshot.value
            val record = handle.record.copy(
                lifecycleState = snapshot.lifecycleState,
                stopReason = snapshot.stopReason,
                outputs = snapshot.outputs,
                snapshot = snapshot,
                correctionRecords = snapshot.correctionRecords,
                events = (handle.record.events + event).takeLast(maxRuntimeEvents),
                errorMessage = snapshot.errorMessage,
                updatedAt = System.currentTimeMillis()
            )
            autonomousRuntimes[runtimeId] = handle.copy(record = record)
            record
        }
        if (updatedRecord != null) {
            runtimeRecordStore.upsert(updatedRecord)
        }
    }

    private suspend fun completeAutonomousRuntime(runtimeId: String) {
        val handle = mutex.withLock { autonomousRuntimes[runtimeId] } ?: return
        val snapshot = handle.runtime.awaitCompletion()
        val profile = profileStore.get(handle.record.agentId)
        if (profile != null) {
            evolutionStrategy.evolve(profile, snapshot.toSessionSnapshot())?.let { evolution ->
                evolution.knowledgeWrites.forEach { knowledgeStore.write(profile.agentId, it) }
                profileStore.upsert(
                    profile.copy(
                        definition = evolution.updatedDefinition ?: profile.definition,
                        config = evolution.updatedConfig ?: profile.config,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        mutex.withLock {
            val current = autonomousRuntimes[runtimeId] ?: return@withLock
            autonomousRuntimes[runtimeId] = current.copy(
                record = current.record.copy(
                    lifecycleState = snapshot.lifecycleState,
                    stopReason = snapshot.stopReason,
                    outputs = snapshot.outputs,
                    snapshot = snapshot,
                    correctionRecords = snapshot.correctionRecords,
                    errorMessage = snapshot.errorMessage,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        getAutonomousRuntime(runtimeId)?.let { runtimeRecordStore.upsert(it) }
        invocationStore.get(runtimeId)?.let { invocation ->
            invocationStore.upsert(
                invocation.copy(
                    status = snapshot.toInvocationStatus(),
                    outputs = snapshot.outputs,
                    finalSnapshot = snapshot.takeIf { it.isTerminal() }?.toSessionSnapshot(),
                    errorMessage = snapshot.errorMessage,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun reconcilePersistentExecutionState() {
        recoveryPolicy.reconcile(
            invocationStore = invocationStore,
            runtimeRecordStore = runtimeRecordStore
        )
    }

    private data class AutonomousRuntimeHandle(
        val runtime: AutonomousAgentRuntime,
        val record: AutonomousAgentRuntimeRecord,
        val collectorJob: Job? = null,
        val completionJob: Job? = null
    ) {
        fun toRecord(): AutonomousAgentRuntimeRecord {
            val snapshot = runtime.snapshot.value
            return record.copy(
                lifecycleState = snapshot.lifecycleState,
                stopReason = snapshot.stopReason,
                outputs = snapshot.outputs,
                snapshot = snapshot,
                correctionRecords = snapshot.correctionRecords,
                errorMessage = snapshot.errorMessage,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}

private fun AgentRunConfig.toAutonomousConfig(): com.example.watcher.agentframework.autonomy.AutonomousAgentConfig {
    return com.example.watcher.agentframework.autonomy.AutonomousAgentConfig(
        maxCycles = maxSteps,
        maxFailures = maxConsecutiveFailures,
        maxIdleCycles = maxIdleTurns,
        loopDelayMillis = defaultWaitMillis,
        maxRuntimeMillis = maxRuntimeMillis,
        enableReflectionCorrection = enableReflectionCorrection,
        maxCorrectionAttemptsPerCycle = maxCorrectionAttemptsPerStep,
        maxCorrectionAttemptsPerRuntime = maxCorrectionAttemptsPerRun
    )
}

private fun AgentInvocationInput.toSignalSeed(): AgentSignalSeed {
    return AgentSignalSeed(
        channel = when (role) {
            AgentMessageRole.User -> com.example.watcher.agentframework.autonomy.SignalChannel.User
            AgentMessageRole.System -> com.example.watcher.agentframework.autonomy.SignalChannel.System
            AgentMessageRole.Tool -> com.example.watcher.agentframework.autonomy.SignalChannel.Tool
            AgentMessageRole.Assistant -> com.example.watcher.agentframework.autonomy.SignalChannel.Agent
            AgentMessageRole.Observation -> com.example.watcher.agentframework.autonomy.SignalChannel.Environment
        },
        content = content,
        metadata = buildMap {
            name?.let { put("name", it) }
        }
    )
}

private fun AutonomousAgentRuntimeRecord.toInvocationStatus(): AgentInvocationStatus {
    return when (lifecycleState) {
        AutonomousLifecycleState.Created,
        AutonomousLifecycleState.Initialized -> AgentInvocationStatus.Pending

        AutonomousLifecycleState.Running,
        AutonomousLifecycleState.Suspended -> AgentInvocationStatus.Running

        AutonomousLifecycleState.Stopped -> when (stopReason) {
            AutonomousStopReason.GoalAchieved -> AgentInvocationStatus.Completed
            else -> AgentInvocationStatus.Stopped
        }
        AutonomousLifecycleState.Failed -> AgentInvocationStatus.Failed
        AutonomousLifecycleState.Destroyed -> when (stopReason) {
            AutonomousStopReason.InterruptedByRestart -> AgentInvocationStatus.Interrupted
            else -> AgentInvocationStatus.Cancelled
        }
    }
}

private fun com.example.watcher.agentframework.autonomy.AutonomousAgentSnapshot.toInvocationStatus(): AgentInvocationStatus {
    return AutonomousAgentRuntimeRecord(
        runtimeId = sessionId,
        agentId = definition.agentId,
        lifecycleState = lifecycleState,
        stopReason = stopReason
    ).toInvocationStatus()
}

private fun AutonomousAgentRuntimeRecord.isTerminal(): Boolean {
    return lifecycleState.isTerminal
}

private fun com.example.watcher.agentframework.autonomy.AutonomousAgentSnapshot.isTerminal(): Boolean {
    return lifecycleState.isTerminal
}

private fun com.example.watcher.agentframework.autonomy.AutonomousAgentSnapshot.toSessionSnapshot(): AgentSessionSnapshot {
    val status = when (lifecycleState) {
        AutonomousLifecycleState.Created,
        AutonomousLifecycleState.Initialized -> AgentSessionStatus.Created

        AutonomousLifecycleState.Running,
        AutonomousLifecycleState.Suspended -> AgentSessionStatus.Running

        AutonomousLifecycleState.Stopped -> if (stopReason == AutonomousStopReason.GoalAchieved) {
            AgentSessionStatus.Completed
        } else {
            AgentSessionStatus.Stopped
        }
        AutonomousLifecycleState.Failed -> AgentSessionStatus.Failed
        AutonomousLifecycleState.Destroyed -> if (stopReason == AutonomousStopReason.InterruptedByRestart) {
            AgentSessionStatus.Interrupted
        } else {
            AgentSessionStatus.Cancelled
        }
    }
    val sessionStopReason = when (stopReason) {
        AutonomousStopReason.GoalAchieved -> AgentStopReason.GoalAchieved
        AutonomousStopReason.StepLimitReached -> AgentStopReason.StepLimitReached
        AutonomousStopReason.RuntimeLimitReached -> AgentStopReason.RuntimeLimitReached
        AutonomousStopReason.IdleLimitReached -> AgentStopReason.IdleLimitReached
        AutonomousStopReason.StoppedByRequest -> AgentStopReason.StoppedByAgent
        AutonomousStopReason.InterruptedByRestart -> AgentStopReason.InterruptedByRestart
        AutonomousStopReason.Cancelled -> AgentStopReason.Cancelled
        AutonomousStopReason.Error -> AgentStopReason.Error
        else -> null
    }
    return AgentSessionSnapshot(
        sessionId = sessionId,
        agentId = definition.agentId,
        agentName = definition.name,
        goal = definition.goal,
        status = status,
        stopReason = sessionStopReason,
        failureMessage = errorMessage,
        stepCount = cycle,
        turns = records.map { record ->
            AgentTurnRecord(
                step = record.cycle,
                decision = record.guardedDecision.decision,
                toolResults = record.outcome.toolResults,
                startedAt = record.startedAt,
                completedAt = record.completedAt
            )
        },
        lastReply = outputs.lastOrNull()
    )
}

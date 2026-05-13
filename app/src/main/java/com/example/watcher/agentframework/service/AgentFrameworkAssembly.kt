package com.example.watcher.agentframework.service

import com.example.watcher.agentframework.autonomy.AutonomousAgentModules
import com.example.watcher.agentframework.autonomy.CommunicationHub
import com.example.watcher.agentframework.autonomy.AutonomousLifecycleState
import com.example.watcher.agentframework.autonomy.AutonomousStopReason
import com.example.watcher.agentframework.autonomy.DefaultStructuredMemoryManagerFactory
import com.example.watcher.agentframework.autonomy.FileBackedStructuredMemoryStore
import com.example.watcher.agentframework.autonomy.InMemoryCommunicationHub
import com.example.watcher.agentframework.autonomy.InMemoryStructuredMemoryStore
import com.example.watcher.agentframework.autonomy.StructuredMemoryManagerFactory
import com.example.watcher.agentframework.autonomy.StructuredMemoryStore
import com.example.watcher.agentframework.autonomy.StructuredMemoryManager
import com.example.watcher.agentframework.autonomy.defaultAutonomousModules
import com.example.watcher.agentframework.knowledge.AgentKnowledgeStore
import com.example.watcher.agentframework.knowledge.InMemoryAgentKnowledgeStore
import com.example.watcher.agentframework.memory.AgentMemoryStore
import com.example.watcher.agentframework.memory.InMemoryAgentMemoryStore
import com.example.watcher.agentframework.runtime.AgentBrain
import com.example.watcher.agentframework.tools.AgentToolExecutor
import com.example.watcher.agentframework.tools.AgentToolRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

data class AgentFrameworkStorage(
    val profileStore: AgentProfileStore = InMemoryAgentProfileStore(),
    val memoryStore: AgentMemoryStore = InMemoryAgentMemoryStore(),
    val knowledgeStore: AgentKnowledgeStore = InMemoryAgentKnowledgeStore(),
    val invocationStore: AgentInvocationStore = InMemoryAgentInvocationStore(),
    val runtimeRecordStore: AutonomousRuntimeRecordStore = InMemoryAutonomousRuntimeRecordStore()
) {
    companion object {
        fun createPersistent(rootDir: File): AgentFrameworkStorage {
            val serviceRoot = File(rootDir, "agentframework").apply { mkdirs() }
            return AgentFrameworkStorage(
                profileStore = FileBackedAgentProfileStore(File(serviceRoot, "profiles")),
                memoryStore = FileBackedAgentMemoryStore(File(serviceRoot, "memory")),
                knowledgeStore = FileBackedAgentKnowledgeStore(File(serviceRoot, "knowledge")),
                invocationStore = FileBackedAgentInvocationStore(File(serviceRoot, "invocations")),
                runtimeRecordStore = FileBackedAutonomousRuntimeRecordStore(File(serviceRoot, "autonomous-runtimes"))
            )
        }
    }
}

data class AgentFrameworkRuntimeComponents(
    val toolRegistry: AgentToolRegistry = AgentToolRegistry(),
    val evolutionStrategy: AgentProfileEvolutionStrategy = HeuristicAgentProfileEvolutionStrategy(),
    val structuredMemoryStore: StructuredMemoryStore = InMemoryStructuredMemoryStore(),
    val structuredMemoryManagerFactory: StructuredMemoryManagerFactory = DefaultStructuredMemoryManagerFactory(),
    val communicationHub: CommunicationHub = InMemoryCommunicationHub(),
    val modulesFactory: AutonomousModulesFactory = DefaultAutonomousModulesFactory(),
    val recoveryPolicy: AgentRuntimeRecoveryPolicy = CancelRunningExecutionsRecoveryPolicy(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
)

interface AutonomousModulesFactory {
    fun create(
        profile: RegisteredAgentProfile,
        brain: AgentBrain,
        toolExecutor: AgentToolExecutor,
        memoryStore: AgentMemoryStore,
        knowledgeStore: AgentKnowledgeStore,
        memoryManager: StructuredMemoryManager,
        communicationHub: CommunicationHub
    ): AutonomousAgentModules
}

class DefaultAutonomousModulesFactory : AutonomousModulesFactory {
    override fun create(
        profile: RegisteredAgentProfile,
        brain: AgentBrain,
        toolExecutor: AgentToolExecutor,
        memoryStore: AgentMemoryStore,
        knowledgeStore: AgentKnowledgeStore,
        memoryManager: StructuredMemoryManager,
        communicationHub: CommunicationHub
    ): AutonomousAgentModules {
        return defaultAutonomousModules(
            brain = brain,
            toolExecutor = toolExecutor,
            memoryStore = memoryStore,
            knowledgeStore = knowledgeStore,
            memoryManager = memoryManager,
            communicationHub = communicationHub,
            toolTimeoutMillis = profile.config.toolTimeoutMillis
        )
    }
}

interface AgentRuntimeRecoveryPolicy {
    // Current crash recovery records unfinished work as interrupted; it does not resume execution.
    suspend fun reconcile(
        invocationStore: AgentInvocationStore,
        runtimeRecordStore: AutonomousRuntimeRecordStore,
        now: Long = System.currentTimeMillis()
    )
}

class CancelRunningExecutionsRecoveryPolicy : AgentRuntimeRecoveryPolicy {
    override suspend fun reconcile(
        invocationStore: AgentInvocationStore,
        runtimeRecordStore: AutonomousRuntimeRecordStore,
        now: Long
    ) {
        runtimeRecordStore.list().forEach { record ->
            if (
                record.lifecycleState != AutonomousLifecycleState.Stopped &&
                record.lifecycleState != AutonomousLifecycleState.Failed &&
                record.lifecycleState != AutonomousLifecycleState.Destroyed
            ) {
                runtimeRecordStore.upsert(
                    record.copy(
                        lifecycleState = AutonomousLifecycleState.Destroyed,
                        stopReason = AutonomousStopReason.InterruptedByRestart,
                        snapshot = record.snapshot?.copy(
                            lifecycleState = AutonomousLifecycleState.Destroyed,
                            stopReason = AutonomousStopReason.InterruptedByRestart
                        ),
                        errorMessage = record.errorMessage
                            ?: "Runtime could not be resumed after service restart.",
                        updatedAt = now
                    )
                )
            }
        }
        invocationStore.list().forEach { record ->
            if (record.status == AgentInvocationStatus.Pending || record.status == AgentInvocationStatus.Running) {
                invocationStore.upsert(
                    record.copy(
                        status = AgentInvocationStatus.Interrupted,
                        errorMessage = record.errorMessage
                            ?: "Invocation could not be resumed after service restart.",
                        updatedAt = now
                    )
                )
            }
        }
    }
}

class AgentFrameworkBuilder {
    private var storage: AgentFrameworkStorage = AgentFrameworkStorage()
    private var components: AgentFrameworkRuntimeComponents = AgentFrameworkRuntimeComponents()
    private val brainFactories = mutableListOf<AgentBrainFactory>()

    fun persistentStorage(rootDir: File): AgentFrameworkBuilder {
        this.storage = AgentFrameworkStorage.createPersistent(rootDir)
        this.components = components.copy(
            structuredMemoryStore = FileBackedStructuredMemoryStore(
                File(File(rootDir, "agentframework"), "structured-memory")
            )
        )
        return this
    }

    fun storage(storage: AgentFrameworkStorage): AgentFrameworkBuilder {
        this.storage = storage
        return this
    }

    fun components(components: AgentFrameworkRuntimeComponents): AgentFrameworkBuilder {
        this.components = components
        return this
    }

    fun addBrainFactory(factory: AgentBrainFactory): AgentFrameworkBuilder {
        brainFactories += factory
        return this
    }

    fun addBrainFactories(factories: Iterable<AgentBrainFactory>): AgentFrameworkBuilder {
        brainFactories += factories
        return this
    }

    fun addBrainCatalog(catalog: AgentBrainCatalog): AgentFrameworkBuilder {
        brainFactories += catalog.factories()
        return this
    }

    fun build(): AgentFrameworkService {
        val service = AgentFrameworkService(
            profileStore = storage.profileStore,
            memoryStore = storage.memoryStore,
            knowledgeStore = storage.knowledgeStore,
            invocationStore = storage.invocationStore,
            runtimeRecordStore = storage.runtimeRecordStore,
            toolRegistry = components.toolRegistry,
            evolutionStrategy = components.evolutionStrategy,
            structuredMemoryStore = components.structuredMemoryStore,
            structuredMemoryManagerFactory = components.structuredMemoryManagerFactory,
            communicationHub = components.communicationHub,
            modulesFactory = components.modulesFactory,
            recoveryPolicy = components.recoveryPolicy,
            scope = components.scope
        ).registerBrainFactories(brainFactories)
        runBlocking {
            service.reconcilePersistentExecutionState()
        }
        return service
    }
}

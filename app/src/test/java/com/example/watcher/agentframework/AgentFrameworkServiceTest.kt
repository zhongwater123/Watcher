package com.example.watcher.agentframework

import com.example.watcher.agentframework.autonomy.AutonomousAgentSnapshot
import com.example.watcher.agentframework.autonomy.AutonomousLifecycleState
import com.example.watcher.agentframework.autonomy.AutonomousStopReason
import com.example.watcher.agentframework.autonomy.SignalChannel
import com.example.watcher.agentframework.core.AgentAction
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentRunConfig
import com.example.watcher.agentframework.core.AgentSessionStatus
import com.example.watcher.agentframework.core.AgentStopReason
import com.example.watcher.agentframework.core.AgentToolCall
import com.example.watcher.agentframework.core.AgentToolDefinition
import com.example.watcher.agentframework.core.AgentToolResult
import com.example.watcher.agentframework.runtime.AgentBrain
import com.example.watcher.agentframework.runtime.AgentBrainRequest
import com.example.watcher.agentframework.service.AgentFrameworkStorage
import com.example.watcher.agentframework.service.StaticAgentBrainFactory
import com.example.watcher.agentframework.service.AgentSignalSeed
import com.example.watcher.agentframework.service.AutonomousAgentRuntimeRecord
import com.example.watcher.agentframework.service.AutonomousAgentStartRequest
import com.example.watcher.agentframework.service.AgentFrameworkService
import com.example.watcher.agentframework.service.AgentInvocationRecord
import com.example.watcher.agentframework.service.AgentInvocationInput
import com.example.watcher.agentframework.service.AgentInvocationRequest
import com.example.watcher.agentframework.service.AgentInvocationStatus
import com.example.watcher.agentframework.service.AgentKnowledgeSeed
import com.example.watcher.agentframework.service.AgentMemorySeed
import com.example.watcher.agentframework.service.AgentRegistration
import com.example.watcher.agentframework.tools.AgentTool
import com.example.watcher.agentframework.tools.AgentToolContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFrameworkServiceTest {

    @Test
    fun serviceAccumulatesEvolutionWithoutTurningRunSummariesIntoKnowledge() = runBlocking {
        val service = AgentFrameworkService()
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                val prompt = request.session.history
                    .lastOrNull { it.role == AgentMessageRole.User }
                    ?.content
                    .orEmpty()
                assertTrue(request.knowledge.entries.isEmpty())
                return AgentDecision(
                    reply = "reply:$prompt",
                    action = AgentAction.Finish(reason = "done", success = true)
                )
            }
        }

        service.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = "independent_agent",
                    name = "Independent Agent",
                    systemInstruction = "Answer and learn across runs.",
                    goal = "Respond to incoming tasks."
                ),
                brain = brain
            )
        )

        val first = service.invoke(
            AgentInvocationRequest(
                agentId = "independent_agent",
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "task-one"
                    )
                )
            )
        )
        val second = service.invoke(
            AgentInvocationRequest(
                agentId = "independent_agent",
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "task-two"
                    )
                )
            )
        )

        assertEquals(AgentInvocationStatus.Completed, first.status)
        assertEquals("reply:task-one", first.outputs.last())
        assertEquals(AgentInvocationStatus.Completed, second.status)
        assertEquals("reply:task-two", second.outputs.last())

        val profile = service.getAgentProfile("independent_agent")
        assertNotNull(profile)
        assertEquals("2", profile!!.definition.metadata["runCount"])
        assertEquals("2", profile.definition.metadata["successCount"])
        assertTrue(service.readAgentKnowledge("independent_agent", limit = 10).isEmpty())

        service.shutdown()
    }

    @Test
    fun serviceSupportsAsyncInvocationAndAwaitingCompletion() = runBlocking {
        val service = AgentFrameworkService()
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                delay(50L)
                return AgentDecision(
                    reply = "async-result",
                    action = AgentAction.Finish(reason = "done", success = true)
                )
            }
        }

        service.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = "async_agent",
                    name = "Async Agent",
                    systemInstruction = "Handle work asynchronously.",
                    goal = "Produce an answer."
                ),
                brain = brain
            )
        )

        val pending = service.invoke(
            AgentInvocationRequest(
                agentId = "async_agent",
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "run async"
                    )
                ),
                awaitCompletion = false
            )
        )

        assertEquals(AgentInvocationStatus.Running, pending.status)

        val current = service.getInvocation(pending.invocationId)
        assertNotNull(current)
        assertTrue(
            current!!.status == AgentInvocationStatus.Running ||
                current.status == AgentInvocationStatus.Completed
        )

        val final = service.awaitInvocation(pending.invocationId)
        assertNotNull(final)
        assertEquals(AgentInvocationStatus.Completed, final!!.status)
        assertEquals("async-result", final.outputs.last())

        service.shutdown()
    }

    @Test
    fun serviceOpensMemoryAndKnowledgeToExternalCallersAndRuntimeTools() = runBlocking {
        val service = AgentFrameworkService()
        var step = 0
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                step += 1
                return when (step) {
                    1 -> {
                        assertTrue(request.memory.working.any { it.content == "external memory seed" })
                        assertTrue(request.knowledge.entries.any { it.content == "external knowledge seed" })
                        AgentDecision(
                            action = AgentAction.UseTools(
                                calls = listOf(
                                    AgentToolCall(
                                        id = "write_mem",
                                        name = "write_memory",
                                        arguments = mapOf(
                                            "scope" to "episodic",
                                            "content" to "runtime memory"
                                        )
                                    ),
                                    AgentToolCall(
                                        id = "read_mem",
                                        name = "read_memory",
                                        arguments = mapOf(
                                            "scope" to "episodic",
                                            "limit" to 5
                                        )
                                    ),
                                    AgentToolCall(
                                        id = "write_knowledge",
                                        name = "write_knowledge",
                                        arguments = mapOf(
                                            "content" to "runtime knowledge",
                                            "tags" to listOf("runtime")
                                        )
                                    ),
                                    AgentToolCall(
                                        id = "query_knowledge",
                                        name = "query_knowledge",
                                        arguments = mapOf(
                                            "query" to "runtime",
                                            "limit" to 5
                                        )
                                    )
                                )
                            )
                        )
                    }

                    else -> {
                        assertTrue(request.memory.episodic.any { it.content == "runtime memory" })
                        assertTrue(request.knowledge.entries.any { it.content == "runtime knowledge" })
                        AgentDecision(
                            reply = "context ready",
                            action = AgentAction.Finish(reason = "done", success = true)
                        )
                    }
                }
            }
        }

        service.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = "context_agent",
                    name = "Context Agent",
                    systemInstruction = "Use memory and knowledge tools when needed.",
                    goal = "Keep useful context."
                ),
                brain = brain
            )
        )

        val result = service.invoke(
            AgentInvocationRequest(
                agentId = "context_agent",
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "run with context"
                    )
                ),
                preloadMemory = listOf(
                    AgentMemorySeed(
                        scope = AgentMemoryScope.Working,
                        content = "external memory seed"
                    )
                ),
                preloadKnowledge = listOf(
                    AgentKnowledgeSeed(
                        content = "external knowledge seed",
                        tags = setOf("seed")
                    )
                )
            )
        )

        assertEquals(AgentInvocationStatus.Completed, result.status)
        assertEquals("context ready", result.outputs.last())

        val runtimeMemory = service.readInvocationMemory(result.invocationId, limit = 10)
        assertTrue(runtimeMemory.any { it.content == "runtime memory" })

        service.writeInvocationMemory(
            result.invocationId,
            scope = AgentMemoryScope.Working,
            content = "external append"
        )
        val updatedMemory = service.readInvocationMemory(result.invocationId, limit = 10)
        assertTrue(updatedMemory.any { it.content == "external append" })

        val runtimeKnowledge = service.queryAgentKnowledge("context_agent", "runtime", limit = 10)
        assertTrue(runtimeKnowledge.any { it.content == "runtime knowledge" })

        val appendedKnowledge = service.writeAgentKnowledge(
            agentId = "context_agent",
            content = "external knowledge append",
            tags = setOf("manual")
        )
        val allKnowledge = service.readAgentKnowledge("context_agent", limit = 20)
        assertTrue(allKnowledge.any { it.entryId == appendedKnowledge.entryId })
        assertTrue(service.deleteAgentKnowledgeEntry("context_agent", appendedKnowledge.entryId))
        val knowledgeAfterDelete = service.readAgentKnowledge("context_agent", limit = 20)
        assertTrue(knowledgeAfterDelete.none { it.entryId == appendedKnowledge.entryId })

        service.shutdown()
    }

    @Test
    fun persistentServiceRetainsProfilesInvocationsAndRuntimeRecordsAcrossRestart() = runBlocking {
        val rootDir = Files.createTempDirectory("agentframework-persistent-test").toFile()
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                val prompt = request.session.history.lastOrNull()?.content.orEmpty()
                return AgentDecision(
                    reply = "persisted:$prompt",
                    action = AgentAction.Finish(reason = "done", success = true)
                )
            }
        }

        val firstService = AgentFrameworkService.createPersistent(rootDir)
        firstService.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = "persistent_agent",
                    name = "Persistent Agent",
                    systemInstruction = "Persist runtime records.",
                    goal = "Handle persisted tasks."
                ),
                brain = brain
            )
        )

        val result = firstService.invoke(
            AgentInvocationRequest(
                agentId = "persistent_agent",
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "store me"
                    )
                )
            )
        )
        assertEquals(AgentInvocationStatus.Completed, result.status)
        firstService.shutdown()

        val secondService = AgentFrameworkService.createPersistent(
            rootDir = rootDir,
            brainFactories = listOf(
                StaticAgentBrainFactory("persistent_agent", brain)
            )
        )

        val restorationStatus = secondService.listAgentRestorationStatus()
        assertTrue(restorationStatus.any { it.agentId == "persistent_agent" && it.restorable })

        val profile = secondService.getAgentProfile("persistent_agent")
        assertNotNull(profile)

        val invocations = secondService.listInvocations("persistent_agent")
        assertTrue(invocations.any { it.invocationId == result.invocationId && it.status == AgentInvocationStatus.Completed })

        val runtimes = secondService.listAutonomousRuntimes("persistent_agent")
        assertTrue(runtimes.any { it.runtimeId == result.invocationId })
        val restoredRuntime = runtimes.first { it.runtimeId == result.invocationId }
        assertNotNull(restoredRuntime.snapshot)
        assertEquals(1, restoredRuntime.snapshot!!.cycle)
        assertTrue(restoredRuntime.snapshot!!.outputs.contains("persisted:store me"))

        val runtimeEvents = secondService.getAutonomousRuntimeEvents(result.invocationId)
        assertTrue(runtimeEvents.isNotEmpty())

        secondService.shutdown()
        rootDir.deleteRecursively()
    }

    @Test
    fun persistentServiceMarksUnfinishedRuntimeInterruptedAcrossRestart() = runBlocking {
        val rootDir = Files.createTempDirectory("agentframework-interrupted-test").toFile()
        val definition = AgentDefinition(
            agentId = "restart_agent",
            name = "Restart Agent",
            systemInstruction = "Wait across restart.",
            goal = "Expose restart interruption."
        )
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                return AgentDecision(
                    reply = "restored",
                    action = AgentAction.Finish(reason = "done", success = true)
                )
            }
        }

        val firstService = AgentFrameworkService.createPersistent(rootDir)
        firstService.registerAgent(
            AgentRegistration(
                definition = definition,
                brain = brain
            )
        )
        firstService.shutdown()

        val runtimeId = "unfinished-runtime"
        val storage = AgentFrameworkStorage.createPersistent(rootDir)
        storage.runtimeRecordStore.upsert(
            AutonomousAgentRuntimeRecord(
                runtimeId = runtimeId,
                agentId = definition.agentId,
                lifecycleState = AutonomousLifecycleState.Running,
                outputs = listOf("partial-output"),
                snapshot = AutonomousAgentSnapshot(
                    sessionId = runtimeId,
                    definition = definition,
                    lifecycleState = AutonomousLifecycleState.Running,
                    cycle = 2,
                    outputs = listOf("partial-output")
                )
            )
        )
        storage.invocationStore.upsert(
            AgentInvocationRecord(
                invocationId = runtimeId,
                agentId = definition.agentId,
                sessionId = runtimeId,
                status = AgentInvocationStatus.Running,
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "keep working"
                    )
                ),
                outputs = listOf("partial-output")
            )
        )

        val secondService = AgentFrameworkService.createPersistent(
            rootDir = rootDir,
            brainFactories = listOf(StaticAgentBrainFactory(definition.agentId, brain))
        )

        val restoredRuntime = secondService.getAutonomousRuntime(runtimeId)
        assertNotNull(restoredRuntime)
        assertEquals(AutonomousLifecycleState.Destroyed, restoredRuntime!!.lifecycleState)
        assertEquals(AutonomousStopReason.InterruptedByRestart, restoredRuntime.stopReason)
        assertNotNull(restoredRuntime.snapshot)
        assertEquals(AutonomousLifecycleState.Destroyed, restoredRuntime.snapshot!!.lifecycleState)
        assertEquals(AutonomousStopReason.InterruptedByRestart, restoredRuntime.snapshot!!.stopReason)

        val invocation = secondService.getInvocation(runtimeId)
        assertNotNull(invocation)
        assertEquals(AgentInvocationStatus.Interrupted, invocation!!.status)
        assertTrue(invocation.status != AgentInvocationStatus.Cancelled)
        assertNotNull(invocation.finalSnapshot)
        assertEquals(AgentSessionStatus.Interrupted, invocation.finalSnapshot!!.status)
        assertEquals(AgentStopReason.InterruptedByRestart, invocation.finalSnapshot!!.stopReason)

        secondService.shutdown()
        rootDir.deleteRecursively()
    }

    @Test
    fun runtimeToolWhitelistLimitsVisibleAndExecutableTools() = runBlocking {
        val service = AgentFrameworkService()
            .registerTool(TestTool("allowed_tool"))
            .registerTool(TestTool("blocked_tool"))
        var step = 0
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                step += 1
                val toolNames = request.availableTools.map { it.name }
                assertTrue(toolNames.contains("allowed_tool"))
                assertTrue(!toolNames.contains("blocked_tool"))
                return if (step == 1) {
                    AgentDecision(
                        action = AgentAction.UseTools(
                            calls = listOf(
                                AgentToolCall(id = "allowed", name = "allowed_tool"),
                                AgentToolCall(id = "blocked", name = "blocked_tool")
                            )
                        )
                    )
                } else {
                    AgentDecision(
                        reply = "done",
                        action = AgentAction.Finish(reason = "done", success = true)
                    )
                }
            }
        }

        service.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = "isolated_agent",
                    name = "Isolated Agent",
                    systemInstruction = "Use scoped tools.",
                    goal = "Respect the runtime whitelist."
                ),
                brain = brain,
                config = AgentRunConfig(maxSteps = 4, defaultWaitMillis = 1L)
            )
        )

        val started = service.startAutonomousAgent(
            AutonomousAgentStartRequest(
                agentId = "isolated_agent",
                initialSignals = listOf(
                    AgentSignalSeed(
                        channel = SignalChannel.User,
                        content = "use tools"
                    )
                ),
                allowedToolNames = setOf("allowed_tool")
            )
        )
        val final = service.awaitAutonomousRuntime(started.runtimeId)
        assertNotNull(final)

        val toolResults = final!!.snapshot!!.records.first().outcome.toolResults
        assertTrue(toolResults.any { it.toolName == "allowed_tool" && it.success })
        val blockedResult = toolResults.first { it.toolName == "blocked_tool" }
        assertEquals(false, blockedResult.success)
        assertEquals("Tool not allowed for this runtime: blocked_tool", blockedResult.error)

        service.shutdown()
    }

    @Test
    fun runtimeWithoutToolWhitelistKeepsAllRegisteredToolsVisible() = runBlocking {
        val service = AgentFrameworkService()
            .registerTool(TestTool("first_tool"))
            .registerTool(TestTool("second_tool"))
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                val toolNames = request.availableTools.map { it.name }
                assertTrue(toolNames.contains("first_tool"))
                assertTrue(toolNames.contains("second_tool"))
                return AgentDecision(
                    reply = "all tools visible",
                    action = AgentAction.Finish(reason = "done", success = true)
                )
            }
        }

        service.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = "compatible_agent",
                    name = "Compatible Agent",
                    systemInstruction = "Use default tool visibility.",
                    goal = "Preserve legacy behavior."
                ),
                brain = brain
            )
        )

        val result = service.invoke(
            AgentInvocationRequest(
                agentId = "compatible_agent",
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "run"
                    )
                )
            )
        )

        assertEquals(AgentInvocationStatus.Completed, result.status)
        assertEquals("all tools visible", result.outputs.last())

        service.shutdown()
    }

    @Test
    fun shutdownWaitsForRuntimeTerminationAndPersistsFinalStatus() = runBlocking {
        val service = AgentFrameworkService()
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                return AgentDecision(
                    action = AgentAction.Wait(
                        reason = "keep waiting",
                        resumeAfterMillis = 1L
                    )
                )
            }
        }

        service.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = "shutdown_agent",
                    name = "Shutdown Agent",
                    systemInstruction = "Wait until stopped.",
                    goal = "Remain idle."
                ),
                brain = brain,
                config = AgentRunConfig(
                    maxIdleTurns = 100,
                    defaultWaitMillis = 1L,
                    maxRuntimeMillis = 5_000L
                )
            )
        )

        val pending = service.invoke(
            AgentInvocationRequest(
                agentId = "shutdown_agent",
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "stay alive"
                    )
                ),
                awaitCompletion = false
            )
        )
        assertEquals(AgentInvocationStatus.Running, pending.status)

        service.shutdown()

        val archived = service.getInvocation(pending.invocationId)
        assertNotNull(archived)
        assertTrue(archived!!.status != AgentInvocationStatus.Running)
    }

    @Test
    fun serviceCapsPersistedRuntimeEventHistory() = runBlocking {
        val service = AgentFrameworkService(maxRuntimeEvents = 3)
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                return AgentDecision(
                    action = AgentAction.Wait(
                        reason = "generate events",
                        resumeAfterMillis = 1L
                    )
                )
            }
        }

        service.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = "event_agent",
                    name = "Event Agent",
                    systemInstruction = "Generate enough cycles to trim events.",
                    goal = "Stop after idling."
                ),
                brain = brain,
                config = AgentRunConfig(
                    maxIdleTurns = 4,
                    defaultWaitMillis = 1L,
                    maxRuntimeMillis = 5_000L
                )
            )
        )

        val result = service.invoke(
            AgentInvocationRequest(
                agentId = "event_agent",
                inputs = listOf(
                    AgentInvocationInput(
                        role = AgentMessageRole.User,
                        content = "run"
                    )
                )
            )
        )

        assertEquals(AgentInvocationStatus.Stopped, result.status)
        val events = service.getAutonomousRuntimeEvents(result.invocationId)
        assertEquals(3, events.size)

        service.shutdown()
    }

    private class TestTool(name: String) : AgentTool {
        override val definition: AgentToolDefinition = AgentToolDefinition(
            name = name,
            description = "Test tool $name"
        )

        override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
            return AgentToolResult(
                callId = call.id,
                toolName = call.name,
                success = true,
                output = mapOf("name" to call.name)
            )
        }
    }
}

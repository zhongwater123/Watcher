package com.example.watcher.agentframework

import com.example.watcher.agentframework.autonomy.AgentSignal
import com.example.watcher.agentframework.autonomy.AutonomousAgentConfig
import com.example.watcher.agentframework.autonomy.AutonomousAgentRuntime
import com.example.watcher.agentframework.autonomy.AutonomousLifecycleState
import com.example.watcher.agentframework.autonomy.AutonomousStopReason
import com.example.watcher.agentframework.autonomy.CorrectionAction
import com.example.watcher.agentframework.autonomy.CorrectionRecord
import com.example.watcher.agentframework.autonomy.CorrectionTrigger
import com.example.watcher.agentframework.autonomy.InMemoryStructuredMemoryManager
import com.example.watcher.agentframework.autonomy.InMemoryCommunicationHub
import com.example.watcher.agentframework.autonomy.SignalChannel
import com.example.watcher.agentframework.autonomy.defaultAutonomousModules
import com.example.watcher.agentframework.core.AgentAction
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentToolCall
import com.example.watcher.agentframework.core.AgentToolDefinition
import com.example.watcher.agentframework.core.AgentToolResult
import com.example.watcher.agentframework.runtime.AgentBrain
import com.example.watcher.agentframework.runtime.AgentBrainRequest
import com.example.watcher.agentframework.tools.AgentTool
import com.example.watcher.agentframework.tools.AgentToolContext
import com.example.watcher.agentframework.tools.AgentToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousAgentRuntimeTest {

    @Test
    fun closedLoopRuntimeRunsToStop() = runBlocking {
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                return AgentDecision(
                    reply = "task completed",
                    action = AgentAction.Finish(
                        reason = "done",
                        success = true
                    )
                )
            }
        }

        val runtime = AutonomousAgentRuntime(
            definition = AgentDefinition(
                agentId = "auto_1",
                name = "Auto Agent",
                systemInstruction = "Complete the task safely",
                goal = "Finish when the job is complete"
            ),
            config = AutonomousAgentConfig(
                maxCycles = 4,
                loopDelayMillis = 1L
            ),
            modules = defaultAutonomousModules(
                brain = brain,
                toolExecutor = AgentToolRegistry()
            ),
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

        runtime.submitSignal(
            AgentSignal(
                channel = SignalChannel.User,
                content = "begin"
            )
        )
        runtime.start()
        val finalSnapshot = runtime.awaitCompletion()

        assertEquals(AutonomousLifecycleState.Stopped, finalSnapshot.lifecycleState)
        assertEquals(1, finalSnapshot.cycle)
        assertTrue(finalSnapshot.outputs.contains("task completed"))
        assertTrue(finalSnapshot.records.isNotEmpty())
    }

    @Test
    fun runtimeCapsRetainedOutputsInCommunicationHub() = runBlocking {
        var cycle = 0
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                cycle += 1
                return if (cycle < 5) {
                    AgentDecision(
                        reply = "step-$cycle",
                        action = AgentAction.Continue
                    )
                } else {
                    AgentDecision(
                        reply = "step-$cycle",
                        action = AgentAction.Finish(reason = "done", success = true)
                    )
                }
            }
        }

        val runtime = AutonomousAgentRuntime(
            definition = AgentDefinition(
                agentId = "auto_cap",
                name = "Capped Agent",
                systemInstruction = "Keep only recent outputs",
                goal = "Run through several cycles"
            ),
            config = AutonomousAgentConfig(
                maxCycles = 6,
                loopDelayMillis = 1L
            ),
            modules = defaultAutonomousModules(
                brain = brain,
                toolExecutor = AgentToolRegistry(),
                communicationHub = InMemoryCommunicationHub(maxOutputsPerSession = 2)
            ),
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

        runtime.submitSignal(
            AgentSignal(
                channel = SignalChannel.User,
                content = "begin"
            )
        )
        runtime.start()
        val finalSnapshot = runtime.awaitCompletion()

        assertEquals(AutonomousLifecycleState.Stopped, finalSnapshot.lifecycleState)
        assertEquals(listOf("step-4", "step-5"), finalSnapshot.outputs)
    }

    @Test
    fun timeoutToolFailureRetriesOriginalDecisionOnce() = runBlocking {
        var toolCalls = 0
        var brainCalls = 0
        val registry = AgentToolRegistry().register(
            object : AgentTool {
                override val definition = AgentToolDefinition("flaky_tool", "Fails once like a timeout")

                override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
                    toolCalls += 1
                    return if (toolCalls == 1) {
                        AgentToolResult(
                            callId = call.id,
                            toolName = call.name,
                            success = false,
                            error = "Tool execution timed out after 1ms"
                        )
                    } else {
                        AgentToolResult(callId = call.id, toolName = call.name, success = true)
                    }
                }
            }
        )
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                brainCalls += 1
                return if (brainCalls == 1) {
                    AgentDecision(
                        action = AgentAction.UseTools(
                            listOf(AgentToolCall(id = "call-1", name = "flaky_tool"))
                        )
                    )
                } else {
                    AgentDecision(
                        reply = "finished after retry",
                        action = AgentAction.Finish("done", success = true)
                    )
                }
            }
        }

        val runtime = AutonomousAgentRuntime(
            definition = testDefinition("timeout_retry"),
            config = AutonomousAgentConfig(maxCycles = 4, loopDelayMillis = 1L),
            modules = defaultAutonomousModules(brain = brain, toolExecutor = registry),
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

        runtime.submitSignal(AgentSignal(channel = SignalChannel.User, content = "begin"))
        runtime.start()
        val finalSnapshot = runtime.awaitCompletion()

        assertEquals(AutonomousLifecycleState.Stopped, finalSnapshot.lifecycleState)
        assertEquals(AutonomousStopReason.GoalAchieved, finalSnapshot.stopReason)
        assertEquals(2, toolCalls)
        assertTrue(finalSnapshot.correctionRecords.any { it.action == CorrectionAction.RetryOriginalDecision })
    }

    @Test
    fun ordinaryToolFailureDoesNotInventRevisedDecision() = runBlocking {
        var toolCalls = 0
        var brainCalls = 0
        val registry = AgentToolRegistry().register(
            object : AgentTool {
                override val definition = AgentToolDefinition("failing_tool", "Always fails")

                override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
                    toolCalls += 1
                    return AgentToolResult(
                        callId = call.id,
                        toolName = call.name,
                        success = false,
                        error = "missing required argument"
                    )
                }
            }
        )
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                brainCalls += 1
                return if (brainCalls == 1) {
                    AgentDecision(
                        action = AgentAction.UseTools(
                            listOf(AgentToolCall(id = "call-1", name = "failing_tool"))
                        )
                    )
                } else {
                    assertTrue(request.definition.goal.contains("Recent correction context"))
                    AgentDecision(
                        reply = "handled failure",
                        action = AgentAction.Finish("done", success = true)
                    )
                }
            }
        }

        val runtime = AutonomousAgentRuntime(
            definition = testDefinition("ordinary_failure"),
            config = AutonomousAgentConfig(maxCycles = 3, loopDelayMillis = 1L),
            modules = defaultAutonomousModules(brain = brain, toolExecutor = registry),
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

        runtime.submitSignal(AgentSignal(channel = SignalChannel.User, content = "begin"))
        runtime.start()
        val finalSnapshot = runtime.awaitCompletion()

        assertEquals(AutonomousStopReason.GoalAchieved, finalSnapshot.stopReason)
        assertEquals(1, toolCalls)
        assertTrue(finalSnapshot.correctionRecords.any { it.action == CorrectionAction.WaitForSignal })
    }

    @Test
    fun ruleBlockedDecisionDoesNotRetryOriginalAction() = runBlocking {
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                return AgentDecision(
                    action = AgentAction.UseTools(
                        listOf(AgentToolCall(id = "bad", name = ""))
                    )
                )
            }
        }
        val runtime = AutonomousAgentRuntime(
            definition = testDefinition("rule_blocked"),
            config = AutonomousAgentConfig(maxCycles = 2, loopDelayMillis = 1L),
            modules = defaultAutonomousModules(brain = brain, toolExecutor = AgentToolRegistry()),
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

        runtime.submitSignal(AgentSignal(channel = SignalChannel.User, content = "begin"))
        runtime.start()
        val finalSnapshot = runtime.awaitCompletion()

        assertEquals(AutonomousLifecycleState.Failed, finalSnapshot.lifecycleState)
        assertEquals(CorrectionTrigger.RuleBlocked, finalSnapshot.correctionRecords.first().trigger)
        assertEquals(CorrectionAction.AbortFailure, finalSnapshot.correctionRecords.first().action)
    }

    @Test
    fun cycleExceptionConsumesOnlyOneFailureQuota() = runBlocking {
        val brain = object : AgentBrain {
            override suspend fun decide(request: AgentBrainRequest): AgentDecision {
                error("boom")
            }
        }
        val runtime = AutonomousAgentRuntime(
            definition = testDefinition("cycle_exception"),
            config = AutonomousAgentConfig(maxCycles = 2, maxFailures = 1, loopDelayMillis = 1L),
            modules = defaultAutonomousModules(brain = brain, toolExecutor = AgentToolRegistry()),
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

        runtime.submitSignal(AgentSignal(channel = SignalChannel.User, content = "begin"))
        runtime.start()
        val finalSnapshot = runtime.awaitCompletion()

        assertEquals(AutonomousLifecycleState.Failed, finalSnapshot.lifecycleState)
        assertEquals(1, finalSnapshot.failureCount)
        assertEquals(CorrectionTrigger.CycleException, finalSnapshot.correctionRecords.first().trigger)
    }

    @Test
    fun correctionWorkingMemoryKeepsOnlyRecentThreeEntries() = runBlocking {
        val manager = InMemoryStructuredMemoryManager()
        repeat(5) { index ->
            manager.onCorrection(
                "session",
                CorrectionRecord(
                    cycle = index + 1,
                    attempt = 1,
                    trigger = CorrectionTrigger.ToolFailure,
                    action = CorrectionAction.WaitForSignal,
                    reason = "failure-$index",
                    failureSignature = "sig-$index"
                )
            )
        }

        val correctionWorking = manager.snapshot("session").working.filter {
            it.metadata["kind"] == "correction"
        }
        assertEquals(3, correctionWorking.size)
        assertTrue(correctionWorking.first().content.contains("failure-2"))
    }

    private fun testDefinition(agentId: String): AgentDefinition {
        return AgentDefinition(
            agentId = agentId,
            name = "Test Agent",
            systemInstruction = "Test correction behavior.",
            goal = "Complete the test task."
        )
    }
}

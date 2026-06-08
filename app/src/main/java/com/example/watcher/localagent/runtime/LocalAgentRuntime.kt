package com.example.watcher.localagent.runtime

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.InMemoryMemoryService
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalAgentRuntime(
    private val agent: BaseAgent,
    appName: String = "watcher_local_agent",
    private val userId: String = "android-user-${UUID.randomUUID()}",
    private val sessionId: String = "android-session-${UUID.randomUUID()}"
) {
    private val runner = InMemoryRunner(
        agent,
        appName,
        InMemorySessionService(),
        InMemoryArtifactService(),
        InMemoryMemoryService(),
        PluginManager()
    )

    val agentName: String
        get() = agent.name

    fun sendMessage(text: String): Flow<LocalAgentRuntimeEvent> = flow {
        val userContent = Content(
            role = Role.USER,
            parts = listOf(Part(text = text))
        )
        runner.runAsync(
            userId,
            sessionId,
            null,
            userContent,
            emptyMap(),
            RunConfig(StreamingMode.NONE)
        ).collect { event ->
            event.toRuntimeEvents().forEach { runtimeEvent ->
                emit(runtimeEvent)
            }
        }
    }

    private fun Event.toRuntimeEvents(): List<LocalAgentRuntimeEvent> {
        val events = mutableListOf<LocalAgentRuntimeEvent>()
        val error = errorMessage
        if (!error.isNullOrBlank()) {
            events += LocalAgentRuntimeEvent.Error(
                code = errorCode,
                message = error
            )
        }

        functionCalls().forEach { call ->
            events += LocalAgentRuntimeEvent.ToolCall(
                name = call.name,
                args = call.args
            )
        }
        functionResponses().forEach { response ->
            events += LocalAgentRuntimeEvent.ToolResult(
                name = response.name,
                response = response.response
            )
        }

        val text = content?.parts
            ?.mapNotNull { it.text }
            ?.joinToString(separator = "\n")
            ?.trim()
        if (!text.isNullOrBlank()) {
            val hasPendingCalls = functionCalls().isNotEmpty()
            events += LocalAgentRuntimeEvent.Text(
                author = author,
                content = text,
                isFinal = !hasPendingCalls
            )
        }
        return events
    }
}

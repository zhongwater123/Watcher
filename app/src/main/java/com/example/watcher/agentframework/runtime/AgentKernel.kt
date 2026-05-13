package com.example.watcher.agentframework.runtime

import com.example.watcher.agentframework.core.AgentConversationItem
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentRunConfig
import com.example.watcher.agentframework.knowledge.AgentKnowledgeStore
import com.example.watcher.agentframework.knowledge.InMemoryAgentKnowledgeStore
import com.example.watcher.agentframework.memory.AgentMemoryStore
import com.example.watcher.agentframework.memory.InMemoryAgentMemoryStore
import com.example.watcher.agentframework.tools.AgentTool
import com.example.watcher.agentframework.tools.AgentToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@Deprecated(
    message = "Legacy runtime path. Use AgentFrameworkService with AutonomousAgentRuntime as the primary agent runtime entry point.",
    level = DeprecationLevel.WARNING
)
class AgentKernel(
    private val memoryStore: AgentMemoryStore = InMemoryAgentMemoryStore(),
    private val knowledgeStore: AgentKnowledgeStore = InMemoryAgentKnowledgeStore(),
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val sessions = linkedMapOf<String, AgentSessionController>()

    fun registerTool(tool: AgentTool): AgentKernel {
        toolRegistry.register(tool)
        return this
    }

    fun createSession(
        definition: AgentDefinition,
        brain: AgentBrain,
        config: AgentRunConfig = AgentRunConfig(),
        initialMessages: List<AgentConversationItem> = emptyList()
    ): AgentSessionController {
        val controller = AgentSessionController(
            definition = definition,
            brain = brain,
            config = config,
            memoryStore = memoryStore,
            knowledgeStore = knowledgeStore,
            toolRegistry = toolRegistry,
            parentScope = scope,
            initialMessages = initialMessages
        )
        sessions[controller.snapshot.value.sessionId] = controller
        return controller
    }

    fun getSession(sessionId: String): AgentSessionController? = sessions[sessionId]

    fun activeSessions(): List<AgentSessionController> = sessions.values.toList()

    fun shutdown() {
        sessions.values.forEach { it.stop() }
        scope.cancel()
    }
}

package com.example.watcher.data.council.agent.orchestration

import com.example.watcher.data.council.agent.memory.CouncilAgentKnowledgeStore
import com.example.watcher.data.council.agent.memory.CouncilAgentSessionMemory
import com.example.watcher.data.council.agent.runtime.CouncilAgentRuntime
import com.example.watcher.data.council.agent.runtime.CouncilLlmAgentBackend
import com.example.watcher.data.council.agent.tools.CouncilAgentToolExecutor
import com.example.watcher.data.council.agent.tools.CouncilKnowledgeQueryTool
import com.example.watcher.data.council.agent.tools.CouncilKnowledgeWriteTool
import com.example.watcher.data.council.agent.tools.CouncilObservationRequestTool
import com.example.watcher.data.council.agent.tools.CouncilSessionMemoryReadTool
import com.example.watcher.data.council.agent.tools.CouncilSessionMemoryWriteTool
import com.example.watcher.data.repository.SceneMemoryManager

class CouncilAgentRuntimeFactory(
    private val sessionMemory: CouncilAgentSessionMemory,
    private val knowledgeStore: CouncilAgentKnowledgeStore,
    private val sceneMemoryManager: SceneMemoryManager
) {
    internal fun create(binding: CouncilExpertAgentBinding): CouncilRegisteredAgent {
        val toolExecutor = CouncilAgentToolExecutor().apply {
            register(CouncilObservationRequestTool(sceneMemoryManager))
            register(CouncilKnowledgeQueryTool(knowledgeStore))
            register(CouncilKnowledgeWriteTool(knowledgeStore))
            register(CouncilSessionMemoryReadTool(sessionMemory))
            register(CouncilSessionMemoryWriteTool(sessionMemory))
        }
        return CouncilRegisteredAgent(
            id = binding.spec.expertId,
            profile = CouncilAgentModelMapper.toAgentProfile(binding.spec),
            runtime = CouncilAgentRuntime(
                backend = CouncilLlmAgentBackend(binding.provider),
                toolExecutor = toolExecutor
            )
        )
    }
}

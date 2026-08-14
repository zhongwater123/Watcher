package com.example.watcher.data.council.agent

import com.example.watcher.data.council.agent.memory.CouncilAgentKnowledgeStore
import com.example.watcher.data.council.agent.memory.CouncilAgentSessionMemory
import com.example.watcher.data.council.agent.orchestration.CouncilAgentRuntimeFactory
import com.example.watcher.data.council.agent.orchestration.CouncilExpertAgentEngine
import com.example.watcher.data.council.agent.orchestration.CouncilDefaultExpertAgentEngine
import com.example.watcher.data.local.CouncilKnowledgeDao
import com.example.watcher.data.repository.SceneMemoryManager

class CouncilAgentFeatureContainer(
    knowledgeDao: CouncilKnowledgeDao,
    sceneMemoryManager: SceneMemoryManager
) {
    private val sessionMemory = CouncilAgentSessionMemory()
    private val knowledgeStore = CouncilAgentKnowledgeStore(knowledgeDao)
    private val runtimeFactory = CouncilAgentRuntimeFactory(
        sessionMemory = sessionMemory,
        knowledgeStore = knowledgeStore,
        sceneMemoryManager = sceneMemoryManager
    )

    val expertAgentEngine: CouncilExpertAgentEngine = CouncilDefaultExpertAgentEngine(
        runtimeFactory = runtimeFactory,
        sessionMemory = sessionMemory
    )
}

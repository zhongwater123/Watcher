package com.example.watcher.data.fitness.agent.planning

import com.example.watcher.data.remote.ChatMessage
import com.example.watcher.data.repository.LlmWalletRepository

data class FitnessAgentModelRequest(
    val agentType: String,
    val model: String,
    val systemPrompt: String,
    val userPrompt: String
)

data class FitnessAgentModelResponse(
    val rawText: String
)

interface FitnessAgentModelClient {
    suspend fun generate(request: FitnessAgentModelRequest): FitnessAgentModelResponse
}

class FitnessWalletAgentModelClient(
    private val llmWalletRepository: LlmWalletRepository
) : FitnessAgentModelClient {
    override suspend fun generate(request: FitnessAgentModelRequest): FitnessAgentModelResponse {
        val provider = llmWalletRepository.resolveOpenAiProvider(request.model)
        return FitnessAgentModelResponse(
            rawText = provider.chat(
                systemPrompt = request.systemPrompt,
                messages = listOf(ChatMessage(role = "user", content = request.userPrompt))
            )
        )
    }
}

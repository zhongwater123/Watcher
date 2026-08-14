package com.example.watcher.data.fitness.agent.planning

import android.util.Log
import com.example.watcher.data.model.FITNESS_COMPANION_LOG_TAG

class FitnessStrategyPlanningAgent(
    private val modelClient: FitnessAgentModelClient,
    private val configuration: FitnessAgentConfiguration = FitnessAgentConfigurations.strategy,
    private val promptBuilder: FitnessStrategyPlanningPromptBuilder =
        FitnessStrategyPlanningPromptBuilder(),
    private val responseParser: FitnessStrategyPlanningResponseParser =
        FitnessStrategyPlanningResponseParser()
) : FitnessStrategyGenerator {
    override suspend fun generate(input: FitnessStrategyGenerationInput): FitnessGeneratedStrategy {
        val systemPrompt = promptBuilder.buildSystemPrompt()
        val userPrompt = promptBuilder.buildUserPrompt(input)
        Log.d(FITNESS_COMPANION_LOG_TAG, "strategy:agent_start model=${configuration.model}")
        val response = modelClient.generate(
            FitnessAgentModelRequest(
                agentType = configuration.agentType,
                model = configuration.model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt
            )
        )
        val generated = responseParser.parse(response.rawText, input)
        Log.d(FITNESS_COMPANION_LOG_TAG, "strategy:agent_success model=${configuration.model}")
        return generated.copy(
            trace = FitnessAgentGenerationTrace(
                prompt = userPrompt,
                rawResponse = response.rawText
            )
        )
    }
}

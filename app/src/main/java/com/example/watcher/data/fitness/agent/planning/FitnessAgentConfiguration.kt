package com.example.watcher.data.fitness.agent.planning

data class FitnessAgentConfiguration(
    val agentType: String,
    val displayName: String,
    val model: String
)

object FitnessAgentConfigurations {
    const val STRATEGY_AGENT_TYPE = "strategy"
    const val WORKOUT_AGENT_TYPE = "workout_plan"

    val strategy = FitnessAgentConfiguration(
        agentType = STRATEGY_AGENT_TYPE,
        displayName = "健身战略规划 Agent",
        model = "doubao-seed-2-0-lite-260428"
    )

    val workout = FitnessAgentConfiguration(
        agentType = WORKOUT_AGENT_TYPE,
        displayName = "单次训练计划 Agent",
        model = "doubao-seed-2-0-lite-260428"
    )
}

package com.example.watcher.data.fitness.agent.planning

import com.google.gson.Gson
import com.google.gson.JsonArray

class FitnessStrategyPlanningResponseParser(
    private val gson: Gson = Gson()
) {
    fun parse(
        rawResponse: String,
        input: FitnessStrategyGenerationInput
    ): FitnessGeneratedStrategy {
        val root = rawResponse.extractFitnessAgentJsonObject()
        val specRoot = root.fitnessObjectOrSelf("strategy_spec")
        val strategyVersion = specRoot.fitnessString(
            "strategy_version",
            specRoot.fitnessString("strategyVersion", "")
        )
        require(strategyVersion.isNotBlank()) { "Strategy agent did not return strategy_version." }
        val goals = specRoot.fitnessArrayOrEmpty("goals")
        require(goals.size() > 0) { "Strategy agent returned empty goals." }
        val generatedGoals = goals.mapIndexed { index, element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject
            val textGoal = element
                .takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asString }.getOrDefault("") }
                .orEmpty()
            val weeks = item?.get("weeks")
                ?.let { runCatching { it.asInt }.getOrNull() }
                ?.coerceAtLeast(1)
                ?: 4
            FitnessGeneratedStrategyGoal(
                title = item?.fitnessString(
                    "title",
                    textGoal.take(28).ifBlank { "阶段 ${index + 1}" }
                ) ?: textGoal.take(28).ifBlank { "阶段 ${index + 1}" },
                phaseLabel = item?.fitnessString("phaseLabel", "第 ${index + 1} 阶段")
                    ?: "第 ${index + 1} 阶段",
                weeks = weeks,
                summary = item?.fitnessString("summary", textGoal) ?: textGoal,
                milestonesJson = gson.toJson(item?.fitnessArrayOrEmpty("milestones") ?: JsonArray()),
                rawJson = element.toString()
            )
        }
        return FitnessGeneratedStrategy(
            spec = FitnessGeneratedStrategySpec(
                strategyVersion = strategyVersion,
                goalsJson = gson.toJson(goals),
                currentPhaseJson = gson.toJson(
                    specRoot.fitnessObjectOrEmpty("current_phase", "currentPhase")
                ),
                weeklyBudgetJson = gson.toJson(
                    specRoot.fitnessObjectOrEmpty(
                        "weekly_budget",
                        "weeklyBudget",
                        "weekly_training_budget"
                    )
                ),
                progressionRulesJson = gson.toJson(
                    specRoot.fitnessObjectOrEmpty("progression_rules", "progressionRules")
                ),
                autoregulationRulesJson = gson.toJson(
                    specRoot.fitnessObjectOrEmpty("autoregulation_rules", "autoregulationRules")
                ),
                hardConstraintsJson = gson.toJson(
                    specRoot.fitnessArrayOrEmpty("hard_constraints", "hardConstraints")
                ),
                replanTriggersJson = gson.toJson(
                    specRoot.fitnessArrayOrEmpty("replan_triggers", "replanTriggers")
                ),
                rawJson = specRoot.toString()
            ),
            goals = generatedGoals
        )
    }
}

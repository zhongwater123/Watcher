package com.example.watcher.data.fitness.agent.planning

data class FitnessPlanningProfile(
    val profileId: String,
    val goalType: String,
    val previousAttempt: String,
    val targetPartsJson: String,
    val currentWeightKg: Float,
    val targetWeightKg: Float?,
    val gender: String,
    val age: Int,
    val heightCm: Int,
    val currentBodyType: String,
    val targetBodyType: String,
    val injuryPartsJson: String,
    val sedentaryLevel: String,
    val sleepQuality: String,
    val dietHabitsJson: String,
    val exerciseFrequency: String,
    val preferredPlacesJson: String,
    val gymVisitsPerWeek: Int,
    val equipmentKnowledge: String,
    val plankSeconds: Int,
    val stairFeeling: String
)

data class FitnessAgentGenerationTrace(
    val prompt: String,
    val rawResponse: String
)

data class FitnessStrategyGenerationInput(
    val profile: FitnessPlanningProfile
)

data class FitnessGeneratedStrategySpec(
    val strategyVersion: String,
    val goalsJson: String,
    val currentPhaseJson: String,
    val weeklyBudgetJson: String,
    val progressionRulesJson: String,
    val autoregulationRulesJson: String,
    val hardConstraintsJson: String,
    val replanTriggersJson: String,
    val rawJson: String
)

data class FitnessGeneratedStrategyGoal(
    val title: String,
    val phaseLabel: String,
    val weeks: Int,
    val summary: String,
    val milestonesJson: String,
    val rawJson: String
)

data class FitnessGeneratedStrategy(
    val spec: FitnessGeneratedStrategySpec,
    val goals: List<FitnessGeneratedStrategyGoal>,
    val trace: FitnessAgentGenerationTrace = FitnessAgentGenerationTrace("", "")
)

data class FitnessWorkoutGenerationInput(
    val profile: FitnessPlanningProfile,
    val strategyVersion: String,
    val strategySpecJson: String,
    val dailyContextJson: String,
    val remainingWeeklyBudgetJson: String,
    val nextDayOffset: Int,
    val currentEpochDay: Long
)

data class FitnessGeneratedWorkoutPlan(
    val title: String,
    val objective: String,
    val plannedDateEpochDay: Long,
    val estimatedMinutes: Int,
    val intensityLabel: String,
    val warmupJson: String,
    val cooldownJson: String,
    val coachNotes: String,
    val sessionId: String,
    val strategyVersion: String,
    val dailyContextJson: String,
    val sessionPlanJson: String,
    val expectedBudgetUsageJson: String,
    val adjustmentsJson: String,
    val stopRulesJson: String,
    val rawJson: String
)

data class FitnessGeneratedWorkoutExercise(
    val sortOrder: Int,
    val category: String,
    val equipment: String,
    val name: String,
    val sets: Int,
    val reps: String,
    val durationSeconds: Int,
    val restSeconds: Int,
    val intensity: String,
    val movementPattern: String,
    val targetMusclesJson: String,
    val warmupSetsJson: String,
    val repRangeMin: Int,
    val repRangeMax: Int,
    val targetRir: Float,
    val restSecondsMin: Int,
    val restSecondsMax: Int,
    val tempo: String,
    val loadSelectionRule: String,
    val progressionRule: String,
    val substitutionsJson: String,
    val stopCondition: String,
    val priority: String,
    val notes: String
)

data class FitnessGeneratedWorkout(
    val plan: FitnessGeneratedWorkoutPlan,
    val exercises: List<FitnessGeneratedWorkoutExercise>,
    val trace: FitnessAgentGenerationTrace = FitnessAgentGenerationTrace("", "")
)

interface FitnessStrategyGenerator {
    suspend fun generate(input: FitnessStrategyGenerationInput): FitnessGeneratedStrategy
}

interface FitnessWorkoutPlanGenerator {
    suspend fun generate(input: FitnessWorkoutGenerationInput): FitnessGeneratedWorkout
}

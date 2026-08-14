package com.example.watcher.data.fitness

import com.example.watcher.data.fitness.agent.planning.FitnessPlanningProfile
import com.example.watcher.data.fitness.agent.planning.FitnessGeneratedStrategy
import com.example.watcher.data.fitness.agent.planning.FitnessGeneratedWorkout
import com.example.watcher.data.model.FitnessStrategyGoalEntity
import com.example.watcher.data.model.FitnessStrategySpecEntity
import com.example.watcher.data.model.FitnessUserProfileEntity
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity
import com.example.watcher.data.model.FitnessWorkoutPlanEntity

fun FitnessUserProfileEntity.toFitnessPlanningProfile() = FitnessPlanningProfile(
    profileId = profileId,
    goalType = goalType,
    previousAttempt = previousAttempt,
    targetPartsJson = targetPartsJson,
    currentWeightKg = currentWeightKg,
    targetWeightKg = targetWeightKg,
    gender = gender,
    age = age,
    heightCm = heightCm,
    currentBodyType = currentBodyType,
    targetBodyType = targetBodyType,
    injuryPartsJson = injuryPartsJson,
    sedentaryLevel = sedentaryLevel,
    sleepQuality = sleepQuality,
    dietHabitsJson = dietHabitsJson,
    exerciseFrequency = exerciseFrequency,
    preferredPlacesJson = preferredPlacesJson,
    gymVisitsPerWeek = gymVisitsPerWeek,
    equipmentKnowledge = equipmentKnowledge,
    plankSeconds = plankSeconds,
    stairFeeling = stairFeeling
)

data class FitnessStrategyPersistenceDraft(
    val spec: FitnessStrategySpecEntity,
    val goals: List<FitnessStrategyGoalEntity>
)

fun FitnessGeneratedStrategy.toPersistenceDraft(
    profileId: String,
    todayEpochDay: Long
): FitnessStrategyPersistenceDraft {
    return FitnessStrategyPersistenceDraft(
        spec = FitnessStrategySpecEntity(
            profileId = profileId,
            strategyVersion = spec.strategyVersion,
            goalsJson = spec.goalsJson,
            currentPhaseJson = spec.currentPhaseJson,
            weeklyBudgetJson = spec.weeklyBudgetJson,
            progressionRulesJson = spec.progressionRulesJson,
            autoregulationRulesJson = spec.autoregulationRulesJson,
            hardConstraintsJson = spec.hardConstraintsJson,
            replanTriggersJson = spec.replanTriggersJson,
            rawJson = spec.rawJson
        ),
        goals = goals.mapIndexed { index, goal ->
            val start = todayEpochDay + index * 28L
            FitnessStrategyGoalEntity(
                profileId = profileId,
                title = goal.title,
                phaseLabel = goal.phaseLabel,
                startDateEpochDay = start,
                endDateEpochDay = start + goal.weeks * 7L,
                summary = goal.summary,
                milestonesJson = goal.milestonesJson,
                rawJson = goal.rawJson
            )
        }
    )
}

data class FitnessWorkoutPersistenceDraft(
    val plan: FitnessWorkoutPlanEntity,
    val exercises: List<FitnessWorkoutExerciseEntity>
)

fun FitnessGeneratedWorkout.toPersistenceDraft(
    profileId: String
): FitnessWorkoutPersistenceDraft {
    return FitnessWorkoutPersistenceDraft(
        plan = FitnessWorkoutPlanEntity(
            profileId = profileId,
            title = plan.title,
            objective = plan.objective,
            plannedDateEpochDay = plan.plannedDateEpochDay,
            estimatedMinutes = plan.estimatedMinutes,
            intensityLabel = plan.intensityLabel,
            warmup = plan.warmupJson,
            cooldown = plan.cooldownJson,
            coachNotes = plan.coachNotes,
            sessionId = plan.sessionId,
            strategyVersion = plan.strategyVersion,
            dailyContextJson = plan.dailyContextJson,
            sessionPlanJson = plan.sessionPlanJson,
            expectedBudgetUsageJson = plan.expectedBudgetUsageJson,
            adjustmentsJson = plan.adjustmentsJson,
            stopRulesJson = plan.stopRulesJson,
            rawJson = plan.rawJson
        ),
        exercises = exercises.map { exercise ->
            FitnessWorkoutExerciseEntity(
                planId = 0,
                sortOrder = exercise.sortOrder,
                category = exercise.category,
                equipment = exercise.equipment,
                name = exercise.name,
                sets = exercise.sets,
                reps = exercise.reps,
                durationSeconds = exercise.durationSeconds,
                restSeconds = exercise.restSeconds,
                intensity = exercise.intensity,
                movementPattern = exercise.movementPattern,
                targetMusclesJson = exercise.targetMusclesJson,
                warmupSetsJson = exercise.warmupSetsJson,
                repRangeMin = exercise.repRangeMin,
                repRangeMax = exercise.repRangeMax,
                targetRir = exercise.targetRir,
                restSecondsMin = exercise.restSecondsMin,
                restSecondsMax = exercise.restSecondsMax,
                tempo = exercise.tempo,
                loadSelectionRule = exercise.loadSelectionRule,
                progressionRule = exercise.progressionRule,
                substitutionsJson = exercise.substitutionsJson,
                stopCondition = exercise.stopCondition,
                priority = exercise.priority,
                notes = exercise.notes
            )
        }
    )
}

package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.data.fitness.agent.planning.FitnessAgentConfigurations
import com.example.watcher.data.fitness.agent.planning.FitnessStrategyGenerationInput
import com.example.watcher.data.fitness.agent.planning.FitnessStrategyGenerator
import com.example.watcher.data.fitness.agent.planning.FitnessWorkoutGenerationInput
import com.example.watcher.data.fitness.agent.planning.FitnessWorkoutPlanGenerator
import com.example.watcher.data.fitness.currentFitnessEpochDay
import com.example.watcher.data.fitness.toFitnessPlanningProfile
import com.example.watcher.data.fitness.toPersistenceDraft
import com.example.watcher.data.local.FitnessCompanionDao
import com.example.watcher.data.model.FITNESS_COMPANION_LOG_TAG
import com.example.watcher.data.model.FitnessAgentRunEntity
import com.example.watcher.data.model.FitnessGenerationStatus
import com.example.watcher.data.model.FitnessGoalType
import com.example.watcher.data.model.FitnessStrategyGoalEntity
import com.example.watcher.data.model.FitnessUserProfileEntity
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity
import com.example.watcher.data.model.FitnessWorkoutLogEntity
import com.example.watcher.data.model.FitnessWorkoutPlanEntity
import com.example.watcher.data.model.FitnessWorkoutStatus
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class FitnessCompanionRepository(
    private val dao: FitnessCompanionDao,
    private val strategyGenerator: FitnessStrategyGenerator,
    private val workoutPlanGenerator: FitnessWorkoutPlanGenerator
) {
    private val gson = Gson()
    private val ledger = FitnessTrainingLedger()

    fun observeProfile(): Flow<FitnessUserProfileEntity?> = dao.observeProfile()
    fun observeStrategyGoals(): Flow<List<FitnessStrategyGoalEntity>> = dao.observeStrategyGoals()
    fun observeWorkoutPlans(): Flow<List<FitnessWorkoutPlanEntity>> = dao.observeWorkoutPlans()
    fun observeExercises(planId: Long): Flow<List<FitnessWorkoutExerciseEntity>> = dao.observeExercisesForPlan(planId)
    fun observeRecentLogs(): Flow<List<FitnessWorkoutLogEntity>> = dao.observeRecentLogs()
    fun observeAgentRuns(): Flow<List<FitnessAgentRunEntity>> = dao.observeAgentRuns()

    suspend fun ensureProfile(): FitnessUserProfileEntity {
        return dao.getProfile() ?: FitnessUserProfileEntity().also { dao.upsertProfile(it) }
    }

    suspend fun saveProfile(profile: FitnessUserProfileEntity) {
        dao.upsertProfile(profile.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun ensureInitialGeneration() = withContext(Dispatchers.IO) {
        val profile = dao.getProfile() ?: return@withContext
        if (!profile.isComplete) return@withContext

        Log.d(FITNESS_COMPANION_LOG_TAG, "ensureInitialGeneration profileId=${profile.profileId}")
        val activeSpec = dao.getActiveStrategySpec(profile.profileId)
        val activeLedger = activeSpec?.let {
            dao.getWeeklyLedger(
                profileId = profile.profileId,
                strategyVersion = it.strategyVersion,
                weekStartEpochDay = ledger.weekStartEpochDay()
            )
        }
        if (activeSpec == null || activeSpec.status == FitnessGenerationStatus.Failed.name || activeLedger?.replanRequired == true) {
            generateStrategyGoals(profile)
        }

        val freshSpec = dao.getActiveStrategySpec(profile.profileId) ?: return@withContext
        val activePlan = dao.getActiveWorkoutPlan()
        Log.d(
            FITNESS_COMPANION_LOG_TAG,
            "ensureInitialGeneration activeSpec=${freshSpec.strategyVersion} activePlanId=${activePlan?.id ?: 0} activePlanVersion=${activePlan?.strategyVersion.orEmpty()} activePlanStatus=${activePlan?.status.orEmpty()}"
        )
        if (activePlan == null || activePlan.strategyVersion != freshSpec.strategyVersion) {
            generateWorkoutPlan(profile, nextDayOffset = 0)
        } else {
            Log.d(FITNESS_COMPANION_LOG_TAG, "ensureInitialGeneration skip workout generation planId=${activePlan.id}")
        }
    }

    suspend fun generateStrategyGoals(profile: FitnessUserProfileEntity) = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val input = FitnessStrategyGenerationInput(profile.toFitnessPlanningProfile())
        Log.d(
            FITNESS_COMPANION_LOG_TAG,
            "strategy:start profileId=${profile.profileId} model=${FitnessAgentConfigurations.strategy.model}"
        )
        dao.insertAgentRun(
            FitnessAgentRunEntity(
                profileId = profile.profileId,
                agentType = FitnessAgentConfigurations.STRATEGY_AGENT_TYPE,
                status = FitnessGenerationStatus.Generating.name,
                promptSummary = "generate fitness strategy"
            )
        )
        runCatching {
            val generated = strategyGenerator.generate(input)
            val result = generated.toPersistenceDraft(
                profileId = profile.profileId,
                todayEpochDay = currentFitnessEpochDay()
            )
            dao.replaceActiveStrategy(
                profileId = profile.profileId,
                spec = result.spec,
                goals = result.goals
            )
            val weeklyLedger = ledger.createWeeklyLedger(profile, result.spec)
            dao.upsertWeeklyLedger(weeklyLedger)
            Log.d(
                FITNESS_COMPANION_LOG_TAG,
                "strategy:success profileId=${profile.profileId} version=${result.spec.strategyVersion} goals=${result.goals.size}"
            )
            dao.insertAgentRun(
                FitnessAgentRunEntity(
                    profileId = profile.profileId,
                    agentType = FitnessAgentConfigurations.STRATEGY_AGENT_TYPE,
                    status = FitnessGenerationStatus.Ready.name,
                    promptSummary = generated.trace.prompt.take(500),
                    rawResponse = generated.trace.rawResponse,
                    parsedJson = gson.toJson(generated.goals),
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
        }.onFailure { error ->
            Log.e(FITNESS_COMPANION_LOG_TAG, "strategy:failed profileId=${profile.profileId}", error)
            dao.insertAgentRun(
                FitnessAgentRunEntity(
                    profileId = profile.profileId,
                    agentType = FitnessAgentConfigurations.STRATEGY_AGENT_TYPE,
                    status = FitnessGenerationStatus.Failed.name,
                    promptSummary = "generate fitness strategy",
                    errorMessage = error.message.orEmpty(),
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
        }
    }

    suspend fun generateWorkoutPlan(
        profile: FitnessUserProfileEntity,
        nextDayOffset: Int = 1
    ) = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (dao.getActiveStrategySpec(profile.profileId) == null) {
            generateStrategyGoals(profile)
        }
        val strategySpec = dao.getActiveStrategySpec(profile.profileId)
        if (strategySpec == null) {
            val message = "No active strategy_spec for workout generation."
            Log.e(FITNESS_COMPANION_LOG_TAG, "workout:failed profileId=${profile.profileId} $message")
            dao.insertAgentRun(
                FitnessAgentRunEntity(
                    profileId = profile.profileId,
                    agentType = FitnessAgentConfigurations.WORKOUT_AGENT_TYPE,
                    status = FitnessGenerationStatus.Failed.name,
                    promptSummary = message,
                    errorMessage = message,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
            return@withContext
        }
        var promptSummary = "build workout generation context"
        runCatching {
            val weekStart = ledger.weekStartEpochDay(currentFitnessEpochDay() + nextDayOffset)
            val weeklyLedger = dao.getWeeklyLedger(
                profileId = profile.profileId,
                strategyVersion = strategySpec.strategyVersion,
                weekStartEpochDay = weekStart
            ) ?: ledger.createWeeklyLedger(profile, strategySpec, weekStart).also { dao.upsertWeeklyLedger(it) }
            val dailyContext = ledger.buildDailyContext(
                profile = profile,
                spec = strategySpec,
                ledger = weeklyLedger,
                nextDayOffset = nextDayOffset
            )
            val input = FitnessWorkoutGenerationInput(
                profile = profile.toFitnessPlanningProfile(),
                strategyVersion = strategySpec.strategyVersion,
                strategySpecJson = strategySpec.rawJson,
                dailyContextJson = dailyContext,
                remainingWeeklyBudgetJson = weeklyLedger.remainingBudgetJson,
                nextDayOffset = nextDayOffset,
                currentEpochDay = currentFitnessEpochDay()
            )
            Log.d(
                FITNESS_COMPANION_LOG_TAG,
                "workout:start profileId=${profile.profileId} nextDayOffset=$nextDayOffset model=${FitnessAgentConfigurations.workout.model}"
            )
            dao.insertAgentRun(
                FitnessAgentRunEntity(
                    profileId = profile.profileId,
                    agentType = FitnessAgentConfigurations.WORKOUT_AGENT_TYPE,
                    status = FitnessGenerationStatus.Generating.name,
                    promptSummary = promptSummary
                )
            )
            val generated = workoutPlanGenerator.generate(input)
            val result = generated.toPersistenceDraft(profile.profileId)
            val validation = ledger.validatePlan(result.plan, result.exercises, weeklyLedger)
            require(validation.isValid) { validation.reason }
            val planId = dao.insertWorkoutPlan(result.plan)
            dao.replaceExercises(
                planId = planId,
                exercises = result.exercises.map { it.copy(planId = planId) }
            )
            Log.d(
                FITNESS_COMPANION_LOG_TAG,
                "workout:success profileId=${profile.profileId} planId=$planId exercises=${result.exercises.size}"
            )
            dao.insertAgentRun(
                FitnessAgentRunEntity(
                    profileId = profile.profileId,
                    agentType = FitnessAgentConfigurations.WORKOUT_AGENT_TYPE,
                    status = FitnessGenerationStatus.Ready.name,
                    promptSummary = generated.trace.prompt.take(500),
                    rawResponse = generated.trace.rawResponse,
                    parsedJson = gson.toJson(generated),
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
        }.onFailure { error ->
            Log.e(FITNESS_COMPANION_LOG_TAG, "workout:failed profileId=${profile.profileId}", error)
            dao.insertAgentRun(
                FitnessAgentRunEntity(
                    profileId = profile.profileId,
                    agentType = FitnessAgentConfigurations.WORKOUT_AGENT_TYPE,
                    status = FitnessGenerationStatus.Failed.name,
                    promptSummary = promptSummary,
                    errorMessage = error.message.orEmpty(),
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
        }
    }

    suspend fun completeWorkout(
        plan: FitnessWorkoutPlanEntity,
        completionLevel: String,
        fatigueLevel: String,
        painSignal: String,
        nextIntensityPreference: String,
        noteOption: String,
        mediaPipeRepCountsByExerciseId: Map<Long, Int> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        Log.d(
            FITNESS_COMPANION_LOG_TAG,
            "workout:complete planId=${plan.id} profileId=${plan.profileId} completion=$completionLevel fatigue=$fatigueLevel pain=$painSignal next=$nextIntensityPreference"
        )
        if (dao.getSessionResultForPlan(plan.id) != null) {
            Log.d(FITNESS_COMPANION_LOG_TAG, "workout:complete_ignored_duplicate planId=${plan.id}")
            return@withContext
        }
        val exercises = dao.getExercisesForPlan(plan.id)
        val weekStart = ledger.weekStartEpochDay(plan.plannedDateEpochDay)
        val weeklyLedger = dao.getWeeklyLedger(
            profileId = plan.profileId,
            strategyVersion = plan.strategyVersion,
            weekStartEpochDay = weekStart
        )
        dao.insertWorkoutLog(
            FitnessWorkoutLogEntity(
                profileId = plan.profileId,
                planId = plan.id,
                completionLevel = completionLevel,
                fatigueLevel = fatigueLevel,
                painSignal = painSignal,
                nextIntensityPreference = nextIntensityPreference,
                noteOption = noteOption,
                completedAt = now
            )
        )
        val resultDraft = ledger.buildSessionResult(
            plan = plan,
            exercises = exercises,
            completionLevel = completionLevel,
            fatigueLevel = fatigueLevel,
            painSignal = painSignal,
            noteOption = noteOption,
            mediaPipeRepCountsByExerciseId = mediaPipeRepCountsByExerciseId,
            completedAt = now
        )
        val sessionResultId = dao.insertSessionResult(resultDraft.sessionResult)
        dao.insertExerciseResults(resultDraft.exerciseResults.map { it.copy(sessionResultId = sessionResultId) })
        weeklyLedger?.let {
            dao.upsertWeeklyLedger(ledger.applySessionResult(it, resultDraft.sessionResult, exercises))
        }
        dao.updateWorkoutPlan(
            plan.copy(
                status = FitnessWorkoutStatus.Completed.name,
                updatedAt = now
            )
        )
        val updatedLedger = weeklyLedger?.let {
            dao.getWeeklyLedger(plan.profileId, plan.strategyVersion, weekStart)
        }
        dao.getProfile()?.takeIf { it.isComplete && updatedLedger?.replanRequired != true }?.let {
            generateWorkoutPlan(it, nextDayOffset = 1)
        }
    }

    companion object {
        fun currentEpochDay(): Long = currentFitnessEpochDay()

        fun buildPaceSummary(profile: FitnessUserProfileEntity): String {
            return when (profile.goalType) {
                FitnessGoalType.WeightLoss.name -> {
                    val target = profile.targetWeightKg
                    if (target == null || target >= profile.currentWeightKg) {
                        "先完成资料后，我会用健康节奏估算减重周期。"
                    } else {
                        val delta = profile.currentWeightKg - target
                        val weeklyLow = max(profile.currentWeightKg * 0.005f, 0.25f)
                        val weeklyHigh = min(profile.currentWeightKg * 0.01f, 0.9f)
                        val fastWeeks = ceil((delta / weeklyHigh).toDouble()).toInt().coerceAtLeast(1)
                        val slowWeeks = ceil((delta / weeklyLow).toDouble()).toInt().coerceAtLeast(fastWeeks)
                        "目标减重约 ${"%.1f".format(delta)} kg，健康节奏预计约 $fastWeeks-$slowWeeks 周。"
                    }
                }
                FitnessGoalType.MuscleTone.name -> {
                    val span = bodyTypeSpan(profile.currentBodyType, profile.targetBodyType).coerceAtLeast(1)
                    val consistency = consistencyFactor(profile.exerciseFrequency)
                    val low = ceil(span * 8 / consistency).toInt()
                    val high = ceil(span * 12 / consistency).toInt()
                    "按当前体型跨度和训练频次，第一轮塑形变化预计约 $low-$high 周可感知。"
                }
                FitnessGoalType.HealthyHabit.name -> {
                    "保持健康会先建立稳定节奏，预计 8-12 周接近每周 150 分钟活动 + 2 次力量训练。"
                }
                else -> "完成资料后，我会给你一个可坚持的训练节奏。"
            }
        }

        private fun bodyTypeSpan(current: String, target: String): Int {
            val order = listOf("偏瘦", "普通", "微胖", "偏胖", "强壮", "线条感")
            val a = order.indexOf(current).takeIf { it >= 0 } ?: 1
            val b = order.indexOf(target).takeIf { it >= 0 } ?: 1
            return kotlin.math.abs(a - b)
        }

        private fun consistencyFactor(frequency: String): Double {
            return when (frequency) {
                "几乎不运动" -> 0.6
                "偶尔" -> 0.8
                "每周1-2次" -> 1.0
                "每周3次以上" -> 1.25
                else -> 1.0
            }
        }
    }
}

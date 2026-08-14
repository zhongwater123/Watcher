package com.example.watcher.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.watcher.data.fitness.FitnessFeatureContainer
import com.example.watcher.data.fitness.agent.feedback.realtime.FitnessRealtimeVlmState
import com.example.watcher.data.local.AppDatabase
import com.example.watcher.data.model.FitnessAgentRunEntity
import com.example.watcher.data.model.FITNESS_COMPANION_LOG_TAG
import com.example.watcher.data.model.FITNESS_REP_COUNTER_LOG_TAG
import com.example.watcher.data.model.FITNESS_VLM_LOG_TAG
import com.example.watcher.data.model.FitnessGenerationStatus
import com.example.watcher.data.model.FitnessGoalType
import com.example.watcher.data.model.FitnessStrategyGoalEntity
import com.example.watcher.data.model.FitnessUserProfileEntity
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity
import com.example.watcher.data.model.FitnessWorkoutLogEntity
import com.example.watcher.data.model.FitnessWorkoutPlanEntity
import com.example.watcher.data.model.FitnessWorkoutStatus
import com.example.watcher.data.repository.ExerciseLibraryUiState
import com.example.watcher.data.repository.ExerciseLibraryRepository
import com.example.watcher.data.repository.FitnessCompanionRepository
import com.example.watcher.data.training.fitness.TrainingFrame
import com.example.watcher.data.training.fitness.TrainingIntervalContext
import com.example.watcher.watcherApplication
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val FITNESS_ONBOARDING_LAST_STEP = 24
private const val LOCAL_FALLBACK_MARKER = "local_fallback"
private const val FITNESS_AGENT_GENERATING_STALE_MS = 10 * 60 * 1000L

data class FitnessOnboardingDraft(
    val goalType: String = "",
    val previousAttempt: String = "",
    val targetParts: Set<String> = emptySet(),
    val targetWeightKg: Float = 60f,
    val gender: String = "",
    val age: Int = 28,
    val heightCm: Int = 170,
    val currentWeightKg: Float = 65f,
    val currentBodyType: String = "",
    val targetBodyType: String = "",
    val injuryParts: Set<String> = emptySet(),
    val sedentaryLevel: String = "",
    val sleepQuality: String = "",
    val dietHabits: Set<String> = emptySet(),
    val rewardPreference: String = "",
    val exerciseFrequency: String = "",
    val preferredPlaces: Set<String> = emptySet(),
    val gymVisitsPerWeek: Int = 0,
    val equipmentKnowledge: String = "",
    val plankSeconds: Int = 30,
    val stairFeeling: String = "",
    val onboardingStep: Int = 0
) {
    val requiresTargetWeight: Boolean get() = goalType == FitnessGoalType.WeightLoss.name
}

data class FitnessCompanionUiState(
    val profile: FitnessUserProfileEntity? = null,
    val strategyGoals: List<FitnessStrategyGoalEntity> = emptyList(),
    val plans: List<FitnessWorkoutPlanEntity> = emptyList(),
    val activePlan: FitnessWorkoutPlanEntity? = null,
    val exercises: List<FitnessWorkoutExerciseEntity> = emptyList(),
    val recentLogs: List<FitnessWorkoutLogEntity> = emptyList(),
    val agentRuns: List<FitnessAgentRunEntity> = emptyList(),
    val generating: Boolean = false,
    val latestError: String? = null
)

class FitnessCompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val gson = Gson()
    private val database = AppDatabase.getDatabase(application)
    private val featureContainer = FitnessFeatureContainer(
        application = application,
        database = database,
        llmWalletRepository = application.watcherApplication().llmWalletRepository
    )
    private val repository = featureContainer.companionRepository
    private val visualFeedbackAnalyzer = featureContainer.visualFeedbackAnalyzer
    private val repCounterRepository = featureContainer.repCounterRepository
    private val exerciseLibraryRepository = featureContainer.exerciseLibraryRepository
    private var exerciseLibraryLoadStarted = false

    private val _draft = MutableStateFlow(FitnessOnboardingDraft())
    private val _exerciseLibraryState = MutableStateFlow(ExerciseLibraryUiState())
    val draft: StateFlow<FitnessOnboardingDraft> = _draft
    val exerciseLibraryState: StateFlow<ExerciseLibraryUiState> = _exerciseLibraryState
    val videoStreamSettings = featureContainer.videoStreamSettings
    val realtimeVlmState: StateFlow<FitnessRealtimeVlmState> = visualFeedbackAnalyzer.state
    val repCounterState = repCounterRepository.state

    private val profileFlow = repository.observeProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val strategyGoalsFlow = repository.observeStrategyGoals()
        .map { goals -> goals.filterNot { it.rawJson == LOCAL_FALLBACK_MARKER } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val plansFlow = repository.observeWorkoutPlans()
        .map { plans -> plans.filterNot { it.rawJson == LOCAL_FALLBACK_MARKER } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activePlanFlow = plansFlow.map { plans ->
        val usablePlans = plans.filter {
            it.generationStatus == FitnessGenerationStatus.Ready.name && it.strategyVersion.isNotBlank()
        }
        usablePlans.firstOrNull { it.status == FitnessWorkoutStatus.Planned.name } ?: usablePlans.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val exercisesFlow = activePlanFlow.flatMapLatest { plan ->
        if (plan == null || plan.id == 0L) flowOf(emptyList()) else repository.observeExercises(plan.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val logsFlow = repository.observeRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val agentRunsFlow = repository.observeAgentRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<FitnessCompanionUiState> = combine(
        profileFlow,
        strategyGoalsFlow,
        plansFlow,
        activePlanFlow,
        exercisesFlow,
        logsFlow,
        agentRunsFlow
    ) { values ->
        val profile = values[0] as FitnessUserProfileEntity?
        val goals = values[1] as List<FitnessStrategyGoalEntity>
        val plans = values[2] as List<FitnessWorkoutPlanEntity>
        val activePlan = values[3] as FitnessWorkoutPlanEntity?
        val exercises = values[4] as List<FitnessWorkoutExerciseEntity>
        val logs = values[5] as List<FitnessWorkoutLogEntity>
        val runs = values[6] as List<FitnessAgentRunEntity>
        val latestRun = runs.firstOrNull()
        val latestRunIsFreshGenerating = latestRun?.status == FitnessGenerationStatus.Generating.name &&
            System.currentTimeMillis() - latestRun.createdAt < FITNESS_AGENT_GENERATING_STALE_MS
        FitnessCompanionUiState(
            profile = profile,
            strategyGoals = goals,
            plans = plans,
            activePlan = activePlan,
            exercises = exercises,
            recentLogs = logs,
            agentRuns = runs,
            generating = latestRunIsFreshGenerating,
            latestError = latestRun?.takeIf { it.status == FitnessGenerationStatus.Failed.name }?.errorMessage?.takeIf(String::isNotBlank)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FitnessCompanionUiState())

    init {
        viewModelScope.launch {
            val profile = repository.ensureProfile()
            _draft.value = profile.toDraft()
            repository.ensureInitialGeneration()
        }
    }

    fun loadExerciseLibrary() {
        if (exerciseLibraryLoadStarted) return
        exerciseLibraryLoadStarted = true
        viewModelScope.launch {
            _exerciseLibraryState.value = ExerciseLibraryUiState(loading = true)
            runCatching { exerciseLibraryRepository.loadExercises() }
                .onSuccess { exercises ->
                    _exerciseLibraryState.value = ExerciseLibraryUiState(
                        loading = false,
                        exercises = exercises,
                        facets = ExerciseLibraryRepository.buildFacets(exercises),
                        totalCount = exercises.size
                    )
                }
                .onFailure { error ->
                    exerciseLibraryLoadStarted = false
                    _exerciseLibraryState.value = ExerciseLibraryUiState(
                        loading = false,
                        errorMessage = error.message.orEmpty().ifBlank { "动作库加载失败" }
                    )
                }
        }
    }

    fun selectGoalType(value: String) = updateDraft { it.copy(goalType = value) }
    fun selectPreviousAttempt(value: String) = updateDraft { it.copy(previousAttempt = value) }
    fun toggleTargetPart(value: String) = updateDraft { it.copy(targetParts = toggleValue(it.targetParts, value, singleAll = "全身")) }
    fun setTargetWeight(value: Float) = updateDraft { it.copy(targetWeightKg = value) }
    fun selectGender(value: String) = updateDraft { it.copy(gender = value) }
    fun setAge(value: Int) = updateDraft { it.copy(age = value) }
    fun setHeight(value: Int) = updateDraft { it.copy(heightCm = value) }
    fun setCurrentWeight(value: Float) = updateDraft {
        val adjustedTarget = if (it.requiresTargetWeight && it.targetWeightKg >= value) {
            (value - 0.5f).coerceAtLeast(35f)
        } else {
            it.targetWeightKg
        }
        it.copy(currentWeightKg = value, targetWeightKg = adjustedTarget)
    }
    fun selectCurrentBodyType(value: String) = updateDraft { it.copy(currentBodyType = value) }
    fun selectTargetBodyType(value: String) = updateDraft { it.copy(targetBodyType = value) }
    fun toggleInjuryPart(value: String) = updateDraft { it.copy(injuryParts = toggleValue(it.injuryParts, value, singleAll = "无")) }
    fun selectSedentary(value: String) = updateDraft { it.copy(sedentaryLevel = value) }
    fun selectSleep(value: String) = updateDraft { it.copy(sleepQuality = value) }
    fun toggleDietHabit(value: String) = updateDraft { it.copy(dietHabits = toggleValue(it.dietHabits, value, singleAll = "饮食规律")) }
    fun selectReward(value: String) = updateDraft { it.copy(rewardPreference = value) }
    fun selectExerciseFrequency(value: String) = updateDraft { it.copy(exerciseFrequency = value) }
    fun togglePreferredPlace(value: String) = updateDraft { it.copy(preferredPlaces = toggleValue(it.preferredPlaces, value, singleAll = "都可以")) }
    fun setGymVisits(value: Int) = updateDraft { it.copy(gymVisitsPerWeek = value) }
    fun selectEquipmentKnowledge(value: String) = updateDraft { it.copy(equipmentKnowledge = value) }
    fun setPlankSeconds(value: Int) = updateDraft { it.copy(plankSeconds = value) }
    fun selectStairFeeling(value: String) = updateDraft { it.copy(stairFeeling = value) }

    fun goNext() {
        val nextStep = (_draft.value.onboardingStep + 1).coerceAtMost(FITNESS_ONBOARDING_LAST_STEP)
        updateDraft { it.copy(onboardingStep = nextStep) }
        persistDraft(isComplete = false)
    }

    fun goBack() {
        updateDraft { it.copy(onboardingStep = (it.onboardingStep - 1).coerceAtLeast(0)) }
        persistDraft(isComplete = false)
    }

    fun persistDraft(isComplete: Boolean = false) {
        val snapshot = _draft.value
        viewModelScope.launch {
            Log.d(FITNESS_COMPANION_LOG_TAG, "profile:save isComplete=$isComplete step=${snapshot.onboardingStep} goal=${snapshot.goalType}")
            repository.saveProfile(snapshot.toProfile(isComplete = isComplete))
            if (isComplete) {
                repository.ensureInitialGeneration()
            }
        }
    }

    fun completeOnboarding() {
        persistDraft(isComplete = true)
    }

    fun startProfileEditing() {
        val snapshot = (profileFlow.value?.toDraft() ?: _draft.value).copy(onboardingStep = 0)
        _draft.value = snapshot
        Log.d(FITNESS_COMPANION_LOG_TAG, "profile:edit_start")
        viewModelScope.launch {
            repository.saveProfile(snapshot.toProfile(isComplete = false))
        }
    }

    fun regenerateStrategyGoals() {
        viewModelScope.launch {
            val profile = profileFlow.value ?: repository.ensureProfile()
            Log.d(FITNESS_COMPANION_LOG_TAG, "strategy:regenerate_requested isComplete=${profile.isComplete}")
            if (profile.isComplete) repository.generateStrategyGoals(profile)
        }
    }

    fun regeneratePlan() {
        viewModelScope.launch {
            val profile = profileFlow.value ?: repository.ensureProfile()
            Log.d(FITNESS_COMPANION_LOG_TAG, "workout:regenerate_requested isComplete=${profile.isComplete}")
            if (profile.isComplete) repository.generateWorkoutPlan(profile, nextDayOffset = 0)
        }
    }

    fun completeWorkout(
        completionLevel: String,
        fatigueLevel: String,
        painSignal: String,
        nextIntensityPreference: String,
        noteOption: String
    ) {
        val plan = activePlanFlow.value ?: return
        Log.d(FITNESS_COMPANION_LOG_TAG, "workout:complete_requested planId=${plan.id}")
        val currentExercise = exercisesFlow.value.firstOrNull()
        val currentRepState = repCounterState.value
        val mediaPipeRepCounts = currentExercise
            ?.takeIf { currentRepState.active || currentRepState.officialRepCount > 0 }
            ?.let { mapOf(it.id to currentRepState.officialRepCount.coerceAtLeast(0)) }
            .orEmpty()
        viewModelScope.launch {
            repository.completeWorkout(
                plan = plan,
                completionLevel = completionLevel,
                fatigueLevel = fatigueLevel,
                painSignal = painSignal,
                nextIntensityPreference = nextIntensityPreference,
                noteOption = noteOption,
                mediaPipeRepCountsByExerciseId = mediaPipeRepCounts
            )
        }
    }

    fun updateTrainingFrame(bitmap: Bitmap?) {
        bitmap?.let { visualFeedbackAnalyzer.submitFrame(TrainingFrame(it)) }
        repCounterRepository.processFrame(bitmap)
    }

    fun startRealtimeVlmFeedback(plan: FitnessWorkoutPlanEntity?, exercise: FitnessWorkoutExerciseEntity?) {
        val profile = profileFlow.value ?: return
        if (plan == null) return
        Log.d(FITNESS_VLM_LOG_TAG, "fitness_vlm:start_requested planId=${plan.id} exercise=${exercise?.name.orEmpty()}")
        val targetExercise = exercise ?: return
        val sessionId = plan.sessionId.ifBlank { "fitness_plan_${plan.id}" }
        visualFeedbackAnalyzer.start(
            TrainingIntervalContext(
                profileId = profile.profileId,
                planId = plan.id,
                sessionId = sessionId,
                intervalId = "${sessionId}_${targetExercise.id}_${System.nanoTime()}",
                exerciseId = targetExercise.id,
                exerciseName = targetExercise.name,
                equipment = targetExercise.equipment,
                movementPattern = targetExercise.movementPattern,
                category = targetExercise.category
            )
        )
    }

    fun startRepCounter(plan: FitnessWorkoutPlanEntity?, exercise: FitnessWorkoutExerciseEntity?) {
        val profile = profileFlow.value ?: return
        if (plan == null) return
        Log.d(FITNESS_REP_COUNTER_LOG_TAG, "rep_counter:start_requested planId=${plan.id} exercise=${exercise?.name.orEmpty()}")
        repCounterRepository.start(profile = profile, plan = plan, exercise = exercise)
    }

    fun stopRepCounter() {
        Log.d(FITNESS_REP_COUNTER_LOG_TAG, "rep_counter:stop_requested")
        repCounterRepository.stop()
    }

    fun stopTrainingAnalyzers() {
        Log.d(FITNESS_VLM_LOG_TAG, "fitness_vlm:stop_requested")
        visualFeedbackAnalyzer.stop()
        repCounterRepository.stop()
    }

    override fun onCleared() {
        visualFeedbackAnalyzer.release()
        repCounterRepository.release()
        super.onCleared()
    }

    fun canAdvance(step: Int = _draft.value.onboardingStep): Boolean {
        val d = _draft.value
        return when (step) {
            0 -> d.goalType.isNotBlank()
            1 -> d.previousAttempt.isNotBlank()
            2 -> true
            3 -> d.targetParts.isNotEmpty()
            4 -> d.currentWeightKg in 35f..220f
            5 -> !d.requiresTargetWeight || (d.targetWeightKg in 35f..120f && d.targetWeightKg < d.currentWeightKg)
            6 -> d.gender.isNotBlank()
            7 -> d.age in 12..90
            8 -> d.heightCm in 120..230
            9 -> d.currentBodyType.isNotBlank()
            10 -> d.targetBodyType.isNotBlank()
            11 -> true
            12 -> d.injuryParts.isNotEmpty()
            13 -> d.sedentaryLevel.isNotBlank()
            14 -> d.sleepQuality.isNotBlank()
            15 -> d.dietHabits.isNotEmpty()
            16 -> true
            17 -> d.exerciseFrequency.isNotBlank()
            18 -> d.preferredPlaces.isNotEmpty()
            19 -> d.gymVisitsPerWeek in 0..7
            20 -> d.equipmentKnowledge.isNotBlank()
            21 -> d.plankSeconds in 0..180
            22 -> d.stairFeeling.isNotBlank()
            23 -> d.rewardPreference.isNotBlank()
            else -> true
        }
    }

    fun paceSummary(): String {
        return FitnessCompanionRepository.buildPaceSummary(_draft.value.toProfile(isComplete = false))
    }

    private fun updateDraft(transform: (FitnessOnboardingDraft) -> FitnessOnboardingDraft) {
        val next = transform(_draft.value)
        _draft.value = next
        viewModelScope.launch {
            repository.saveProfile(next.toProfile(isComplete = false))
        }
    }

    private fun toggleValue(values: Set<String>, value: String, singleAll: String? = null): Set<String> {
        if (singleAll != null && value == singleAll) return setOf(singleAll)
        val withoutSingle = if (singleAll != null) values - singleAll else values
        return if (value in withoutSingle) withoutSingle - value else withoutSingle + value
    }

    private fun FitnessUserProfileEntity.toDraft(): FitnessOnboardingDraft {
        return FitnessOnboardingDraft(
            goalType = goalType,
            previousAttempt = previousAttempt,
            targetParts = targetPartsJson.toStringSet(),
            targetWeightKg = targetWeightKg ?: 60f,
            gender = gender,
            age = age,
            heightCm = heightCm,
            currentWeightKg = currentWeightKg,
            currentBodyType = currentBodyType,
            targetBodyType = targetBodyType,
            injuryParts = injuryPartsJson.toStringSet(),
            sedentaryLevel = sedentaryLevel,
            sleepQuality = sleepQuality,
            dietHabits = dietHabitsJson.toStringSet(),
            rewardPreference = rewardPreference,
            exerciseFrequency = exerciseFrequency,
            preferredPlaces = preferredPlacesJson.toStringSet(),
            gymVisitsPerWeek = gymVisitsPerWeek,
            equipmentKnowledge = equipmentKnowledge,
            plankSeconds = plankSeconds,
            stairFeeling = stairFeeling,
            onboardingStep = onboardingStep
        )
    }

    private fun FitnessOnboardingDraft.toProfile(isComplete: Boolean): FitnessUserProfileEntity {
        val now = System.currentTimeMillis()
        return FitnessUserProfileEntity(
            goalType = goalType,
            previousAttempt = previousAttempt,
            targetPartsJson = gson.toJson(targetParts.toList()),
            targetWeightKg = if (requiresTargetWeight) targetWeightKg else null,
            gender = gender,
            age = age,
            heightCm = heightCm,
            currentWeightKg = currentWeightKg,
            currentBodyType = currentBodyType,
            targetBodyType = targetBodyType,
            injuryPartsJson = gson.toJson(injuryParts.toList()),
            sedentaryLevel = sedentaryLevel,
            sleepQuality = sleepQuality,
            dietHabitsJson = gson.toJson(dietHabits.toList()),
            rewardPreference = rewardPreference,
            exerciseFrequency = exerciseFrequency,
            preferredPlacesJson = gson.toJson(preferredPlaces.toList()),
            gymVisitsPerWeek = gymVisitsPerWeek,
            equipmentKnowledge = equipmentKnowledge,
            plankSeconds = plankSeconds,
            stairFeeling = stairFeeling,
            onboardingStep = onboardingStep,
            isComplete = isComplete,
            createdAt = profileFlow.value?.createdAt ?: now,
            updatedAt = now
        )
    }

    private fun String.toStringSet(): Set<String> {
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(this, type).toSet()
        }.getOrDefault(emptySet())
    }
}

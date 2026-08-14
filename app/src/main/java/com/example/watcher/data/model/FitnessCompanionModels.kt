package com.example.watcher.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class FitnessGoalType {
    WeightLoss,
    MuscleTone,
    HealthyHabit
}

enum class FitnessGenerationStatus {
    Idle,
    Generating,
    Ready,
    Failed
}

enum class FitnessWorkoutStatus {
    Planned,
    Completed,
    Skipped
}

@Entity(tableName = "fitness_user_profiles")
data class FitnessUserProfileEntity(
    @PrimaryKey val profileId: String = DEFAULT_PROFILE_ID,
    val goalType: String = "",
    val previousAttempt: String = "",
    val targetPartsJson: String = "[]",
    val targetWeightKg: Float? = null,
    val gender: String = "",
    val age: Int = 28,
    val heightCm: Int = 170,
    val currentWeightKg: Float = 65f,
    val currentBodyType: String = "",
    val targetBodyType: String = "",
    val injuryPartsJson: String = "[]",
    val sedentaryLevel: String = "",
    val sleepQuality: String = "",
    val dietHabitsJson: String = "[]",
    val rewardPreference: String = "",
    val exerciseFrequency: String = "",
    val preferredPlacesJson: String = "[]",
    val gymVisitsPerWeek: Int = 0,
    val equipmentKnowledge: String = "",
    val plankSeconds: Int = 30,
    val stairFeeling: String = "",
    val onboardingStep: Int = 0,
    val isComplete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_PROFILE_ID = "default"
    }
}

@Entity(
    tableName = "fitness_media_assets",
    indices = [Index("profileId"), Index("assetType")]
)
data class FitnessMediaAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val assetType: String,
    val localPath: String,
    val mimeType: String = "",
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "fitness_strategy_goals",
    indices = [Index("profileId"), Index("status")]
)
data class FitnessStrategyGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val title: String,
    val phaseLabel: String,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val summary: String,
    val milestonesJson: String = "[]",
    val status: String = FitnessGenerationStatus.Ready.name,
    val rawJson: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "fitness_strategy_specs",
    indices = [Index("profileId"), Index("strategyVersion"), Index("status"), Index("isActive")]
)
data class FitnessStrategySpecEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val strategyVersion: String,
    val status: String = FitnessGenerationStatus.Ready.name,
    val isActive: Boolean = true,
    val goalsJson: String = "[]",
    val currentPhaseJson: String = "{}",
    val weeklyBudgetJson: String = "{}",
    val progressionRulesJson: String = "{}",
    val autoregulationRulesJson: String = "{}",
    val hardConstraintsJson: String = "[]",
    val replanTriggersJson: String = "[]",
    val rawJson: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "fitness_weekly_ledgers",
    indices = [Index("profileId"), Index("strategyVersion"), Index("weekStartEpochDay"), Index("replanRequired")]
)
data class FitnessWeeklyLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val strategyVersion: String,
    val weekStartEpochDay: Long,
    val weekIndex: Int = 1,
    val weeklyBudgetJson: String = "{}",
    val actualsJson: String = "{}",
    val remainingBudgetJson: String = "{}",
    val readinessTrendJson: String = "[]",
    val painTrendJson: String = "[]",
    val replanRequired: Boolean = false,
    val replanReason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "fitness_workout_plans",
    indices = [Index("profileId"), Index("status"), Index("plannedDateEpochDay")]
)
data class FitnessWorkoutPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val title: String,
    val objective: String,
    val plannedDateEpochDay: Long,
    val estimatedMinutes: Int,
    val intensityLabel: String,
    val warmup: String = "",
    val cooldown: String = "",
    val coachNotes: String = "",
    val sessionId: String = "",
    val strategyVersion: String = "",
    val dailyContextJson: String = "{}",
    val sessionPlanJson: String = "{}",
    val expectedBudgetUsageJson: String = "{}",
    val adjustmentsJson: String = "[]",
    val stopRulesJson: String = "[]",
    val status: String = FitnessWorkoutStatus.Planned.name,
    val generationStatus: String = FitnessGenerationStatus.Ready.name,
    val rawJson: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "fitness_workout_exercises",
    foreignKeys = [
        ForeignKey(
            entity = FitnessWorkoutPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("planId")]
)
data class FitnessWorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val sortOrder: Int,
    val category: String,
    val equipment: String,
    val name: String,
    val sets: Int = 0,
    val reps: String = "",
    val durationSeconds: Int = 0,
    val restSeconds: Int = 60,
    val intensity: String = "",
    val movementPattern: String = "",
    val targetMusclesJson: String = "[]",
    val warmupSetsJson: String = "[]",
    val repRangeMin: Int = 0,
    val repRangeMax: Int = 0,
    val targetRir: Float = 0f,
    val restSecondsMin: Int = 0,
    val restSecondsMax: Int = 0,
    val tempo: String = "",
    val loadSelectionRule: String = "",
    val progressionRule: String = "",
    val substitutionsJson: String = "[]",
    val stopCondition: String = "",
    val priority: String = "",
    val notes: String = ""
)

@Entity(
    tableName = "fitness_workout_logs",
    foreignKeys = [
        ForeignKey(
            entity = FitnessWorkoutPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("profileId"), Index("planId"), Index("completedAt")]
)
data class FitnessWorkoutLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val planId: Long,
    val completionLevel: String,
    val fatigueLevel: String,
    val painSignal: String,
    val nextIntensityPreference: String,
    val noteOption: String = "",
    val completedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "fitness_session_results",
    foreignKeys = [
        ForeignKey(
            entity = FitnessWorkoutPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("profileId"), Index("planId"), Index("sessionId"), Index("completedAt")]
)
data class FitnessSessionResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val planId: Long,
    val sessionId: String = "",
    val strategyVersion: String = "",
    val completionRate: Float = 0f,
    val actualDurationMin: Int = 0,
    val sessionRpe: Float = 0f,
    val painEventsJson: String = "[]",
    val unexpectedFatigue: Boolean = false,
    val userFeedback: String = "",
    val postSessionReadiness: Int = 0,
    val rawJson: String = "{}",
    val completedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "fitness_exercise_results",
    foreignKeys = [
        ForeignKey(
            entity = FitnessSessionResultEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionResultId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FitnessWorkoutExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionResultId"), Index("exerciseId")]
)
data class FitnessExerciseResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionResultId: Long,
    val exerciseId: Long,
    val actualLoad: String = "",
    val actualSets: Int = 0,
    val actualReps: String = "",
    val actualRpe: Float = 0f,
    val actualRir: Float = 0f,
    val completionStatus: String = "",
    val painScore: Int = 0,
    val substituted: Boolean = false,
    val unfinishedReason: String = ""
)

@Entity(
    tableName = "fitness_realtime_feedback_events",
    indices = [
        Index("profileId"),
        Index("planId"),
        Index("exerciseId"),
        Index("sessionId"),
        Index("exerciseIntervalId"),
        Index("segmentId"),
        Index("knowledgePackId"),
        Index("knowledgePackTag"),
        Index("eventType"),
        Index("createdAt")
    ]
)
data class FitnessRealtimeFeedbackEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val planId: Long = 0,
    val exerciseId: Long = 0,
    val sessionId: String = "",
    val exerciseIntervalId: String = "",
    val segmentId: String = "",
    val observerId: String = "",
    val knowledgePackId: String = "",
    val knowledgePackTag: String = "",
    val exerciseName: String = "",
    val exerciseEquipment: String = "",
    val eventType: String,
    val status: String = "",
    val segmentStartElapsedMs: Long = 0,
    val segmentEndElapsedMs: Long = 0,
    val analysisFinishedElapsedMs: Long = 0,
    val rawObserverJson: String = "",
    val rawCoachJson: String = "",
    val finalFeedback: String = "",
    val discardReason: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "fitness_agent_runs",
    indices = [Index("profileId"), Index("agentType"), Index("status"), Index("createdAt")]
)
data class FitnessAgentRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val agentType: String,
    val status: String,
    val promptSummary: String,
    val rawResponse: String = "",
    val parsedJson: String = "",
    val errorMessage: String = "",
    val durationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

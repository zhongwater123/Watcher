package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.watcher.data.model.FitnessAgentRunEntity
import com.example.watcher.data.model.FitnessExerciseResultEntity
import com.example.watcher.data.model.FitnessMediaAssetEntity
import com.example.watcher.data.model.FitnessRealtimeFeedbackEventEntity
import com.example.watcher.data.model.FitnessRepEventEntity
import com.example.watcher.data.model.FitnessStrategyGoalEntity
import com.example.watcher.data.model.FitnessStrategySpecEntity
import com.example.watcher.data.model.FitnessSessionResultEntity
import com.example.watcher.data.model.FitnessUserProfileEntity
import com.example.watcher.data.model.FitnessWeeklyLedgerEntity
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity
import com.example.watcher.data.model.FitnessWorkoutLogEntity
import com.example.watcher.data.model.FitnessWorkoutPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FitnessCompanionDao {
    @Query("SELECT * FROM fitness_user_profiles WHERE profileId = :profileId LIMIT 1")
    fun observeProfile(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): Flow<FitnessUserProfileEntity?>

    @Query("SELECT * FROM fitness_user_profiles WHERE profileId = :profileId LIMIT 1")
    suspend fun getProfile(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): FitnessUserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: FitnessUserProfileEntity)

    @Query("SELECT * FROM fitness_strategy_goals WHERE profileId = :profileId ORDER BY startDateEpochDay ASC, id ASC")
    fun observeStrategyGoals(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): Flow<List<FitnessStrategyGoalEntity>>

    @Query("SELECT * FROM fitness_strategy_goals WHERE profileId = :profileId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestStrategyGoal(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): FitnessStrategyGoalEntity?

    @Query("DELETE FROM fitness_strategy_goals WHERE profileId = :profileId")
    suspend fun deleteStrategyGoals(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrategyGoal(goal: FitnessStrategyGoalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrategyGoals(goals: List<FitnessStrategyGoalEntity>)

    @Query("SELECT * FROM fitness_strategy_specs WHERE profileId = :profileId AND isActive = 1 ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getActiveStrategySpec(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): FitnessStrategySpecEntity?

    @Query("SELECT * FROM fitness_strategy_specs WHERE profileId = :profileId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getLatestStrategySpec(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): FitnessStrategySpecEntity?

    @Query("UPDATE fitness_strategy_specs SET isActive = 0, updatedAt = :updatedAt WHERE profileId = :profileId AND isActive = 1")
    suspend fun deactivateActiveStrategySpecs(
        profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrategySpec(spec: FitnessStrategySpecEntity): Long

    @Query("SELECT * FROM fitness_workout_plans WHERE profileId = :profileId ORDER BY plannedDateEpochDay DESC, id DESC")
    fun observeWorkoutPlans(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): Flow<List<FitnessWorkoutPlanEntity>>

    @Query("SELECT * FROM fitness_workout_plans WHERE profileId = :profileId ORDER BY plannedDateEpochDay DESC, id DESC LIMIT 1")
    suspend fun getLatestWorkoutPlan(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): FitnessWorkoutPlanEntity?

    @Query("SELECT * FROM fitness_workout_plans WHERE profileId = :profileId AND status = 'Planned' AND generationStatus = 'Ready' AND strategyVersion != '' ORDER BY plannedDateEpochDay DESC, id DESC LIMIT 1")
    suspend fun getActiveWorkoutPlan(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID): FitnessWorkoutPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutPlan(plan: FitnessWorkoutPlanEntity): Long

    @Update
    suspend fun updateWorkoutPlan(plan: FitnessWorkoutPlanEntity)

    @Query("SELECT * FROM fitness_workout_exercises WHERE planId = :planId ORDER BY sortOrder ASC, id ASC")
    fun observeExercisesForPlan(planId: Long): Flow<List<FitnessWorkoutExerciseEntity>>

    @Query("SELECT * FROM fitness_workout_exercises WHERE planId = :planId ORDER BY sortOrder ASC, id ASC")
    suspend fun getExercisesForPlan(planId: Long): List<FitnessWorkoutExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutExercises(exercises: List<FitnessWorkoutExerciseEntity>)

    @Query("DELETE FROM fitness_workout_exercises WHERE planId = :planId")
    suspend fun deleteExercisesForPlan(planId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutLog(log: FitnessWorkoutLogEntity): Long

    @Query("SELECT * FROM fitness_weekly_ledgers WHERE profileId = :profileId AND strategyVersion = :strategyVersion AND weekStartEpochDay = :weekStartEpochDay LIMIT 1")
    suspend fun getWeeklyLedger(
        profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
        strategyVersion: String,
        weekStartEpochDay: Long
    ): FitnessWeeklyLedgerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeeklyLedger(ledger: FitnessWeeklyLedgerEntity): Long

    @Query("SELECT * FROM fitness_session_results WHERE planId = :planId LIMIT 1")
    suspend fun getSessionResultForPlan(planId: Long): FitnessSessionResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessionResult(result: FitnessSessionResultEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseResults(results: List<FitnessExerciseResultEntity>)

    @Query("SELECT * FROM fitness_workout_logs WHERE profileId = :profileId ORDER BY completedAt DESC LIMIT :limit")
    fun observeRecentLogs(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID, limit: Int = 10): Flow<List<FitnessWorkoutLogEntity>>

    @Query("SELECT * FROM fitness_workout_logs WHERE profileId = :profileId ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getRecentLogs(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID, limit: Int = 5): List<FitnessWorkoutLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaAsset(asset: FitnessMediaAssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentRun(run: FitnessAgentRunEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRealtimeFeedbackEvent(event: FitnessRealtimeFeedbackEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepEvent(event: FitnessRepEventEntity): Long

    @Query("SELECT * FROM fitness_rep_events WHERE sessionId = :sessionId ORDER BY repIndex ASC, id ASC")
    fun observeRepEvents(sessionId: String): Flow<List<FitnessRepEventEntity>>

    @Query("SELECT * FROM fitness_realtime_feedback_events WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRealtimeFeedbackEvents(sessionId: String, limit: Int = 50): Flow<List<FitnessRealtimeFeedbackEventEntity>>

    @Query("SELECT * FROM fitness_agent_runs WHERE profileId = :profileId ORDER BY createdAt DESC LIMIT :limit")
    fun observeAgentRuns(profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID, limit: Int = 20): Flow<List<FitnessAgentRunEntity>>

    @Transaction
    suspend fun replaceStrategyGoals(profileId: String, goals: List<FitnessStrategyGoalEntity>) {
        deleteStrategyGoals(profileId)
        insertStrategyGoals(goals)
    }

    @Transaction
    suspend fun replaceActiveStrategy(
        profileId: String,
        spec: FitnessStrategySpecEntity,
        goals: List<FitnessStrategyGoalEntity>
    ): Long {
        deactivateActiveStrategySpecs(profileId)
        val specId = insertStrategySpec(spec.copy(isActive = true, updatedAt = System.currentTimeMillis()))
        replaceStrategyGoals(profileId, goals)
        return specId
    }

    @Transaction
    suspend fun replaceExercises(planId: Long, exercises: List<FitnessWorkoutExerciseEntity>) {
        deleteExercisesForPlan(planId)
        insertWorkoutExercises(exercises)
    }
}

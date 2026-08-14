package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.example.watcher.data.model.FitnessExerciseEntity
import com.example.watcher.data.model.FitnessExerciseInstructionEntity
import com.example.watcher.data.model.FitnessExerciseInstructionStepEntity
import com.example.watcher.data.model.FitnessExerciseLibraryMetaEntity
import com.example.watcher.data.model.FitnessExerciseSecondaryMuscleEntity

data class FitnessExerciseWithDetails(
    @Embedded val exercise: FitnessExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "exerciseId")
    val instructions: List<FitnessExerciseInstructionEntity>,
    @Relation(parentColumn = "id", entityColumn = "exerciseId")
    val steps: List<FitnessExerciseInstructionStepEntity>,
    @Relation(parentColumn = "id", entityColumn = "exerciseId")
    val secondaryMuscles: List<FitnessExerciseSecondaryMuscleEntity>
)

@Dao
interface ExerciseLibraryDao {
    @Query("SELECT * FROM fitness_exercise_library_meta WHERE datasetId = :datasetId LIMIT 1")
    suspend fun getMeta(datasetId: String): FitnessExerciseLibraryMetaEntity?

    @Query("SELECT COUNT(*) FROM fitness_exercises")
    suspend fun countExercises(): Int

    @Transaction
    @Query("SELECT * FROM fitness_exercises ORDER BY displayNameZh COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    suspend fun getAllExerciseDetails(): List<FitnessExerciseWithDetails>

    @Transaction
    @Query("SELECT * FROM fitness_exercises WHERE id = :id LIMIT 1")
    suspend fun getExerciseDetail(id: String): FitnessExerciseWithDetails?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExercises(exercises: List<FitnessExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstructions(instructions: List<FitnessExerciseInstructionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstructionSteps(steps: List<FitnessExerciseInstructionStepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSecondaryMuscles(muscles: List<FitnessExerciseSecondaryMuscleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: FitnessExerciseLibraryMetaEntity)

    @Query("DELETE FROM fitness_exercise_instruction_steps")
    suspend fun clearInstructionSteps()

    @Query("DELETE FROM fitness_exercise_instructions")
    suspend fun clearInstructions()

    @Query("DELETE FROM fitness_exercise_secondary_muscles")
    suspend fun clearSecondaryMuscles()

    @Query("DELETE FROM fitness_exercises")
    suspend fun clearExercises()

    @Query("DELETE FROM fitness_exercise_library_meta")
    suspend fun clearMeta()

    @Transaction
    suspend fun replaceLibrary(
        meta: FitnessExerciseLibraryMetaEntity,
        exercises: List<FitnessExerciseEntity>,
        instructions: List<FitnessExerciseInstructionEntity>,
        steps: List<FitnessExerciseInstructionStepEntity>,
        muscles: List<FitnessExerciseSecondaryMuscleEntity>
    ) {
        clearInstructionSteps()
        clearInstructions()
        clearSecondaryMuscles()
        clearExercises()
        clearMeta()
        insertExercises(exercises)
        insertInstructions(instructions)
        insertInstructionSteps(steps)
        insertSecondaryMuscles(muscles)
        upsertMeta(meta)
    }
}

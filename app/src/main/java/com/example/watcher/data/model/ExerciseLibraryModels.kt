package com.example.watcher.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fitness_exercises",
    indices = [
        Index("category"),
        Index("body_part"),
        Index("equipment"),
        Index("target")
    ]
)
data class FitnessExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val displayNameZh: String,
    val category: String,
    @ColumnInfo(name = "body_part") val bodyPart: String,
    val equipment: String,
    @ColumnInfo(name = "muscle_group") val muscleGroup: String,
    val target: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val image: String,
    @ColumnInfo(name = "gif_url") val gifUrl: String,
    val attribution: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    val searchText: String
)

@Entity(
    tableName = "fitness_exercise_instructions",
    primaryKeys = ["exerciseId", "language"],
    indices = [Index("exerciseId"), Index("language")]
)
data class FitnessExerciseInstructionEntity(
    val exerciseId: String,
    val language: String,
    val fullText: String
)

@Entity(
    tableName = "fitness_exercise_instruction_steps",
    primaryKeys = ["exerciseId", "language", "stepIndex"],
    indices = [Index("exerciseId"), Index("language")]
)
data class FitnessExerciseInstructionStepEntity(
    val exerciseId: String,
    val language: String,
    val stepIndex: Int,
    val text: String
)

@Entity(
    tableName = "fitness_exercise_secondary_muscles",
    primaryKeys = ["exerciseId", "muscle"],
    indices = [Index("exerciseId"), Index("muscle")]
)
data class FitnessExerciseSecondaryMuscleEntity(
    val exerciseId: String,
    val muscle: String
)

@Entity(tableName = "fitness_exercise_library_meta")
data class FitnessExerciseLibraryMetaEntity(
    @PrimaryKey val datasetId: String,
    val sourceHash: String,
    val exerciseCount: Int,
    val importedAt: Long
)

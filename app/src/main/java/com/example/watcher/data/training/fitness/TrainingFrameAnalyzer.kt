package com.example.watcher.data.training.fitness

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

const val FITNESS_TRAINING_STREAM_OWNER = "fitness_training"

data class TrainingIntervalContext(
    val profileId: String,
    val planId: Long,
    val sessionId: String,
    val intervalId: String,
    val exerciseId: Long,
    val actionId: String = "",
    val exerciseName: String,
    val equipment: String,
    val movementPattern: String = "",
    val category: String = "",
    val setIndex: Int = 0
)

data class TrainingFrame(
    val bitmap: Bitmap,
    val capturedAtMs: Long = System.currentTimeMillis()
)

interface TrainingFrameAnalyzer<State> {
    val state: StateFlow<State>

    fun start(context: TrainingIntervalContext)

    fun submitFrame(frame: TrainingFrame)

    fun stop()

    fun release()
}

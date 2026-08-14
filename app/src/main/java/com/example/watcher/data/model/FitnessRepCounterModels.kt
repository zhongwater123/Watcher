package com.example.watcher.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.watcher.data.local.pose.PoseDetectionResult

enum class FitnessRepCounterStatus {
    Initializing,
    Ready,
    Counting,
    Unreliable,
    Unsupported,
    Error
}

enum class FitnessRepCounterPhase {
    WaitingBaseline,
    MovingAway,
    AtExtreme,
    Returning,
    RepCompleted,
    Unreliable
}

enum class FitnessRepDominantAxis {
    Vertical,
    Horizontal,
    Diagonal,
    Unknown
}

enum class FitnessRepQualityLabel {
    Good,
    Partial,
    Unstable,
    LowVisibility
}

data class FitnessRepQuality(
    val repIndex: Int = 0,
    val startElapsedMs: Long = 0L,
    val endElapsedMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeLandmarks: List<String> = emptyList(),
    val dominantAxis: FitnessRepDominantAxis = FitnessRepDominantAxis.Unknown,
    val signalId: String = "",
    val signalLabel: String = "",
    val rangeScore: Float = 0f,
    val smoothnessScore: Float = 0f,
    val visibilityScore: Float = 0f,
    val symmetryScore: Float = 0f,
    val periodStabilityScore: Float = 0f,
    val confidence: Float = 0f,
    val qualityLabel: FitnessRepQualityLabel = FitnessRepQualityLabel.Unstable,
    val rawSignalsJson: String = "{}"
)

data class FitnessRepCounterState(
    val active: Boolean = false,
    val status: FitnessRepCounterStatus = FitnessRepCounterStatus.Unsupported,
    val repCount: Int = 0,
    val phase: FitnessRepCounterPhase = FitnessRepCounterPhase.WaitingBaseline,
    val activeLandmarks: List<String> = emptyList(),
    val poseResult: PoseDetectionResult? = null,
    val dominantAxis: FitnessRepDominantAxis = FitnessRepDominantAxis.Unknown,
    val confidence: Float = 0f,
    val lastRepQuality: FitnessRepQuality? = null,
    val notCountingReason: String = "",
    val selectedSignalId: String = "",
    val selectedSignalLabel: String = "",
    val signalScore: Float = 0f,
    val signalAmplitude: Float = 0f,
    val estimatedPeriodMs: Long = 0L,
    val candidateCount: Int = 0,
    val inferenceTimeMs: Long = 0L,
    val fps: Int = 0,
    val primarySignalId: String = "",
    val primarySignalType: String = "",
    val primaryAngleDeg: Float = 0f,
    val lowThreshold: Float = 0f,
    val highThreshold: Float = 0f,
    val confirmationSignalId: String = "",
    val rejectionReason: String = "",
    val primarySignalFamily: String = "",
    val provisionalSignalFamily: String = "",
    val provisionalFamilyRepCount: Int = 0,
    val preferredSignalFamily: String = "",
    val displayRepCount: Int = 0,
    val officialRepCount: Int = 0,
    val calibrationPhase: String = "",
    val candidateFamily: String = "",
    val candidatePendingCount: Int = 0,
    val lockedFamily: String = "",
    val dynamicLowThreshold: Float = 0f,
    val dynamicHighThreshold: Float = 0f,
    val dynamicMinAmplitude: Float = 0f,
    val dynamicPeriodMinMs: Long = 0L,
    val dynamicPeriodMaxMs: Long = 0L,
    val filteredFamily: String = "",
    val filterReason: String = "",
    val confirmedRepQualities: List<FitnessRepQuality> = emptyList()
)

@Entity(
    tableName = "fitness_rep_events",
    indices = [
        Index("profileId"),
        Index("planId"),
        Index("exerciseId"),
        Index("sessionId"),
        Index("exerciseIntervalId"),
        Index("createdAt")
    ]
)
data class FitnessRepEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = FitnessUserProfileEntity.DEFAULT_PROFILE_ID,
    val planId: Long = 0,
    val exerciseId: Long = 0,
    val sessionId: String = "",
    val exerciseIntervalId: String = "",
    val repIndex: Int = 0,
    val startElapsedMs: Long = 0L,
    val endElapsedMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeLandmarksJson: String = "[]",
    val dominantAxis: String = FitnessRepDominantAxis.Unknown.name,
    val rangeScore: Float = 0f,
    val smoothnessScore: Float = 0f,
    val visibilityScore: Float = 0f,
    val symmetryScore: Float = 0f,
    val confidence: Float = 0f,
    val qualityLabel: String = FitnessRepQualityLabel.Unstable.name,
    val rawSignalsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)

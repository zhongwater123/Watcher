package com.example.watcher.data.fitness.agent.feedback.realtime

data class FitnessRealtimeVlmConfiguration(
    val workerCount: Int = 2,
    val workerStaggerMs: Long = 800L,
    val frameRetentionMs: Long = 6_000L,
    val frameCaptureIntervalMs: Long = 500L,
    val frameMaxLongEdgePx: Int = 720,
    val frameJpegQuality: Int = 72,
    val imageDetail: String = "low",
    val maxOutputTokens: Int = 384,
    val temperature: Double = 0.0,
    val emptyFrameRetryMs: Long = 300L,
    val duplicateFrameRetryMs: Long = 200L,
    val workerMinIntervalMs: Long = 250L,
    val maxResultAgeMs: Long = 7_000L,
    val factWindowMs: Long = 6_000L,
    val maxFacts: Int = 12,
    val maxCurrentFacts: Int = 4,
    val maxActiveProbes: Int = 2,
    val probeActiveMs: Long = 6_000L,
    val maxProbeObservationAttempts: Int = 4,
    val strongEvidenceThreshold: Float = 0.95f,
    val minimumEvidenceConfidence: Float = 0.75f,
    val cumulativeEvidenceThreshold: Float = 1.60f,
    val minimumIndependentFrameGapMs: Long = 300L,
    val directCoachConfidenceThreshold: Float = 0.90f,
    val promptVersion: String = "fitness_realtime_vlm_v0.4",
    val model: String = "doubao-seed-1-6-flash-250828"
) {
    init {
        require(workerCount > 0)
        require(maxFacts > 0)
        require(maxCurrentFacts > 0)
        require(maxActiveProbes > 0)
        require(maxProbeObservationAttempts > 0)
    }
}

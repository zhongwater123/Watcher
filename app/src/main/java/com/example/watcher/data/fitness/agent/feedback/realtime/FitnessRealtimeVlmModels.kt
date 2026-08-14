package com.example.watcher.data.fitness.agent.feedback.realtime

import com.example.watcher.data.training.fitness.TrainingIntervalContext

enum class FitnessVlmObservability {
    CLEAR,
    PARTIAL,
    NOT_OBSERVABLE,
    INSUFFICIENT_EVIDENCE
}

enum class FitnessVlmProbeStatus {
    OPEN,
    OBSERVING,
    SUPPORTED,
    REFUTED,
    EXPIRED
}

data class FitnessVlmObservationDisplay(
    val observation: String,
    val confidence: Float,
    val observability: FitnessVlmObservability,
    val acceptedAsEvidence: Boolean
)

data class FitnessVlmCoachDisplay(
    val message: String,
    val confidence: Float,
    val acceptedAsFeedback: Boolean,
    val blockReasons: List<String>
)

data class FitnessRealtimeVlmState(
    val active: Boolean = false,
    val supported: Boolean = false,
    val analyzing: Boolean = false,
    val sessionId: String = "",
    val exerciseIntervalId: String = "",
    val currentExerciseName: String = "",
    val statusText: String = "",
    val latestObservations: List<FitnessVlmObservationDisplay> = emptyList(),
    val latestObservationFrameSeq: Long = 0L,
    val latestCoachCandidate: FitnessVlmCoachDisplay? = null,
    val latestCoachFrameSeq: Long = 0L,
    val latestVerifiedFeedback: String = "",
    val factCount: Int = 0,
    val activeProbeCount: Int = 0,
    val lastError: String? = null
)

data class FitnessVlmVisualFact(
    val factId: String,
    val requestId: String,
    val frameSeq: Long,
    val capturedAtMs: Long,
    val observation: String,
    val confidence: Float,
    val observability: FitnessVlmObservability
)

data class FitnessVlmProbeView(
    val probeId: String,
    val question: String
)

data class FitnessVlmPromptContext(
    val exercise: TrainingIntervalContext,
    val rollingFacts: List<FitnessVlmVisualFact>,
    val activeProbes: List<FitnessVlmProbeView>
)

internal data class FitnessVlmFactDraft(
    val ref: String,
    val observation: String,
    val confidence: Float,
    val observability: FitnessVlmObservability
)

internal data class FitnessVlmProbeResultDraft(
    val probeId: String,
    val result: String,
    val confidence: Float,
    val factRef: String?
)

internal data class FitnessVlmNewProbeDraft(
    val question: String,
    val sourceFactRef: String,
    val candidateFinding: String
)

internal data class FitnessVlmCoachCandidateDraft(
    val message: String,
    val basedOnFactRefs: List<String>,
    val originProbeId: String?,
    val confidence: Float
)

internal data class FitnessVlmParsedResponse(
    val currentFacts: List<FitnessVlmFactDraft>,
    val probeResults: List<FitnessVlmProbeResultDraft>,
    val newProbe: FitnessVlmNewProbeDraft?,
    val coachCandidate: FitnessVlmCoachCandidateDraft?
)

data class FitnessVlmProbeEvidence(
    val factId: String,
    val frameSeq: Long,
    val capturedAtMs: Long,
    val result: String,
    val confidence: Float,
    val observability: FitnessVlmObservability
)

data class FitnessVlmProbeRecord(
    val probeId: String,
    val sourceFactIds: List<String>,
    val sourceCapturedAtMs: Long,
    val activatedAtElapsedMs: Long,
    val question: String,
    val candidateFinding: String,
    val status: FitnessVlmProbeStatus = FitnessVlmProbeStatus.OPEN,
    val observationAttempts: Int = 0,
    val evidence: List<FitnessVlmProbeEvidence> = emptyList()
)

data class FitnessVlmFinding(
    val finding: String,
    val basedOnFactIds: List<String>,
    val originProbeId: String,
    val confidence: Float
)

data class FitnessVlmCoachFeedback(
    val message: String,
    val basedOnFactIds: List<String>,
    val originProbeId: String?,
    val confidence: Float
)

data class FitnessVlmProbeTransition(
    val probeId: String,
    val from: FitnessVlmProbeStatus,
    val to: FitnessVlmProbeStatus,
    val reason: String
)

internal data class FitnessVlmMergeResult(
    val acceptedFacts: List<FitnessVlmVisualFact>,
    val transitions: List<FitnessVlmProbeTransition>,
    val findings: List<FitnessVlmFinding>,
    val feedback: FitnessVlmCoachFeedback?,
    val acceptedProbe: FitnessVlmProbeRecord?,
    val discardReasons: List<String>,
    val factCount: Int,
    val activeProbeCount: Int
) {
    val status: String
        get() = when {
            feedback != null -> "coach_displayed"
            transitions.any { it.to == FitnessVlmProbeStatus.SUPPORTED } -> "probe_supported"
            transitions.any { it.to == FitnessVlmProbeStatus.REFUTED } -> "probe_refuted"
            transitions.any { it.to == FitnessVlmProbeStatus.EXPIRED } -> "probe_expired"
            discardReasons.isNotEmpty() -> "coach_suppressed"
            else -> "accepted"
        }
}

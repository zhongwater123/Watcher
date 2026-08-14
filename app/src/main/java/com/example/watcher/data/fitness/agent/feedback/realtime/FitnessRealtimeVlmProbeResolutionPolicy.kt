package com.example.watcher.data.fitness.agent.feedback.realtime

internal data class FitnessVlmProbeResolutionDecision(
    val status: FitnessVlmProbeStatus,
    val basedOnFactIds: List<String>,
    val confidence: Float,
    val reason: String
)

internal interface FitnessVlmProbeResolutionPolicy {
    fun evaluate(probe: FitnessVlmProbeRecord): FitnessVlmProbeResolutionDecision?
}

internal class ConfigurableFitnessVlmProbeResolutionPolicy(
    private val configuration: FitnessRealtimeVlmConfiguration
) : FitnessVlmProbeResolutionPolicy {
    override fun evaluate(probe: FitnessVlmProbeRecord): FitnessVlmProbeResolutionDecision? {
        val independent = independentEvidence(probe.evidence)
        val supporting = independent.filter { it.result == "supported" }
        val refuting = independent.filter { it.result == "refuted" }
        val supportSatisfied = evidenceSatisfied(supporting)
        val refuteSatisfied = evidenceSatisfied(refuting)
        if (supportSatisfied == refuteSatisfied) return null

        val evidence = if (supportSatisfied) supporting else refuting
        return FitnessVlmProbeResolutionDecision(
            status = if (supportSatisfied) FitnessVlmProbeStatus.SUPPORTED else FitnessVlmProbeStatus.REFUTED,
            basedOnFactIds = evidence.map { it.factId },
            confidence = evidence.map { it.confidence }.average().toFloat().coerceIn(0f, 1f),
            reason = if (supportSatisfied) "evidence_supported" else "evidence_refuted"
        )
    }

    private fun evidenceSatisfied(
        evidence: List<FitnessVlmProbeEvidence>
    ): Boolean {
        if (evidence.isEmpty()) return false
        if (
            evidence.any {
                it.observability == FitnessVlmObservability.CLEAR &&
                    it.confidence >= configuration.strongEvidenceThreshold
            }
        ) {
            return true
        }

        val usable = evidence.filter { it.confidence >= configuration.minimumEvidenceConfidence }
        if (usable.isEmpty()) return false
        if (usable.map { it.capturedAtMs }.distinct().size < 2) {
            return false
        }
        return usable.sumOf { evidenceWeight(it).toDouble() }.toFloat() >=
            configuration.cumulativeEvidenceThreshold
    }

    private fun independentEvidence(evidence: List<FitnessVlmProbeEvidence>): List<FitnessVlmProbeEvidence> {
        val selected = mutableListOf<FitnessVlmProbeEvidence>()
        evidence
            .distinctBy { it.factId }
            .sortedBy { it.capturedAtMs }
            .forEach { candidate ->
                val closeIndex = selected.indexOfLast {
                    kotlin.math.abs(candidate.capturedAtMs - it.capturedAtMs) <
                        configuration.minimumIndependentFrameGapMs
                }
                if (closeIndex < 0) {
                    selected += candidate
                } else if (candidate.confidence > selected[closeIndex].confidence) {
                    selected[closeIndex] = candidate
                }
            }
        return selected
    }

    private fun evidenceWeight(evidence: FitnessVlmProbeEvidence): Float {
        val observabilityWeight = when (evidence.observability) {
            FitnessVlmObservability.CLEAR -> 1f
            FitnessVlmObservability.PARTIAL -> 0.5f
            FitnessVlmObservability.NOT_OBSERVABLE,
            FitnessVlmObservability.INSUFFICIENT_EVIDENCE -> 0f
        }
        return evidence.confidence * observabilityWeight
    }
}

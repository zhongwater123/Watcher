package com.example.watcher.data.fitness.agent.feedback.realtime

import com.example.watcher.data.training.fitness.TrainingIntervalContext

internal class FitnessRealtimeVlmEvidenceStore(
    private val configuration: FitnessRealtimeVlmConfiguration,
    private val resolutionPolicy: FitnessVlmProbeResolutionPolicy =
        ConfigurableFitnessVlmProbeResolutionPolicy(configuration)
) {
    private val facts = linkedMapOf<String, FitnessVlmVisualFact>()
    private val probes = linkedMapOf<String, FitnessVlmProbeRecord>()
    private val pendingTransitions = mutableListOf<FitnessVlmProbeTransition>()

    @Synchronized
    fun promptContext(
        capturedAtMs: Long,
        nowElapsedMs: Long,
        exercise: TrainingIntervalContext
    ): FitnessVlmPromptContext {
        prune(capturedAtMs, nowElapsedMs)
        return FitnessVlmPromptContext(
            exercise = exercise,
            rollingFacts = facts.values.sortedBy { it.capturedAtMs },
            activeProbes = probes.values
                .filter { it.isActive() }
                .map {
                    FitnessVlmProbeView(
                        probeId = it.probeId,
                        question = it.question
                    )
                }
        )
    }

    @Synchronized
    fun merge(
        parsed: FitnessVlmParsedResponse,
        requestId: String,
        frameSeq: Long,
        capturedAtMs: Long,
        nowElapsedMs: Long
    ): FitnessVlmMergeResult {
        prune(capturedAtMs, nowElapsedMs)
        val discardReasons = mutableListOf<String>()
        val transitions = drainPendingTransitions()
        val currentFactsByLocalRef = linkedMapOf<String, FitnessVlmVisualFact>()

        parsed.currentFacts.forEachIndexed { index, draft ->
            if (
                draft.observability == FitnessVlmObservability.NOT_OBSERVABLE ||
                draft.observability == FitnessVlmObservability.INSUFFICIENT_EVIDENCE
            ) {
                return@forEachIndexed
            }
            val fact = FitnessVlmVisualFact(
                factId = "F_${requestId}_${index + 1}",
                requestId = requestId,
                frameSeq = frameSeq,
                capturedAtMs = capturedAtMs,
                observation = draft.observation,
                confidence = draft.confidence,
                observability = draft.observability
            )
            currentFactsByLocalRef[draft.ref] = fact
            facts[fact.factId] = fact
        }

        val findings = mutableListOf<FitnessVlmFinding>()
        val processedProbeIds = mutableSetOf<String>()
        parsed.probeResults.forEach { result ->
            if (!processedProbeIds.add(result.probeId)) {
                discardReasons += "duplicate_probe_result:${result.probeId}"
                return@forEach
            }
            var probe = probes[result.probeId]
            if (probe == null) {
                discardReasons += "unknown_probe:${result.probeId}"
                return@forEach
            }
            if (!probe.isActive()) {
                discardReasons += "terminal_probe_result:${result.probeId}"
                return@forEach
            }
            if (result.result !in PROBE_RESULTS) {
                discardReasons += "probe_result_not_allowed:${result.probeId}:${result.result}"
                return@forEach
            }

            val from = probe.status
            probe = probe.copy(
                status = FitnessVlmProbeStatus.OBSERVING,
                observationAttempts = probe.observationAttempts + 1
            )
            probes[probe.probeId] = probe
            if (from == FitnessVlmProbeStatus.OPEN) {
                transitions += FitnessVlmProbeTransition(
                    probeId = probe.probeId,
                    from = from,
                    to = FitnessVlmProbeStatus.OBSERVING,
                    reason = "first_probe_result"
                )
            }

            if (!result.result.isUninformative()) {
                val fact = result.factRef?.let(currentFactsByLocalRef::get)
                if (fact == null) {
                    discardReasons += "probe_result_missing_current_fact:${result.probeId}"
                    return@forEach
                }
                if (fact.capturedAtMs <= probe.sourceCapturedAtMs) {
                    discardReasons += "probe_result_not_future:${result.probeId}"
                    return@forEach
                }
                if (probe.evidence.none { it.factId == fact.factId }) {
                    probe = probe.copy(
                        evidence = probe.evidence + FitnessVlmProbeEvidence(
                            factId = fact.factId,
                            frameSeq = fact.frameSeq,
                            capturedAtMs = fact.capturedAtMs,
                            result = result.result,
                            confidence = minOf(result.confidence, fact.confidence),
                            observability = fact.observability
                        )
                    )
                    probes[probe.probeId] = probe
                }
            }

            val decision = resolutionPolicy.evaluate(probe)
            if (decision != null) {
                transitionProbe(probe, decision.status, decision.reason, transitions)
                if (decision.status == FitnessVlmProbeStatus.SUPPORTED) {
                    findings += FitnessVlmFinding(
                        finding = probe.candidateFinding,
                        basedOnFactIds = decision.basedOnFactIds,
                        originProbeId = probe.probeId,
                        confidence = decision.confidence
                    )
                }
            } else if (probe.observationAttempts >= configuration.maxProbeObservationAttempts) {
                transitionProbe(probe, FitnessVlmProbeStatus.EXPIRED, "observation_attempt_limit", transitions)
            }
        }

        val feedback = validateCoachCandidate(
            draft = parsed.coachCandidate,
            currentFactsByLocalRef = currentFactsByLocalRef,
            findings = findings,
            discardReasons = discardReasons
        )
        val acceptedProbe = acceptNewProbe(
            draft = parsed.newProbe,
            requestId = requestId,
            currentFactsByLocalRef = currentFactsByLocalRef,
            nowElapsedMs = nowElapsedMs,
            discardReasons = discardReasons
        )

        pruneFacts(capturedAtMs)
        return FitnessVlmMergeResult(
            acceptedFacts = currentFactsByLocalRef.values.toList(),
            transitions = transitions,
            findings = findings,
            feedback = feedback,
            acceptedProbe = acceptedProbe,
            discardReasons = discardReasons,
            factCount = facts.size,
            activeProbeCount = probes.values.count { it.isActive() }
        )
    }

    @Synchronized
    fun clear() {
        facts.clear()
        probes.clear()
        pendingTransitions.clear()
    }

    private fun validateCoachCandidate(
        draft: FitnessVlmCoachCandidateDraft?,
        currentFactsByLocalRef: Map<String, FitnessVlmVisualFact>,
        findings: List<FitnessVlmFinding>,
        discardReasons: MutableList<String>
    ): FitnessVlmCoachFeedback? {
        if (draft == null) return null
        val resolvedFacts = draft.basedOnFactRefs.map { ref ->
            currentFactsByLocalRef[ref] ?: facts[ref]
        }
        if (resolvedFacts.any { it == null }) {
            discardReasons += "coach_unknown_fact_reference"
            return null
        }
        val factList = resolvedFacts.filterNotNull().distinctBy { it.factId }
        if (factList.isEmpty() || factList.none { it.factId in currentFactsByLocalRef.values.map(FitnessVlmVisualFact::factId) }) {
            discardReasons += "coach_requires_current_fact"
            return null
        }

        val originProbeId = draft.originProbeId
        if (originProbeId != null) {
            val finding = findings.firstOrNull { it.originProbeId == originProbeId }
            if (finding == null) {
                discardReasons += "coach_probe_not_supported:$originProbeId"
                return null
            }
            if (factList.none { it.factId in finding.basedOnFactIds }) {
                discardReasons += "coach_missing_finding_evidence:$originProbeId"
                return null
            }
        } else {
            if (draft.confidence < configuration.directCoachConfidenceThreshold) {
                discardReasons += "direct_coach_low_confidence"
                return null
            }
            if (factList.any { it.observability != FitnessVlmObservability.CLEAR }) {
                discardReasons += "direct_coach_unclear_evidence"
                return null
            }
            if (factList.any { it.confidence < configuration.minimumEvidenceConfidence }) {
                discardReasons += "direct_coach_weak_fact"
                return null
            }
        }

        return FitnessVlmCoachFeedback(
            message = draft.message,
            basedOnFactIds = factList.map { it.factId },
            originProbeId = originProbeId,
            confidence = draft.confidence
        )
    }

    private fun acceptNewProbe(
        draft: FitnessVlmNewProbeDraft?,
        requestId: String,
        currentFactsByLocalRef: Map<String, FitnessVlmVisualFact>,
        nowElapsedMs: Long,
        discardReasons: MutableList<String>
    ): FitnessVlmProbeRecord? {
        if (draft == null) return null
        if (probes.values.count { it.isActive() } >= configuration.maxActiveProbes) {
            discardReasons += "probe_capacity"
            return null
        }
        val sourceFact = currentFactsByLocalRef[draft.sourceFactRef]
        if (sourceFact == null) {
            discardReasons += "new_probe_requires_current_facts"
            return null
        }
        val probeId = "Q_$requestId"
        if (probes.containsKey(probeId)) {
            discardReasons += "duplicate_probe_id:$probeId"
            return null
        }
        val probe = FitnessVlmProbeRecord(
            probeId = probeId,
            sourceFactIds = listOf(sourceFact.factId),
            sourceCapturedAtMs = sourceFact.capturedAtMs,
            activatedAtElapsedMs = nowElapsedMs,
            question = draft.question,
            candidateFinding = draft.candidateFinding
        )
        probes[probeId] = probe
        return probe
    }

    private fun transitionProbe(
        probe: FitnessVlmProbeRecord,
        status: FitnessVlmProbeStatus,
        reason: String,
        transitions: MutableList<FitnessVlmProbeTransition>
    ) {
        if (!probe.isActive() || status == probe.status) return
        probes[probe.probeId] = probe.copy(status = status)
        transitions += FitnessVlmProbeTransition(
            probeId = probe.probeId,
            from = probe.status,
            to = status,
            reason = reason
        )
    }

    private fun prune(latestCapturedAtMs: Long, nowElapsedMs: Long) {
        pruneFacts(latestCapturedAtMs)
        probes.values.toList().forEach { probe ->
            if (probe.isActive() && nowElapsedMs - probe.activatedAtElapsedMs >= configuration.probeActiveMs) {
                probes[probe.probeId] = probe.copy(status = FitnessVlmProbeStatus.EXPIRED)
                pendingTransitions += FitnessVlmProbeTransition(
                    probeId = probe.probeId,
                    from = probe.status,
                    to = FitnessVlmProbeStatus.EXPIRED,
                    reason = "ttl_expired"
                )
            }
        }
    }

    private fun pruneFacts(latestCapturedAtMs: Long) {
        val cutoff = latestCapturedAtMs - configuration.factWindowMs
        val iterator = facts.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.capturedAtMs < cutoff) iterator.remove()
        }
        while (facts.size > configuration.maxFacts) {
            val oldest = facts.values.minByOrNull { it.capturedAtMs } ?: break
            facts.remove(oldest.factId)
        }
    }

    private fun drainPendingTransitions(): MutableList<FitnessVlmProbeTransition> {
        val drained = pendingTransitions.toMutableList()
        pendingTransitions.clear()
        return drained
    }

    private fun FitnessVlmProbeRecord.isActive(): Boolean {
        return status == FitnessVlmProbeStatus.OPEN || status == FitnessVlmProbeStatus.OBSERVING
    }

    private fun String.isUninformative(): Boolean {
        return equals("not_observable", ignoreCase = true)
    }

    private companion object {
        private val PROBE_RESULTS = setOf("supported", "refuted", "not_observable")
    }
}

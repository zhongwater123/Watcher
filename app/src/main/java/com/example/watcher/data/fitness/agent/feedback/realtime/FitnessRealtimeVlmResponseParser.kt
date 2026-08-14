package com.example.watcher.data.fitness.agent.feedback.realtime

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

internal object FitnessRealtimeVlmResponseParser {
    private val gson = Gson()

    fun parse(
        raw: String,
        configuration: FitnessRealtimeVlmConfiguration
    ): Result<FitnessVlmParsedResponse> = runCatching {
        val json = extractJsonBlock(raw) ?: error("response_does_not_contain_json")
        val payload = try {
            gson.fromJson(json, ResponsePayload::class.java)
        } catch (error: JsonSyntaxException) {
            throw IllegalArgumentException("invalid_json", error)
        } ?: error("empty_json_payload")

        val facts = payload.facts.orEmpty().map { fact ->
            val ref = fact.ref.required("facts.ref")
            require(LOCAL_FACT_REF.matches(ref)) { "invalid_fact_ref:$ref" }
            FitnessVlmFactDraft(
                ref = ref,
                observation = fact.text.required("facts.text"),
                confidence = fact.confidence.validConfidence("facts.confidence"),
                observability = fact.visibility.toObservability()
            )
        }
        require(facts.isNotEmpty()) { "missing_facts" }
        require(facts.size <= configuration.maxCurrentFacts) { "too_many_facts" }
        require(facts.map { it.ref }.distinct().size == facts.size) { "duplicate_fact_ref" }

        val probeResults = payload.probeResults.orEmpty().map { result ->
            val parsedResult = result.result.required("probe_results.result")
            require(parsedResult in PROBE_RESULTS) { "invalid_probe_result:$parsedResult" }
            val factRef = result.fact?.trim()?.takeIf(String::isNotBlank)
            if (parsedResult == "not_observable") {
                require(factRef == null) { "uninformative_probe_result_must_not_reference_fact" }
            } else {
                require(factRef != null && LOCAL_FACT_REF.matches(factRef)) {
                    "probe_result_requires_current_fact_ref"
                }
            }
            FitnessVlmProbeResultDraft(
                probeId = result.id.required("probe_results.id"),
                result = parsedResult,
                confidence = result.confidence.validConfidence("probe_results.confidence"),
                factRef = factRef
            )
        }

        val newProbe = payload.probe?.let { probe ->
            val source = probe.source.required("probe.source")
            require(LOCAL_FACT_REF.matches(source)) { "invalid_probe_source:$source" }
            FitnessVlmNewProbeDraft(
                question = probe.question.required("probe.question"),
                sourceFactRef = source,
                candidateFinding = probe.finding.required("probe.finding")
            )
        }

        val coachCandidate = payload.coach?.let { coach ->
            val message = coach.text.orEmpty().trim()
            if (message.isBlank()) {
                null
            } else {
                FitnessVlmCoachCandidateDraft(
                    message = message.take(MAX_COACH_MESSAGE_CHARS),
                    basedOnFactRefs = coach.facts.orEmpty().map(String::trim).filter(String::isNotBlank).distinct(),
                    originProbeId = coach.probe?.trim()?.takeIf(String::isNotBlank),
                    confidence = coach.confidence.validConfidence("coach.confidence")
                )
            }
        }

        FitnessVlmParsedResponse(
            currentFacts = facts,
            probeResults = probeResults,
            newProbe = newProbe,
            coachCandidate = coachCandidate
        )
    }

    private fun String?.required(field: String): String {
        return orEmpty().trim().also { require(it.isNotBlank()) { "missing_$field" } }
    }

    private fun Float?.validConfidence(field: String): Float {
        val value = this ?: error("missing_$field")
        require(value.isFinite() && value in 0f..1f) { "invalid_$field" }
        return value
    }

    private fun String?.toObservability(): FitnessVlmObservability {
        return when (orEmpty().trim().lowercase()) {
            "clear" -> FitnessVlmObservability.CLEAR
            "partial" -> FitnessVlmObservability.PARTIAL
            "not_observable" -> FitnessVlmObservability.NOT_OBSERVABLE
            "insufficient_evidence" -> FitnessVlmObservability.INSUFFICIENT_EVIDENCE
            else -> error("invalid_facts.visibility")
        }
    }

    private fun extractJsonBlock(response: String): String? {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else null
    }

    private data class ResponsePayload(
        @SerializedName("facts") val facts: List<FactPayload>? = emptyList(),
        @SerializedName("probe_results") val probeResults: List<ProbeResultPayload>? = emptyList(),
        @SerializedName("probe") val probe: ProbePayload? = null,
        @SerializedName("coach") val coach: CoachPayload? = null
    )

    private data class FactPayload(
        @SerializedName("ref") val ref: String? = null,
        @SerializedName("text") val text: String? = null,
        @SerializedName("confidence") val confidence: Float? = null,
        @SerializedName("visibility") val visibility: String? = null
    )

    private data class ProbeResultPayload(
        @SerializedName("id") val id: String? = null,
        @SerializedName("result") val result: String? = null,
        @SerializedName("confidence") val confidence: Float? = null,
        @SerializedName("fact") val fact: String? = null
    )

    private data class ProbePayload(
        @SerializedName("question") val question: String? = null,
        @SerializedName("source") val source: String? = null,
        @SerializedName("finding") val finding: String? = null
    )

    private data class CoachPayload(
        @SerializedName("text") val text: String? = null,
        @SerializedName("facts") val facts: List<String>? = emptyList(),
        @SerializedName("probe") val probe: String? = null,
        @SerializedName("confidence") val confidence: Float? = null
    )

    private val LOCAL_FACT_REF = Regex("C[1-9][0-9]?")
    private val PROBE_RESULTS = setOf("supported", "refuted", "not_observable")
    private const val MAX_COACH_MESSAGE_CHARS = 96
}

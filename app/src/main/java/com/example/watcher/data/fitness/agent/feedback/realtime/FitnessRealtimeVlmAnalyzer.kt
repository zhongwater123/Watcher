package com.example.watcher.data.fitness.agent.feedback.realtime

import android.os.SystemClock
import android.util.Log
import com.example.watcher.data.local.FitnessCompanionDao
import com.example.watcher.data.model.FITNESS_VLM_LOG_TAG
import com.example.watcher.data.model.FITNESS_VLM_WIRE_LOG_TAG
import com.example.watcher.data.model.FitnessRealtimeFeedbackEventEntity
import com.example.watcher.data.remote.ArkChatCompletionStreamEvent
import com.example.watcher.data.remote.ArkStreamingClient
import com.example.watcher.data.remote.DoubaoChatCompletionRequest
import com.example.watcher.data.remote.DoubaoChatContentItem
import com.example.watcher.data.remote.DoubaoChatImageUrl
import com.example.watcher.data.remote.DoubaoChatMessage
import com.example.watcher.data.remote.RetrofitClient
import com.example.watcher.data.repository.ArkConfig
import com.example.watcher.data.repository.LlmWalletRepository
import com.example.watcher.data.training.fitness.FITNESS_TRAINING_STREAM_OWNER
import com.example.watcher.data.training.fitness.TrainingFrame
import com.example.watcher.data.training.fitness.TrainingFrameAnalyzer
import com.example.watcher.data.training.fitness.TrainingIntervalContext
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FitnessRealtimeVlmAnalyzer(
    private val dao: FitnessCompanionDao,
    private val streamingClient: ArkStreamingClient,
    private val llmWalletRepository: LlmWalletRepository,
    private val configuration: FitnessRealtimeVlmConfiguration = FitnessRealtimeVlmConfiguration()
) : TrainingFrameAnalyzer<FitnessRealtimeVlmState> {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gson = Gson()
    private val droppedCount = AtomicInteger(0)
    private val recordedFrameCount = AtomicInteger(0)
    private val analyzedFrameCount = AtomicInteger(0)
    private val submittedFrameSeq = AtomicLong(0L)
    private val lastDispatchedFrameSeq = AtomicLong(0L)
    private var analysisJob: Job? = null
    @Volatile private var activeIntervalId: String = ""
    @Volatile private var latestSubmittedFrame: SubmittedFrame? = null

    private val _state = MutableStateFlow(FitnessRealtimeVlmState())
    override val state: StateFlow<FitnessRealtimeVlmState> = _state.asStateFlow()

    override fun start(context: TrainingIntervalContext) {
        stop()
        if (context.exerciseName.isBlank()) {
            _state.value = FitnessRealtimeVlmState(
                supported = false,
                currentExerciseName = "当前动作",
                statusText = "缺少动作上下文"
            )
            return
        }

        droppedCount.set(0)
        recordedFrameCount.set(0)
        analyzedFrameCount.set(0)
        submittedFrameSeq.set(0L)
        lastDispatchedFrameSeq.set(0L)

        val sessionId = context.sessionId.ifBlank { "fitness_vlm_${UUID.randomUUID()}" }
        val intervalId = context.intervalId.ifBlank {
            "${sessionId}_${context.exerciseId}_${SystemClock.elapsedRealtime()}"
        }
        val intervalEvidenceStore = FitnessRealtimeVlmEvidenceStore(configuration)
        val intervalPendingCount = AtomicInteger(0)
        activeIntervalId = intervalId
        _state.value = FitnessRealtimeVlmState(
            active = true,
            supported = true,
            sessionId = sessionId,
            exerciseIntervalId = intervalId,
            currentExerciseName = context.exerciseName,
            statusText = "正在观察动作"
        )

        analysisJob = scope.launch {
            val frameBuffer = FitnessRealtimeVlmFrameBuffer(
                retentionMs = configuration.frameRetentionMs,
                maxLongEdgePx = configuration.frameMaxLongEdgePx,
                jpegQuality = configuration.frameJpegQuality
            )
            Log.d(
                FITNESS_VLM_LOG_TAG,
                "fitness_vlm:start session=$sessionId interval=$intervalId exercise=${context.exerciseName} " +
                    "workers=${configuration.workerCount} captureIntervalMs=${configuration.frameCaptureIntervalMs} " +
                    "model=${configuration.model} promptVersion=${configuration.promptVersion}"
            )
            try {
                val jobs = mutableListOf<Job>()
                jobs += launch {
                    captureFrames(
                        frameBuffer = frameBuffer,
                        intervalId = intervalId,
                        sessionId = sessionId
                    )
                }
                repeat(configuration.workerCount) { index ->
                    jobs += launch {
                        runWorkerLoop(
                            workerId = "W${index + 1}",
                            initialDelayMs = configuration.workerStaggerMs * index,
                            frameBuffer = frameBuffer,
                            context = context,
                            sessionId = sessionId,
                            intervalId = intervalId,
                            store = intervalEvidenceStore,
                            intervalPendingCount = intervalPendingCount
                        )
                    }
                }
                jobs.joinAll()
            } finally {
                frameBuffer.clear()
                intervalEvidenceStore.clear()
                Log.d(
                    FITNESS_VLM_LOG_TAG,
                    "fitness_vlm:stop session=$sessionId interval=$intervalId analyzed=${analyzedFrameCount.get()} dropped=${droppedCount.get()}"
                )
                if (_state.value.exerciseIntervalId == intervalId) {
                    _state.value = _state.value.copy(
                        active = false,
                        analyzing = false,
                        statusText = "训练观察已结束"
                    )
                }
            }
        }
    }

    override fun submitFrame(frame: TrainingFrame) {
        if (_state.value.active && activeIntervalId.isNotBlank()) {
            latestSubmittedFrame = SubmittedFrame(
                sourceSeq = submittedFrameSeq.incrementAndGet(),
                frame = frame
            )
        }
    }

    override fun stop() {
        activeIntervalId = ""
        latestSubmittedFrame = null
        analysisJob?.cancel()
        analysisJob = null
        if (_state.value.active) {
            _state.value = _state.value.copy(
                active = false,
                analyzing = false,
                statusText = "训练观察已结束"
            )
        }
    }

    override fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun captureFrames(
        frameBuffer: FitnessRealtimeVlmFrameBuffer,
        intervalId: String,
        sessionId: String
    ) {
        var lastCapturedSourceSeq = 0L
        var missCount = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && activeIntervalId == intervalId) {
            val submitted = latestSubmittedFrame
            if (submitted == null || submitted.sourceSeq <= lastCapturedSourceSeq) {
                missCount += 1
            } else {
                val buffered = frameBuffer.offer(
                    frame = submitted.frame,
                    sourceFrameSeq = submitted.sourceSeq,
                    bufferedAtElapsedMs = SystemClock.elapsedRealtime(),
                    source = FITNESS_TRAINING_STREAM_OWNER
                )
                if (buffered != null) {
                    lastCapturedSourceSeq = submitted.sourceSeq
                    missCount = 0
                    recordedFrameCount.incrementAndGet()
                    Log.d(
                        FITNESS_VLM_LOG_TAG,
                        "fitness_vlm:frame_buffered session=$sessionId interval=$intervalId frameSeq=${buffered.frameSeq} " +
                            "sourceSeq=${buffered.sourceFrameSeq} capturedAt=${buffered.capturedAtMs} bytes=${buffered.byteLength}"
                    )
                }
            }
            delay(configuration.frameCaptureIntervalMs.coerceAtLeast(1L))
        }
    }

    private suspend fun runWorkerLoop(
        workerId: String,
        initialDelayMs: Long,
        frameBuffer: FitnessRealtimeVlmFrameBuffer,
        context: TrainingIntervalContext,
        sessionId: String,
        intervalId: String,
        store: FitnessRealtimeVlmEvidenceStore,
        intervalPendingCount: AtomicInteger
    ) {
        delay(initialDelayMs)
        var lastWorkerFrameSeq = 0L
        while (kotlinx.coroutines.currentCoroutineContext().isActive && activeIntervalId == intervalId) {
            val frame = frameBuffer.latestFrame(SystemClock.elapsedRealtime())
            if (frame == null) {
                delay(configuration.emptyFrameRetryMs)
                continue
            }
            if (frame.frameSeq <= lastWorkerFrameSeq) {
                delay(configuration.duplicateFrameRetryMs)
                continue
            }
            val globalSeq = lastDispatchedFrameSeq.get()
            if (frame.frameSeq <= globalSeq || !lastDispatchedFrameSeq.compareAndSet(globalSeq, frame.frameSeq)) {
                delay(configuration.duplicateFrameRetryMs)
                continue
            }
            lastWorkerFrameSeq = frame.frameSeq
            analyzeFrame(
                workerId = workerId,
                frame = frame,
                context = context,
                sessionId = sessionId,
                intervalId = intervalId,
                store = store,
                intervalPendingCount = intervalPendingCount
            )
            delay(configuration.workerMinIntervalMs)
        }
    }

    private suspend fun analyzeFrame(
        workerId: String,
        frame: FitnessRealtimeVlmFrame,
        context: TrainingIntervalContext,
        sessionId: String,
        intervalId: String,
        store: FitnessRealtimeVlmEvidenceStore,
        intervalPendingCount: AtomicInteger
    ) {
        val requestId = "R${frame.frameSeq}_${UUID.randomUUID().toString().take(8)}"
        val requestStartElapsedMs = SystemClock.elapsedRealtime()
        val promptContext = store.promptContext(
            capturedAtMs = frame.capturedAtMs,
            nowElapsedMs = requestStartElapsedMs,
            exercise = context
        )
        val prompt = FitnessRealtimeVlmPromptBuilder.build(promptContext)
        intervalPendingCount.incrementAndGet()
        _state.value = _state.value.copy(
            analyzing = true
        )
        Log.d(
            FITNESS_VLM_LOG_TAG,
            "fitness_vlm:dispatch worker=$workerId request=$requestId frameSeq=${frame.frameSeq} " +
                "capturedAt=${frame.capturedAtMs} rollingFacts=${promptContext.rollingFacts.size} " +
                "activeProbes=${promptContext.activeProbes.size}"
        )

        try {
            val analysis = requestVlm(workerId, requestId, frame, prompt)
            val finishedElapsedMs = SystemClock.elapsedRealtime()
            val frameAgeMs = maxOf(
                (System.currentTimeMillis() - frame.capturedAtMs).coerceAtLeast(0L),
                finishedElapsedMs - frame.bufferedAtElapsedMs
            )
            if (frameAgeMs > configuration.maxResultAgeMs || activeIntervalId != intervalId) {
                droppedCount.incrementAndGet()
                Log.d(
                    FITNESS_VLM_LOG_TAG,
                    "fitness_vlm:discarded worker=$workerId request=$requestId frameSeq=${frame.frameSeq} " +
                        "reason=stale frameAgeMs=$frameAgeMs maxResultAgeMs=${configuration.maxResultAgeMs}"
                )
                persistResponseEvent(
                    context = context,
                    sessionId = sessionId,
                    intervalId = intervalId,
                    workerId = workerId,
                    requestId = requestId,
                    frame = frame,
                    finishedElapsedMs = finishedElapsedMs,
                    raw = analysis.raw,
                    status = "stale",
                    discardReason = "stale_${frameAgeMs}ms"
                )
                return
            }

            val parsed = FitnessRealtimeVlmResponseParser.parse(analysis.raw, configuration)
            if (parsed.isFailure) {
                val reason = parsed.exceptionOrNull()?.message.orEmpty().ifBlank { "parse_failed" }
                droppedCount.incrementAndGet()
                Log.w(
                    FITNESS_VLM_LOG_TAG,
                    "fitness_vlm:parse_failed worker=$workerId request=$requestId frameSeq=${frame.frameSeq} reason=$reason"
                )
                persistResponseEvent(
                    context = context,
                    sessionId = sessionId,
                    intervalId = intervalId,
                    workerId = workerId,
                    requestId = requestId,
                    frame = frame,
                    finishedElapsedMs = finishedElapsedMs,
                    raw = analysis.raw,
                    status = "parse_failed",
                    discardReason = reason
                )
                _state.value = _state.value.copy(
                    statusText = "持续观察中",
                    lastError = reason
                )
                return
            }

            val parsedResponse = parsed.getOrThrow()
            val reportedObservations = parsedResponse.currentFacts
                .map { fact ->
                    FitnessVlmObservationDisplay(
                        observation = fact.observation,
                        confidence = fact.confidence,
                        observability = fact.observability,
                        acceptedAsEvidence = fact.observability == FitnessVlmObservability.CLEAR ||
                            fact.observability == FitnessVlmObservability.PARTIAL
                    )
                }
            val merge = store.merge(
                parsed = parsedResponse,
                requestId = requestId,
                frameSeq = frame.frameSeq,
                capturedAtMs = frame.capturedAtMs,
                nowElapsedMs = finishedElapsedMs
            )
            val verifiedFeedback = merge.feedback
            val reportedCoachDraft = parsedResponse.coachCandidate
                ?.takeIf { it.message.isNotBlank() }
            val reportedCoach = reportedCoachDraft?.let { coach ->
                val blockReasons = when {
                    verifiedFeedback != null -> emptyList()
                    else -> merge.discardReasons
                        .filter { reason -> reason.startsWith("coach_") || reason.startsWith("direct_coach_") }
                        .ifEmpty { listOf("evidence_not_closed") }
                }
                FitnessVlmCoachDisplay(
                    message = coach.message,
                    confidence = coach.confidence,
                    acceptedAsFeedback = verifiedFeedback != null,
                    blockReasons = blockReasons
                )
            }

            val finalStatus = when {
                verifiedFeedback != null -> "coach_verified"
                reportedCoach != null -> "coach_unverified"
                else -> merge.status
            }
            analyzedFrameCount.incrementAndGet()
            _state.update { current ->
                val hasNewerObservation = reportedObservations.isNotEmpty() &&
                    frame.frameSeq >= current.latestObservationFrameSeq
                val hasNewerCoach = reportedCoach != null && frame.frameSeq >= current.latestCoachFrameSeq
                current.copy(
                    latestObservations = if (hasNewerObservation) {
                        reportedObservations
                    } else {
                        current.latestObservations
                    },
                    latestObservationFrameSeq = if (hasNewerObservation) {
                        frame.frameSeq
                    } else {
                        current.latestObservationFrameSeq
                    },
                    latestCoachCandidate = if (hasNewerCoach) reportedCoach else current.latestCoachCandidate,
                    latestCoachFrameSeq = if (hasNewerCoach) frame.frameSeq else current.latestCoachFrameSeq,
                    latestVerifiedFeedback = verifiedFeedback?.message ?: current.latestVerifiedFeedback,
                    statusText = when {
                        verifiedFeedback != null -> "教练反馈已通过闭环"
                        reportedCoach != null -> "已收到未验证反馈"
                        else -> "持续观察中"
                    },
                    factCount = merge.factCount,
                    activeProbeCount = merge.activeProbeCount,
                    lastError = null
                )
            }
            persistResponseEvent(
                context = context,
                sessionId = sessionId,
                intervalId = intervalId,
                workerId = workerId,
                requestId = requestId,
                frame = frame,
                finishedElapsedMs = finishedElapsedMs,
                raw = analysis.raw,
                status = finalStatus,
                finalFeedback = verifiedFeedback?.message.orEmpty(),
                discardReason = merge.discardReasons.joinToString("|"),
                normalized = gson.toJson(
                    mapOf(
                        "request_id" to requestId,
                        "prompt_version" to configuration.promptVersion,
                        "model" to configuration.model,
                        "worker_id" to workerId,
                        "frame_seq" to frame.frameSeq,
                        "source_frame_seq" to frame.sourceFrameSeq,
                        "captured_at_ms" to frame.capturedAtMs,
                        "returned_at_ms" to System.currentTimeMillis(),
                        "request_ms" to analysis.requestMs,
                        "first_text_ms" to analysis.firstTextMs,
                        "accepted_facts" to merge.acceptedFacts,
                        "probe_transitions" to merge.transitions,
                        "findings" to merge.findings,
                        "accepted_probe" to merge.acceptedProbe,
                        "reported_coach_candidate" to reportedCoachDraft,
                        "verified_coach_feedback" to verifiedFeedback,
                        "shown_in_ui" to (reportedCoach != null)
                    )
                )
            )
            persistProbeTransitions(
                transitions = merge.transitions,
                context = context,
                sessionId = sessionId,
                intervalId = intervalId,
                workerId = workerId,
                requestId = requestId,
                frame = frame,
                finishedElapsedMs = finishedElapsedMs
            )
            Log.d(
                FITNESS_VLM_LOG_TAG,
                "fitness_vlm:done worker=$workerId request=$requestId frameSeq=${frame.frameSeq} status=$finalStatus " +
                    "reportedFacts=${parsedResponse.currentFacts.size} evidenceFacts=${merge.acceptedFacts.size} " +
                    "coachReported=${reportedCoach != null} coachVerified=${verifiedFeedback != null} " +
                    "activeProbes=${merge.activeProbeCount} discard=${merge.discardReasons.joinToString("|")}"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val finishedElapsedMs = SystemClock.elapsedRealtime()
            Log.e(
                FITNESS_VLM_LOG_TAG,
                "fitness_vlm:failed worker=$workerId request=$requestId frameSeq=${frame.frameSeq}",
                error
            )
            persistResponseEvent(
                context = context,
                sessionId = sessionId,
                intervalId = intervalId,
                workerId = workerId,
                requestId = requestId,
                frame = frame,
                finishedElapsedMs = finishedElapsedMs,
                raw = "",
                status = "failed",
                discardReason = error.message.orEmpty()
            )
            _state.value = _state.value.copy(
                statusText = "持续观察中",
                lastError = error.message
            )
        } finally {
            val remaining = intervalPendingCount.decrementAndGet().coerceAtLeast(0)
            if (_state.value.exerciseIntervalId == intervalId) {
                _state.value = _state.value.copy(analyzing = remaining > 0)
            }
        }
    }

    private suspend fun requestVlm(
        workerId: String,
        requestId: String,
        frame: FitnessRealtimeVlmFrame,
        prompt: String
    ): VlmAnalysis {
        val startedAt = SystemClock.elapsedRealtime()
        var firstTextMs = 0L
        Log.d(
            FITNESS_VLM_LOG_TAG,
            "fitness_vlm:provider_resolve_start worker=$workerId request=$requestId frameSeq=${frame.frameSeq}"
        )
        val providerConfig = llmWalletRepository.resolveArkResponsesConfig(ArkConfig.videoAnalysisModel)
        Log.d(
            FITNESS_VLM_LOG_TAG,
            "fitness_vlm:provider_resolved worker=$workerId request=$requestId provider=${providerConfig.displayName} " +
                "source=${providerConfig.source} endpoint=${providerConfig.endpoint} configuredModel=${providerConfig.modelName}"
        )
        val request = DoubaoChatCompletionRequest(
            model = configuration.model,
            messages = listOf(
                DoubaoChatMessage(
                    role = "user",
                    content = listOf(
                        DoubaoChatContentItem(
                            type = "image_url",
                            imageUrl = DoubaoChatImageUrl(
                                url = frame.imageDataUri,
                                detail = configuration.imageDetail
                            )
                        ),
                        DoubaoChatContentItem(type = "text", text = prompt)
                    )
                )
            ),
            stream = true,
            maxTokens = configuration.maxOutputTokens,
            temperature = configuration.temperature
        )
        val wireHeader = "fitness_vlm_wire worker=$workerId request=$requestId frameSeq=${frame.frameSeq}"
        val wireRequest = request.copy(
            messages = request.messages.map { message ->
                message.copy(
                    content = message.content.map { item ->
                        item.copy(
                            imageUrl = item.imageUrl?.copy(
                                url = "data:image/jpeg;base64,<omitted:${frame.byteLength}_bytes>"
                            )
                        )
                    }
                )
            }
        )
        Log.d(
            FITNESS_VLM_WIRE_LOG_TAG,
            "$wireHeader actualRoute=${RetrofitClient.BASE_URL}api/v3/chat/completions " +
                "configuredEndpoint=${providerConfig.endpoint} provider=${providerConfig.displayName} " +
                "configuredModel=${providerConfig.modelName} actualModel=${request.model}"
        )
        logWire("$wireHeader request_body", gson.toJson(wireRequest))
        Log.d(
            FITNESS_VLM_LOG_TAG,
            "fitness_vlm:request_start worker=$workerId request=$requestId frameSeq=${frame.frameSeq} " +
                "capturedAt=${frame.capturedAtMs} promptChars=${prompt.length} imageBytes=${frame.byteLength}"
        )
        return try {
            val raw = streamingClient.streamChatCompletion(
                authorization = providerConfig.bearerToken(),
                requestPayload = request
            ) { event ->
                if (event is ArkChatCompletionStreamEvent.ContentDelta && firstTextMs == 0L) {
                    firstTextMs = SystemClock.elapsedRealtime() - startedAt
                }
            }.trim()
            val requestMs = SystemClock.elapsedRealtime() - startedAt
            Log.d(
                FITNESS_VLM_WIRE_LOG_TAG,
                "$wireHeader response_meta requestMs=$requestMs firstTextMs=$firstTextMs chars=${raw.length}"
            )
            logWire("$wireHeader response_body", raw)
            VlmAnalysis(
                raw = raw,
                requestMs = requestMs,
                firstTextMs = firstTextMs
            )
        } catch (error: Exception) {
            Log.e(FITNESS_VLM_WIRE_LOG_TAG, "$wireHeader request_failed", error)
            throw error
        }
    }

    private suspend fun persistResponseEvent(
        context: TrainingIntervalContext,
        sessionId: String,
        intervalId: String,
        workerId: String,
        requestId: String,
        frame: FitnessRealtimeVlmFrame,
        finishedElapsedMs: Long,
        raw: String,
        status: String,
        finalFeedback: String = "",
        discardReason: String = "",
        normalized: String = ""
    ) {
        dao.insertRealtimeFeedbackEvent(
            FitnessRealtimeFeedbackEventEntity(
                profileId = context.profileId,
                planId = context.planId,
                exerciseId = context.exerciseId,
                sessionId = sessionId,
                exerciseIntervalId = intervalId,
                segmentId = requestId,
                observerId = workerId,
                exerciseName = context.exerciseName,
                exerciseEquipment = context.equipment,
                eventType = "vlm_response",
                status = status,
                segmentStartElapsedMs = frame.bufferedAtElapsedMs,
                segmentEndElapsedMs = frame.bufferedAtElapsedMs,
                analysisFinishedElapsedMs = finishedElapsedMs,
                rawObserverJson = raw,
                rawCoachJson = normalized.ifBlank {
                    gson.toJson(
                        mapOf(
                            "request_id" to requestId,
                            "prompt_version" to configuration.promptVersion,
                            "model" to configuration.model,
                            "worker_id" to workerId,
                            "frame_seq" to frame.frameSeq,
                            "source_frame_seq" to frame.sourceFrameSeq,
                            "captured_at_ms" to frame.capturedAtMs,
                            "returned_at_ms" to System.currentTimeMillis()
                        )
                    )
                },
                finalFeedback = finalFeedback,
                discardReason = discardReason
            )
        )
    }

    private suspend fun persistProbeTransitions(
        transitions: List<FitnessVlmProbeTransition>,
        context: TrainingIntervalContext,
        sessionId: String,
        intervalId: String,
        workerId: String,
        requestId: String,
        frame: FitnessRealtimeVlmFrame,
        finishedElapsedMs: Long
    ) {
        transitions.filter { it.to.isTerminal() }.forEach { transition ->
            dao.insertRealtimeFeedbackEvent(
                FitnessRealtimeFeedbackEventEntity(
                    profileId = context.profileId,
                    planId = context.planId,
                    exerciseId = context.exerciseId,
                    sessionId = sessionId,
                    exerciseIntervalId = intervalId,
                    segmentId = "${requestId}_${transition.probeId}_${transition.to.name.lowercase()}",
                    observerId = workerId,
                    exerciseName = context.exerciseName,
                    exerciseEquipment = context.equipment,
                    eventType = "probe_terminal",
                    status = "probe_${transition.to.name.lowercase()}",
                    segmentStartElapsedMs = frame.bufferedAtElapsedMs,
                    segmentEndElapsedMs = frame.bufferedAtElapsedMs,
                    analysisFinishedElapsedMs = finishedElapsedMs,
                    rawObserverJson = gson.toJson(transition),
                    discardReason = transition.reason
                )
            )
        }
    }

    private fun FitnessVlmProbeStatus.isTerminal(): Boolean {
        return this == FitnessVlmProbeStatus.SUPPORTED ||
            this == FitnessVlmProbeStatus.REFUTED ||
            this == FitnessVlmProbeStatus.EXPIRED
    }

    private fun logWire(header: String, content: String) {
        Log.d(FITNESS_VLM_WIRE_LOG_TAG, "$header length=${content.length}")
        content.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            Log.d(FITNESS_VLM_WIRE_LOG_TAG, "$header chunk=$index $chunk")
        }
    }

    private data class SubmittedFrame(
        val sourceSeq: Long,
        val frame: TrainingFrame
    )

    private data class VlmAnalysis(
        val raw: String,
        val requestMs: Long,
        val firstTextMs: Long
    )

    private companion object {
        private const val LOG_CHUNK_SIZE = 3_500
    }
}

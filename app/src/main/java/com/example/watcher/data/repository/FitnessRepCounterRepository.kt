package com.example.watcher.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.watcher.data.local.FitnessCompanionDao
import com.example.watcher.data.local.pose.DelegateType
import com.example.watcher.data.local.pose.ModelComplexity
import com.example.watcher.data.local.pose.PoseDetectorConfig
import com.example.watcher.data.local.pose.PoseEstimationEngine
import com.example.watcher.data.model.FITNESS_REP_COUNTER_LOG_TAG
import com.example.watcher.data.model.FitnessRepCounterState
import com.example.watcher.data.model.FitnessRepCounterStatus
import com.example.watcher.data.model.FitnessRepEventEntity
import com.example.watcher.data.model.FitnessRepQuality
import com.example.watcher.data.model.FitnessUserProfileEntity
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity
import com.example.watcher.data.model.FitnessWorkoutPlanEntity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class FitnessRepCounterRepository(
    context: Context,
    private val dao: FitnessCompanionDao
) {
    private val appContext = context.applicationContext
    private val engine = PoseEstimationEngine(appContext)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val processing = AtomicBoolean(false)
    private val gson = Gson()
    private val cycleCounter = VisualCycleCounter(gson)

    private val _state = MutableStateFlow(FitnessRepCounterState())
    val state: StateFlow<FitnessRepCounterState> = _state.asStateFlow()

    @Volatile private var activeContext: CounterContext? = null
    @Volatile private var lastProcessElapsedMs: Long = 0L
    @Volatile private var lastVideoTimestampMs: Long = 0L
    private var frameCount = 0
    private var lastFpsElapsedMs = 0L
    private var currentFps = 0
    private var lastStateLogKey = ""
    private var lastSampleLogElapsedMs = 0L
    private var lastRejectedLogKey = ""
    private var lastCandidateSeenKey = ""
    private var lastLockedFamily = ""
    private var lastFilteredLogKey = ""
    private var lastWindowLogKey = ""
    private var lastPersistedRepIndex = 0

    fun start(
        profile: FitnessUserProfileEntity,
        plan: FitnessWorkoutPlanEntity,
        exercise: FitnessWorkoutExerciseEntity?
    ) {
        stop(resetToIdle = false)
        if (!supportsCycleCounting(exercise)) {
            _state.value = FitnessRepCounterState(
                active = false,
                status = FitnessRepCounterStatus.Unsupported,
                notCountingReason = "unsupported for automatic rep counting"
            )
            Log.d(
                FITNESS_REP_COUNTER_LOG_TAG,
                "rep_counter:unsupported planId=${plan.id} exercise=${exercise?.name.orEmpty()} reps=${exercise?.reps.orEmpty()} duration=${exercise?.durationSeconds ?: 0}"
            )
            return
        }
        val targetExercise = exercise ?: return

        val now = SystemClock.elapsedRealtime()
        activeContext = CounterContext(
            profileId = profile.profileId,
            planId = plan.id,
            exerciseId = targetExercise.id,
            sessionId = plan.sessionId.ifBlank { "fitness_rep_${plan.id}_${targetExercise.id}_$now" },
            exerciseIntervalId = "${plan.id}_${targetExercise.id}_rep_$now"
        )
        cycleCounter.reset()
        lastProcessElapsedMs = 0L
        lastVideoTimestampMs = 0L
        frameCount = 0
        currentFps = 0
        lastFpsElapsedMs = now
        lastStateLogKey = ""
        lastSampleLogElapsedMs = 0L
        lastRejectedLogKey = ""
        lastCandidateSeenKey = ""
        lastLockedFamily = ""
        lastFilteredLogKey = ""
        lastWindowLogKey = ""
        lastPersistedRepIndex = 0
        _state.value = FitnessRepCounterState(
            active = true,
            status = FitnessRepCounterStatus.Initializing,
            notCountingReason = "initializing local counter"
        )
        val expectedContext = activeContext
        scope.launch {
            val result = engine.initializeForVideo(
                PoseDetectorConfig(
                    modelComplexity = ModelComplexity.LITE,
                    maxNumPoses = 2,
                    minDetectionConfidence = VISIBILITY_THRESHOLD,
                    minTrackingConfidence = VISIBILITY_THRESHOLD,
                    delegateType = DelegateType.GPU
                )
            )
            if (expectedContext == null || activeContext != expectedContext) {
                engine.release()
                return@launch
            }
            result.fold(
                onSuccess = { delegate ->
                    _state.value = _state.value.copy(
                        active = true,
                        status = FitnessRepCounterStatus.Ready,
                        notCountingReason = "waiting for stable angle cycle",
                        fps = 0
                    )
                    Log.d(
                        FITNESS_REP_COUNTER_LOG_TAG,
                        "rep_counter:started planId=${plan.id} exercise=${targetExercise.name} delegate=${delegate.label} session=${activeContext?.sessionId.orEmpty()} interval=${activeContext?.exerciseIntervalId.orEmpty()}"
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        active = false,
                        status = FitnessRepCounterStatus.Error,
                        notCountingReason = error.message ?: "MediaPipe initialization failed"
                    )
                    Log.w(FITNESS_REP_COUNTER_LOG_TAG, "rep_counter:init_failed", error)
                }
            )
        }
    }

    fun processFrame(bitmap: Bitmap?) {
        val contextAtDispatch = activeContext
        if (bitmap == null || contextAtDispatch == null || !_state.value.active) return
        if (_state.value.status == FitnessRepCounterStatus.Initializing || _state.value.status == FitnessRepCounterStatus.Error) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessElapsedMs < PROCESS_INTERVAL_MS) return
        if (!processing.compareAndSet(false, true)) return
        lastProcessElapsedMs = now
        val timestampMs = now.coerceAtLeast(lastVideoTimestampMs + 1L)
        lastVideoTimestampMs = timestampMs
        scope.launch {
            try {
                val result = engine.detectForVideo(bitmap, timestampMs)
                if (activeContext != contextAtDispatch) return@launch
                val next = cycleCounter.update(result, now)
                updateFps(now)
                val updated = next.copy(
                    active = true,
                    poseResult = result,
                    inferenceTimeMs = result.inferenceTimeMs,
                    fps = currentFps
                )
                _state.value = updated
                logStateIfChanged(updated)
                logSampleIfDue(updated, now)
                logRejectionIfChanged(updated)
                logCalibrationEvents(updated)
                val confirmedQualities = next.confirmedRepQualities.ifEmpty {
                    next.lastRepQuality?.let { listOf(it) }.orEmpty()
                }
                confirmedQualities
                    .filter { it.repIndex > lastPersistedRepIndex }
                    .sortedBy { it.repIndex }
                    .forEach { quality ->
                        persistRep(quality)
                        lastPersistedRepIndex = quality.repIndex
                    }
            } catch (e: Exception) {
                if (activeContext != contextAtDispatch) return@launch
                _state.value = _state.value.copy(
                    status = FitnessRepCounterStatus.Error,
                    notCountingReason = e.message ?: "counter processing failed"
                )
                Log.w(FITNESS_REP_COUNTER_LOG_TAG, "rep_counter:frame_failed", e)
            } finally {
                processing.set(false)
            }
        }
    }

    fun stop(resetToIdle: Boolean = true) {
        activeContext = null
        processing.set(false)
        engine.release()
        cycleCounter.reset()
        lastPersistedRepIndex = 0
        lastStateLogKey = ""
        lastSampleLogElapsedMs = 0L
        lastRejectedLogKey = ""
        lastCandidateSeenKey = ""
        lastLockedFamily = ""
        lastFilteredLogKey = ""
        lastWindowLogKey = ""
        if (resetToIdle) {
            _state.value = FitnessRepCounterState(
                active = false,
                status = FitnessRepCounterStatus.Unsupported,
                notCountingReason = ""
            )
        }
        Log.d(FITNESS_REP_COUNTER_LOG_TAG, "rep_counter:stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun persistRep(quality: FitnessRepQuality) {
        val context = activeContext ?: return
        val event = FitnessRepEventEntity(
            profileId = context.profileId,
            planId = context.planId,
            exerciseId = context.exerciseId,
            sessionId = context.sessionId,
            exerciseIntervalId = context.exerciseIntervalId,
            repIndex = quality.repIndex,
            startElapsedMs = quality.startElapsedMs,
            endElapsedMs = quality.endElapsedMs,
            durationMs = quality.durationMs,
            activeLandmarksJson = gson.toJson(quality.activeLandmarks),
            dominantAxis = quality.dominantAxis.name,
            rangeScore = quality.rangeScore,
            smoothnessScore = quality.smoothnessScore,
            visibilityScore = quality.visibilityScore,
            symmetryScore = quality.symmetryScore,
            confidence = quality.confidence,
            qualityLabel = quality.qualityLabel.name,
            rawSignalsJson = quality.rawSignalsJson
        )
        withContext(Dispatchers.IO) { dao.insertRepEvent(event) }
        Log.d(
            FITNESS_REP_COUNTER_LOG_TAG,
            "rep_counter:rep_saved session=${context.sessionId} interval=${context.exerciseIntervalId} rep=${quality.repIndex} signal=${quality.signalId.ifBlank { "-" }} confidence=${quality.confidence} label=${quality.qualityLabel}"
        )
    }

    private fun updateFps(nowElapsedMs: Long) {
        frameCount += 1
        val elapsed = nowElapsedMs - lastFpsElapsedMs
        if (elapsed >= 1_000L) {
            currentFps = (frameCount * 1000L / elapsed).toInt()
            frameCount = 0
            lastFpsElapsedMs = nowElapsedMs
        }
    }

    private fun logStateIfChanged(state: FitnessRepCounterState) {
        val key = listOf(
            state.status.name,
            state.phase.name,
            state.repCount.toString(),
            state.displayRepCount.toString(),
            state.officialRepCount.toString(),
            state.calibrationPhase,
            state.candidateFamily,
            state.lockedFamily,
            state.dominantAxis.name,
            state.selectedSignalId,
            state.primarySignalType,
            state.notCountingReason
        ).joinToString("|")
        if (key == lastStateLogKey) return
        lastStateLogKey = key
        Log.d(
            FITNESS_REP_COUNTER_LOG_TAG,
            "rep_counter:state status=${state.status} phase=${state.phase} calibration=${state.calibrationPhase.ifBlank { "-" }} reps=${state.repCount} display=${state.displayRepCount} official=${state.officialRepCount} candidateFamily=${state.candidateFamily.ifBlank { "-" }} pending=${state.candidatePendingCount} lockedFamily=${state.lockedFamily.ifBlank { "-" }} axis=${state.dominantAxis} signal=${state.selectedSignalId.ifBlank { "-" }} family=${state.primarySignalFamily.ifBlank { "-" }} type=${state.primarySignalType.ifBlank { "-" }} angle=${state.primaryAngleDeg} low=${state.lowThreshold} high=${state.highThreshold} dynamicLow=${state.dynamicLowThreshold} dynamicHigh=${state.dynamicHighThreshold} dynamicMinAmp=${state.dynamicMinAmplitude} dynamicPeriod=${state.dynamicPeriodMinMs}-${state.dynamicPeriodMaxMs} confirm=${state.confirmationSignalId.ifBlank { "-" }} score=${state.signalScore} amplitude=${state.signalAmplitude} confidence=${state.confidence} reason=${state.notCountingReason.ifBlank { "-" }} rejection=${state.rejectionReason.ifBlank { "-" }} filtered=${state.filteredFamily.ifBlank { "-" }} filterReason=${state.filterReason.ifBlank { "-" }} fps=${state.fps} inferenceMs=${state.inferenceTimeMs}"
        )
    }

    private fun logSampleIfDue(state: FitnessRepCounterState, nowElapsedMs: Long) {
        if (nowElapsedMs - lastSampleLogElapsedMs < SAMPLE_LOG_INTERVAL_MS) return
        lastSampleLogElapsedMs = nowElapsedMs
        Log.d(
            FITNESS_REP_COUNTER_LOG_TAG,
            "rep_counter:sample status=${state.status} phase=${state.phase} calibration=${state.calibrationPhase.ifBlank { "-" }} reps=${state.repCount} display=${state.displayRepCount} official=${state.officialRepCount} poses=${state.poseResult?.landmarks?.size ?: 0} candidates=${state.candidateCount} candidateFamily=${state.candidateFamily.ifBlank { "-" }} pending=${state.candidatePendingCount} lockedFamily=${state.lockedFamily.ifBlank { "-" }} primary=${state.primarySignalId.ifBlank { "-" }} family=${state.primarySignalFamily.ifBlank { "-" }} type=${state.primarySignalType.ifBlank { "-" }} label=${state.selectedSignalLabel.ifBlank { "-" }} angle=${state.primaryAngleDeg} low=${state.lowThreshold} high=${state.highThreshold} dynamicLow=${state.dynamicLowThreshold} dynamicHigh=${state.dynamicHighThreshold} dynamicMinAmp=${state.dynamicMinAmplitude} dynamicPeriod=${state.dynamicPeriodMinMs}-${state.dynamicPeriodMaxMs} confirm=${state.confirmationSignalId.ifBlank { "-" }} score=${state.signalScore} amplitude=${state.signalAmplitude} confidence=${state.confidence} periodMs=${state.estimatedPeriodMs} fps=${state.fps} inferenceMs=${state.inferenceTimeMs} reason=${state.notCountingReason.ifBlank { "-" }} rejection=${state.rejectionReason.ifBlank { "-" }} filtered=${state.filteredFamily.ifBlank { "-" }} filterReason=${state.filterReason.ifBlank { "-" }}"
        )
    }

    private fun logRejectionIfChanged(state: FitnessRepCounterState) {
        val reason = state.rejectionReason.ifBlank { return }
        val key = listOf(
            state.repCount.toString(),
            state.primarySignalId,
            reason,
            state.phase.name
        ).joinToString("|")
        if (key == lastRejectedLogKey) return
        lastRejectedLogKey = key
        Log.d(
            FITNESS_REP_COUNTER_LOG_TAG,
            "rep_counter:rep_rejected reps=${state.repCount} phase=${state.phase} primary=${state.primarySignalId.ifBlank { "-" }} type=${state.primarySignalType.ifBlank { "-" }} angle=${state.primaryAngleDeg} low=${state.lowThreshold} high=${state.highThreshold} confirm=${state.confirmationSignalId.ifBlank { "-" }} score=${state.signalScore} amplitude=${state.signalAmplitude} confidence=${state.confidence} reason=$reason"
        )
    }

    private fun logCalibrationEvents(state: FitnessRepCounterState) {
        if (state.candidateFamily.isNotBlank() && state.candidatePendingCount > 0) {
            val key = "${state.candidateFamily}|${state.candidatePendingCount}|${state.displayRepCount}|${state.officialRepCount}"
            if (key != lastCandidateSeenKey) {
                lastCandidateSeenKey = key
                Log.d(
                    FITNESS_REP_COUNTER_LOG_TAG,
                    "rep_counter:rep_candidate_seen family=${state.candidateFamily} pending=${state.candidatePendingCount} display=${state.displayRepCount} official=${state.officialRepCount} phase=${state.calibrationPhase.ifBlank { "-" }} primary=${state.primarySignalId.ifBlank { "-" }} confidence=${state.confidence}"
                )
            }
        }

        if (state.lockedFamily.isNotBlank() && state.lockedFamily != lastLockedFamily) {
            lastLockedFamily = state.lockedFamily
            Log.d(
                FITNESS_REP_COUNTER_LOG_TAG,
                "rep_counter:rep_family_locked family=${state.lockedFamily} display=${state.displayRepCount} official=${state.officialRepCount} pending=${state.candidatePendingCount} dynamicLow=${state.dynamicLowThreshold} dynamicHigh=${state.dynamicHighThreshold} dynamicMinAmp=${state.dynamicMinAmplitude} dynamicPeriod=${state.dynamicPeriodMinMs}-${state.dynamicPeriodMaxMs}"
            )
        }

        if (state.filteredFamily.isNotBlank()) {
            val key = "${state.filteredFamily}|${state.filterReason}|${state.displayRepCount}|${state.officialRepCount}"
            if (key != lastFilteredLogKey) {
                lastFilteredLogKey = key
                Log.d(
                    FITNESS_REP_COUNTER_LOG_TAG,
                    "rep_counter:rep_candidate_filtered family=${state.filteredFamily} activeFamily=${state.candidateFamily.ifBlank { state.lockedFamily.ifBlank { "-" } }} reason=${state.filterReason.ifBlank { "-" }} display=${state.displayRepCount} official=${state.officialRepCount}"
                )
            }
        }

        if (state.lockedFamily.isNotBlank()) {
            val key = "${state.lockedFamily}|${state.dynamicLowThreshold}|${state.dynamicHighThreshold}|${state.dynamicMinAmplitude}|${state.dynamicPeriodMinMs}|${state.dynamicPeriodMaxMs}|${state.officialRepCount}"
            if (key != lastWindowLogKey) {
                lastWindowLogKey = key
                Log.d(
                    FITNESS_REP_COUNTER_LOG_TAG,
                    "rep_counter:rep_window_updated family=${state.lockedFamily} official=${state.officialRepCount} dynamicLow=${state.dynamicLowThreshold} dynamicHigh=${state.dynamicHighThreshold} dynamicMinAmp=${state.dynamicMinAmplitude} dynamicPeriod=${state.dynamicPeriodMinMs}-${state.dynamicPeriodMaxMs}"
                )
            }
        }
    }

    private fun supportsCycleCounting(exercise: FitnessWorkoutExerciseEntity?): Boolean {
        if (exercise == null || exercise.reps.isBlank() || exercise.durationSeconds > 0) return false
        val repsText = exercise.reps.lowercase()
        val durationLikeReps = listOf("sec", "second", "seconds", "min", "minute", "minutes").any {
            repsText.contains(it)
        }
        if (durationLikeReps) return false
        val text = listOf(exercise.category, exercise.equipment, exercise.name).joinToString(" ")
        val staticWords = listOf("plank", "hold", "stretch", "static", "mobility", "recovery")
        return staticWords.none { text.contains(it, ignoreCase = true) }
    }

    private data class CounterContext(
        val profileId: String,
        val planId: Long,
        val exerciseId: Long,
        val sessionId: String,
        val exerciseIntervalId: String
    )

    private companion object {
        private const val PROCESS_INTERVAL_MS = 100L
        private const val SAMPLE_LOG_INTERVAL_MS = 1_000L
        private const val VISIBILITY_THRESHOLD = 0.55f
    }
}

package com.example.watcher.data.repository

import com.example.watcher.data.local.pose.NormalizedLandmark
import com.example.watcher.data.local.pose.OneEuroFilter
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.model.FitnessRepCounterPhase
import com.example.watcher.data.model.FitnessRepCounterState
import com.example.watcher.data.model.FitnessRepCounterStatus
import com.example.watcher.data.model.FitnessRepDominantAxis
import com.example.watcher.data.model.FitnessRepQuality
import com.example.watcher.data.model.FitnessRepQualityLabel
import com.google.gson.Gson
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class VisualCycleCounter(
    private val gson: Gson = Gson()
) {
    private val histories = mutableMapOf<String, ArrayDeque<SignalSample>>()
    private val coordinateFilters = Array(LANDMARK_FILTER_COUNT * 3) {
        OneEuroFilter(minCutoff = 1.2, beta = 0.012, dCutoff = 1.0)
    }
    private val pendingCycles = ArrayDeque<CandidateCycle>()
    private val alternateCycles = ArrayDeque<CandidateCycle>()
    private val lockedCycles = ArrayDeque<CandidateCycle>()

    private var selectedSignalId = ""
    private var selectedMisses = 0
    private var selectedLockedAtElapsedMs = 0L
    private var thresholdLow = 0f
    private var thresholdHigh = 0f
    private var startZone: ThresholdZone? = null
    private var waitingForRearmZone: ThresholdZone? = null
    private var repStartElapsedMs = 0L
    private var lastCycleEndElapsedMs = 0L
    private var lastOfficialRepElapsedMs = 0L
    private var displayRepCount = 0
    private var officialRepCount = 0
    private var phase = FitnessRepCounterPhase.WaitingBaseline
    private var calibrationPhase = CALIBRATION_EXPLORING
    private var lastRepQuality: FitnessRepQuality? = null
    private var lastConfirmedQualities = emptyList<FitnessRepQuality>()
    private var lastRejectionReason = ""
    private var lastFilteredFamily = ""
    private var lastFilterReason = ""
    private var unreliableStartedElapsedMs = 0L
    private var bodyFrameState: BodyFrame? = null
    private var candidateFamily = ""
    private var alternateFamily = ""
    private var lockedFamily = ""
    private var dynamicWindow = DynamicWindow()

    fun reset() {
        histories.clear()
        coordinateFilters.forEach { it.reset() }
        pendingCycles.clear()
        alternateCycles.clear()
        lockedCycles.clear()
        selectedSignalId = ""
        selectedMisses = 0
        selectedLockedAtElapsedMs = 0L
        thresholdLow = 0f
        thresholdHigh = 0f
        startZone = null
        waitingForRearmZone = null
        repStartElapsedMs = 0L
        lastCycleEndElapsedMs = 0L
        lastOfficialRepElapsedMs = 0L
        displayRepCount = 0
        officialRepCount = 0
        phase = FitnessRepCounterPhase.WaitingBaseline
        calibrationPhase = CALIBRATION_EXPLORING
        lastRepQuality = null
        lastConfirmedQualities = emptyList()
        lastRejectionReason = ""
        lastFilteredFamily = ""
        lastFilterReason = ""
        unreliableStartedElapsedMs = 0L
        bodyFrameState = null
        candidateFamily = ""
        alternateFamily = ""
        lockedFamily = ""
        dynamicWindow = DynamicWindow()
    }

    fun update(result: PoseDetectionResult, elapsedMs: Long): FitnessRepCounterState {
        lastConfirmedQualities = emptyList()
        lastFilteredFamily = ""
        lastFilterReason = ""

        val pose = result.landmarks.firstOrNull()
        val rawLandmarks = pose?.normalizedLandmarks
        if (rawLandmarks.isNullOrEmpty()) {
            return unreliableState(elapsedMs, "no pose detected", emptyList())
        }
        if (result.landmarks.size > 1) {
            return unreliableState(elapsedMs, "multiple poses detected", emptyList())
        }

        val landmarks = smoothLandmarks(rawLandmarks, elapsedMs)
        val visibleCore = TRACKED_LANDMARKS.mapNotNull { tracked ->
            landmarks.getOrNull(tracked.index)
                ?.takeIf { it.visibility >= VISIBILITY_THRESHOLD }
                ?.let { tracked to it }
        }
        val angleCandidates = buildAngleCandidates(landmarks)
        val canUseAngleOnly = angleCandidates.any(::canUseAngleOnlyCandidate)
        if (visibleCore.size < MIN_VISIBLE_LANDMARKS && !canUseAngleOnly) {
            return unreliableState(
                elapsedMs = elapsedMs,
                reason = "not enough visible landmarks",
                activeLandmarks = visibleCore.map { it.first.label }
            )
        }

        val bodyBox = visibleCore
            .takeIf { it.size >= MIN_VISIBLE_LANDMARKS }
            ?.let { bodyBox(it.map { tracked -> tracked.second }) }
        val stableBodyFrame = if (bodyBox != null) {
            val rawBodyFrame = bodyFrame(landmarks, visibleCore, bodyBox)
            val smoothed = smoothBodyFrame(rawBodyFrame)
            if (smoothed == null && !canUseAngleOnly) {
                return unreliableState(elapsedMs, "body scale jump", visibleCore.map { it.first.label })
            }
            if (smoothed == null) {
                bodyFrameState = null
                lastFilterReason = "angle-only: body scale jump"
            }
            smoothed
        } else {
            lastFilterReason = "angle-only: not enough visible landmarks"
            null
        }
        unreliableStartedElapsedMs = 0L
        releaseStaleLockedFamily(elapsedMs)

        val candidates = if (bodyBox != null && stableBodyFrame != null) {
            buildCandidates(landmarks, visibleCore, bodyBox, stableBodyFrame, angleCandidates)
        } else {
            angleCandidates
        }
        candidates.forEach { candidate ->
            val history = histories.getOrPut(candidate.id) { ArrayDeque() }
            history.addLast(
                SignalSample(
                    elapsedMs = elapsedMs,
                    value = candidate.value,
                    visibilityScore = candidate.visibilityScore
                )
            )
        }
        prune(elapsedMs)

        val stats = candidates.mapNotNull { candidate ->
            val history = histories[candidate.id]?.toList().orEmpty()
            candidateStats(candidate, history)
        }
        val primaryStats = stats.filter { it.candidate.type == SignalType.StrongAngle }
        val mediumStats = stats.filter { it.candidate.type == SignalType.MediumRelative }
        val best = bestSelectableSignal(primaryStats)
        val selected = selectSignal(best, primaryStats, elapsedMs)
        if (selected == null) {
            phase = FitnessRepCounterPhase.WaitingBaseline
            return state(
                status = FitnessRepCounterStatus.Ready,
                reason = "waiting for stable angle cycle",
                confidence = best?.score ?: 0f,
                activeLandmarks = best?.candidate?.activeLandmarks.orEmpty(),
                axis = best?.candidate?.axis ?: FitnessRepDominantAxis.Unknown,
                stats = best,
                confirmation = null,
                candidateCount = candidates.size
            )
        }

        val confirmation = confirmationFor(selected, mediumStats)
        return updateCycle(
            stats = selected,
            confirmation = confirmation,
            sideConflict = hasConflictingCounterpart(selected, primaryStats),
            elapsedMs = elapsedMs,
            candidateCount = candidates.size
        )
    }

    private fun unreliableState(
        elapsedMs: Long,
        reason: String,
        activeLandmarks: List<String>
    ): FitnessRepCounterState {
        if (unreliableStartedElapsedMs == 0L) unreliableStartedElapsedMs = elapsedMs
        val freezeElapsedMs = elapsedMs - unreliableStartedElapsedMs
        val shouldReset = selectedSignalId.isBlank() || freezeElapsedMs > UNRELIABLE_RESET_MS
        if (shouldReset) {
            resetActiveCycle(clearSignal = true)
            coordinateFilters.forEach { it.reset() }
            bodyFrameState = null
            if (freezeElapsedMs > UNRELIABLE_RELEASE_MS) {
                resetCalibration()
            }
        } else {
            phase = FitnessRepCounterPhase.Unreliable
            lastRejectionReason = "freeze: $reason"
        }
        calibrationPhase = if (shouldReset && lockedFamily.isBlank()) CALIBRATION_UNRELIABLE else calibrationPhase
        return state(
            status = FitnessRepCounterStatus.Unreliable,
            reason = if (shouldReset) reason else "freeze: $reason",
            confidence = 0f,
            activeLandmarks = activeLandmarks,
            axis = FitnessRepDominantAxis.Unknown,
            stats = null,
            confirmation = null,
            candidateCount = 0
        )
    }

    private fun selectSignal(best: SignalStats?, allPrimaryStats: List<SignalStats>, elapsedMs: Long): SignalStats? {
        val current = allPrimaryStats.firstOrNull { it.candidate.id == selectedSignalId }
        val currentUsable = current != null &&
            current.score >= KEEP_SIGNAL_SCORE &&
            current.amplitude >= MIN_ANGLE_ROM_DEGREES * 0.75f &&
            familyAllowed(signalFamily(current.candidate.id))

        if (selectedSignalId.isNotBlank() && currentUsable) {
            selectedMisses = 0
            val bestFamily = best?.candidate?.id?.let(::signalFamily).orEmpty()
            val currentFamily = signalFamily(current.candidate.id)
            val switchMargin = if (bestFamily != currentFamily) ALTERNATE_OBSERVE_MARGIN else SWITCH_SCORE_MARGIN
            val canObserveAlternate = lockedFamily.isBlank() &&
                candidateFamily.isNotBlank() &&
                bestFamily.isNotBlank() &&
                bestFamily != candidateFamily
            if (
                best != null &&
                best.candidate.id != selectedSignalId &&
                best.score > current.score + switchMargin &&
                (canSwitchSignalFamily(current.candidate.id, best.candidate.id) || canObserveAlternate) &&
                phase in SWITCHABLE_PHASES
            ) {
                lockSignal(best, elapsedMs)
                return best
            }
            refreshThresholds(current)
            return current
        }

        if (selectedSignalId.isNotBlank()) {
            selectedMisses += 1
            if (current != null && selectedMisses < SIGNAL_MISS_LIMIT) {
                refreshThresholds(current)
                return current
            }
            resetActiveCycle(clearSignal = true)
        }

        val lockCandidate = candidateForFamilyLock(best, allPrimaryStats)
        if (lockCandidate == null || !isLockable(lockCandidate)) return null
        lockSignal(lockCandidate, elapsedMs)
        return lockCandidate
    }

    private fun bestSelectableSignal(primaryStats: List<SignalStats>): SignalStats? {
        val scoped = if (lockedFamily.isBlank()) primaryStats else primaryStats.filter { signalFamily(it.candidate.id) == lockedFamily }
        return scoped.maxWithOrNull(
            compareBy<SignalStats> { it.score }
                .thenBy { it.amplitudeScore }
                .thenBy { it.visibilityScore }
        )
    }

    private fun candidateForFamilyLock(best: SignalStats?, allPrimaryStats: List<SignalStats>): SignalStats? {
        if (lockedFamily.isNotBlank()) {
            return bestForFamily(allPrimaryStats, lockedFamily)
        }
        if (candidateFamily.isNotBlank()) {
            val candidateBest = bestForFamily(allPrimaryStats, candidateFamily)
            if (candidateBest != null && isLockable(candidateBest)) {
                val bestFamily = best?.candidate?.id?.let(::signalFamily).orEmpty()
                val canObserveAlternate = best != null &&
                    bestFamily.isNotBlank() &&
                    bestFamily != candidateFamily &&
                    best.score > candidateBest.score + ALTERNATE_OBSERVE_MARGIN
                if (!canObserveAlternate) return candidateBest
            }
        }
        return best
    }

    private fun bestForFamily(allPrimaryStats: List<SignalStats>, family: String): SignalStats? {
        return allPrimaryStats
            .filter { signalFamily(it.candidate.id) == family }
            .maxWithOrNull(
                compareBy<SignalStats> { it.score }
                    .thenBy { it.amplitudeScore }
                    .thenBy { it.visibilityScore }
            )
    }

    private fun isLockable(stats: SignalStats): Boolean {
        return stats.score >= LOCK_SIGNAL_SCORE &&
            stats.amplitude >= MIN_ANGLE_ROM_DEGREES &&
            stats.reversalCount >= MIN_LOCK_REVERSALS
    }

    private fun familyAllowed(family: String): Boolean {
        return when {
            family.isBlank() -> false
            lockedFamily.isNotBlank() -> family == lockedFamily
            else -> true
        }
    }

    private fun canSwitchSignalFamily(currentId: String, nextId: String): Boolean {
        val currentFamily = signalFamily(currentId)
        val nextFamily = signalFamily(nextId)
        if (currentFamily.isBlank() || nextFamily.isBlank()) return false
        return currentFamily == nextFamily
    }

    private fun signalFamily(signalId: String): String {
        return when {
            signalId.contains("_elbow_angle") -> "elbow"
            signalId.contains("_knee_angle") -> "knee"
            signalId.contains("_hip_angle") -> "hip"
            signalId.contains("_shoulder_angle") -> "shoulder"
            else -> ""
        }
    }

    private fun lockSignal(stats: SignalStats, elapsedMs: Long) {
        selectedSignalId = stats.candidate.id
        selectedMisses = 0
        selectedLockedAtElapsedMs = elapsedMs
        thresholdLow = stats.lowThreshold
        thresholdHigh = stats.highThreshold
        startZone = null
        waitingForRearmZone = null
        repStartElapsedMs = 0L
        lastRejectionReason = ""
        phase = FitnessRepCounterPhase.WaitingBaseline
    }

    private fun refreshThresholds(stats: SignalStats) {
        thresholdLow = stats.lowThreshold
        thresholdHigh = stats.highThreshold
    }

    private fun resetActiveCycle(clearSignal: Boolean) {
        if (clearSignal) {
            selectedSignalId = ""
            selectedMisses = 0
            selectedLockedAtElapsedMs = 0L
        }
        thresholdLow = 0f
        thresholdHigh = 0f
        startZone = null
        waitingForRearmZone = null
        repStartElapsedMs = 0L
        phase = FitnessRepCounterPhase.Unreliable
    }

    private fun resetCalibration() {
        pendingCycles.clear()
        alternateCycles.clear()
        lockedCycles.clear()
        candidateFamily = ""
        alternateFamily = ""
        lockedFamily = ""
        dynamicWindow = DynamicWindow()
        displayRepCount = officialRepCount
        calibrationPhase = CALIBRATION_EXPLORING
    }

    private fun updateCycle(
        stats: SignalStats,
        confirmation: SignalStats?,
        sideConflict: Boolean,
        elapsedMs: Long,
        candidateCount: Int
    ): FitnessRepCounterState {
        val signal = stats.latestValue
        val zone = zoneFor(signal, stats.lowThreshold, stats.highThreshold)
        val repCooldownPassed = elapsedMs - lastCycleEndElapsedMs >= REP_COOLDOWN_MS
        val confirmationOk = confirmationOk(stats, confirmation)
        lastRejectionReason = ""

        when (phase) {
            FitnessRepCounterPhase.WaitingBaseline,
            FitnessRepCounterPhase.RepCompleted,
            FitnessRepCounterPhase.Unreliable -> {
                if (waitingForRearmZone != null) {
                    if (zone == waitingForRearmZone) {
                        startZone = zone
                        waitingForRearmZone = null
                        repStartElapsedMs = elapsedMs
                        phase = FitnessRepCounterPhase.MovingAway
                    } else {
                        phase = FitnessRepCounterPhase.RepCompleted
                    }
                } else if (zone != ThresholdZone.Middle && repCooldownPassed) {
                    startZone = zone
                    repStartElapsedMs = elapsedMs
                    phase = FitnessRepCounterPhase.MovingAway
                } else {
                    phase = FitnessRepCounterPhase.WaitingBaseline
                }
            }
            FitnessRepCounterPhase.MovingAway,
            FitnessRepCounterPhase.AtExtreme,
            FitnessRepCounterPhase.Returning -> {
                val start = startZone
                val opposite = start?.opposite()
                val duration = elapsedMs - repStartElapsedMs
                phase = when {
                    zone == ThresholdZone.Middle -> FitnessRepCounterPhase.Returning
                    opposite != null && zone == opposite -> FitnessRepCounterPhase.AtExtreme
                    else -> FitnessRepCounterPhase.MovingAway
                }
                if (duration > MAX_REP_DURATION_MS) {
                    lastRejectionReason = "rep timeout"
                    startZone = null
                    waitingForRearmZone = null
                    repStartElapsedMs = 0L
                    phase = FitnessRepCounterPhase.WaitingBaseline
                } else if (opposite != null && zone == opposite) {
                    val baseQuality = buildQuality(
                        repIndex = displayRepCount + 1,
                        startElapsedMs = repStartElapsedMs,
                        endElapsedMs = elapsedMs,
                        stats = stats,
                        confirmation = confirmation,
                        confirmationOk = confirmationOk,
                        candidateCount = candidateCount
                    )
                    val cycle = CandidateCycle(
                        family = signalFamily(stats.candidate.id),
                        signalId = stats.candidate.id,
                        signalLabel = stats.candidate.label,
                        startElapsedMs = repStartElapsedMs,
                        endElapsedMs = elapsedMs,
                        durationMs = duration,
                        lowAngle = stats.lowThreshold,
                        highAngle = stats.highThreshold,
                        amplitude = stats.amplitude,
                        score = stats.score,
                        confidence = baseQuality.confidence,
                        visibilityScore = stats.visibilityScore,
                        smoothnessScore = stats.smoothnessScore,
                        periodStabilityScore = stats.periodStabilityScore,
                        activeLandmarks = stats.candidate.activeLandmarks,
                        axis = stats.candidate.axis,
                        quality = baseQuality
                    )
                    val rejection = rejectionReason(
                        cycle = cycle,
                        stats = stats,
                        durationMs = duration,
                        repCooldownPassed = repCooldownPassed,
                        confirmationOk = confirmationOk,
                        sideConflict = sideConflict
                    )
                    if (rejection == null) {
                        acceptCandidateCycle(cycle)
                        lastCycleEndElapsedMs = elapsedMs
                    } else {
                        lastRejectionReason = rejection
                    }
                    waitingForRearmZone = start
                    startZone = null
                    phase = FitnessRepCounterPhase.RepCompleted
                }
            }
        }

        val confidence = confidenceFor(stats)
        val reason = when {
            lastRejectionReason.isNotBlank() -> lastRejectionReason
            stats.score < LOCK_SIGNAL_SCORE -> "angle cycle is unstable"
            stats.amplitude < MIN_ANGLE_ROM_DEGREES -> "angle range is too small"
            confidence < MIN_REP_CONFIDENCE && phase != FitnessRepCounterPhase.WaitingBaseline -> "angle cycle below counting threshold"
            else -> ""
        }
        val status = if (phase == FitnessRepCounterPhase.WaitingBaseline || phase == FitnessRepCounterPhase.RepCompleted) {
            FitnessRepCounterStatus.Ready
        } else {
            FitnessRepCounterStatus.Counting
        }
        return state(
            status = status,
            reason = reason,
            confidence = confidence,
            activeLandmarks = stats.candidate.activeLandmarks,
            axis = stats.candidate.axis,
            stats = stats,
            confirmation = confirmation,
            candidateCount = candidateCount
        )
    }

    private fun acceptCandidateCycle(cycle: CandidateCycle) {
        if (cycle.family.isBlank()) {
            lastRejectionReason = "rep rejected: unknown angle family"
            return
        }
        if (lockedFamily.isNotBlank()) {
            acceptLockedCycle(cycle)
            return
        }
        if (candidateFamily.isBlank()) {
            candidateFamily = cycle.family
            pendingCycles.clear()
            alternateCycles.clear()
            alternateFamily = ""
        }
        if (cycle.family == candidateFamily) {
            appendPendingCycle(cycle)
            alternateCycles.clear()
            alternateFamily = ""
            updateCandidateFeedback()
            if (pendingCycles.size >= FAMILY_LOCK_REPS) {
                lockPendingFamily()
            }
            return
        }
        handleOffFamilyCycle(cycle)
    }

    private fun appendPendingCycle(cycle: CandidateCycle) {
        pendingCycles.addLast(cycle)
        while (pendingCycles.size > FAMILY_LOCK_REPS) pendingCycles.removeFirst()
    }

    private fun updateCandidateFeedback() {
        displayRepCount = officialRepCount
        calibrationPhase = when {
            pendingCycles.size >= FAMILY_LOCK_REPS -> CALIBRATION_LOCKED
            pendingCycles.size >= CANDIDATE_CONFIRMING_REPS -> CALIBRATION_CONFIRMING
            pendingCycles.size >= CANDIDATE_FEEDBACK_REPS -> CALIBRATION_ACTIVE
            else -> CALIBRATION_EXPLORING
        }
    }

    private fun handleOffFamilyCycle(cycle: CandidateCycle) {
        lastFilteredFamily = cycle.family
        lastFilterReason = "off-family candidate filtered"
        if (alternateFamily != cycle.family) {
            alternateFamily = cycle.family
            alternateCycles.clear()
        }
        alternateCycles.addLast(cycle)
        while (alternateCycles.size > FAMILY_LOCK_REPS) alternateCycles.removeFirst()
        if (alternateCycles.size >= FAMILY_LOCK_REPS && alternateWindowBeatsCurrent()) {
            candidateFamily = alternateFamily
            pendingCycles.clear()
            pendingCycles.addAll(alternateCycles)
            alternateCycles.clear()
            alternateFamily = ""
            updateCandidateFeedback()
            lockPendingFamily()
        }
    }

    private fun alternateWindowBeatsCurrent(): Boolean {
        if (alternateCycles.size < FAMILY_LOCK_REPS) return false
        if (pendingCycles.size < CANDIDATE_FEEDBACK_REPS) return true
        val alternateScore = alternateCycles.map { it.confidence }.average().toFloat()
        val currentScore = pendingCycles.map { it.confidence }.average().toFloat()
        return alternateScore > currentScore + ALTERNATE_TAKEOVER_MARGIN
    }

    private fun lockPendingFamily() {
        lockedFamily = candidateFamily
        calibrationPhase = CALIBRATION_LOCKED
        dynamicWindow = buildDynamicWindow(pendingCycles.toList())
        officialRepCount += pendingCycles.size
        displayRepCount = officialRepCount
        val confirmed = pendingCycles.mapIndexed { index, cycle ->
            qualityForCycle(cycle, officialRepCount - pendingCycles.size + index + 1)
        }
        lastConfirmedQualities = confirmed
        lastRepQuality = confirmed.lastOrNull()
        lastOfficialRepElapsedMs = pendingCycles.lastOrNull()?.endElapsedMs ?: lastOfficialRepElapsedMs
        lockedCycles.clear()
        pendingCycles.forEach { lockedCycles.addLast(it) }
        trimLockedCycles()
    }

    private fun acceptLockedCycle(cycle: CandidateCycle) {
        if (cycle.family != lockedFamily) {
            lastFilteredFamily = cycle.family
            lastFilterReason = "locked-family mismatch"
            return
        }
        if (!dynamicWindowAccepts(cycle)) {
            lastRejectionReason = "rep rejected: outside dynamic window"
            return
        }
        officialRepCount += 1
        displayRepCount = officialRepCount
        val quality = qualityForCycle(cycle, officialRepCount)
        lastConfirmedQualities = listOf(quality)
        lastRepQuality = quality
        lastOfficialRepElapsedMs = cycle.endElapsedMs
        lockedCycles.addLast(cycle)
        trimLockedCycles()
        dynamicWindow = buildDynamicWindow(lockedCycles.toList())
        calibrationPhase = CALIBRATION_LOCKED
    }

    private fun qualityForCycle(cycle: CandidateCycle, repIndex: Int): FitnessRepQuality {
        val raw = mapOf(
            "family" to cycle.family,
            "signalId" to cycle.signalId,
            "signalLabel" to cycle.signalLabel,
            "lowAngle" to cycle.lowAngle,
            "highAngle" to cycle.highAngle,
            "amplitude" to cycle.amplitude,
            "durationMs" to cycle.durationMs,
            "score" to cycle.score,
            "confidence" to cycle.confidence,
            "lockedFamily" to lockedFamily,
            "candidateFamily" to candidateFamily,
            "candidatePendingCount" to pendingCycles.size,
            "dynamicLowThreshold" to dynamicWindow.lowThreshold,
            "dynamicHighThreshold" to dynamicWindow.highThreshold,
            "dynamicMinAmplitude" to dynamicWindow.minAmplitude,
            "dynamicPeriodMinMs" to dynamicWindow.periodMinMs,
            "dynamicPeriodMaxMs" to dynamicWindow.periodMaxMs
        )
        return cycle.quality.copy(
            repIndex = repIndex,
            rawSignalsJson = gson.toJson(raw)
        )
    }

    private fun trimLockedCycles() {
        while (lockedCycles.size > DYNAMIC_WINDOW_CYCLE_LIMIT) lockedCycles.removeFirst()
    }

    private fun buildDynamicWindow(cycles: List<CandidateCycle>): DynamicWindow {
        if (cycles.isEmpty()) return DynamicWindow()
        val low = median(cycles.map { it.lowAngle })
        val high = median(cycles.map { it.highAngle })
        val amplitude = median(cycles.map { it.amplitude })
        val period = median(cycles.map { it.durationMs.toFloat() }).toLong()
        val minPeriod = (period * 0.55f).toLong().coerceAtLeast(MIN_REP_DURATION_MS)
        val maxPeriod = (period * 1.85f).toLong().coerceAtMost(MAX_REP_DURATION_MS)
        val visibility = median(cycles.map { it.visibilityScore })
        val noise = median(cycles.map { (1f - it.smoothnessScore).coerceIn(0f, 1f) })
        return DynamicWindow(
            lowThreshold = low,
            highThreshold = high,
            minAmplitude = max(MIN_ANGLE_ROM_DEGREES, amplitude * 0.55f),
            periodMinMs = minPeriod,
            periodMaxMs = maxPeriod,
            minVisibility = (visibility * 0.72f).coerceIn(0.35f, VISIBILITY_THRESHOLD),
            noiseFloor = noise
        )
    }

    private fun dynamicWindowAccepts(cycle: CandidateCycle): Boolean {
        if (dynamicWindow.periodMinMs == 0L || dynamicWindow.periodMaxMs == 0L) return true
        return cycle.amplitude >= dynamicWindow.minAmplitude &&
            cycle.durationMs in dynamicWindow.periodMinMs..dynamicWindow.periodMaxMs &&
            cycle.visibilityScore >= dynamicWindow.minVisibility &&
            cycle.smoothnessScore >= (MIN_REP_SMOOTHNESS - dynamicWindow.noiseFloor * 0.12f).coerceAtLeast(0.25f)
    }

    private fun releaseStaleLockedFamily(elapsedMs: Long) {
        if (lockedFamily.isBlank()) return
        if (lastOfficialRepElapsedMs == 0L) return
        if (elapsedMs - lastOfficialRepElapsedMs < LOCKED_FAMILY_RELEASE_MS) return
        candidateFamily = ""
        alternateFamily = ""
        lockedFamily = ""
        pendingCycles.clear()
        alternateCycles.clear()
        lockedCycles.clear()
        dynamicWindow = DynamicWindow()
        selectedSignalId = ""
        calibrationPhase = CALIBRATION_EXPLORING
    }

    private fun rejectionReason(
        cycle: CandidateCycle,
        stats: SignalStats,
        durationMs: Long,
        repCooldownPassed: Boolean,
        confirmationOk: Boolean,
        sideConflict: Boolean
    ): String? {
        return when {
            sideConflict -> "rep rejected: conflicting sides"
            durationMs < MIN_REP_DURATION_MS -> "rep rejected: too fast"
            !repCooldownPassed -> "rep rejected: cooldown"
            stats.amplitude < MIN_ANGLE_ROM_DEGREES -> "rep rejected: angle range too small"
            stats.smoothnessScore < MIN_REP_SMOOTHNESS -> "rep rejected: jittery angle"
            cycle.confidence < MIN_REP_CONFIDENCE -> "rep rejected: low confidence"
            cycle.confidence < HIGH_CONFIDENCE_WITHOUT_CONFIRM && !confirmationOk -> "rep rejected: no confirming body motion"
            lockedFamily.isNotBlank() && cycle.family != lockedFamily -> "rep rejected: locked-family mismatch"
            lockedFamily.isNotBlank() && !dynamicWindowAccepts(cycle) -> "rep rejected: outside dynamic window"
            else -> null
        }
    }

    private fun hasConflictingCounterpart(primary: SignalStats, primaryStats: List<SignalStats>): Boolean {
        val counterpartId = counterpartId(primary.candidate.id) ?: return false
        val counterpart = primaryStats.firstOrNull { it.candidate.id == counterpartId } ?: return false
        if (counterpart.score < KEEP_SIGNAL_SCORE || counterpart.amplitude < MIN_ANGLE_ROM_DEGREES) return false
        val primaryValues = histories[primary.candidate.id]?.map { it.value }.orEmpty()
        val counterpartValues = histories[counterpartId]?.map { it.value }.orEmpty()
        val count = minOf(primaryValues.size, counterpartValues.size)
        if (count < MIN_HISTORY_SAMPLES) return false
        val left = primaryValues.takeLast(count)
        val right = counterpartValues.takeLast(count)
        return correlation(left, right) < SIDE_CONFLICT_CORRELATION
    }

    private fun counterpartId(id: String): String? {
        return when {
            id.startsWith("left_") -> id.replaceFirst("left_", "right_")
            id.startsWith("right_") -> id.replaceFirst("right_", "left_")
            else -> null
        }
    }

    private fun correlation(left: List<Float>, right: List<Float>): Float {
        if (left.size != right.size || left.size < 2) return 0f
        val leftAvg = left.average().toFloat()
        val rightAvg = right.average().toFloat()
        var numerator = 0f
        var leftDenominator = 0f
        var rightDenominator = 0f
        left.indices.forEach { index ->
            val l = left[index] - leftAvg
            val r = right[index] - rightAvg
            numerator += l * r
            leftDenominator += l * l
            rightDenominator += r * r
        }
        val denominator = sqrt(leftDenominator * rightDenominator).coerceAtLeast(0.0001f)
        return (numerator / denominator).coerceIn(-1f, 1f)
    }

    private fun confirmationFor(primary: SignalStats, mediumStats: List<SignalStats>): SignalStats? {
        return mediumStats
            .filter { it.score >= MEDIUM_CONFIRM_SCORE && it.amplitude >= it.minAmplitude }
            .maxWithOrNull(
                compareBy<SignalStats> { confirmationCompatibility(primary, it) }
                    .thenBy { it.score }
            )
    }

    private fun confirmationOk(primary: SignalStats, confirmation: SignalStats?): Boolean {
        if (primary.score >= HIGH_CONFIDENCE_WITHOUT_CONFIRM && primary.amplitude >= MIN_ANGLE_ROM_DEGREES * 1.4f) {
            return true
        }
        if (confirmation == null) return false
        return confirmationCompatibility(primary, confirmation) >= 0.45f
    }

    private fun confirmationCompatibility(primary: SignalStats, confirmation: SignalStats): Float {
        val primaryPeriod = primary.estimatedPeriodMs.takeIf { it > 0L } ?: return confirmation.score
        val confirmPeriod = confirmation.estimatedPeriodMs.takeIf { it > 0L } ?: return confirmation.score * 0.75f
        val diff = abs(primaryPeriod - confirmPeriod).toFloat()
        val periodScore = (1f - diff / primaryPeriod.coerceAtLeast(1L)).coerceIn(0f, 1f)
        return (confirmation.score * 0.55f + periodScore * 0.45f).coerceIn(0f, 1f)
    }

    private fun buildQuality(
        repIndex: Int,
        startElapsedMs: Long,
        endElapsedMs: Long,
        stats: SignalStats,
        confirmation: SignalStats?,
        confirmationOk: Boolean,
        candidateCount: Int
    ): FitnessRepQuality {
        val confidence = confidenceFor(stats)
        val label = when {
            stats.visibilityScore < VISIBILITY_THRESHOLD -> FitnessRepQualityLabel.LowVisibility
            confidence >= 0.75f -> FitnessRepQualityLabel.Good
            confidence >= 0.55f -> FitnessRepQualityLabel.Partial
            else -> FitnessRepQualityLabel.Unstable
        }
        val raw = mapOf(
            "signalId" to stats.candidate.id,
            "signalLabel" to stats.candidate.label,
            "signalType" to stats.candidate.type.name,
            "family" to signalFamily(stats.candidate.id),
            "angleDeg" to stats.latestValue,
            "angleRangeDeg" to stats.amplitude,
            "lowThreshold" to stats.lowThreshold,
            "highThreshold" to stats.highThreshold,
            "signalScore" to stats.score,
            "amplitudeScore" to stats.amplitudeScore,
            "smoothnessScore" to stats.smoothnessScore,
            "visibilityScore" to stats.visibilityScore,
            "periodStabilityScore" to stats.periodStabilityScore,
            "estimatedPeriodMs" to stats.estimatedPeriodMs,
            "candidateCount" to candidateCount,
            "confirmationSignalId" to confirmation?.candidate?.id.orEmpty(),
            "confirmationOk" to confirmationOk,
            "sampleCount" to stats.sampleCount
        )
        return FitnessRepQuality(
            repIndex = repIndex,
            startElapsedMs = startElapsedMs,
            endElapsedMs = endElapsedMs,
            durationMs = endElapsedMs - startElapsedMs,
            activeLandmarks = stats.candidate.activeLandmarks,
            dominantAxis = stats.candidate.axis,
            signalId = stats.candidate.id,
            signalLabel = stats.candidate.label,
            rangeScore = stats.amplitudeScore,
            smoothnessScore = stats.smoothnessScore,
            visibilityScore = stats.visibilityScore,
            symmetryScore = 0.5f,
            periodStabilityScore = stats.periodStabilityScore,
            confidence = confidence,
            qualityLabel = label,
            rawSignalsJson = gson.toJson(raw)
        )
    }

    private fun candidateStats(candidate: SignalCandidate, history: List<SignalSample>): SignalStats? {
        if (history.size < MIN_HISTORY_SAMPLES) return null
        val values = history.map { it.value }
        val min = values.minOrNull() ?: return null
        val max = values.maxOrNull() ?: return null
        val amplitude = max - min
        val minAmplitude = when (candidate.type) {
            SignalType.StrongAngle -> MIN_ANGLE_ROM_DEGREES
            SignalType.MediumRelative -> max(candidate.minAmplitude, noiseFloor(values) * 3f)
        }
        val goodAmplitude = max(candidate.goodAmplitude, minAmplitude * 2.2f)
        val amplitudeScore = ((amplitude - minAmplitude) / (goodAmplitude - minAmplitude)).coerceIn(0f, 1f)
        val smoothnessScore = smoothnessScore(values, amplitude)
        val period = periodStats(history, minAmplitude * 0.22f)
        val reversalScore = when {
            period.reversalCount >= MIN_LOCK_REVERSALS -> 1f
            period.reversalCount == 1 -> 0.45f
            else -> 0.15f
        }
        val visibilityScore = history.map { it.visibilityScore }.average().toFloat().coerceIn(0f, 1f)
        val typeWeight = if (candidate.type == SignalType.StrongAngle) 1f else 0.72f
        val score = (
            amplitudeScore * 0.38f +
                visibilityScore * 0.22f +
                smoothnessScore * 0.18f +
                period.periodStabilityScore * 0.14f +
                reversalScore * 0.08f
            ).coerceIn(0f, 1f) * typeWeight
        val lowThreshold = percentile(values, LOW_THRESHOLD_PERCENTILE)
        val highThreshold = percentile(values, HIGH_THRESHOLD_PERCENTILE)
        return SignalStats(
            candidate = candidate,
            latestValue = history.last().value,
            amplitude = amplitude,
            minAmplitude = minAmplitude,
            goodAmplitude = goodAmplitude,
            lowThreshold = lowThreshold,
            highThreshold = highThreshold,
            amplitudeScore = amplitudeScore,
            smoothnessScore = smoothnessScore,
            visibilityScore = visibilityScore,
            periodStabilityScore = period.periodStabilityScore,
            estimatedPeriodMs = period.estimatedPeriodMs,
            reversalCount = period.reversalCount,
            score = score,
            sampleCount = history.size
        )
    }

    private fun confidenceFor(stats: SignalStats): Float {
        return (
            stats.amplitudeScore * 0.34f +
                stats.score * 0.30f +
                stats.visibilityScore * 0.18f +
                stats.smoothnessScore * 0.10f +
                stats.periodStabilityScore * 0.08f
            ).coerceIn(0f, 1f)
    }

    private fun state(
        status: FitnessRepCounterStatus,
        reason: String,
        confidence: Float,
        activeLandmarks: List<String>,
        axis: FitnessRepDominantAxis,
        stats: SignalStats?,
        confirmation: SignalStats?,
        candidateCount: Int
    ): FitnessRepCounterState {
        val primaryId = stats?.candidate?.id ?: selectedSignalId
        val primaryFamily = signalFamily(primaryId)
        val candidateCountForState = pendingCycles.size
        return FitnessRepCounterState(
            active = true,
            status = status,
            repCount = officialRepCount,
            phase = phase,
            activeLandmarks = activeLandmarks,
            dominantAxis = axis,
            confidence = confidence,
            lastRepQuality = lastRepQuality,
            notCountingReason = reason,
            selectedSignalId = primaryId,
            selectedSignalLabel = stats?.candidate?.label.orEmpty(),
            signalScore = stats?.score ?: 0f,
            signalAmplitude = stats?.amplitude ?: 0f,
            estimatedPeriodMs = stats?.estimatedPeriodMs ?: 0L,
            candidateCount = candidateCount,
            primarySignalId = primaryId,
            primarySignalType = stats?.candidate?.type?.name.orEmpty(),
            primaryAngleDeg = stats?.latestValue ?: 0f,
            lowThreshold = stats?.lowThreshold ?: thresholdLow,
            highThreshold = stats?.highThreshold ?: thresholdHigh,
            confirmationSignalId = confirmation?.candidate?.id.orEmpty(),
            rejectionReason = lastRejectionReason,
            primarySignalFamily = primaryFamily,
            provisionalSignalFamily = candidateFamily,
            provisionalFamilyRepCount = candidateCountForState,
            preferredSignalFamily = lockedFamily,
            displayRepCount = displayRepCount,
            officialRepCount = officialRepCount,
            calibrationPhase = calibrationPhase,
            candidateFamily = candidateFamily,
            candidatePendingCount = candidateCountForState,
            lockedFamily = lockedFamily,
            dynamicLowThreshold = dynamicWindow.lowThreshold,
            dynamicHighThreshold = dynamicWindow.highThreshold,
            dynamicMinAmplitude = dynamicWindow.minAmplitude,
            dynamicPeriodMinMs = dynamicWindow.periodMinMs,
            dynamicPeriodMaxMs = dynamicWindow.periodMaxMs,
            filteredFamily = lastFilteredFamily,
            filterReason = lastFilterReason,
            confirmedRepQualities = lastConfirmedQualities
        )
    }

    private fun buildCandidates(
        landmarks: List<NormalizedLandmark>,
        visibleCore: List<Pair<TrackedLandmark, NormalizedLandmark>>,
        bodyBox: BodyBox,
        bodyFrame: BodyFrame,
        angleCandidates: List<SignalCandidate>
    ): List<SignalCandidate> {
        val candidates = angleCandidates.toMutableList()

        addPairDifferenceCandidate(candidates, landmarks, bodyFrame, 15, 16, "wrist_pair_y_delta", "wrist alternating vertical")
        addPairDifferenceCandidate(candidates, landmarks, bodyFrame, 13, 14, "elbow_pair_y_delta", "elbow alternating vertical")
        addPairDifferenceCandidate(candidates, landmarks, bodyFrame, 25, 26, "knee_pair_y_delta", "knee alternating vertical")
        addPairDifferenceCandidate(candidates, landmarks, bodyFrame, 27, 28, "ankle_pair_y_delta", "ankle alternating vertical")

        if (visibleCore.size >= MIN_VISIBLE_LANDMARKS) {
            val avgVisibility = visibleCore.map { it.second.visibility }.average().toFloat().coerceIn(0f, 1f)
            val boxMinAmp = COORDINATE_MIN_AMPLITUDE * 0.8f
            candidates += SignalCandidate(
                id = "body_height",
                label = "body frame height",
                value = bodyBox.height / bodyFrame.scale,
                axis = FitnessRepDominantAxis.Vertical,
                visibilityScore = avgVisibility,
                activeLandmarks = visibleCore.map { it.first.label },
                minAmplitude = boxMinAmp,
                goodAmplitude = boxMinAmp * 2.4f,
                type = SignalType.MediumRelative
            )
            centerFor(PART_UPPER, landmarks, bodyFrame)?.let { center ->
                candidates += SignalCandidate(
                    id = "upper_body_center_y",
                    label = "upper body center vertical",
                    value = center.value,
                    axis = FitnessRepDominantAxis.Vertical,
                    visibilityScore = center.visibility,
                    activeLandmarks = center.labels,
                    minAmplitude = boxMinAmp,
                    goodAmplitude = boxMinAmp * 2.4f,
                    type = SignalType.MediumRelative
                )
            }
            centerFor(PART_LOWER, landmarks, bodyFrame)?.let { center ->
                candidates += SignalCandidate(
                    id = "lower_body_center_y",
                    label = "lower body center vertical",
                    value = center.value,
                    axis = FitnessRepDominantAxis.Vertical,
                    visibilityScore = center.visibility,
                    activeLandmarks = center.labels,
                    minAmplitude = boxMinAmp,
                    goodAmplitude = boxMinAmp * 2.4f,
                    type = SignalType.MediumRelative
                )
            }
        }
        return candidates
    }

    private fun buildAngleCandidates(landmarks: List<NormalizedLandmark>): List<SignalCandidate> {
        val candidates = mutableListOf<SignalCandidate>()
        addAngleCandidate(candidates, landmarks, 11, 13, 15, "left_elbow_angle", "left elbow angle")
        addAngleCandidate(candidates, landmarks, 12, 14, 16, "right_elbow_angle", "right elbow angle")
        addAngleCandidate(candidates, landmarks, 23, 25, 27, "left_knee_angle", "left knee angle")
        addAngleCandidate(candidates, landmarks, 24, 26, 28, "right_knee_angle", "right knee angle")
        addAngleCandidate(candidates, landmarks, 11, 23, 25, "left_hip_angle", "left hip angle")
        addAngleCandidate(candidates, landmarks, 12, 24, 26, "right_hip_angle", "right hip angle")
        addAngleCandidate(candidates, landmarks, 13, 11, 23, "left_shoulder_angle", "left shoulder angle")
        addAngleCandidate(candidates, landmarks, 14, 12, 24, "right_shoulder_angle", "right shoulder angle")
        return candidates
    }

    private fun canUseAngleOnlyCandidate(candidate: SignalCandidate): Boolean {
        if (candidate.type != SignalType.StrongAngle) return false
        if (candidate.visibilityScore < ANGLE_ONLY_VISIBILITY_THRESHOLD) return false
        val family = signalFamily(candidate.id)
        val activeFamily = when {
            lockedFamily.isNotBlank() -> lockedFamily
            candidateFamily.isNotBlank() -> candidateFamily
            selectedSignalId.isNotBlank() -> signalFamily(selectedSignalId)
            else -> ""
        }
        return activeFamily.isBlank() || family == activeFamily
    }

    private fun addPairDifferenceCandidate(
        target: MutableList<SignalCandidate>,
        landmarks: List<NormalizedLandmark>,
        bodyFrame: BodyFrame,
        leftIndex: Int,
        rightIndex: Int,
        id: String,
        label: String
    ) {
        val left = landmarks.getOrNull(leftIndex)
        val right = landmarks.getOrNull(rightIndex)
        if (left == null || right == null) return
        if (left.visibility < VISIBILITY_THRESHOLD || right.visibility < VISIBILITY_THRESHOLD) return
        target += SignalCandidate(
            id = id,
            label = label,
            value = abs(relativeY(left, bodyFrame) - relativeY(right, bodyFrame)),
            axis = FitnessRepDominantAxis.Vertical,
            visibilityScore = ((left.visibility + right.visibility) * 0.5f).coerceIn(0f, 1f),
            activeLandmarks = listOfNotNull(labelForIndex(leftIndex), labelForIndex(rightIndex)),
            minAmplitude = COORDINATE_MIN_AMPLITUDE,
            goodAmplitude = COORDINATE_MIN_AMPLITUDE * 3.1f,
            type = SignalType.MediumRelative
        )
    }

    private fun addAngleCandidate(
        target: MutableList<SignalCandidate>,
        landmarks: List<NormalizedLandmark>,
        aIndex: Int,
        bIndex: Int,
        cIndex: Int,
        id: String,
        label: String
    ) {
        val a = landmarks.getOrNull(aIndex)
        val b = landmarks.getOrNull(bIndex)
        val c = landmarks.getOrNull(cIndex)
        if (a == null || b == null || c == null) return
        if (b.visibility < CORE_JOINT_VISIBILITY_THRESHOLD) return
        val validSideCount = listOf(a, c).count { it.visibility >= SIDE_JOINT_VISIBILITY_THRESHOLD }
        if (validSideCount == 0) return
        val value = angle(a, b, c)
        target += SignalCandidate(
            id = id,
            label = label,
            value = value,
            axis = FitnessRepDominantAxis.Diagonal,
            visibilityScore = listOf(a.visibility, b.visibility, c.visibility).average().toFloat().coerceIn(0f, 1f),
            activeLandmarks = listOfNotNull(labelForIndex(aIndex), labelForIndex(bIndex), labelForIndex(cIndex)),
            minAmplitude = MIN_ANGLE_ROM_DEGREES,
            goodAmplitude = GOOD_ANGLE_ROM_DEGREES,
            type = SignalType.StrongAngle
        )
    }

    private fun centerFor(
        indices: List<Int>,
        landmarks: List<NormalizedLandmark>,
        bodyFrame: BodyFrame
    ): CenterSignal? {
        val points = indices.mapNotNull { index ->
            landmarks.getOrNull(index)
                ?.takeIf { it.visibility >= VISIBILITY_THRESHOLD }
                ?.let { index to it }
        }
        if (points.size < 2) return null
        return CenterSignal(
            value = points.map { relativeY(it.second, bodyFrame) }.average().toFloat(),
            visibility = points.map { it.second.visibility }.average().toFloat(),
            labels = points.mapNotNull { labelForIndex(it.first) }
        )
    }

    private fun prune(elapsedMs: Long) {
        histories.values.forEach { history ->
            while (history.isNotEmpty() && elapsedMs - history.first().elapsedMs > HISTORY_MS) {
                history.removeFirst()
            }
        }
        histories.entries.removeAll { it.value.isEmpty() }
    }

    private fun bodyBox(points: List<NormalizedLandmark>): BodyBox {
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        return BodyBox(
            centerX = (minX + maxX) * 0.5f,
            centerY = (minY + maxY) * 0.5f,
            width = (maxX - minX).coerceAtLeast(0.001f),
            height = (maxY - minY).coerceAtLeast(0.001f)
        )
    }

    private fun bodyFrame(
        landmarks: List<NormalizedLandmark>,
        visibleCore: List<Pair<TrackedLandmark, NormalizedLandmark>>,
        bodyBox: BodyBox
    ): BodyFrame {
        val shoulderCenter = midpointIfVisible(landmarks, 11, 12)
        val hipCenter = midpointIfVisible(landmarks, 23, 24)
        val frameCenter = if (shoulderCenter != null && hipCenter != null) {
            BodyPoint(
                x = (shoulderCenter.x + hipCenter.x) * 0.5f,
                y = (shoulderCenter.y + hipCenter.y) * 0.5f
            )
        } else {
            BodyPoint(x = bodyBox.centerX, y = bodyBox.centerY)
        }
        val torsoScale = if (shoulderCenter != null && hipCenter != null) {
            hypot((shoulderCenter.x - hipCenter.x).toDouble(), (shoulderCenter.y - hipCenter.y).toDouble()).toFloat()
        } else {
            0f
        }
        val fallbackScale = max(bodyBox.height, bodyBox.width).coerceAtLeast(0.001f)
        val visibleScale = visibleCore.map { it.second }.let { points ->
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            max(maxX - minX, maxY - minY)
        }
        val scale = torsoScale
            .takeIf { it >= MIN_BODY_SCALE }
            ?: visibleScale.takeIf { it >= MIN_BODY_SCALE }
            ?: fallbackScale
        return BodyFrame(
            centerX = frameCenter.x,
            centerY = frameCenter.y,
            scale = scale.coerceAtLeast(MIN_BODY_SCALE)
        )
    }

    private fun smoothBodyFrame(raw: BodyFrame): BodyFrame? {
        val previous = bodyFrameState
        if (previous != null) {
            val scaleRatio = raw.scale / previous.scale.coerceAtLeast(MIN_BODY_SCALE)
            if (scaleRatio !in MIN_SCALE_RATIO..MAX_SCALE_RATIO) return null
        }
        val smoothed = if (previous == null) {
            raw
        } else {
            BodyFrame(
                centerX = previous.centerX * BODY_FRAME_SMOOTHING + raw.centerX * (1f - BODY_FRAME_SMOOTHING),
                centerY = previous.centerY * BODY_FRAME_SMOOTHING + raw.centerY * (1f - BODY_FRAME_SMOOTHING),
                scale = previous.scale * BODY_FRAME_SMOOTHING + raw.scale * (1f - BODY_FRAME_SMOOTHING)
            )
        }
        bodyFrameState = smoothed
        return smoothed
    }

    private fun smoothLandmarks(landmarks: List<NormalizedLandmark>, elapsedMs: Long): List<NormalizedLandmark> {
        val timestamp = elapsedMs / 1000.0
        return landmarks.mapIndexed { index, landmark ->
            if (index >= LANDMARK_FILTER_COUNT) {
                landmark
            } else {
                val filterIndex = index * 3
                landmark.copy(
                    x = coordinateFilters[filterIndex].filter(landmark.x.toDouble(), timestamp).toFloat(),
                    y = coordinateFilters[filterIndex + 1].filter(landmark.y.toDouble(), timestamp).toFloat(),
                    z = coordinateFilters[filterIndex + 2].filter(landmark.z.toDouble(), timestamp).toFloat()
                )
            }
        }
    }

    private fun midpointIfVisible(
        landmarks: List<NormalizedLandmark>,
        leftIndex: Int,
        rightIndex: Int
    ): BodyPoint? {
        val left = landmarks.getOrNull(leftIndex)?.takeIf { it.visibility >= VISIBILITY_THRESHOLD }
        val right = landmarks.getOrNull(rightIndex)?.takeIf { it.visibility >= VISIBILITY_THRESHOLD }
        if (left == null || right == null) return null
        return BodyPoint(
            x = (left.x + right.x) * 0.5f,
            y = (left.y + right.y) * 0.5f
        )
    }

    private fun relativeY(point: NormalizedLandmark, bodyFrame: BodyFrame): Float {
        return (point.y - bodyFrame.centerY) / bodyFrame.scale
    }

    private fun angle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Float {
        val abx = a.x - b.x
        val aby = a.y - b.y
        val cbx = c.x - b.x
        val cby = c.y - b.y
        val dot = abx * cbx + aby * cby
        val len = sqrt((abx * abx + aby * aby) * (cbx * cbx + cby * cby)).coerceAtLeast(0.0001f)
        return Math.toDegrees(acos((dot / len).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    private fun zoneFor(value: Float, low: Float, high: Float): ThresholdZone {
        return when {
            value <= low -> ThresholdZone.Low
            value >= high -> ThresholdZone.High
            else -> ThresholdZone.Middle
        }
    }

    private fun noiseFloor(values: List<Float>): Float {
        if (values.size < 5) return 0f
        val residuals = values.mapIndexedNotNull { index, value ->
            if (index == 0 || index == values.lastIndex) {
                null
            } else {
                val smoothed = (values[index - 1] + value + values[index + 1]) / 3f
                abs(value - smoothed)
            }
        }
        return residuals.averageOrZero()
    }

    private fun smoothnessScore(values: List<Float>, amplitude: Float): Float {
        if (values.size < 5 || amplitude <= 0.0001f) return 0.5f
        val residual = noiseFloor(values)
        return (1f - residual / (amplitude * 0.42f).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    }

    private fun periodStats(history: List<SignalSample>, minDelta: Float): PeriodStats {
        if (history.size < 5) return PeriodStats(0.45f, 0L, 0)
        var previousSign = 0
        val reversalTimes = mutableListOf<Long>()
        history.zipWithNext().forEach { (a, b) ->
            val delta = b.value - a.value
            val sign = when {
                delta > minDelta -> 1
                delta < -minDelta -> -1
                else -> 0
            }
            if (sign != 0 && previousSign != 0 && sign != previousSign) {
                reversalTimes += b.elapsedMs
            }
            if (sign != 0) previousSign = sign
        }
        if (reversalTimes.size < 2) return PeriodStats(0.45f, 0L, reversalTimes.size)
        val intervals = reversalTimes.zipWithNext().map { (a, b) -> (b - a).toFloat() }
            .filter { it >= MIN_REP_DURATION_MS * 0.35f }
        if (intervals.isEmpty()) return PeriodStats(0.45f, 0L, reversalTimes.size)
        val avg = intervals.average().toFloat()
        val sd = sqrt(intervals.map { (it - avg).pow(2) }.average().toFloat())
        val stability = (1f - sd / avg.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return PeriodStats(
            periodStabilityScore = stability,
            estimatedPeriodMs = (avg * 2f).toLong(),
            reversalCount = reversalTimes.size
        )
    }

    private fun percentile(values: List<Float>, percentile: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val position = (sorted.lastIndex * percentile).coerceIn(0f, sorted.lastIndex.toFloat())
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
        val fraction = position - lower
        return sorted[lower] * (1f - fraction) + sorted[upper] * fraction
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        return percentile(values, 0.5f)
    }

    private fun labelForIndex(index: Int): String? {
        return TRACKED_LANDMARKS.firstOrNull { it.index == index }?.label
    }

    private fun List<Float>.averageOrZero(): Float {
        return if (isEmpty()) 0f else average().toFloat()
    }

    private data class SignalCandidate(
        val id: String,
        val label: String,
        val value: Float,
        val axis: FitnessRepDominantAxis,
        val visibilityScore: Float,
        val activeLandmarks: List<String>,
        val minAmplitude: Float,
        val goodAmplitude: Float,
        val type: SignalType
    )

    private data class SignalSample(
        val elapsedMs: Long,
        val value: Float,
        val visibilityScore: Float
    )

    private data class SignalStats(
        val candidate: SignalCandidate,
        val latestValue: Float,
        val amplitude: Float,
        val minAmplitude: Float,
        val goodAmplitude: Float,
        val lowThreshold: Float,
        val highThreshold: Float,
        val amplitudeScore: Float,
        val smoothnessScore: Float,
        val visibilityScore: Float,
        val periodStabilityScore: Float,
        val estimatedPeriodMs: Long,
        val reversalCount: Int,
        val score: Float,
        val sampleCount: Int
    )

    private data class CandidateCycle(
        val family: String,
        val signalId: String,
        val signalLabel: String,
        val startElapsedMs: Long,
        val endElapsedMs: Long,
        val durationMs: Long,
        val lowAngle: Float,
        val highAngle: Float,
        val amplitude: Float,
        val score: Float,
        val confidence: Float,
        val visibilityScore: Float,
        val smoothnessScore: Float,
        val periodStabilityScore: Float,
        val activeLandmarks: List<String>,
        val axis: FitnessRepDominantAxis,
        val quality: FitnessRepQuality
    )

    private data class DynamicWindow(
        val lowThreshold: Float = 0f,
        val highThreshold: Float = 0f,
        val minAmplitude: Float = 0f,
        val periodMinMs: Long = 0L,
        val periodMaxMs: Long = 0L,
        val minVisibility: Float = 0f,
        val noiseFloor: Float = 0f
    )

    private data class PeriodStats(
        val periodStabilityScore: Float,
        val estimatedPeriodMs: Long,
        val reversalCount: Int
    )

    private data class BodyBox(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float
    )

    private data class BodyFrame(
        val centerX: Float,
        val centerY: Float,
        val scale: Float
    )

    private data class BodyPoint(
        val x: Float,
        val y: Float
    )

    private data class CenterSignal(
        val value: Float,
        val visibility: Float,
        val labels: List<String>
    )

    private data class TrackedLandmark(
        val index: Int,
        val label: String,
        val displayLabel: String
    )

    private enum class SignalType {
        StrongAngle,
        MediumRelative
    }

    private enum class ThresholdZone {
        Low,
        Middle,
        High;

        fun opposite(): ThresholdZone {
            return when (this) {
                Low -> High
                High -> Low
                Middle -> Middle
            }
        }
    }

    private companion object {
        private const val VISIBILITY_THRESHOLD = 0.55f
        private const val CORE_JOINT_VISIBILITY_THRESHOLD = 0.55f
        private const val SIDE_JOINT_VISIBILITY_THRESHOLD = 0.35f
        private const val ANGLE_ONLY_VISIBILITY_THRESHOLD = 0.55f
        private const val MIN_VISIBLE_LANDMARKS = 4
        private const val MIN_HISTORY_SAMPLES = 8
        private const val HISTORY_MS = 4_500L
        private const val MIN_REP_DURATION_MS = 700L
        private const val REP_COOLDOWN_MS = 650L
        private const val MAX_REP_DURATION_MS = 8_000L
        private const val MIN_REP_CONFIDENCE = 0.55f
        private const val MIN_REP_SMOOTHNESS = 0.38f
        private const val LOCK_SIGNAL_SCORE = 0.52f
        private const val KEEP_SIGNAL_SCORE = 0.38f
        private const val SWITCH_SCORE_MARGIN = 0.18f
        private const val SIGNAL_MISS_LIMIT = 4
        private const val MIN_LOCK_REVERSALS = 2
        private const val UNRELIABLE_RESET_MS = 1_500L
        private const val UNRELIABLE_RELEASE_MS = 3_000L
        private const val CANDIDATE_FEEDBACK_REPS = 3
        private const val CANDIDATE_CONFIRMING_REPS = 4
        private const val FAMILY_LOCK_REPS = 5
        private const val DYNAMIC_WINDOW_CYCLE_LIMIT = 8
        private const val LOCKED_FAMILY_RELEASE_MS = 12_000L
        private const val ALTERNATE_TAKEOVER_MARGIN = 0.12f
        private const val ALTERNATE_OBSERVE_MARGIN = 0.24f
        private const val BODY_FRAME_SMOOTHING = 0.78f
        private const val MIN_BODY_SCALE = 0.08f
        private const val MIN_SCALE_RATIO = 0.75f
        private const val MAX_SCALE_RATIO = 1.25f
        private const val MIN_ANGLE_ROM_DEGREES = 24f
        private const val GOOD_ANGLE_ROM_DEGREES = 56f
        private const val COORDINATE_MIN_AMPLITUDE = 0.08f
        private const val LOW_THRESHOLD_PERCENTILE = 0.20f
        private const val HIGH_THRESHOLD_PERCENTILE = 0.80f
        private const val MEDIUM_CONFIRM_SCORE = 0.42f
        private const val HIGH_CONFIDENCE_WITHOUT_CONFIRM = 0.70f
        private const val SIDE_CONFLICT_CORRELATION = -0.55f
        private const val LANDMARK_FILTER_COUNT = 33
        private const val CALIBRATION_EXPLORING = "Exploring"
        private const val CALIBRATION_ACTIVE = "CandidateActive"
        private const val CALIBRATION_CONFIRMING = "CandidateConfirming"
        private const val CALIBRATION_LOCKED = "Locked"
        private const val CALIBRATION_UNRELIABLE = "Unreliable"

        private val SWITCHABLE_PHASES = setOf(
            FitnessRepCounterPhase.WaitingBaseline,
            FitnessRepCounterPhase.RepCompleted,
            FitnessRepCounterPhase.Unreliable
        )

        private val TRACKED_LANDMARKS = listOf(
            TrackedLandmark(11, "left_shoulder", "left shoulder"),
            TrackedLandmark(12, "right_shoulder", "right shoulder"),
            TrackedLandmark(13, "left_elbow", "left elbow"),
            TrackedLandmark(14, "right_elbow", "right elbow"),
            TrackedLandmark(15, "left_wrist", "left wrist"),
            TrackedLandmark(16, "right_wrist", "right wrist"),
            TrackedLandmark(23, "left_hip", "left hip"),
            TrackedLandmark(24, "right_hip", "right hip"),
            TrackedLandmark(25, "left_knee", "left knee"),
            TrackedLandmark(26, "right_knee", "right knee"),
            TrackedLandmark(27, "left_ankle", "left ankle"),
            TrackedLandmark(28, "right_ankle", "right ankle")
        )

        private val PART_UPPER = listOf(11, 12, 13, 14, 15, 16)
        private val PART_LOWER = listOf(23, 24, 25, 26, 27, 28)
    }
}

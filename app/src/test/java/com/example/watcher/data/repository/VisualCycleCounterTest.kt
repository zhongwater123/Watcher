package com.example.watcher.data.repository

import com.example.watcher.data.local.pose.NormalizedLandmark
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.local.pose.PoseLandmarkSet
import com.example.watcher.data.model.FitnessRepCounterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VisualCycleCounterTest {
    @Test
    fun elbowCalibrationLocksOnFifthCycleAndBackfillsCount() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter)
        var elapsed = WARMUP_END_MS

        repeat(2) {
            val result = runElbowRep(counter, elapsed)
            state = result.state
            elapsed = result.elapsedMs
        }
        assertEquals(0, state.repCount)
        assertEquals(0, state.displayRepCount)
        assertEquals(0, state.officialRepCount)
        assertEquals("elbow", state.candidateFamily)
        assertEquals(2, state.candidatePendingCount)

        runElbowRep(counter, elapsed).also {
            state = it.state
            elapsed = it.elapsedMs
        }
        assertEquals(0, state.repCount)
        assertEquals(0, state.displayRepCount)
        assertEquals(0, state.officialRepCount)
        assertEquals(3, state.candidatePendingCount)
        assertEquals("CandidateActive", state.calibrationPhase)

        runElbowRep(counter, elapsed).also {
            state = it.state
            elapsed = it.elapsedMs
        }
        assertEquals(0, state.displayRepCount)
        assertEquals(0, state.officialRepCount)
        assertEquals(4, state.candidatePendingCount)
        assertEquals("CandidateConfirming", state.calibrationPhase)

        runElbowRep(counter, elapsed).also {
            state = it.state
            elapsed = it.elapsedMs
        }
        assertEquals(5, state.repCount)
        assertEquals(5, state.displayRepCount)
        assertEquals(5, state.officialRepCount)
        assertEquals("Locked", state.calibrationPhase)
        assertEquals("elbow", state.lockedFamily)
        assertEquals(5, state.confirmedRepQualities.size)
        assertTrue(state.dynamicLowThreshold < state.dynamicHighThreshold)
        assertTrue(state.dynamicMinAmplitude > 0f)

        runElbowRep(counter, elapsed).also {
            state = it.state
        }
        assertEquals(6, state.repCount)
        assertEquals(6, state.officialRepCount)
        assertEquals(1, state.confirmedRepQualities.size)
    }

    @Test
    fun singleOffFamilyCycleIsFilteredWithoutBreakingCandidateFamily() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter)
        var elapsed = WARMUP_END_MS

        repeat(3) {
            val result = runElbowRep(counter, elapsed)
            state = result.state
            elapsed = result.elapsedMs
        }
        assertEquals(0, state.displayRepCount)
        assertEquals(3, state.candidatePendingCount)
        assertEquals("elbow", state.candidateFamily)

        runKneeRep(counter, elapsed, elbowStatic = true).also {
            state = it.state
            elapsed = it.elapsedMs
        }
        assertEquals(0, state.displayRepCount)
        assertEquals(0, state.officialRepCount)
        assertEquals("elbow", state.candidateFamily)
        assertTrue(state.filteredFamily.isBlank() || state.filteredFamily == "knee")

        repeat(2) {
            val result = runElbowRep(counter, elapsed)
            state = result.state
            elapsed = result.elapsedMs
        }
        assertEquals(5, state.officialRepCount)
        assertEquals("elbow", state.lockedFamily)
    }

    @Test
    fun alternateFamilyCanTakeOverWhenItFormsStableWindow() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter)
        var elapsed = WARMUP_END_MS

        repeat(2) {
            val result = runElbowRep(counter, elapsed)
            state = result.state
            elapsed = result.elapsedMs
        }
        assertEquals("elbow", state.candidateFamily)
        assertEquals(0, state.displayRepCount)

        repeat(6) {
            val result = runKneeRep(counter, elapsed, elbowStatic = true)
            state = result.state
            elapsed = result.elapsedMs
        }

        assertEquals("knee", state.lockedFamily)
        assertTrue(state.officialRepCount >= 5)
        assertEquals(state.officialRepCount, state.displayRepCount)
    }

    @Test
    fun lockedFamilyRejectsOtherStrongFamilies() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter)
        var elapsed = WARMUP_END_MS

        repeat(5) {
            val result = runElbowRep(counter, elapsed)
            state = result.state
            elapsed = result.elapsedMs
        }
        assertEquals("elbow", state.lockedFamily)
        assertEquals(5, state.officialRepCount)

        repeat(4) {
            val result = runKneeRep(counter, elapsed, elbowStatic = true)
            state = result.state
            elapsed = result.elapsedMs
        }

        assertEquals("elbow", state.lockedFamily)
        assertEquals(5, state.officialRepCount)
    }

    @Test
    fun shoulderAndHipCoordinateJitterDoesNotCreateFalseReps() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter)
        var elapsed = WARMUP_END_MS
        val jitter = listOf(0f, 0.012f, -0.01f, 0.015f, -0.008f, 0.006f)

        repeat(10) {
            jitter.forEach { offset ->
                state = counter.update(
                    frame(
                        elapsed,
                        shoulderOffsetX = offset,
                        hipOffsetX = -offset * 0.5f
                    ),
                    elapsed
                )
                elapsed += 120L
            }
        }

        assertEquals(0, state.repCount)
        assertFalse(state.primarySignalId.contains("_diagonal"))
        assertFalse(state.primarySignalId.endsWith("_x"))
    }

    @Test
    fun thresholdJitterAroundSmallAngleRangeDoesNotEnterCandidateWindow() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter)
        var elapsed = WARMUP_END_MS
        val jitter = listOf(160f, 164f, 168f, 162f, 166f, 161f, 165f)

        repeat(8) {
            jitter.forEach { angle ->
                state = counter.update(frame(elapsed, leftElbowAngleDeg = angle), elapsed)
                elapsed += 140L
            }
        }

        assertEquals(0, state.repCount)
        assertEquals(0, state.candidatePendingCount)
        assertTrue(state.status == FitnessRepCounterStatus.Ready || state.confidence < 0.55f)
    }

    @Test
    fun transientMultiplePeopleFreezesWithoutClearingPendingWindow() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter)
        var elapsed = WARMUP_END_MS

        repeat(3) {
            val result = runElbowRep(counter, elapsed)
            state = result.state
            elapsed = result.elapsedMs
        }
        assertEquals(0, state.displayRepCount)
        assertEquals(3, state.candidatePendingCount)
        assertEquals("elbow", state.candidateFamily)

        repeat(4) {
            state = counter.update(frame(elapsed, extraPerson = true), elapsed)
            elapsed += 160L
        }

        assertEquals(0, state.displayRepCount)
        assertEquals("elbow", state.candidateFamily)
        assertEquals(FitnessRepCounterStatus.Unreliable, state.status)
        assertTrue(state.notCountingReason.contains("freeze"))

        repeat(2) {
            val result = runElbowRep(counter, elapsed)
            state = result.state
            elapsed = result.elapsedMs
        }

        assertEquals(5, state.officialRepCount)
        assertEquals("elbow", state.lockedFamily)
        assertNotEquals(FitnessRepCounterStatus.Error, state.status)
    }

    @Test
    fun oneReliableSideCanLockWhenOtherSideIsOccluded() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter, rightVisibility = 0.1f)
        var elapsed = WARMUP_END_MS

        repeat(5) {
            val result = runElbowRep(counter, elapsed, rightVisibility = 0.1f)
            state = result.state
            elapsed = result.elapsedMs
        }

        assertEquals(5, state.officialRepCount)
        assertEquals("elbow", state.lockedFamily)
        assertTrue(state.primarySignalId.startsWith("left_"))
    }

    @Test
    fun lockedElbowCanFinishRepWhenOnlyTheArmRemainsVisible() {
        val counter = VisualCycleCounter()
        var state = warmUp(counter)
        var elapsed = WARMUP_END_MS

        repeat(5) {
            val result = runElbowRep(counter, elapsed)
            state = result.state
            elapsed = result.elapsedMs
        }
        assertEquals("elbow", state.lockedFamily)
        assertEquals(5, state.officialRepCount)

        runElbowRep(counter, elapsed, leftArmOnly = true).also {
            state = it.state
        }

        assertEquals(6, state.officialRepCount)
        assertEquals(6, state.displayRepCount)
        assertEquals("elbow", state.lockedFamily)
        assertTrue(state.primarySignalId.startsWith("left_elbow"))
    }

    private fun runElbowRep(
        counter: VisualCycleCounter,
        startElapsedMs: Long,
        rightVisibility: Float = 0.95f,
        leftArmOnly: Boolean = false
    ): RepRunResult {
        var state = com.example.watcher.data.model.FitnessRepCounterState()
        var elapsed = startElapsedMs
        elbowAngleRepValues().forEach { angle ->
            state = counter.update(
                frame(
                    elapsed,
                    leftElbowAngleDeg = angle,
                    rightVisibility = rightVisibility,
                    leftArmOnly = leftArmOnly
                ),
                elapsed
            )
            elapsed += STEP_MS
        }
        return RepRunResult(state, elapsed)
    }

    private fun runKneeRep(
        counter: VisualCycleCounter,
        startElapsedMs: Long,
        elbowStatic: Boolean = false
    ): RepRunResult {
        var state = com.example.watcher.data.model.FitnessRepCounterState()
        var elapsed = startElapsedMs
        kneeAngleRepValues().forEach { angle ->
            state = counter.update(
                frame(
                    elapsed,
                    leftElbowAngleDeg = if (elbowStatic) 166f else angle,
                    rightElbowAngleDeg = if (elbowStatic) 166f else angle,
                    leftKneeAngleDeg = angle,
                    rightKneeAngleDeg = angle
                ),
                elapsed
            )
            elapsed += STEP_MS
        }
        return RepRunResult(state, elapsed)
    }

    private fun warmUp(
        counter: VisualCycleCounter,
        rightVisibility: Float = 0.95f
    ): com.example.watcher.data.model.FitnessRepCounterState {
        var state = com.example.watcher.data.model.FitnessRepCounterState()
        var elapsed = 0L
        repeat(8) {
            state = counter.update(frame(elapsed, rightVisibility = rightVisibility), elapsed)
            elapsed += 120L
        }
        return state
    }

    private fun elbowAngleRepValues(): List<Float> {
        return listOf(166f, 150f, 128f, 104f, 86f, 106f, 130f, 152f, 166f)
    }

    private fun kneeAngleRepValues(): List<Float> {
        return listOf(166f, 148f, 126f, 104f, 88f, 108f, 132f, 152f, 166f)
    }

    private fun frame(
        elapsedMs: Long,
        leftElbowAngleDeg: Float = 166f,
        rightElbowAngleDeg: Float = 166f,
        leftKneeAngleDeg: Float = 166f,
        rightKneeAngleDeg: Float = 166f,
        visibility: Float = 0.95f,
        rightVisibility: Float = visibility,
        shoulderOffsetX: Float = 0f,
        hipOffsetX: Float = 0f,
        bodyOffsetX: Float = 0f,
        bodyOffsetY: Float = 0f,
        leftArmOnly: Boolean = false,
        extraPerson: Boolean = false
    ): PoseDetectionResult {
        val points = MutableList(33) {
            NormalizedLandmark(0.5f + bodyOffsetX, 0.5f + bodyOffsetY, 0f, visibility, visibility)
        }
        fun set(index: Int, x: Float, y: Float, v: Float = visibility) {
            points[index] = NormalizedLandmark(x + bodyOffsetX, y + bodyOffsetY, 0f, v, v)
        }

        set(11, 0.42f + shoulderOffsetX, 0.26f)
        set(12, 0.58f - shoulderOffsetX, 0.26f, rightVisibility)
        set(23, 0.44f + hipOffsetX, 0.58f)
        set(24, 0.56f - hipOffsetX, 0.58f, rightVisibility)

        set(13, 0.36f + shoulderOffsetX, 0.40f)
        set(15, elbowWristX(0.36f + shoulderOffsetX, leftElbowAngleDeg), elbowWristY(0.40f, leftElbowAngleDeg))
        set(14, 0.64f - shoulderOffsetX, 0.40f, rightVisibility)
        set(16, rightElbowWristX(0.64f - shoulderOffsetX, rightElbowAngleDeg), elbowWristY(0.40f, rightElbowAngleDeg), rightVisibility)

        set(25, 0.43f + hipOffsetX, 0.76f)
        set(27, kneeAnkleX(0.43f + hipOffsetX, leftKneeAngleDeg, left = true), kneeAnkleY(0.76f, leftKneeAngleDeg))
        set(26, 0.57f - hipOffsetX, 0.76f, rightVisibility)
        set(28, kneeAnkleX(0.57f - hipOffsetX, rightKneeAngleDeg, left = false), kneeAnkleY(0.76f, rightKneeAngleDeg), rightVisibility)

        if (leftArmOnly) {
            listOf(12, 14, 16, 23, 24, 25, 26, 27, 28).forEach { index ->
                points[index] = points[index].copy(visibility = 0.1f, presence = 0.1f)
            }
        }

        val primary = PoseLandmarkSet(points, worldLandmarks = null)
        val landmarkSets = if (extraPerson) {
            val second = points.map { point ->
                point.copy(x = (point.x + 0.18f).coerceAtMost(0.98f))
            }
            listOf(primary, PoseLandmarkSet(second, worldLandmarks = null))
        } else {
            listOf(primary)
        }
        return PoseDetectionResult(
            landmarks = landmarkSets,
            timestampMs = elapsedMs,
            inferenceTimeMs = 1L
        )
    }

    private fun elbowWristX(elbowX: Float, angleDeg: Float): Float {
        val radians = Math.toRadians(angleDeg.toDouble())
        return elbowX + (sin(radians) * FOREARM_LENGTH).toFloat()
    }

    private fun rightElbowWristX(elbowX: Float, angleDeg: Float): Float {
        val radians = Math.toRadians(angleDeg.toDouble())
        return elbowX - (sin(radians) * FOREARM_LENGTH).toFloat()
    }

    private fun elbowWristY(elbowY: Float, angleDeg: Float): Float {
        val radians = Math.toRadians(angleDeg.toDouble())
        return elbowY + (-cos(radians) * FOREARM_LENGTH).toFloat()
    }

    private fun kneeAnkleX(kneeX: Float, angleDeg: Float, left: Boolean): Float {
        val radians = Math.toRadians(angleDeg.toDouble())
        val sign = if (left) 1f else -1f
        return kneeX + sign * (sin(radians) * SHIN_LENGTH).toFloat()
    }

    private fun kneeAnkleY(kneeY: Float, angleDeg: Float): Float {
        val radians = Math.toRadians(angleDeg.toDouble())
        return kneeY + (-cos(radians) * SHIN_LENGTH).toFloat()
    }

    private data class RepRunResult(
        val state: com.example.watcher.data.model.FitnessRepCounterState,
        val elapsedMs: Long
    )

    private companion object {
        private const val STEP_MS = 240L
        private const val WARMUP_END_MS = 960L
        private const val FOREARM_LENGTH = 0.18f
        private const val SHIN_LENGTH = 0.18f
    }
}

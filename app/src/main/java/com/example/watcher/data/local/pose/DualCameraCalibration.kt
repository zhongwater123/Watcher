package com.example.watcher.data.local.pose

import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Dual-camera adaptive calibration — angle-independent of person's facing direction.
 *
 * Algorithm: Uses the normalized shoulder-width X-component from each camera.
 * The sum rA² + rB² has a predictable relationship to the camera angle θ:
 *   - At θ=90°: the sum is CONSTANT (regardless of person rotation)
 *   - At θ≠90°: the sum oscillates as person rotates
 *   - The ratio min/max of this sum directly reveals θ
 *
 * Formula: θ = arccos((1 - R) / (1 + R)), where R = minSum / maxSum
 *
 * No reference locking needed. No assumption about person's facing direction.
 * Works as long as the person naturally rotates slightly during movement.
 */
class DualCameraCalibration {

    companion object {
        private const val TAG = "Calibration"
        private const val COLLECTION_FRAMES = 120   // ~8-10 seconds of data, then lock final result
        private const val MIN_VISIBILITY = 0.4f
        private const val MIN_SHOULDER_X = 0.02f
    }

    // Public state
    var cameraAngleDeg: Float = 90f
        private set
    var calibrationConfidence: Float = 0f
        private set
    var isConverged: Boolean = false
        private set

    // Sliding window of sumSq values
    private val sumSqWindow = mutableListOf<Float>()
    private var frameCount = 0

    // Debug values
    var lastRA: Float = 0f; private set
    var lastRB: Float = 0f; private set
    var lastSumSq: Float = 0f; private set

    data class CalibrationInput(
        val frontLandmarks: List<NormalizedLandmark>,
        val sideLandmarks: List<NormalizedLandmark>
    )

    data class CalibrationResult(
        val angleDeg: Float,
        val confidence: Float,
        val converged: Boolean,
        val rA: Float,
        val rB: Float,
        val sumSq: Float,
        val debugInfo: String
    )

    fun update(input: CalibrationInput): CalibrationResult {
        // Once finalized, stop updating
        if (isConverged) return currentResult("LOCKED: ${"%.1f".format(cameraAngleDeg)}°")

        val front = input.frontLandmarks
        val side = input.sideLandmarks

        if (front.size < 33 || side.size < 33) return currentResult("skip")
        if (front[11].visibility < MIN_VISIBILITY || front[12].visibility < MIN_VISIBILITY ||
            side[11].visibility < MIN_VISIBILITY || side[12].visibility < MIN_VISIBILITY ||
            front[0].visibility < MIN_VISIBILITY || side[0].visibility < MIN_VISIBILITY) {
            return currentResult("skip: visibility")
        }

        val rA = computeNormalizedShoulderRatio(front)
        val rB = computeNormalizedShoulderRatio(side)
        if (rA < MIN_SHOULDER_X || rB < MIN_SHOULDER_X) return currentResult("skip: ratio")

        lastRA = rA
        lastRB = rB
        lastSumSq = rA * rA + rB * rB
        sumSqWindow.add(lastSumSq)
        frameCount++

        if (frameCount % 15 == 0) {
            Log.d(TAG, "collecting ${sumSqWindow.size}/$COLLECTION_FRAMES " +
                "rA=${"%.3f".format(rA)} rB=${"%.3f".format(rB)} sum=${"%.3f".format(lastSumSq)}")
        }

        // FINALIZE after collecting enough frames
        if (sumSqWindow.size >= COLLECTION_FRAMES) {
            val sorted = sumSqWindow.sorted()
            val p10 = sorted[(sorted.size * 0.10).toInt()]
            val p90 = sorted[(sorted.size * 0.90).toInt().coerceAtMost(sorted.size - 1)]
            val R = if (p90 > 0.001f) p10 / p90 else 1f
            val cosTheta = ((1f - R) / (1f + R)).coerceIn(-1f, 1f)
            cameraAngleDeg = Math.toDegrees(acos(cosTheta).toDouble()).toFloat()
            calibrationConfidence = R
            isConverged = true

            Log.i(TAG, "══════════════════════════════")
            Log.i(TAG, "  FINAL ANGLE: ${"%.1f".format(cameraAngleDeg)}°")
            Log.i(TAG, "  Confidence: ${"%.2f".format(R)} (R = p10/p90)")
            Log.i(TAG, "  Frames used: ${sumSqWindow.size}")
            Log.i(TAG, "  p10=${"%.3f".format(p10)} p90=${"%.3f".format(p90)}")
            Log.i(TAG, "══════════════════════════════")
        }

        return currentResult("collecting ${sumSqWindow.size}/$COLLECTION_FRAMES")
    }

    fun reset() {
        cameraAngleDeg = 90f
        calibrationConfidence = 0f
        isConverged = false
        sumSqWindow.clear()
        frameCount = 0
        Log.i(TAG, "Calibration reset")
    }

    // ── Internal ──

    /**
     * Compute distance-independent shoulder width ratio:
     * shoulder_x_extent / neck_y_extent
     *
     * - shoulder_x: horizontal distance between L/R shoulders (affected by camera angle)
     * - neck_y: vertical distance from shoulder midpoint to nose (NOT affected by horizontal camera angle)
     *
     * This ratio cancels out the distance-to-camera factor.
     */
    private fun computeNormalizedShoulderRatio(landmarks: List<NormalizedLandmark>): Float {
        // Shoulder horizontal extent (X component only)
        val shoulderX = abs(landmarks[12].x - landmarks[11].x)

        // Neck vertical extent (Y component: shoulder midpoint → nose)
        val shoulderMidY = (landmarks[11].y + landmarks[12].y) / 2f
        val neckY = abs(landmarks[0].y - shoulderMidY)

        if (neckY < 0.01f) return 0f  // avoid division by zero

        return shoulderX / neckY
    }

    private fun currentResult(debug: String) = CalibrationResult(
        angleDeg = cameraAngleDeg,
        confidence = calibrationConfidence,
        converged = isConverged,
        rA = lastRA,
        rB = lastRB,
        sumSq = lastSumSq,
        debugInfo = debug
    )
}
